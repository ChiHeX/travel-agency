# 交易模块详细设计

覆盖订单、支付、支付回调、退款、评价五条链路。所有端点使用 `/api` 前缀，
契约的唯一事实来源是 `docs/openapi.yaml` + `docs/API.md`。

> 本文保留交易模块设计；历史验证证据及待验收事项统一见[测试报告](../testing/TEST_REPORT.md)。

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

## 4. 确认报名的业务复核

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

## 5. 报名审核异常的站内通知

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

## 6. 审计一致性

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

### 6.1 留痕的边界：哪些交易动作**不**进 `operation_log`

`operation_log` 是**后台写操作**日志（`GET /admin/logs`，契约 `x-roles: [ADMIN]`），
不是"所有状态变化"的流水。判据是**动作的执行者是不是后台员工**：

| 动作 | 执行者 | 进 `operation_log` | 靠什么追溯 |
|---|---|---|---|
| 确认报名 / 退款审核 | 后台员工 | ✅ | 上表 |
| 用户取消订单 | 用户本人 | ❌ | `travel_order.status` + `cancelled_at` |
| 支付回调入账 | 支付宝（第三方） | ❌ | `payment.third_party_trade_no` + `paid_at` |
| 用户提交退款申请 | 用户本人 | ❌ | `refund` 行本身 + `travel_order.status` |

两条不写的理由并不相同：

- **取消订单**：`operator_id` 的外键指向 `sys_user`，填用户 id 技术上可行，但这份日志是**后台视图**，
  混进用户自助动作会把"员工做过什么"淹掉；而这次变更的可追溯性已由订单自己的
  `status` / `cancelled_at` 完整承担。
- **支付回调**：**没有合法操作人** —— 支付宝不是 `sys_user`，而 `operator_id` 是 `NOT NULL` 且带外键。
  硬填订单所属用户会伪造出一条"该用户执行了后台操作"的假记录，比不记更糟。

> ⚠️ **已知缺口**：回调**因金额不符被拒**（`409 PAYMENT_AMOUNT_MISMATCH`）目前没有任何留痕 ——
> 属于"外部事件被拒绝却查不到"。补它要先定操作人取值（新增系统账号？），再定**留痕怎么活过回滚**：
> 三种做法都可行，取舍不同（见下）。这一缺口已登记在
> [测试报告](../testing/TEST_REPORT.md)的「未完成项与风险」里，随联合验收一并复核。

**让留痕活过业务回滚的可选做法**（不是只有 `noRollbackFor` 一条路）：

| 做法 | 机制 | 代价 / 适用 |
|---|---|---|
| 收窄 `noRollbackFor` | 只让指定异常不回滚，事务提交 | 本模块 `confirm` / `approveRefund` 用的就是它。前提是**该异常下确实没有需要回滚的业务写入** |
| 留痕走独立事务 | 留痕方法标 `@Transactional(propagation = REQUIRES_NEW)` | 业务照常回滚、留痕独立提交，两者互不牵连；需要另开连接，且要接受"业务失败但留痕成功"是预期状态 |
| 落独立事件日志 | 留痕写进与业务表无关的日志/消息表，随业务事务一起回滚后再由外层补记 | 最干净，但要多一条投递路径 |

**选择原则**：为保留一条日志而**笼统地取消整个业务事务的回滚**是不可取的 —— 那会把本该撤销的
业务写入一起提交，用数据一致性换日志，得不偿失。所以像「回调金额不符被拒」这种
**业务本来就该整体回滚**的路径，应该优先用独立事务或独立事件日志，而不是 `noRollbackFor`。

### 6.2 时间戳必须由数据库维护

`updatedAt` 在契约里是**必返字段**（订单、团期、酒店、线路、文章、出行人…），前端拿它当"最后更新时间"，
所以它必须真的随改动前进。`BaseEntity.updatedAt` 没有任何填充注解，实体又是从库里读出来的 ——
持有的正是**旧值**；MyBatis-Plus `updateById` 默认按 NOT_NULL 策略把它写进 `SET`，
压掉了列定义上的 `ON UPDATE CURRENT_TIMESTAMP`，该列于是永远停在创建时间。
**最坏的是支付回调**：条件 UPDATE 先原子推进支付单（此时 `ON UPDATE` 生效、时间戳前进），
紧接着 `updateById` 又把旧值写回去，等于前进后再倒退。

修法分两半，缺一半都会留下错值：

1. **写入侧**：给两列时间戳加 `@TableField(updateStrategy = FieldStrategy.NEVER)`，把写入权交回数据库
   （只禁 update，insert 仍走 `DEFAULT CURRENT_TIMESTAMP`）。
2. **返回侧**：UPDATE 之后**内存实体仍持有旧时间戳**，`FieldStrategy.NEVER` 管不到"响应里装的是什么"。
   凡是用写操作返回实体的接口（`cancel`、`confirm`…）都必须**回查一次再装配视图**，
   否则就是"库对了、响应还是旧的"，前端要等下一次查询才看到新值。
   回查而不是在内存里补 `LocalDateTime.now()`，是为了不让 JVM 时钟成为第二个时间来源。

实测数据与反向对照见[测试报告](../testing/TEST_REPORT.md)的「时间戳由数据库维护」一节。

## 7. 支付与出款链路

### 7.1 收银台

四项配置（`ALIPAY_APP_ID`、`ALIPAY_APP_PRIVATE_KEY`、`ALIPAY_PUBLIC_KEY`、`ALIPAY_NOTIFY_URL`）
**全配齐**才生成真实沙箱收银台链接，`notify_url` 作为公共参数参与签名；缺任意一项时返回 409 `PAYMENT_NOT_CONFIGURED`，不生成支付链接。详见 `README.md` 的「支付宝沙箱」。

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
> 真实验签、金额核对、重复回调、出款与待确认重试必须在沙箱里单独验证（见[测试报告](../testing/TEST_REPORT.md)的待验收事项）。
