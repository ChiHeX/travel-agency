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
其中「时间戳由数据库维护」一节来自 `updated_at` 的修复：最新 `dev` 合入该修复分支之后重新跑过，
这一节的数字与反向对照属于该轮新建的证据，其余小节仍按历史记录阅读。

### 测试用例

交易模块共 **13 个测试类 / 142 条用例**（历史统计口径），全部通过（`TRAVEL_MYSQL_TEST=true`，真实 MySQL）。
表中最后两行由 `updated_at` 的修复新增。

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
| `UpdatedAtDatabaseMaintainedIntegrationTest` | **`updated_at` 必须由数据库维护**：取消订单、后台确认、支付回调三条路径都要真的推进该列（跨秒边界断言，避免"同一秒假绿"）；支付回调额外断言**支付单自己**那一列；取消与确认还断言**响应里的 `updatedAt` 与库一致**（只查库会漏掉"库对了、响应是旧的"） |
| `ConsultationUpdatedAtContractIntegrationTest` | **关闭咨询的响应必须带回库里的最新 `updatedAt`**：同一类错值（库已前进、响应仍是旧值）在 `ConsultationView` 上的回归，走 MockMvc 比对"响应 vs 库" |

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
不是大面积误伤。还原后全量恢复 **571/571**。（该次实验在 #55 分支上进行，当时全量 571 条；
本分支同步最新 `dev` 后的全量条数见下。）

#### 时间戳由数据库维护（真实 MySQL）

`updated_at` 被 `updateById` 写回旧值这件事，只有**跨过秒边界再看一眼**才暴露得出来：
`DATETIME` 没有小数位，插入与更新若落在同一秒，即使该列从未前进，两次读到的值也相等 ——
不跨秒的断言会假绿。用例因此在两次读之间等待 1.1 秒。

**A. 写入侧：`FieldStrategy.NEVER` 是否真的把写入权交回数据库**

```text
修复前（同一库、同一路径）
  cancel   : before=2026-10-07T18:02:12  after=2026-10-07T18:02:12   ← 冻结
  markPaid : before=2026-10-07T18:02:13  after=2026-10-07T18:02:13   ← 冻结

修复后
  cancel   : before=2026-10-07T18:02:41  after=2026-10-07T18:02:42   ← 前进 1 秒
  markPaid : before=2026-10-07T18:02:42  after=2026-10-07T18:02:43   ← 前进 1 秒
```

反向对照（只摘掉 `updatedAt` 上的注解、其余不动，同一套用例立刻转红）：

```text
取消是一次业务更新，updated_at 必须前进：before=2026-10-17T13:22:54 after=2026-10-17T13:22:54
回调入账是一次业务更新，订单 updated_at 必须前进：before=2026-10-17T13:22:56 after=2026-10-17T13:22:56
```

**B. 返回侧：写操作的响应是否带回新值**

写库对了不等于响应对了。`cancel` / `confirm` 直接返回 `OrderView`，
`ConsultationController.close` 直接返回 `ConsultationView` —— 如果把更新前读到的实体原样交给
`XxxView.from(...)`，库是新的、响应还是旧的。反向对照（只把响应处的回查去掉）：

```text
取消接口的响应必须带回库里的最新 updatedAt，不能是更新前的旧值
  ==> expected: <2026-10-17T13:21:59> but was: <2026-10-17T13:21:58>
确认接口的响应必须带回库里的最新 updatedAt，不能是更新前的旧值
  ==> expected: <2026-10-17T13:22>   but was: <2026-10-17T13:21:59>
关闭咨询接口的响应必须带回库里的最新 updatedAt，不能是更新前的旧值
  ==> expected: <2026-10-17T13:30:01> but was: <2026-10-17T13:29:59>
```

三处都只有一个断言的差距、都恰好差若干秒：**这不是"差不多对"，是明确的错值**。

这三条路径都做过分层核对，顺序是「先确认断言会红，再确认修复后转绿」——
否则很容易写出永远为真的断言（比如拿内存对象自己跟自己比）。

库里留下的那几行订单本身就是证据：修复前跑出来的行 `updated_at == created_at`，
修复后跑出来的行 `updated_at = created_at + 1s`，同库同表并列可见。

### 回归结果与前置条件

**本分支（已合入最新 `dev`）全量回归**：后端 **603 条用例，603 通过、0 失败、0 错误、0 跳过**
（`TRAVEL_MYSQL_TEST=true`，本机 MySQL 9.7，`mvn clean verify`，打包产物生成）。
本修复自身新增 4 条用例：取消订单、后台确认、支付回调三条时间戳路径，
以及关闭咨询的响应回查（`ConsultationUpdatedAtContractIntegrationTest`）；其余增量来自最新 `dev`
合入的其它模块。合并 `dev` 之前这套修复曾在 575 条全量上验证过一次，那一轮不能替代同步后的验证，
因此以本次 603 条的结果为准。

前端本轮未改代码：契约校验、**330 条前端测试**与生产构建均通过（保留既有大分包警告）。

> 环境注意：本机 Maven 默认由 Mockito 自附加 inline mock-maker，在没有权限派出 agent 的
> 受限环境里会让 269 条单测统一报
> `Could not initialize plugin: interface org.mockito.plugins.MockMaker`。
> 那不是代码问题，用 Mockito 官方推荐的做法把 jar 直接当 agent 传入即可：
> `mvn test -DargLine="-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar"`。

> ⚠️ **跑测试前必须先执行迁移 `010-add-hotel-accommodation.sql`。**
> 本机库未执行 010 时，`hotel.city` / `route_itinerary_day.accommodation_type` 会报
> `Unknown column`，**39 条与交易无关的用例（酒店 / 线路 / 导游）会一起变红**，
> 看起来像大面积回归。这是环境问题而不是代码问题 —— 本次实测确认过这一点
> （补 010 之后同一套代码从 39 红变为 0 红）。`012` 只影响判重查询效率，不执行也能通过。
>
> 测试结束后核对 12 张表的行数基线，确认零漂移。本次复核所用库的跑前 / 跑后完全一致
> （`travel_order 6 / payment 6 / refund 1 / departure 40 / guide 2 / sys_user 8 /
> sys_user_role 6 / travel_route 10 / favorite 3 / staff 1 / operation_log 1 / sys_message 3`）。
> 这组数字与历史记录里的库不同（历史为 `travel_order 34 / payment 34 / refund 8 / …`）——
> 本地库的数据量本来就不一样，需要核对的是**跑前跑后没有变化**，不是具体数量。
> 时间戳用例的 `tearDown` 还要显式清掉自己写入的 `operation_log`（`confirm` 会以测试用户为操作人
> 留痕，而 `fk_operation_log_operator` 指向 `sys_user`，不清就会在删用户时撞外键）。

### 未完成项与风险

- 🔴 **支付宝沙箱真实联调尚未完成（B-05）**：本环境**没有配置沙箱密钥**
  （`ALIPAY_APP_ID` 等四项），也没有公网可达的 `notify_url`，因此**尚未**演示
  "真实支付发起 → 官方验签回调 → 金额核对 → 重复回调 → 退款通过 → 结果待确认 → 重试"
  这一串真实动作。**当前所有支付/退款结论都来自测试替身与本地模拟支付，
  不能宣称支付链路已完成验收。** 需要的输入与方案见下。
- ⬜ **退款对账的定时收敛未做**：`PROCESSING`（待确认）目前依赖人工筛出后用同一请求号重试，
  没有定时任务自动向支付宝查询收敛。金额不大时够用，但缺少兜底。
- ⬜ **回调金额不符被拒时没有留痕**：`409 PAYMENT_AMOUNT_MISMATCH` 是一个"外部事件被拒绝"却查不到的缺口。
  补它要先定两件事：操作人取值（回调没有对应的 `sys_user`，可能要新增一个系统账号，而不是给现有账号
  硬编一个操作人），以及**留痕怎么活过回滚** —— 独立事务、独立事件日志、收窄 `noRollbackFor` 三条路
  都可行，取舍见[交易模块设计](../design/TRADE_MODULE.md)的 §6.1。这条路径**业务本来就该整体回滚**
  （金额不符的订单不该被推进），所以倾向用前两者：不宜为了留一条日志就取消整个业务事务的回滚。
  回调异常日志也不必强行塞进后台操作日志（它是后台员工视角的视图）。
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
