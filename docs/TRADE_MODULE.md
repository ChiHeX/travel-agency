# 交易模块（成员 B）设计与验证

覆盖订单、支付、支付回调、退款、评价五条链路。所有端点挂在全局 `context-path: /api` 之下，
契约的唯一事实来源是 `docs/openapi.yaml` + `docs/API.md`。

> 本文记录**当前实现**的设计取舍与验证证据。历史回归报告（`AUTH_REGRESSION.md`、
> `PR9_REGRESSION.md`）里关于支付适配器的旧结论已被后续实现取代，两处都已补记指向本文。

## 1. 端点清单

| 端 | 方法 | 路径 | 说明 |
|---|---|---|---|
| 用户 | POST | `/orders` | 下单；占用团期名额 |
| 用户 | GET | `/orders` | 我的订单分页 |
| 用户 | GET | `/orders/{orderNo}` | 订单详情（含出行人快照） |
| 用户 | POST | `/orders/{orderNo}/pay` | 发起支付，返回收银台地址 |
| 用户 | POST | `/orders/{orderNo}/cancel` | 取消未支付订单，释放名额 |
| 用户 | POST | `/orders/{orderNo}/refunds` | 申请退款（支持 `Idempotency-Key`） |
| 用户 | POST | `/orders/{orderNo}/reviews` | 评价（订单已完成、每单一次） |
| 后台 | GET | `/admin/orders`、`/admin/orders/{orderNo}` | 订单列表与详情 |
| 后台 | POST | `/admin/orders/{orderNo}/confirm` | **确认报名**（业务复核 + 名额迁移） |
| 后台 | GET | `/admin/refunds`、`/admin/refunds/{id}` | 退款申请列表与详情 |
| 后台 | POST | `/admin/refunds/{id}/approve` | 审核通过（触发真实出款） |
| 后台 | POST | `/admin/refunds/{id}/reject` | 审核拒绝（需填写意见） |
| 后台 | GET | `/admin/reviews` | 评价列表 |
| 后台 | PATCH | `/admin/reviews/{id}/status` | 评价可见状态 VISIBLE / HIDDEN |
| 回调 | POST | `/payments/alipay/notify` | 支付宝异步通知（公开，必须验签） |

## 2. 状态机

### 2.1 订单

```text
WAIT_PAY ──支付成功──> PAID_WAIT_CONFIRM ──确认报名──> CONFIRMED ──出团──> TRAVELLING ──行程结束──> COMPLETED
    │                         │                          │
    └──取消──> CANCELLED      └──申请退款──> REFUND_APPLYING ──出款成功──> REFUNDED
                                              └──审核拒绝──> 回到申请前的业务状态
```

- `PAID_WAIT_CONFIRM` 与 `CONFIRMED` 都可以申请退款，退款单用 `original_order_status`
  记住申请前的状态，决定名额回退到 `reserved_people` 还是 `confirmed_people`
  （由 `OrderStatusReservedValuesTest` 与退款用例共同锁定）。
- **`REFUND_PROCESSING` 与 `REFUND_REJECTED` 是契约保留值，实现不写入订单**：退款的"处理中"
  记在 `refund.status` 上，拒绝则把订单直接恢复成申请前状态。这一约束由
  `OrderStatusReservedValuesTest` 拦住，避免有人悄悄改变状态机而文档还写着旧流程。

### 2.2 退款单

```text
APPLYING ──审核通过（入口抢占）──> PROCESSING ──出款成功（出口幂等闸门）──> REFUNDED
    └──────────────────审核拒绝──────────────────────────────> REJECTED
```

`PROCESSING` **不是**一闪而过的中间态，而是"出款已发出去、结果尚未确认"的**持久**状态：

- 它出现的原因是支付宝回 `code=10000` 但 `fund_change` 不是 `Y`（或字段缺失），且用同一
  `out_request_no` 调退款查询也没拿到确定结论 —— 此时**钱可能已经退出去**。
- 因此不能退回 `APPLYING`：那会让管理员还能点"拒绝"，而拒绝分支会把订单恢复成申请前状态，
  造出「钱退了、后台显示退款被拒、订单仍已支付」这种对不上账且无法自证的组合。
- 收敛方式是**用同一请求号再点一次"同意"**（支付宝按 `out_request_no` 幂等，不会重复出款）。

## 3. 并发与事务

| 场景 | 机制 |
|---|---|
| 下单 / 取消 / 确认名额迁移 | 单条条件 UPDATE：`WHERE COALESCE(reserved,0)+COALESCE(confirmed,0)+N <= max_people`，影响行数 ≠ 1 即回 409 `DEPARTURE_CAPACITY_INSUFFICIENT` |
| 同一订单并发确认 | `SELECT ... FOR UPDATE` 订单行锁 + 状态迁移条件 UPDATE（`WHERE id=? AND status='PAID_WAIT_CONFIRM'`）双保险 |
| 同一订单并发申请退款 | 订单行锁把申请串行化，后到者看到已有 `APPLYING`/`PROCESSING` 申请 → 409 `REFUND_ALREADY_APPLYING` |
| 同一退款单并发审核 | 入口抢占 `APPLYING → PROCESSING`；出口再以 `WHERE status <> 'REFUNDED'` 抢占 `→ REFUNDED` 兼作**幂等闸门** |
| 重复下单 / 重复提交 | `idempotency_record`（`Idempotency-Key` 请求头）记录并重放首次业务结果 |
| 支付回调重复投递 | 按 `payment_no` + 订单状态判定，入账只发生一次 |

**原则**：所有不可重复的副作用（名额迁移、线路有效报名数、订单/支付单状态、站内通知）
都排在"抢到了某条带旧状态条件的 UPDATE"之后，靠影响行数而不是靠事务回滚来决定谁执行。

## 4. 确认报名的业务复核（B-02）

`POST /admin/orders/{orderNo}/confirm` 在此前只检查订单状态、团期状态与容量。现在按 PRD §11.1
**重新核对**三项，全部通过才允许进入 `CONFIRMED`：

| 复核项 | 不通过的原因码（`code` 恒为 `ORDER_AUDIT_ANOMALY`） |
|---|---|
| 订单 `paymentStatus` 与支付单 `status` 都必须是 `PAID` | `PAYMENT_NOT_SETTLED` |
| 出行人快照条数 = `adult_count + child_count` | `TRAVELER_SNAPSHOT_MISMATCH` |
| 每条快照的姓名 / 证件类型 / 证件号码都非空 | `TRAVELER_IDENTITY_INCOMPLETE` |

- **复核点刻意排在所有写入之前**，因此异常订单不会进入 `CONFIRMED`，也不会改名额、改线路统计。
- `confirm` 上的 `@Transactional(noRollbackFor = OrderAuditAnomalyException.class)` 让这个异常
  **不回滚**事务：唯一被提交的是"一条审计留痕 + 一条站内通知"。若让它回滚，通知会一起消失，
  用户就看不到异常说明了。

## 5. 报名审核异常的站内通知（B-03）

- **触发**：上面任一复核项不通过时（PRD §29「订单审核异常」）。
- **可见性**：用户通过 `GET /messages` 看到 `type = ORDER_AUDIT_ANOMALY` 的通知，标题形如
  「订单 {orderNo} 报名审核异常：{原因短标签}」，正文说明原因并提示"订单仍保留在待确认状态，
  工作人员处理后可重新确认"。
- **去重**：按 `user_id + type + title` 精确匹配判重，同一订单 + **同一异常原因**只发一次。
  原因发生变化（例如先缺证件号、补齐后又发现人数不符）会各通知一次，不把新问题静默吞掉。
  确认动作已被订单行锁串行化，不存在并发重复插入。
- **脱敏**：接口 `message` 与通知正文只回"第 N 位出行人缺少哪个字段"，**不含姓名与证件号**
  （对齐 `API.md` §10 与 PRD §53.1/§53.2）。这一条有专门用例断言。
- **索引**：判重查询由迁移 `012-add-message-audit-dedup-index.sql` 加
  `idx_message_user_type_title (user_id, type, title)` 支撑。**只加普通索引，不加唯一键**：
  去重是业务判据而不是库约束 —— 这条判重查询跑在 `confirm` 的订单行锁之内（确认动作已被
  串行化），不需要唯一键当并发闸门；而唯一键必须包含 `title`，标题里已带订单号与原因短标签，
  不同订单、不同原因的标题本就不同，加了也不会互相冲突。普通索引在这里只解决查询效率。

## 6. 审计一致性（B-04）

`operation_log` 的写入全部移入 Service 的业务事务内，Controller 不再各自记录：

| 场景 | `operationType` | `result` |
|---|---|---|
| 确认报名 | `CONFIRM` | `SUCCESS` |
| 报名复核异常 | `AUDIT_ANOMALY` | `FAILURE` |
| 退款审核通过 | `APPROVE` | `SUCCESS` |
| 退款审核拒绝 | `REJECT` | `SUCCESS` |
| 出款结果待确认 | `APPROVE_UNCONFIRMED` | `SUCCESS` |
| 评价可见状态变更 | `STATUS` | `SUCCESS` |

- **为什么"待确认"是 `SUCCESS` 而不是 `FAILURE`**：契约 `OperationLog.result` 只有两个取值，
  而"出款已发出去、结果未确认"这一刻钱可能已经退出去。标成 `FAILURE` 会被读成"退款被拒、
  订单已恢复"，正好是这套设计要避免的误读。区分靠的是**独立的 `operationType`**，详情里写清"结果待确认"。
- **被并发闸门挡下、没有真正推进状态的请求不写留痕**：它没有产生任何操作。
- **事务边界**：`processRefund` 是 `approveRefund` / `rejectRefund` 的共用实现，**不是事务入口**
  （自调用，Spring 代理不生效，因此它不标 `@Transactional`）。`noRollbackFor` 挂在两个公开入口上。

## 7. 支付与出款链路

### 7.1 收银台

四项配置（`ALIPAY_APP_ID`、`ALIPAY_APP_PRIVATE_KEY`、`ALIPAY_PUBLIC_KEY`、`ALIPAY_NOTIFY_URL`）
**全配齐**才生成真实沙箱收银台链接，`notify_url` 作为公共参数参与签名；不齐时回退占位地址，
接口仍有稳定形状。详见 `README.md` 的「支付宝沙箱」。

### 7.2 回调入账

- 验签走**支付宝官方 SDK 的 RSA2**，并**始终精确核对 `app_id`**；配置了 `ALIPAY_SELLER_ID`
  时再核对 `seller_id`。
- 自建 HMAC 路径只在**未配置沙箱密钥**时作为降级保留，**不代表官方验签**。
- 支付结果以服务端验签后的回调为准，不以浏览器跳转为准；按支付单与订单状态保证重复投递只入账一次。

### 7.3 出款（退款）

- **fail-closed**：出款配置不齐直接 409 `REFUND_NOT_CONFIGURED` 并列出缺失的环境变量名（不泄露密钥），
  绝不落 `REFUNDED`。
- **出款排在所有状态写入之前**：拿到确定的成功响应后才释放名额、落 `REFUNDED`、回退线路计数。
- **幂等键恒定**：`out_request_no = "RF" + refundId`，同一退款单的重试复用同一请求号，
  支付宝侧据此幂等，不会重复出款。
- **判定不看 SDK 的 `isSuccess()`**：必须 `code == "10000"` **且** `fund_change == "Y"` 才算确定成功；
  `code` 非空且不是 `10000` 视为明确失败（钱没动，可重试或拒绝）；
  `fund_change` 为 `N`/缺失则用同一请求号调 `alipay.trade.fastpay.refund.query` 复核，
  仍无结论才落到 `PROCESSING`（待确认）。

### 7.4 本地模拟支付

`local-payment & !prod & !production` 且 `server.address` 为回环地址时才注册，默认 404。
渠道 `LOCAL_SIMULATION`、交易号 `LOCAL-` 前缀，复用 `OrderService.markPaid` 入账；
模拟订单**不支持**退款（申请与审核都回 409 `LOCAL_PAYMENT_REFUND_UNSUPPORTED`，不调支付宝网关）。

> ⚠️ **模拟支付不能用来宣称"支付链路已完成"**：它不经过支付宝验签、不出网、不动资金。
> 真实验签、金额核对、重复回调、出款与待确认重试必须在沙箱里单独验证（见 §9 未完成项）。

## 8. 测试用例

交易模块共 **12 个测试类 / 141 条用例**，全部通过（`TRAVEL_MYSQL_TEST=true`，真实 MySQL）。

| 测试类 | 覆盖 |
|---|---|
| `OrderServiceCreateTest` | 下单价格快照、出行人快照、名额占用、幂等 |
| `OrderServicePaymentTest` | 发起支付、回调入账、金额核对、重复回调、收银台闸门 |
| `OrderServiceConfirmTest` | **确认报名**：成功返回 `OrderEnvelope`、状态守卫、团期关闭、状态迁移闸门、名额抢占、三项复核（支付未到账 / 快照数量不符 / 实名缺失且不泄露姓名证件号）、重复异常只通知一次 |
| `OrderServiceRefundTest` | 退款申请与审核：出款成功才落 `REFUNDED`、名额只释放一次、配置不齐 fail-closed、明确失败 503、结果未确认、待确认重试与并发、请求号恒定、拒绝恢复原状态、审计留痕 |
| `OrderServiceReviewTest` | 评价唯一性、未完成不能评价、越权、线路评分重算、**后台改可见状态 + 审计留痕** |
| `DepartureCapacityConcurrencyIntegrationTest` | 名额守恒压测（30 抢 10）、单车超容量、**并发确认只生效一次**、并发退款审核、**审核异常"业务回滚 + 留痕通知提交"** |
| `RefundPendingConfirmationIntegrationTest` | 「出款结果未确认」的事务语义：新连接读到持久 `PROCESSING`、拒绝被拦、同请求号重试收敛、**待确认与通过的审计留痕都已提交** |
| `TradingFlowContractIntegrationTest` | 端到端契约：报名→支付→回调→确认→出团→完成→评价，以及取消/退款全链路 |
| `LocalPaymentIntegrationTest` / `LocalPaymentServiceTest` | 本地模拟支付的注册条件、幂等、与支付宝路径互斥 |
| `OrderStatusReservedValuesTest` | 状态枚举保留值不被写入 |
| `AlipayGatewayClientTest` | 验签、`app_id`/`seller_id` 核对、出款结果三态分类 |

### 8.1 并发实测数据（真实 MySQL，8 线程）

```text
[CONFIRM-RACE]  threads=8 succeeded=1 rejected=7 dbReserved=0 dbConfirmed=2 validBookingCount=1 confirmLogs=1
[REFUND-RACE]   threads=8 succeeded=1 rejected=7 dbReserved=0 dbConfirmed=0 validBookingCount=0
[CAPACITY-STRESS] requests=30 capacity=10 succeeded=10 rejected=20 dbReserved=10 dbConfirmed=0 dbMax=10
```

- 同一订单 8 个并发确认：**只成功 1 次**，其余 7 次 `409 ORDER_STATE_CONFLICT`；
  名额只从 `reserved` 迁到 `confirmed` 一次（2 人），线路有效报名数只 `+1`，审计留痕恰好 1 条。
- 30 个并发下单抢 10 个名额：成功数恰好等于容量，占用不超上限，无超额订单与支付单。

## 9. 回归结果与前置条件

**最新全量回归**：后端 **570 条用例，570 通过、0 失败、0 跳过**
（`TRAVEL_MYSQL_TEST=true`，本机 MySQL 9.7）。本次改动新增 9 条用例（`561 → 570`）。

> ⚠️ **跑测试前必须先执行迁移 `010-add-hotel-accommodation.sql`。**
> 本机库未执行 010 时，`hotel.city` / `route_itinerary_day.accommodation_type` 会报
> `Unknown column`，**39 条与交易无关的用例（酒店 / 线路 / 导游）会一起变红**，
> 看起来像大面积回归。这是环境问题而不是代码问题 —— 本次实测确认过这一点
> （补 010 之后同一套代码从 39 红变为 0 红）。`012` 只影响判重查询效率，不执行也能通过。
>
> 测试结束后核对 12 张表的行数基线，确认零漂移（`travel_order 34 / payment 34 / refund 8 /
> departure 20 / guide 3 / sys_user 15 / sys_user_role 15 / travel_route 10 / favorite 3 /
> staff 1 / operation_log 27`）。

## 10. 未完成项与风险

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

### 10.1 B-05 需要什么

| 项 | 说明 |
|---|---|
| 沙箱应用 | 开放平台沙箱应用的 `APPID` 与「接口加签方式」里生成的**应用私钥** |
| 支付宝公钥 | 沙箱应用页给出的**支付宝公钥**（用于验签） |
| 买家账号 | 沙箱买家账号与登录/支付密码，用于在收银台完成一笔真实支付 |
| `notify_url` | 必须**公网可达**。本机可用内网穿透（如 ngrok / cpolar）临时映射 8080 端口 |
| 可选 | `ALIPAY_SELLER_ID`（配置后会一并核对商户 UID） |

配置方式见 `README.md` 的「支付宝沙箱」。四项齐备后会产出**脱敏**联调报告
（记录每一步的请求/响应结构与 `code`/`fund_change`，不记录私钥、公钥全文与完整交易号）。
