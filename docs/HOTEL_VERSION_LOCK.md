# 酒店资料乐观锁（版本号与冲突提示）记录

验证基线：`hotel-version-lock` 分支基于 PR #42 的首轮复审结果 `f405ef9`（2026-09-29）。本记录对应本分支自己的一次契约变更，不涉及 PR #42 已审内容。

## 背景

PR #42 复审决定：**不接受酒店资料长期采用静默"最后保存覆盖"** —— 两位工作人员各自打开同一条酒店资料、先后保存时，后保存的人会无意覆盖前一位对地址 / 联系电话 / 状态 / 坐标的改动。要求参照团期 `6d3ec91`（`feature/departure-version-lock`）的做法补版本号与冲突提示；因涉及请求与响应契约，另开 PR；**该修复必须在发布到 `main` 前完成**。

## 契约变更（docs/API.md §12）

1. **`Hotel` 新增必填 `version`**（integer，≥ 0）。
   与团期的做法不同：团期把版本号放在后台专用视图 `AdminDeparture` 里，因为共用的 `Departure` 同时服务于公开线路详情、导游端与订单详情。`Hotel` 只被后台酒店端点使用（没有公开酒店接口），因此直接加在 `Hotel` 上，不额外造一个视图。
2. **`HotelUpsertRequest` 拆成两个 schema**（破坏性重命名，影响面见下）：
   - `HotelCreateRequest`：不含 `version`，新建由服务端从 0 起算；提交该字段被严格模式拒绝（400）。
   - `HotelUpdateRequest`：字段与建档相同，另需回传读取时的 `version`；缺字段按 422 处理，不静默当成 0。
3. **`PUT /admin/hotels/{hotelId}` 新增 409**：`HOTEL_VERSION_CONFLICT`，说明里点明"本次修改未生效，调用方应重新读取后再决定是否覆盖"。

**兼容影响**：`HotelUpsertRequest` 这个名字被移除，任何按旧 schema 发送修改请求的调用方都必须改为回传 `version`（否则 422）；创建请求不受影响。仓库内唯一的调用方是前端 `HotelFormDialog`，已同步修改。响应结构上 `Hotel` 多了一个必填字段，严格模式的消费方需要同步（前端只读不校验）。

## 数据库

- `sql/schema.sql`：`hotel` 表新增 `version INT NOT NULL DEFAULT 0`（放在 `status` 之后）。
- 新增 `sql/migrations/008-add-hotel-version.sql`：存量库补列（幂等，按 `migrations/README.md` 的模板用 `information_schema` 判断），并登记到该 README 的变更记录表。

## 后端

- `Hotel` 实体增加 `version`；`HotelView` 增加 `version` —— 用 `Integer`，因为契约里它是 JSON number，而全局序列化器会把 `Long` 写成字符串（那是给主键用的）。
- `HotelService#create`：`version = 0`（服务端决定，客户端不参与）。
- `HotelService#update`：
  - 版本判定用 `lockHotel` 的**锁内当前读**（与上一轮的状态审计日志修复共用同一把行锁），保证"读到的版本"就是"写入时依据的版本"；不一致直接 409。
  - UPDATE 带 `WHERE id = ? AND version = ?`，`SET` 里始终写 `version = 提交版本 + 1`。版本条件因此成为第二道防线，同时消掉一个旧约定：`SET` 里必然有变化，所以"影响 0 行"不再有"字段没变化"这种歧义，也就不再依赖驱动的 `useAffectedRows` 语义。
  - 影响 0 行的兜底重新读一次：行不在了按 404，版本对不上按 409（把冲突说成"不存在"会让调用方重新建一条重复资料）。
  - 失败路径不写任何字段、不记操作日志：`STATUS` 审计日志的比较基准仍是锁内当前读，不会被过期表单影响。

## 前端

`HotelFormDialog`：

- 维护 `baseVersion`：打开弹窗取 `props.hotel.version`，新建为 `null`（请求体不带该字段），保存成功后以**响应里的新版本**为准（否则第二次保存必然冲突）。
- 命中 409 `HOTEL_VERSION_CONFLICT` 时进入冲突面板，**不丢弃用户输入**：
  - 用列表端点的 keyword 检索取回服务器最新资料（契约没有单条酒店详情端点，`GET /admin/hotels/{hotelId}` 不存在，因此按打开弹窗时的名称检索、按 id 匹配）；
  - 列出与服务器不一致的字段并显示服务器当前版本；
  - 两个动作：「载入服务器最新数据」（表单回到服务器状态，以最新版本为基准）与「保留我的修改并覆盖」（用最新版本号重新提交用户填写的值，属知情覆盖）；
  - 取不到最新资料时给出提示，并且两个动作都禁用 —— 不提供"盲目覆盖"入口。
- 面板样式与团期表单同一套视觉口径。

## 测试

- `HotelServiceTest`（+3）：过期版本 → 409 且不写库不记日志；版本一致时 `WHERE` 带版本条件、`SET` 推进版本号；0 行且行仍在时按 409 而不是 404。
- `HotelAdminWebContractTest`（+1，并扩展现有用例）：建档请求带 `version` → 400；修改缺 `version` → 422；负数版本 → 422。
- `HotelAdminContractIntegrationTest`：全链路断言版本推进（创建 0 → 修改 1 → 停用 2 → 再编辑 3），并用版本 2 的陈旧表单再提交一次，断言 409、字段未变、版本未前进。
- `HotelFormDialog.spec.js`（+4）：编辑请求回传版本号；409 时展示冲突面板并保留输入、列出差异；「载入服务器最新数据」后用最新版本保存；「保留我的修改并覆盖」用最新版本重提交；取不到最新资料时禁止覆盖。
- `HotelStatusLogConcurrencyIntegrationTest` 的修改请求同步补上版本号（否则会先被乐观锁拦下，测不到状态审计日志）。

## 复测

```powershell
cd backend
$env:TRAVEL_MYSQL_TEST = 'true'
mvn -ntp test
Remove-Item Env:TRAVEL_MYSQL_TEST
```

```powershell
cd frontend
npm test
npm run build
npm run contract:validate
```

存量库需要先执行迁移：

```bash
mysql -h localhost -u travel -p travel_agency < sql/migrations/008-add-hotel-version.sql
```

本轮结果：后端 **468 passed / 0 failed**（PR #42 复审后为 464，本轮 +4）；前端 **48 passed / 0 failed**（+4）；前端生产构建与 `steady validate ../docs/openapi.yaml` 通过。

## 契约确认状态

本轮契约变更由**项目层面评审决定**驱动（"参照团期做版本号与冲突提示；这涉及请求和响应契约，单独开 PR 更清楚"）。**未记录前端、测试成员的单独确认**，不据此声称三方已分别确认；如需成员级确认，请在合并前补记。

## 合并顺序

本分支基于 PR #42 的分支（`dev` 上还没有酒店模块）。因此：

1. #42 先合并进 `dev`；
2. 本 PR 的 base 改为 `dev`（或先合并本 PR 到 #42 分支再一起进 `dev`）；
3. 按评审决定，酒店资料乐观锁必须在发布到 `main` 前完成 —— 即本 PR 与 #42 都必须早于 `main` 的那次合并。
