# 架构与接口说明

## 分层结构

```text
frontend (Vue SPA)
  ├─ 用户端 PublicLayout
  ├─ 独立订单详情、付款与支付结果页 PaymentLayout（/orders/:orderNo、/payment/:orderNo；旧账户地址重定向并保留查询参数）
  ├─ 管理后台 AdminLayout
  └─ 导游工作台 AdminLayout + 路由角色守卫
          │ /api
backend (Spring Boot REST)
  ├─ controller   HTTP、参数校验、权限入口
  ├─ service      业务事务、状态机、名额控制
  ├─ mapper       MyBatis-Plus BaseMapper
  ├─ entity       领域持久化模型
  └─ common       JWT、RBAC、异常、分页、配置
          │
MySQL (schema.sql)
```

用户端和管理后台使用同一前端工程，权限由前端路由守卫和后端 Spring Security 双重判断。后端不把前端隐藏按钮当作权限边界。

JWT 验签通过后，认证过滤器仍需查询账号当前状态；已停用、已删除或不存在的账号不能凭有效期内的旧令牌访问受保护接口。

## 核心关系

```text
TravelRoute 1 ── N RouteItineraryDay 1 ── N RouteItineraryItem
TravelRoute 1 ── N Departure N ── 1 Guide
User 1 ── N TravelOrder N ── 1 Departure
TravelOrder 1 ── N OrderTraveler (历史实名快照)
TravelOrder 1 ── 1 Payment
TravelOrder 1 ── N Refund / 0..1 Review
```

团期价格从 `departure` 复制到订单的 `adult_unit_price` / `child_unit_price`；出行人从常用资料复制到 `order_traveler`，后续修改不会影响历史订单。

## 订单状态机

```text
WAIT_PAY ──支付回调──> PAID_WAIT_CONFIRM ──工作人员确认──> CONFIRMED
   │                                             │
   └─取消──> CANCELLED                           └─导游开始──> TRAVELLING ──结束──> COMPLETED

PAID_WAIT_CONFIRM / CONFIRMED ──用户申请──> REFUND_APPLYING
REFUND_APPLYING ──同意（先真出款，成功才落状态）──> REFUNDED
REFUND_APPLYING ──拒绝──> 恢复原业务状态（PAID_WAIT_CONFIRM / CONFIRMED）
```

**订单状态里不出现 `REFUND_PROCESSING` 与 `REFUND_REJECTED`**，尽管契约的 `OrderStatus` 枚举保留了它们：

- 退款的"处理中"落在**退款单**上（`refund.status = PROCESSING`，出款已发起但结果未确认的持久态），
  订单在确认结果前一直是 `REFUND_APPLYING`。把订单也推成 `REFUND_PROCESSING` 没有收益，
  反而会让"拒绝即恢复订单"这条路径在钱可能已经退出去时变得不可判。
- 拒绝路径直接用 `refund.original_order_status` 把订单恢复成申请前的业务状态，
  `REFUND_REJECTED` 只是个瞬时中间值，落库没有任何可观察意义。

两个枚举值保留是为了契约兼容（客户端可能仍按它们分支）。订单一旦离开 `REFUND_APPLYING`，
只可能变成 `REFUNDED`（出款成功）或回到申请前的业务状态（拒绝）。
`OrderStatusReservedValuesTest` 钉住"实现里不写这两个状态"：谁要真正落库，
就得同时更新本节、PRD §12 与该测试。

订单创建时在事务内用条件 `UPDATE` 增加 `reserved_people`，支付待确认仍占用名额；取消/退款释放名额。工作人员确认时再次检查 `confirmed_people + 当前人数 <= max_people`，避免并发超卖。

报名页到付款页使用保留历史记录的跳转。报名表单在当前登录会话的 Pinia 内存草稿中保留，返回时恢复联系人、人数、出行人和备注；完整证件信息不写入浏览器持久存储或 URL，刷新页面后内存草稿不保留。未修改资料时继续使用已创建的订单；修改后提交需用户确认取消原待支付订单，再重新创建并由后端校验价格和名额。退出登录或切换会话会清除草稿。

## 主要 API 分组

本节只描述模块边界和权限归属。精确接口与模型以 [OpenAPI 定义](openapi.yaml) 为准，通用规则以 [API 契约](API.md) 为准，避免在架构文档中复制并逐渐产生过期接口清单。

| 分组 | 主要接口 | 权限 |
|---|---|---|
| 认证 | `POST /api/auth/register`、`POST /api/auth/login`、`GET /api/auth/me` | 注册/登录公开，其余登录 |
| 线路 | `GET /api/routes`、`GET /api/routes/{id}` | 公开 |
| 账户 | `/api/account`、`/api/travelers`、`/api/favorites`、`/api/messages` | USER |
| 订单 | `/api/orders`、`/api/payments/alipay/notify` | 用户；支付通知公开但必须按支付宝规范验签 |
| 后台 | `/api/admin/**` | STAFF / ADMIN，用户与日志接口再限制 ADMIN |
| 导游 | `/api/guide/**` | GUIDE / ADMIN，业务方法校验本人 guide_id |
| 内容 | `GET /api/articles`、`GET /api/attractions`、`/api/consultations` | 公开浏览；咨询需登录 |
| 地点指南 | `GET /api/place-guides/**`、`/api/admin/place-guides/**` | 公开浏览已发布指南；STAFF / ADMIN 编排与发布 |

## 资源与导游模块

订单所有者通过 `OrderItineraryService` 校验归属与已报名状态后，复用 `AdminRouteService.itineraryDays`
读取当前每日安排。该订单专属入口不受线路上下架影响，不开放后台接口或酒店详情；返回当前资料，
不是历史行程快照。订单价格、出行人快照保持不变。

各子模块的职责划分。前端页面只做展示与编排，业务判定一律以后端为准。

| 子模块 | 后端 | 前端 |
|---|---|---|
| 线路与每日行程 | `AdminRouteService` / `AdminRouteController`，公开 `RouteService` | 线路管理、行程编排页 |
| 景点 | `AttractionService` / `AdminAttractionController`，公开 `AttractionController` | 景点管理、景点详情 |
| 酒店 | `HotelService` / `AdminHotelController`，公开 `HotelController` | 酒店资料管理、酒店详情 |
| 地点指南 | `PlaceGuideService` / `AdminPlaceGuideController` | 指南编辑、地图联动 |
| 团期 | `DepartureService` / `AdminDepartureController` | 团期管理 |
| 导游工作台 | `GuideController` + `DepartureService` | 工作台、带团详情、游客名单 |

由后端保证的规则：

- **线路 / 行程**：线路状态 `DRAFT / PUBLISHED / OFFLINE`；每日行程按 `route_id + day_number` 唯一，行程项按 `sort_no` 排序；住宿安排四种类型 `HOTEL / STANDARD / NONE / PENDING`，只有 `HOTEL` 关联酒店、`STANDARD` 必须填写住宿标准（数据库 `ck_day_accommodation` 约束 + 服务层 `AccommodationConsistent` 校验）。
- **景点**：坐标必须成对（`CoordinatePairComplete`），历史「半截坐标」由 `009` 迁移统一清成 `NULL`，避免用户端地图静默丢弃。
- **酒店**：资料带 `version` 乐观锁，两位工作人员先后保存时后保存方收到版本冲突而不是静默覆盖；图片只登记外链，按 `sort_order` 展示。
- **团期**：新建即 `DRAFT`，需上架成 `OPEN` 才能售卖；`FINISHED` / `CANCELLED` 是终态不可回退；名额只由下单 / 支付 / 退款 / 确认链路维护；同一导游不能带时间重叠的两个团期；后台改期、改挂线路与乐观锁都以条件 `UPDATE` + 影响行数判定。
- **导游**：只能查看和操作本人负责的团期（不存在 404、非本人 403）；游客名单只返回本人团期下有效订单的出行人，证件号脱敏。

### 导游「即将出发」的统一口径

工作台 `GET /api/guide/dashboard` 的 `upcoming` 与列表 `GET /api/guide/departures?scope=UPCOMING` 共用
`DepartureService.UPCOMING_EXCLUDED_STATUSES` + `isUpcoming()`：排除 `DRAFT / TRAVELLING / FINISHED / CANCELLED`，
且 `start_date >= 当天`。日期以库内日期为准（`CURRENT_DATE()` / `DepartureMapper#databaseToday()`），
两端不会各自跑偏。排除 `DRAFT` 是为了让"即将出发"与"能开始行程"取到同一个集合（见下表），
不会给导游列出一个点进去必然 409 的草稿团期。工作台卡片的说明文案是「尚未出发的团期」，与该口径一致。

### 带团详情页的状态操作

按钮由团期当前状态唯一决定，与后端状态机对齐，不放后端必然返回 409 的入口：

| 团期状态 | 按钮 | 接口 |
|---|---|---|
| `OPEN` / `FULL` / `CLOSED` | 开始行程 | `POST /api/guide/departures/{departureId}/start` |
| `TRAVELLING` | 标记行程已结束 | `POST /api/guide/departures/{departureId}/complete` |
| `DRAFT` / `FINISHED` / `CANCELLED` | 不显示 | — |

前端调用统一走 `src/api/modules.js` 的 `guideApi`（`dashboard` / `departures` / `detail` / `passengers` / `start` / `complete`）。
两个按钮都带 `pending` 防重复点击，失败时提示服务端错误信息并重新拉取，保证页面展示的是服务端真实状态。

## 数据合规约束

- 证件号在响应中默认脱敏；导游接口只提供最小必要联系方式和紧急联系人。
- 密码只保存 BCrypt 哈希；日志不记录密码、Token、完整证件号和支付敏感参数。
- `sql/test-data.sql` 的账号、线路和景点均标记为测试/演示数据，不代表真实经营数据。
- 用户端主地图使用 Leaflet，默认加载天地图 Web Mercator WMTS 矢量底图与中文注记，并保留可切换的 OpenStreetMap 底图。地图源配置集中在前端 `src/api/mapTiles.js`，浏览器端 Key 通过 `VITE_TIANDITU_KEY` 注入；用户选择保存在本地存储，切换只替换底图层，保留视角与业务覆盖物。缺少 Key 或加载失败时显示提示。线路详情加载后，在主地图上显示景点标记与顺序连线。地图保留当前平台版权及来源提示；坐标仍使用行程数据提供的 WGS-84，不调用导航服务，不改变业务 API 或数据库。
- 地点指南单独使用 `place_guide` 和 `place_guide_item` 保存多景点清单；指南详情使用景点坐标确定地图聚焦区域，不显示景点标记或线路连线。地点详情通过行程项的 `attraction_id` 关联已发布线路，并依据未来开放团期去重展示线路卡片；用户在线路详情查看和选择团期。
- 支付仅定位于支付宝沙箱业务链路演示，不处理真实商业资金。
- 本地模拟支付由 `local-payment & !prod & !production` 条件下的 `LocalPaymentService` 和
  `LocalPaymentController` 提供，且启动时要求 `server.address` 为回环地址。默认不注册模拟入口。
  `/payments/options` 向已登录用户报告是否可用；`/payments/local/{orderNo}` 校验所有者、订单/支付状态及金额，
  对订单和支付记录加行锁，以订单号保证幂等，复用 `OrderService.markPaid` 事务入账逻辑。
  支付渠道为 `LOCAL_SIMULATION`，交易号带 `LOCAL-` 前缀。支付宝发起支付、入账及取消订单也锁定订单行，
  防止模拟入账与取消/支付请求互相覆盖。模拟订单申请和审核退款均被拒绝，不调用支付宝退款网关。

## 站内消息与提醒

站内消息统一落在 `sys_message`，由 `MessageController` 提供列表 / 未读数 / 已读接口。消息类型包括支付成功、
报名确认、订单审核异常（`ORDER_AUDIT_ANOMALY`）、退款审核结果、团期状态变化（`DEPARTURE_STATUS`）
与即将出发提醒（`DEPARTURE_REMINDER`）。

- **订单审核异常**：工作人员确认报名时若业务复核不通过，`OrderService#confirm` 在**任何写入之前**
  拦下并把原因通知下单用户（PRD §29）。该通知**不改变订单状态**（仍停在 `PAID_WAIT_CONFIRM`，预留名额不动），
  用户补齐资料后可重新确认。消息按「订单号 + 异常原因」去重，同一问题不重复发送、原因变化则各通知一次；
  正文与接口 `message` 只回「第 N 位出行人缺少哪个字段」，不含姓名与证件号。
  完整设计见 [交易模块说明](TRADE_MODULE.md) §4 / §5。

- **团期状态变化**：后台 `DepartureService#changeStatus` 与导游 `start` / `complete` 在状态**确实变化**后，
  用一条 `INSERT ... SELECT DISTINCT` 向该团期下有效订单（排除未支付 / 已取消 / 已退款）的用户投递消息；
  消息与状态写入同一事务，回滚时一并回滚，同一变更不重复通知。
- **即将出发提醒**：`DepartureReminderService` 由 `@Scheduled` 定时触发，在出发前配置天数内为符合出行条件的订单
  各发一条消息；以 `departure_reminder(order_id, remind_type)` 唯一键保证任务重复执行、多实例并发都不重复发送。
  窗口的「今天」与团期日期判断一律以库内日期（`CURRENT_DATE()`）为准，与"可报名/即将出发"口径一致。
  候选查询读的是事务快照，因此每条候选在写入前还会用 `TravelOrderMapper#lockEligibleReminderOrder`
  做一次**当前读复核并加行锁**：查询之后才退款完成或团期被取消的订单会被跳过，不会收到提醒，
  也不会留下"已发送"标记（条件重新成立时下个调度周期仍可发送）。

## 日期口径

"今天"统一取自数据库（`TravelOrderMapper#databaseToday` / `DepartureMapper#databaseToday`，底层 `CURDATE()`），
或在 SQL 里直接用 `CURRENT_DATE()`。JVM 与 MySQL 会话时区不一致时（CI/容器常见 UTC），`LocalDate.now()`
会与库内日期错开一天，导致"可报名团期""即将出发"等判断在同一天内不一致，因此公开团期、首页近期团期、
景点关联团期、导游筛选与工作台统计都不再使用 `LocalDate.now()`。
