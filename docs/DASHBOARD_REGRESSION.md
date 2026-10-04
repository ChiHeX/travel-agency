# Dashboard 数据统计回归记录

验证基线：`dev` 的 `62e9510`（2026-10-04）。修复分支：`feature/admin-dashboard-statistics`。

范围：PRD §32「后台 Dashboard」的统计数据链路 —— 契约 `GET /admin/dashboard`（`DashboardData`）
→ `AdminController.dashboard` → `TravelOrderMapper` / `TravelRouteMapper` → 后台工作台页面。

## 检测结论

统计接口本身在 PR #33（`67990a2`）已补齐：11 个必填字段、`days`（7/30）窗口、缺失日期补零、
计数字段的 `integer` 类型、`idx_order_created_at` 索引都已实现并有契约测试。本次检测发现
**三处未完成**，均在本分支修复。

### 修复 1：`openDepartureCount` 少了「未过出发日期」条件（后端）

原实现只数 `status = OPEN`。全站对「可报名团期」的判定都是 `status = OPEN 且 start_date >= 当天`
（`OrderService` 下单校验与占名额的条件 UPDATE、`RouteService` 的公开团期、`HomeService` 的近期团期都如此，
当天可报名），而库里没有任何定时任务会把过期团期推进到 `TRAVELLING`/`FINISHED`：一个已经出发、
但工作人员没有手动收口的团期会**一直**被算成可报名。PRD §32 这一项的措辞就是「可报名团期」。

改动：`departureMapper` 计数加上 `.ge("start_date", today)`，`today` 沿用 `TravelOrderMapper.databaseToday()`
（库内日期，与 `todayOrderCount`、`orderTrend` 同一来源，避免 JVM 与库会话时区不一致时错开一天）。

### 修复 2：后台工作台没有消费三个统计数组（前端）

`AdminDashboardView.vue` 此前只渲染 8 个标量指标，契约里必填的 `orderTrend` / `popularRoutes` /
`popularDestinations` **完全没有被消费**：页面留着「后端提供按日或按周聚合数据后，此处显示趋势图」
这类占位文案（在接口补齐后已经与实现不符），`days` 窗口没有任何入口，请求失败还会显示成
「暂无统计数据，请确认后端数据库连接正常」，把「取数失败」和「确实没有数据」混为一谈。

改动（全部使用接口返回的数据，不引入任何本地假数据）：

- 订单趋势：按 `orderTrend` 画柱状图（原生 SVG，不新增依赖），柱高按窗口内峰值归一，
  `orderCount = 0` 的补零日期不画柱但保留日期刻度；30 天窗口每 5 天标一个日期。
  每根柱带 `<title>` 提示（日期、订单数、报名人次、已支付金额），`svg` 上有 `role="img"` + `aria-label`。
- 近 7 / 30 天切换：按契约 enum 发 `days`，切换时用请求序号丢弃过期响应，
  避免「点了近 30 天、图却是近 7 天」。
- 窗口合计（订单数、报名人次、已支付金额）由返回序列累加得到，金额按分累加。
- 热门目的地：按 `validBookingCount` 归一的排行条，0 不画条。
- 热门线路：列表项链接到后台线路详情（`admin-route-detail`）。
- 加载 / 空数据 / 失败三态分开：初次加载显示骨架；失败显示错误信息与「重新加载」；
  数组为空显示各自的空状态文案。

### 修复 3：卡片文案与后端口径不一致（前端）

| 卡片 | 原文案 | 实际口径 / 现文案 |
| --- | --- | --- |
| `userCount` | 平台注册用户 / 已验证游客账号 | `sys_user` 中 `deleted = 0` 的**全部**账号（含游客、导游、工作人员、管理员，与 `GET /admin/users` 列表口径一致）→ 平台注册账号 |
| `todayOrderCount` | 今日新增订单 / 今日创建的有效单 | 今日创建的**全部**订单，不分状态（含待支付、已取消）→ 今日创建的全部订单 |
| `participantCount` | 累计出行总人次 | 未取消、未退款订单的 `adult_count + child_count`，含尚未出行的订单 → 累计报名人次 |
| `grossOrderAmount` | 已支付订单总额 / 支付流水汇总 | `payment_status = PAID` 的订单金额合计；退款完成后订单与支付单都变为 `REFUNDED`，因此是「已支付且未退款」→ 已支付且未退款的订单合计 |
| `openDepartureCount` | 开放报名团期 | 见修复 1 → 可报名团期 / OPEN 且未过出发日期 |

## 各指标口径（修复后）

| 字段 | 口径 |
| --- | --- |
| `userCount` | `sys_user.deleted = 0` 的账号数（含内部账号） |
| `publishedRouteCount` | `travel_route.status = PUBLISHED` 且未删除 |
| `openDepartureCount` | `departure.status = OPEN` 且 `start_date >= CURDATE()` |
| `todayOrderCount` | `travel_order.created_at >= 当天 00:00`，不分状态 |
| `pendingConfirmCount` | `travel_order.status = PAID_WAIT_CONFIRM` |
| `pendingRefundCount` | `travel_order.status = REFUND_APPLYING`；退款单进入 `PROCESSING`（待确认出款）时订单仍停在该状态，因此仍计入待处理 |
| `participantCount` | `status NOT IN (CANCELLED, REFUNDED)` 的订单 `adult_count + child_count` 之和 |
| `grossOrderAmount` | `payment_status = PAID` 的 `total_amount` 之和（退款完成后为 `REFUNDED`，即净已支付金额） |
| `orderTrend` | 窗口 `[今天-days+1, 今天]` 内按 `DATE(created_at)` 聚合，缺失日期补零，长度恒为 `days`；`orderAmount` 只算 `payment_status = PAID` |
| `popularRoutes` | 已上架且有效报名数 > 0，按 `valid_booking_count` 降序取前 8（与 `GET /home`、`GET /routes?sort=validBookingCount,desc` 同口径） |
| `popularDestinations` | 已上架线路按 `destination` 汇总 `valid_booking_count`，降序取前 8（与 `GET /home` 同口径） |

## 复测

后端（需要真实 MySQL）：

```powershell
cd backend
$env:JAVA_HOME = 'C:\Users\lenovo\.jdks\ms-21.0.11'
$env:TRAVEL_MYSQL_TEST = 'true'
mvn --batch-mode -o clean verify
```

前端：

```powershell
cd frontend
npm test
npm run contract:validate
npm run build
```

本次结果：

- 后端 `AdminDashboardContractIntegrationTest` 7 个用例通过（新增 `openDepartureCountExcludesDepartedDepartures`）；
  全量 `mvn -o clean verify`（`TRAVEL_MYSQL_TEST=true`，真 MySQL）：
  **558 passed / 0 failed / 0 errors / 0 skipped，BUILD SUCCESS**（`dev` 基线 557，新增 1）。
- **变异验证**：把 `openDepartureCount` 改回只按 `status = OPEN` 计数后，新用例失败
  （`expected: <2> but was: <3>` —— 昨天那条 OPEN 团期被多算了），确认用例真的钉住了缺陷。
- 前端：在 `dev` 的旧页面上跑新增的 7 个用例 → **7 failed**；当前实现 → **219 passed（22 个文件）**；
  `npm run contract:validate` → All good；`npm run build` 通过。
- 本机提示：Node 25 会注入一个 `localStorage` 全局并盖掉 jsdom 的实现，
  `MapPreview.spec.js` / `MapPreview.lifecycle.spec.js` 会报 `localStorage.clear is not a function`。
  失败发生在用例 `beforeEach` 第一行，且这两个文件与本分支不共享任何代码；用
  `NODE_OPTIONS=--no-experimental-webstorage` 运行即 10 passed。CI 固定 Node 24，不受影响。

## 已知口径差异（不在本分支处理）

1. **PRD §27 与实现不一致**：PRD 写「热门目的地按有效报名游客数量统计」，而 `popularDestinations`
   汇总的是 `travel_route.valid_booking_count` —— 该列在订单确认时 `+1`、退款通过时 `-1`，
   统计的是**订单数**而不是游客人数。它由 `GET /home` 与工作台共用同一条 SQL，改成人数口径会同时影响首页排行，
   需先确定是否把线路上的计数换成人数口径（或改为按订单表 `adult_count + child_count` 聚合）。
2. **PRD §32 的可选图表「团期报名情况」在契约里没有字段**，要实现需先按 `docs/API.md` 的流程改冻结契约。
3. **`REFUND_PROCESSING` 订单状态从未被写入**：PRD §12 与 `ARCHITECTURE.md` 的状态机里
   `REFUND_APPLYING ──同意──> REFUND_PROCESSING ──完成──> REFUNDED`，实现是同意后直接落到 `REFUNDED`，
   `PROCESSING` 只存在于退款单上。属订单状态机（成员 B 的模块），本分支未改。
4. **后台共用设计系统样式缺失**：`b2fc15e`（2026-09-03）重写 `global.css` 时删掉了
   `.admin-shell` / `.admin-sidebar` / `.admin-topbar` / `.admin-content` / `.stats-grid` / `.stat-card` /
   `.admin-page-head` 等整块后台样式，而 `AdminLayout.vue` 与各后台页面仍在用这些类名；
   此后新增的后台页面（如地点指南管理）都各自定义局部样式。本页需要的样式也放在组件内，
   与近期做法一致；**全局恢复会影响所有后台页面（成员 E 的范围），建议单独开分支处理**。
