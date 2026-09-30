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
| `frontend/src/components/__tests__/GuideFormDialog.spec.js`（新增） | 10 个用例：请求体形状、账号名正则、密码字符 / 字节双上限、码点计数、必填与长度边界、清空可选字段提交 `null`、409 就地提示不丢输入。 |
| `frontend/src/views/admin/__tests__/AdminResourcesView.spec.js` | 增补 7 个导游用例：分页取数、中文状态、无占位按钮、启停调用与失败回滚、POST/PUT 刷新策略、STAFF 与 ADMIN 的按钮可见性差异。 |

### 仍然只由后端保证的规则

- 同一导游在时间重叠的团期上不能被重复指派：范围重叠判断无法用唯一键表达，
  由 `DepartureService#checkGuideConflict` 在 `guide` 行锁（`selectByIdForUpdate`）内完成，加锁顺序固定为「先导游、后团期」。
- 启用 / 停用会同步改写 `sys_user.status`，停用后该导游无法登录。
- 创建导游账号、启停导游仅 `ADMIN`；修改资料对 `STAFF` 开放。

前端按钮的显隐只是体验优化，最终判定始终以后端 RBAC 为准。

## 复测

```powershell
cd frontend
npm test
npm run build
```

本轮结果：前端 **67 passed / 0 failed**（改动前 50）；前端生产构建通过。
后端未改动，沿用 `dev` 上既有结论。

后端测试需要 JDK 21 与 MySQL，本机环境为 JDK 8 且无 Maven，未在本地执行：

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
