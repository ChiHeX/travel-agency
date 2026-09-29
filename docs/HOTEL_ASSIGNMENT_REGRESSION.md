# 酒店资料与行程安排的复审回归记录（PR #42）

验证基线：本分支的首轮提交 `b11e5ae`（2026-09-29，PR #42 首轮）。复审修复分为：前端表单、后端行程闸门、后端状态审计日志、契约与文档。

## 已复现并修复

| 场景 | 基线行为 | 修复后行为 |
| --- | --- | --- |
| 只改地址保存酒店（未动状态） | 请求体每次都带表单里读到的旧 `status`，把另一位管理员刚完成的停用覆盖回 `ACTIVE` | 未改动状态时不提交 `status`，后端保留库内现值（`HotelFormDialog.shouldSubmitStatus`） |
| 保存成功后把状态改回原值再保存 | 第二次保存会漏掉 `status`，界面显示启用而库内仍是停用 | "原始值"以服务端确认的状态为准（保存成功后回写），改回启用会提交 |
| 行程安排期间酒店被停用并提交 | 普通查询读的是事务快照，仍看到 `ACTIVE`，行程照样落库 | `SELECT ... FOR UPDATE` 当前读 → 422 `VALIDATION_ERROR` |
| 停用正在进行（未提交，持有行锁）时安排行程 | 状态读穿过旧快照，插入在 FK 共享锁上等待，停用提交后行程成功落库 | 在状态检查处等待同一把行锁，停用提交后读到 `DISABLED` → 422，不留任何行程 |
| 换挂的目标酒店在读写之间被停用 | 换挂成功 | 422，且当天仍指向原酒店 |
| 两人同时显式提交状态时的 `STATUS` 审计日志 | 比较基准是普通查询读到的旧快照：`DISABLED → ACTIVE` 这类真实变化被漏记，别人刚完成的停用又被重复记成本次变化 | 状态判定、写入与日志同处酒店行锁内，日志只反映真实发生的变化 |
| 酒店 / 景点候选项超过 100 条 | 第 101 条起在界面上选不到 | 逐页取全量（`utils/paging.js`） |
| `PUT /admin/itinerary-days/{dayId}` 契约 | 只列 200 / 404，实现却会返回 422 | 契约补 `422 ValidationFailed` |

前端前两条与后端第 3～7 条在修复前都用测试复现过：把被测代码退回普通查询后，`HotelAssignmentConcurrencyIntegrationTest` 3/3 变红（行程被成功安排进已停用的酒店），`HotelStatusLogConcurrencyIntegrationTest` 2/2 变红（一条多记、一条漏记），加回行锁后全部通过。

`HotelService#delete` 的引用检查同样改到这把行锁内（与行程安排加锁顺序一致，先酒店行后子表），因此"被行程引用 → 409"不再依赖外键兜底；原有的外键竞态兜底保留为第二道防线。

## 契约变更（docs/API.md §12）

- **内容**：`PUT /admin/itinerary-days/{dayId}`、`PUT /admin/hotels/{hotelId}` 新增 `422 ValidationFailed` 响应；`ItineraryDayRequest.hotelId` 增加 `description`（停用酒店不能安排进新行程）。
- **兼容影响**：只新增"变更前就已经会返回"的错误响应（行程天数序号冲突、酒店资料字段校验在变更前即返回 422），成功响应、字段、类型、必填性、权限与状态流转均未变。调用方只需按 422 展示 `message`；前端 axios 拦截器已统一处理非 2xx 响应，无需修改字段映射。
- **确认状态**：本 PR 的契约修正由**项目层面评审决定**确认（"同意在两个 PUT 端点的 OpenAPI 中补 422；这是应当在 #42 内完成的契约修正"，见 PR #42 评论）。**未记录前端、测试成员的单独确认**，不得据此声称三方已分别确认；如需成员级确认，请在合并前补记。
- **仍未补齐（建议另开契约变更，不在本 PR 内单方面改动）**：`PUT /admin/attractions/{attractionId}`、`PUT /admin/itinerary-items/{itemId}`、`PUT /admin/guides/{guideId}`、`PUT /admin/staff/{staffId}`、`PUT /admin/articles/{articleId}` 以及若干 `PATCH .../status` 端点同样会在字段语义校验失败时返回 422，但契约未声明；各家的 `400`（契约外字段、JSON 格式错误）也普遍未列。

## 评审决定与后续安排

| 事项 | 决定 |
| --- | --- |
| 停用酒店不得再安排进新行程 | 采纳（本 PR 已实现）；已引用它的行程保留原酒店并允许修改其它内容 |
| 两个 PUT 端点补 422 | 采纳，属 #42 内应完成的契约修正 |
| 酒店资料并发编辑（静默"最后保存覆盖"） | **不接受长期如此**：参照团期做版本号与冲突提示。涉及请求与响应契约，另开 PR；#42 可先进 `dev`，该修复必须在发布到 `main` 前完成 |
| 列表不显示简介 / emoji 搜索截断 / 错误提示重复 | 体验问题，不阻断 #42 |
| 已上架线路改挂酒店 | 交由线路模块处理，不在本 PR 扩大范围 |
| 停用景点仍可被新引用 | 交由景点模块处理，不在本 PR 扩大范围 |

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

`HotelAssignmentConcurrencyIntegrationTest` 与 `HotelStatusLogConcurrencyIntegrationTest` 刻意不加 `@Transactional`：测试事务会把并发写入并进同一个连接与事务，交错构造不出来，未提交的夹具对独立连接也不可见。两个用例集都用真实 MySQL + 独立连接（必要时加工作线程）构造交错，结束后按外键倒序清理自建数据；未设置 `TRAVEL_MYSQL_TEST=true` 时整体跳过。

本轮结果：后端 **464 passed / 0 failed**（首轮 456；复审 +3 行程交错用例、+2 服务单测、+2 状态日志交错用例）；前端 **44 passed / 0 failed**（首轮 34）；前端生产构建通过；`steady validate ../docs/openapi.yaml` 通过。

## 未处理（按评审决定安排）

- **酒店资料并发编辑**（不接受的静默"最后保存覆盖"）：另开 PR，参照团期 `6d3ec91` 给酒店修改请求加 `version`（响应里回版本、冲突回 409）。涉及请求与响应契约，属于独立变更；#42 可先进 `dev`，但**该修复必须在发布到 `main` 前完成**。已在 `feat/hotel-version-lock` 实现：契约上把 `HotelUpsertRequest` 拆成 `HotelCreateRequest`（不含版本）与 `HotelUpdateRequest`（必填版本），并新增 409 `HOTEL_VERSION_CONFLICT`；实现口径见 `docs/HOTEL_VERSION_LOCK.md`。
- 体验问题（后台列表不展示 `intro`、搜索框 `maxlength` 按 UTF-16 码元截断、保存失败时弹窗与拦截器各提示一次）：暂不阻断 #42，单独排期。
- 范围外交给对应模块：`AdminRouteService#updateDay` 不校验线路是否已上架（线路模块）；行程项目的景点下拉不过滤停用景点（景点模块）。本 PR 不扩大范围。
