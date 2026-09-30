# 导游管理模块补充与回归记录（成员 C）

范围：PRD §38「导游管理」与 §39「导游工作台」。本记录只覆盖本次补齐的部分与回归结论，
不改动已冻结的 OpenAPI 契约。

## 检测结论（补齐前）

| 层 | 组件 | 状态 |
| --- | --- | --- |
| 数据库 | `guide` 表、`departure.guide_id` 外键 | 已具备 |
| 后端 | `Guide` / `GuideMapper` / `GuideService`、`AdminController` 的 `GET/POST/PUT /admin/guides`、`PATCH /admin/guides/{id}/status` | 已实现，与契约一致 |
| 后端 | 同一导游时间范围重叠校验（`DepartureService#checkGuideConflict` + `GuideMapper.selectByIdForUpdate` 行锁） | 已实现 |
| 后端 | 导游工作台 `GET /guide/dashboard`、`/guide/departures`、`/guide/departures/{id}/start|complete`、`/passengers` | 已实现 |
| 后端测试 | `Pr9ContractIntegrationTest`、`PasswordByteLimitContractIntegrationTest`、`GuideTripContractIntegrationTest` | 已覆盖 `/admin/guides` CRUD、权限、状态流转与导游端链路 |
| 前端 | 导游工作台 `views/guide/*`、`guideApi` | 已实现 |
| 前端 | 团期弹窗分配导游（`DepartureFormDialog`） | 已实现 |
| 前端 | **后台导游管理页（`AdminResourcesView` 的 `guides` 资源）** | **未完成：只读** |
| 前端测试 | 导游相关用例 | 缺失 |

### 未完成的具体表现

1. 「新增」按钮走通用占位分支，只弹一句 `新增表单已对接对应后端 CRUD API`，没有表单；
   `adminApi.createGuide` 定义了但没有任何调用方。
2. 行内没有「编辑」与「启用 / 停用」入口，`adminApi.updateGuide` / `updateGuideStatus` 同样无人调用
   —— 契约里的 `POST`、`PUT`、`PATCH /status` 在前端全部悬空。
3. 状态列硬编码 `<span class="tag success">{{ row.status }}</span>`：停用也显示绿色，
   且直接把库内枚举 `ACTIVE` / `DISABLED` 抛给运营。
4. 导游列表没有翻页控件（`guides` 不在 `PAGED_RESOURCES` 里），第 21 条之后无法在界面上管理。

## 本次改动

| 文件 | 内容 |
| --- | --- |
| `frontend/src/components/GuideFormDialog.vue`（新增） | 导游新增 / 修改表单。新增走 `GuideCreateRequest`（`username/password/name/phone/intro`），修改走 `GuideUpdateRequest`（`name/phone/intro`）。前端校验与后端、契约同口径：用户名 `^[A-Za-z0-9_]{3,32}$`；密码 **8–72 字符且不超过 72 UTF-8 字节**（BCrypt 上限，25 个汉字只有 25 字符却占 75 字节）；姓名 1–64、电话 3–20、简介 ≤1000，长度按 Unicode 码点计数。 |
| `frontend/src/views/admin/AdminResourcesView.vue` | 导游资源接入「+ 新增导游」「编辑」「启用 / 停用」；状态列改用 `AccountStatus` 中文映射；`guides` 纳入分页；按契约 `x-roles` 用 `auth.hasRole('ADMIN')` 控制新增与启停按钮；移除通用占位按钮。 |
| `frontend/src/components/__tests__/GuideFormDialog.spec.js`（新增） | 13 个用例：请求体形状、账号名正则、密码字符 / 字节双上限、码点计数、输入框不设 `maxlength`、码点计数器、必填与长度边界、清空可选字段提交 `null`、409 就地提示不丢输入。 |
| `frontend/src/views/admin/__tests__/AdminResourcesView.spec.js` | 增补 10 个导游用例：分页取数、中文状态、无占位按钮、启停调用与失败回滚、启停在途时禁用按钮且连点不发第二个请求、两个导游互不阻塞、POST/PUT 刷新策略、STAFF 与 ADMIN 的按钮可见性差异。 |

### 仍然只由后端保证的规则

- 同一导游在时间重叠的团期上不能被重复指派：范围重叠判断无法用唯一键表达，
  由 `DepartureService#checkGuideConflict` 在 `guide` 行锁（`selectByIdForUpdate`）内完成，加锁顺序固定为「先导游、后团期」。
- 启用 / 停用会同步改写 `sys_user.status`，停用后该导游无法登录。
- 创建导游账号、启停导游仅 `ADMIN`；修改资料对 `STAFF` 开放。

前端按钮的显隐只是体验优化，最终判定始终以后端 RBAC 为准。

## 补修：启停并发与长度口径（同一分支的第二轮）

首轮接入后有两条被复核出来的问题，都在本轮修掉。

### 1. 启停按钮可重复请求，失败回滚会显示与库内相反的状态

`changeGuideStatus` 是「乐观改写 + 失败回滚」，按钮文案又由 `row.status` 反推，但首次实现
没有拦住在途的第二次点击。复现：快速点「停用 → 启用」，两个请求都失败时按后进先出回滚 ——
后发的把状态写回 `DISABLED`，而库内其实仍是 `ACTIVE`（第一次请求从未落库），
页面就此停在**与数据库相反**的状态上，运营还会照着这个错状态继续操作。

修复：`AdminResourcesView` 增加 `pendingGuideStatus`（`Set<guideId>`），既是「该导游有启停在途」
的标记，也是按钮 `:disabled` 的依据；按钮在途时显示「提交中…」。

- 用 `Set` 而不是单一标量：两个导游可以各自独立启停，互不阻塞。
- 同时拦住调用与禁用按钮两层 —— 只禁用按钮挡不住测试或键盘触发的第二次调用。
- 回滚只写回这一行对象；期间列表若已被重新拉取（换成了新行对象），对旧对象的赋值不会影响界面。

### 2. 文字长度校验没有真正与契约、后端一致

三处口径原本不一致：

| 层 | 首轮实现的口径 | 问题 |
| --- | --- | --- |
| 契约 `maxLength` | JSON Schema，数**字符（码点）** | 基准 |
| 前端校验 | 码点（`[...str].length`） | 与契约一致 |
| 输入框 `maxlength` | UTF-16 **码元** | 64 个 emoji 的姓名（64 码点 / 128 码元）在输入阶段被静默截断成 32 个 |
| 后端 `GuideAccountRequest` / `GuideUpdateRequest` | `@Size` = UTF-16 **码元** | 契约允许、`VARCHAR(64)` 也存得下的 64 个 emoji 姓名被回 422 |

即：**前端校验通过的内容，输入框存不下、后端也会拒**。首轮的测试只 mock 了接口，
因此无法证明真实保存成功 —— 这一档恰好绕过了所有断言。

修复（统一到契约口径）：

- 后端 `name` / `phone` / `intro` 改用 `@CodePointLength`，与同仓的
  `HotelCreateRequest` / `HotelUpdateRequest` 口径一致（那两处本就是码点）。
  密码的字符上限也一并改为 `@CodePointLength`（BCrypt 的 72 UTF-8 字节上限仍由
  `@Utf8ByteLength` 单独守住，两个约束缺一不可）。
- 前端输入框**不再设 `maxlength`**，改为「不截断 + 实时码点计数（超出变红）+ 提交时校验」。
  密码框同理：72 个 UTF-16 码元对汉字/emoji 只有 24–36 个字符，用码元数截断会悄悄改短密码。
- 计数上限 `NAME_MAX / PHONE_MAX / INTRO_MAX` 只声明一次，模板与校验共用，避免漂移。

> 这是本次接入**暴露出的已有后端缺口**（`@Size` 写法早于本分支），不是前端单独能修好的问题：
> 前端把码点校验放松或收紧都无法同时对上 `maxlength` 与 `@Size`，只有把后端拉到契约口径才闭合。

## 复测

```powershell
cd frontend
npm test
npm run build
```

本轮结果：前端 **72 passed / 0 failed**（首轮 67，补修新增 5：启停并发 3、码点输入上限 2）；
前端生产构建通过。

后端本轮有改动（`GuideAccountRequest` / `GuideUpdateRequest` 的长度约束），
并新增 `Pr9ContractIntegrationTest#guideLengthLimitsCountUnicodeCodePointsNotUtf16Units`
作为码点口径的回归防线。后端测试需要 JDK 21 与 MySQL，本机环境为 JDK 8 且无 Maven，
**未在本地执行** —— 这条结论待有 JDK 21 的环境确认后再更新：

```powershell
cd backend
$env:TRAVEL_MYSQL_TEST = 'true'
mvn -ntp test
```

## 范围外（不在本 PR 内单方面改动）

- 契约未声明的 422：`PUT /admin/guides/{guideId}` 在字段语义校验失败时会返回 422，
  但 `docs/openapi.yaml` 只列了 200 / 404。这属于契约变更，需先改契约并同步受影响成员，
  按 `docs/HOTEL_ASSIGNMENT_REGRESSION.md` 的记录另开变更处理。
- 导游密码重置：契约没有对应端点，改密码走导游本人的账号安全流程（`PUT /account/password`）。
- 同一处「长度按码元还是码点」的隐患在其它模块仍然存在：`RouteUpsertRequest`、
  `ArticleRequest`、`AttractionUpsertRequest`、`StaffAccountRequest`、`PlaceGuideRequest`
  等仍用 `@Size`。目前只有酒店、导游两档拉齐到码点口径，
  统一它们会改动旅游线路 / 攻略 / 景点 / 员工等模块的行为，需另开变更并各自补测试。
- 列表仍没有 `status` 筛选入口（契约 `GET /admin/guides` 声明了该参数、后端已实现）。
