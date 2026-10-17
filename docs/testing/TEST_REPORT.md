# 项目测试报告

## 1. 范围与证据口径

整理日期：2026-10-16。本报告汇总仓库已有的认证、导游与攻略、交易和后台回归记录；本次仅整理文档，没有重新运行应用测试。
各阶段的测试数量属于各自版本，不能相加，也不能视为当前提交的全量结果。当前版本是否通过应核对对应提交的 CI 报告。
数据库测试需启用 `TRAVEL_MYSQL_TEST=true`；未启用而跳过的结果不构成数据库验收。
模拟 API、SDK 测试替身和本地模拟付款不等于真实支付宝沙箱或浏览器人工验收通过。

## 2. 认证与权限回归

历史基线：2026-09-17，`9d9cad2`。原记录为后端 45 个测试通过、后端打包和前端构建通过。

| 检查项 | 该轮验证结果 |
|---|---|
| 注册 | 返回 201 和 Location；ID 为字符串，expiresIn 为数字，createdAt 可解析为带时区时间 |
| 重复账号 | 返回 409，包含数据库唯一键冲突 |
| 清空个人资料 | 可空字段确实写入 SQL NULL |
| 停用账号 | 旧 JWT 无法继续访问受保护资源 |
| CORS | 允许白名单 Origin 及 Idempotency-Key，拒绝非白名单 Origin；检查实际响应头 |

对应 `AuthContractIntegrationTest`，使用真实 Spring HTTP 链、JWT、MyBatis-Plus 和 MySQL，测试数据随事务回滚。
原记录中的 HMAC 支付结论仅适用于当时版本，不作为现行支付能力说明。

## 3. 导游、攻略与请求校验回归

历史基线：2026-09-18，PR #9 的 `7a08d40` 叠加认证修复。原记录为后端 56 个测试通过、零失败、零错误、零跳过，打包及前端构建通过。

- 导游建档在事务内创建登录账号、角色和导游资料，返回 201 / Location；重复账号返回 409。
- 导游新增与启停限 ADMIN；工作人员可以编辑基础资料，不能改绑账号或直接改状态。
- 导游更新保留账号关联和 username，可清空简介；不存在时返回 404。
- 停用账号后旧令牌与密码登录均被拒绝，重新启用恢复访问。
- 编辑攻略保留发布状态、作者和发布时间；可空字段可清空，发布状态由专用端点控制。
- 未知 JSON 字段返回 400；标题、正文与导游输入长度接受契约校验。
- 发起支付缺 Idempotency-Key 返回 400，长度不符返回 422。

对应 `Pr9ContractIntegrationTest`；原轮次不覆盖前端导游页面、真实沙箱和并发压测。
旧文档中关于“只有 HMAC”“退款处理中不可持久观察”等现状描述已被后续实现替代，不再作为当前限制重复维护。

## 4. 后台回归与人工验收安排

日期：2026-10-16。范围：接替 PR #63，保留原提交，补齐固定角色工作人员管理及关键前端回归。
自动化测试使用模拟 API，不代表真实数据库、第三方支付或浏览器人工验收通过。

### 自动化用例与结果

| 范围 | 用例位置 | 本地结果 |
|---|---|---|
| 员工创建、编辑、固定字段、重复错误、重试、密码字节、提交锁、取消 | `frontend/src/components/__tests__/StaffFormDialog.spec.js` | 通过 |
| 用户／员工页面挂载、展示、搜索分页、启停确认、工作人员 ID、失败状态、列表竞态 | `frontend/src/views/admin/__tests__/AdminUsersView.regression.spec.js` | 通过 |
| 原 PR 作者在接手期间补充的双模式、表单接线、启停、分页和列表测试 | `frontend/src/views/admin/__tests__/AdminUsersView.spec.js` | 保留原提交，16 个测试通过 |
| 八种实际订单筛选、确认取消、订单详情竞态、退款意见、待确认安全重试、各后台列表竞态 | `frontend/src/views/admin/__tests__/AdminWorkflows.spec.js` | 通过 |
| ADMIN／STAFF／GUIDE／USER 路由守卫及后台菜单 | `frontend/src/router/__tests__/adminAccess.spec.js` | 通过 |
| 全量前端测试 | 项目既有检查入口 | 34 个文件、330 个测试全部通过；接手新增 39 个测试，另保留作者补充的 16 个测试 |
| 接口契约校验、生产构建 | 项目既有检查入口 | 均通过；构建保留既有大分包警告 |

后端本轮未改动；本地未运行 MySQL 回归。新分支的远程 CI 需单独确认，不以原 PR 的绿色状态替代。
构建已有大分包警告不影响产物生成，本轮不做无关打包重构。

### 缺陷与复测

| 缺陷 | 跟进责任 | 状态与复测证据 |
|---|---|---|
| 用户／员工页缺少模式、弹窗、标签变量 | 后台前端负责人 | 已修复，双模式挂载及入口回归通过 |
| 员工恢复未确认 | 后台前端负责人 | 已修复，启停与取消请求断言通过 |
| 把保留订单状态误称为退款单枚举 | 后台前端负责人 | 已纠正代码注释，订单选项精确断言通过 |
| 列表旧响应覆盖新筛选 | 后台前端负责人 | 已修复并补各后台列表竞态测试 |
| 保存员工期间仍可用 Escape 关闭表单 | 后台前端负责人 | 已修复，提交锁与关闭策略测试通过 |

上表责任对应 PRD 的模块分工，不代表已向团队成员发送任务或取得人工验收签字。

### 四个主流程验收安排

验收范围依据[产品需求文档](../PRD.md)中的 MVP 验收主流程。

| 流程 | 验收步骤与预期 | 协同角色 | 实际执行状态 |
|---|---|---|---|
| 游客报名 | 建线路／行程／团期并分配导游 → 发布 → USER 报名 → 沙箱支付 → STAFF 核实确认；订单、名额和消息一致 | 后台前端负责人协调；交易后端、业务资源后端、用户端前端负责人参与 | 未执行：需运行环境和支付沙箱联合验收 |
| 导游执行 | GUIDE 仅查看本人团期及脱敏名单 → 开始 → 完成；他人团期访问失败 | 后台前端负责人协调；业务资源后端与交易后端负责人参与 | 未执行：需真实团期与多账号浏览器验收 |
| 评价 | USER 对已完成订单评价 → STAFF 查看／隐藏 → 公开页面同步；重复或越权评价失败 | 后台前端负责人协调；交易后端与用户端前端负责人参与 | 未执行：需完整已完成订单链路 |
| 退款 | USER 申请 → STAFF 填意见 → 同意／拒绝；待确认时只能安全重试且不重复出款 | 后台前端负责人协调；交易后端与用户端前端负责人参与 | 未执行：需支付沙箱及异常结果复核 |

执行时补充：环境／构建提交、测试账号类型（不写密码）、执行人、执行时间、订单或退款单号、
实际结果、证据和关联缺陷。发现问题记录复现步骤，修复后由交叉测试人员复测；未执行项不得改为通过。

### 任务交付口径

工作人员创建、编辑、启停、搜索分页，以及订单退款状态展示、权限检查和后台交互已完成实现核查。
关键自动化测试与实际运行记录已补齐，后台操作手册已链接现有用户端手册。

测试协调方面，已汇总用例、缺陷和验收安排；游客报名、导游执行、评价和退款四条流程的联合人工验收
仍待执行，不能标成全部完成。

## 5. 交易测试证据与待验收事项

以下保留原交易模块报告的历史数据（记录于成员 B 的交易交付阶段，未提供精确构建 SHA），不代表本次整理重新验证。环境问题与未完成项也是该轮记录，需在后续联合验收时逐项复核。

### 测试用例

交易模块共 **13 个测试类 / 142 条用例**，全部通过（`TRAVEL_MYSQL_TEST=true`，真实 MySQL）。

| 测试类 | 覆盖 |
|---|---|
| `OrderServiceCreateTest` | 下单价格快照、出行人快照、名额占用、幂等 |
| `OrderServicePaymentTest` | 发起支付、回调入账、金额核对、重复回调、收银台闸门 |
| `OrderServiceConfirmTest` | **确认报名**：成功返回 `OrderEnvelope`、状态守卫、团期关闭、状态迁移闸门、名额抢占、三项复核（支付未到账 / 快照数量不符 / 实名缺失且不泄露姓名证件号）、重复异常只通知一次 |
| `OrderServiceRefundTest` | 退款申请与审核：出款成功才落 `REFUNDED`、名额只释放一次、配置不齐 fail-closed、明确失败 503、结果未确认、待确认重试与并发、请求号恒定、拒绝恢复原状态、审计留痕 |
| `OrderServiceReviewTest` | 评价唯一性、未完成不能评价、越权、线路评分重算、**后台改可见状态 + 审计留痕** |
| `DepartureCapacityConcurrencyIntegrationTest` | 名额守恒压测（30 抢 10）、单车超容量、**并发确认只生效一次**、并发退款审核、**审核异常"业务回滚 + 留痕通知提交"** |
| `RefundPendingConfirmationIntegrationTest` | 「出款结果未确认」的事务语义：新连接读到持久 `PROCESSING`、拒绝被拦、同请求号重试收敛、**待确认与通过的审计留痕都已提交** |
| `OrderAuditRollbackIntegrationTest` | **审计写入失败 ⇒ 业务修改一起回滚**：用 `operation_log.operator_id` 的外键失败注入审计写失败，断言订单状态、`confirmed_at`、团期名额、线路统计、确认留痕、确认通知全部随事务回滚（含合法操作人的对照组） |
| `TradingFlowContractIntegrationTest` | 端到端契约：报名→支付→回调→确认→出团→完成→评价，以及取消/退款全链路 |
| `LocalPaymentIntegrationTest` / `LocalPaymentServiceTest` | 本地模拟支付的注册条件、幂等、与支付宝路径互斥 |
| `OrderStatusReservedValuesTest` | 状态枚举保留值不被写入 |
| `AlipayGatewayClientTest` | 验签、`app_id`/`seller_id` 核对、出款结果三态分类 |

#### 并发实测数据（真实 MySQL，8 线程）

```text
[CONFIRM-RACE]  threads=8 succeeded=1 rejected=7 dbReserved=0 dbConfirmed=2 validBookingCount=1 confirmLogs=1
[REFUND-RACE]   threads=8 succeeded=1 rejected=7 dbReserved=0 dbConfirmed=0 validBookingCount=0
[CAPACITY-STRESS] requests=30 capacity=10 succeeded=10 rejected=20 dbReserved=10 dbConfirmed=0 dbMax=10
```

- 同一订单 8 个并发确认：**只成功 1 次**，其余 7 次 `409 ORDER_STATE_CONFLICT`；
  名额只从 `reserved` 迁到 `confirmed` 一次（2 人），线路有效报名数只 `+1`，审计留痕恰好 1 条。
- 30 个并发下单抢 10 个名额：成功数恰好等于容量，占用不超上限，无超额订单与支付单。

#### 审计失败时的事务原子性（真实 MySQL）

`OrderService.confirm` 的写入顺序是「订单状态迁移 → 团期名额迁移 → 线路统计回填 → **写审计**」，
所以"审计与业务在同一个事务"这件事，只有在审计写失败时才能验证。让审计写失败用的是库自己的
约束（`operation_log.operator_id` 的外键 `fk_operation_log_operator`），不建触发器、不改表结构、
也不 mock 掉 Recorder，因此走的仍是真实事务与真实连接。

```text
[对照] 合法操作人  -> CONFIRMED, confirmed_at 已写, confirmLogs=1
[实验] 不存在的操作人 -> 抛 DataIntegrityViolationException（fk_operation_log_operator）
                       订单 PAID_WAIT_CONFIRM, confirmed_at=NULL,
                       团期 reserved/confirmed 均回到调用前, validBookingCount 回到调用前,
                       confirmLogs=0, 确认通知=0
```

**反向对照（证明这条用例真的守得住这个性质）**：把那条确认留痕包进 `try/catch` 吞掉失败 ——
等价于回到 PR 之前的形状（那时审计写在 Controller 里、业务事务已经提交），
该用例立刻变红：

```text
Expected org.springframework.dao.DataIntegrityViolationException to be thrown, but nothing was thrown.
```

同一变异下全量 **571 条中只红这一条**（`succeeded=570 failed=1`），说明断言精准指向这条性质，
不是大面积误伤。还原后全量恢复 **571/571**。

### 回归结果与前置条件

**该轮全量回归**：后端 **571 条用例，571 通过、0 失败、0 跳过**
（`TRAVEL_MYSQL_TEST=true`，本机 MySQL 9.7）。该轮改动新增 10 条用例（`561 → 571`；
末一条 `OrderAuditRollbackIntegrationTest` 是应评审意见补的"审计写失败 ⇒ 业务回滚"数据库测试）。

> ⚠️ **跑测试前必须先执行迁移 `010-add-hotel-accommodation.sql`。**
> 本机库未执行 010 时，`hotel.city` / `route_itinerary_day.accommodation_type` 会报
> `Unknown column`，**39 条与交易无关的用例（酒店 / 线路 / 导游）会一起变红**，
> 看起来像大面积回归。这是环境问题而不是代码问题 —— 本次实测确认过这一点
> （补 010 之后同一套代码从 39 红变为 0 红）。`012` 只影响判重查询效率，不执行也能通过。
>
> 原报告记录测试结束后核对表行数基线，确认零漂移（`travel_order 34 / payment 34 / refund 8 /
> departure 20 / guide 3 / sys_user 15 / sys_user_role 15 / travel_route 10 / favorite 3 /
> staff 1 / operation_log 27`）。

### 未完成项与风险

- 🔴 **支付宝沙箱真实联调尚未完成（B-05）**：本环境**没有配置沙箱密钥**
  （`ALIPAY_APP_ID` 等四项），也没有公网可达的 `notify_url`，因此**尚未**演示
  "真实支付发起 → 官方验签回调 → 金额核对 → 重复回调 → 退款通过 → 结果待确认 → 重试"
  这一串真实动作。**当前所有支付/退款结论都来自测试替身与本地模拟支付，
  不能宣称支付链路已完成验收。** 需要的输入与方案见下。
- ⬜ **退款对账的定时收敛未做**：`PROCESSING`（待确认）目前依赖人工筛出后用同一请求号重试，
  没有定时任务自动向支付宝查询收敛。金额不大时够用，但缺少兜底。
- ⬜ **前端联调未回执**：订单详情、退款审核、评价管理三个页面尚未由前端反馈联调结果；
  契约字段与 `openapi.yaml` 一致，但页面行为未验收。
- ⬜ **共享 dev 库的 `005` 索引仍未生效**（`travel_order` 缺 `idx_order_created_at`），
  与本次改动无关，属历史遗留。

#### B-05 需要什么

| 项 | 说明 |
|---|---|
| 沙箱应用 | 开放平台沙箱应用的 `APPID` 与「接口加签方式」里生成的**应用私钥** |
| 支付宝公钥 | 沙箱应用页给出的**支付宝公钥**（用于验签） |
| 买家账号 | 沙箱买家账号与登录/支付密码，用于在收银台完成一笔真实支付 |
| `notify_url` | 必须**公网可达**。本机可用内网穿透（如 ngrok / cpolar）临时映射 8080 端口 |
| 可选 | `ALIPAY_SELLER_ID`（配置后会一并核对商户 UID） |

配置方式见 `README.md` 的「支付宝沙箱」。四项齐备后会产出**脱敏**联调报告
（记录每一步的请求/响应结构与 `code`/`fund_change`，不记录私钥、公钥全文与完整交易号）。
