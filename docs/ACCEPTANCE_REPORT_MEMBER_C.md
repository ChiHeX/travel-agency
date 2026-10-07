# 成员 C 交付与验收报告

> 分支：`feature/notifications-and-remaining-delivery`（基于最新 `dev`）。
> 范围：C-01 ~ C-10。本文给出**变更说明、可复现的验证证据、验收清单**，以及需在真实运行时执行的步骤。
>
> 相关文档：[DEPLOYMENT](DEPLOYMENT.md)（C-08）、[DATABASE_DESIGN](DATABASE_DESIGN.md)（C-09）、
> [DATA_SOURCES](DATA_SOURCES.md)（C-09）、[MODULE_RESOURCES](MODULE_RESOURCES.md)（C-10）。

## 0. 交付物索引

| 编号 | 交付物 | 位置 |
|---|---|---|
| C-01 | 即将出发提醒（服务 + 幂等表 + 定时任务） | `DepartureReminderService`、`departure_reminder` 表、`migrations/011` |
| C-02 | 团期状态变化通知 | `DepartureService#changeStatus/start/complete` + `MessageMapper#insertForDepartureParticipants` |
| C-03 | 导游「即将出发」筛选统一 | `DepartureService#pageUpcoming/isUpcoming`、`GuideController`、`GuideDashboardView.vue` |
| C-04 | 导游游客名单消除 N+1 | `GuideController#passengerList` |
| C-05 | 日期时区口径统一 | `DepartureMapper#databaseToday`、`HomeService`、`RouteService`、`AttractionService`、`AdminRouteService` |
| C-06/C-07 | 验收与统计核验 | 本文 §3、§4 |
| C-08 | HTTPS 部署 | `deploy/nginx.https.conf`、`deploy/docker-compose.https.yml`、[DEPLOYMENT.md](DEPLOYMENT.md) |
| C-09 | 数据库设计/迁移/来源 | [DATABASE_DESIGN.md](DATABASE_DESIGN.md)、[DATA_SOURCES.md](DATA_SOURCES.md)、`migrations/011` |
| C-10 | 模块文档与联调 | `MODULE_RESOURCES.md`、`views/guide/GuideTripsView.vue`（开始/结束行程按钮） |

## 1. C-01 即将出发提醒

**实现**：定时任务 `DepartureReminderService`（默认每天 09:00，`app.reminder.upcoming-cron` 可覆盖）
在出发前 `app.reminder.upcoming-days`（默认 **3 天**）的窗口内，为符合出行条件的订单各发一条站内消息。

| 完成标准 | 落实方式 |
|---|---|
| 明确提前多久提醒 | `app.reminder.upcoming-days=3`，窗口 `[今天, 今天+N]` 在库内用 `CURRENT_DATE()` 判定 |
| 仅通知符合出行条件的订单 | 候选订单限 `status='CONFIRMED'` 且团期 `NOT IN ('CANCELLED','FINISHED')` |
| 任务重复执行不重复发消息 | 先抢占 `departure_reminder(order_id, remind_type)` 唯一键，抢占成功才写消息 |
| 取消/退款完成不发送 | 二者订单状态不是 `CONFIRMED`，天然不在候选中 |

**验证**（真实 MySQL，事务内构造受控数据并回滚）：

| 校验 | 结果 |
|---|---|
| 候选查询命中受控已确认订单（出发前 2 天） | ✅ =1 |
| 同一 (order_id, remind_type) 重复插入 | ✅ 首次 =1，第二次被忽略 =0 |
| 单元测试 `DepartureReminderServiceTest`：发送一次 / 重复跳过 / 关闭开关无查询 | ✅ 4 passed（`mvn test`） |
| 集成测试 `DepartureReminderIntegrationTest`：仅确认订单被提醒、取消/未支付不提醒、重复执行幂等 | ✅ 1 passed（临时/本机 MySQL，`TRAVEL_MYSQL_TEST=true`） |

## 2. C-02 团期状态变化通知

**实现**：后台 `changeStatus` 与导游 `start` / `complete` 在**真正改走状态之后**调用
`notifyDepartureStatusChange`，通过一条 `INSERT ... SELECT DISTINCT` 向该团期下**有效订单**（排除未支付/已取消/已退款）的用户投递消息。

| 完成标准 | 落实方式 |
|---|---|
| 覆盖后台及导游触发 | `changeStatus`（PATCH 状态）与 `start`/`complete`（导游）均调用同一通知方法 |
| 通知受影响用户 | 按 `departure_id` 批量取有效订单用户，`DISTINCT` 去重 |
| 事务回滚不留下错误消息 | 消息写入与状态写入同一事务；回滚时消息一并回滚 |
| 同一变更不重复通知 | 仅当状态确实变化（后台）或条件 `UPDATE` 影响 1 行（导游）才通知；重复提交同状态不通知 |

**验证**（真实 MySQL，事务内构造并回滚）：团期下同时存在 `CONFIRMED` 与 `WAIT_PAY` 订单时，
批量通知只插入 **1** 条（`DISTINCT` + 排除未支付）。
单元测试 `DepartureAdminServiceTest` 新增：状态变化时调用通知、状态未变化时不通知。

## 3. C-06 资源与导游执行链路验收

> 说明：链路逻辑由后端保证并有契约/并发测试覆盖；下表标注 ✅=本次已核对（SQL/代码/已有测试），
> ▶=需在运行环境按步骤执行（见 §5）。

| 验收项 | 通过标准 | 证据 |
|---|---|---|
| 线路发布 | DRAFT→PUBLISHED 后公开可见，OFFLINE 不可见 | ✅ `AdminRouteService` + `RouteAdminContractIntegrationTest` |
| 行程排序 | 行程天按 `day_number`、行程项按 `sort_no` 稳定排序 | ✅ `uk_route_day` 唯一键 + `idx_item_day`/`sort_no` 排序 |
| 景点坐标 | 坐标成对；半截坐标加固为 NULL | ✅ 库内 `attraction` 18/18 成对；`009` 迁移 + `CoordinatePairComplete` |
| 四种住宿安排 | `HOTEL/STANDARD/NONE/PENDING` 自洽；仅 HOTEL 关联酒店 | ✅ `AccommodationType` + `ck_day_accommodation` 约束 |
| 酒店启停及版本冲突 | 停用后不可用；并发保存返回版本冲突 | ✅ `hotel.version` + `HotelStatusLogConcurrencyIntegrationTest`、`HotelServiceTest` |
| 导游时间冲突 | 同一导游重叠团期返回 409 | ✅ `lockOverlappingDepartureIds`（当前读）+ `DepartureStateConcurrencyIntegrationTest` |
| 本人团期权限 | 非本人团期 403、不存在 404 | ✅ `GuideController#ownedDeparture` + `GuideTripContractIntegrationTest` |
| 开始/完成行程 | `OPEN/FULL/CLOSED→TRAVELLING`、`TRAVELLING→FINISHED`；非法迁移 409 | ✅ `GuideTripContractIntegrationTest` |
| 与 B 验证订单同步状态 | 出发 `CONFIRMED→TRAVELLING`；结束 `→COMPLETED` 且写 `completed_at` | ✅ `DepartureService#cascadeOrderStatus` + `GuideTripContractIntegrationTest` |

## 4. C-07 Dashboard 统计口径与实际结果

数据源：`GET /api/admin/dashboard`（`AdminController#dashboard`）。日期以**库内日期**为准。

### 4.1 口径

| 指标 | 口径 |
|---|---|
| 用户数量 | `sys_user` 中 `deleted=0` |
| 有效线路数量 | `travel_route` 中 `status='PUBLISHED'` |
| 可报名团期 | `status='OPEN'` **且** `start_date >= 当天`（当天可报名） |
| 今日订单 | `created_at >= 当天 00:00` |
| 待确认订单 | `status='PAID_WAIT_CONFIRM'` |
| 待处理退款 | `status='REFUND_APPLYING'` |
| 总报名人数 | `Σ(adult_count+child_count)`，`status NOT IN ('CANCELLED','REFUNDED')` |
| 订单金额统计 | `Σ(total_amount)`，`payment_status='PAID'` |
| 订单趋势 | 近 7/30 天，按 `DATE(created_at)` 分组，缺失日期补零 |
| 热门线路 | `valid_booking_count`（有效报名订单数）排行前 8 |
| 热门目的地 | 按**有效报名游客人数**排行前 8（与线路侧同一「有效」集合） |
| 团期报名情况 | 未来 `OPEN/FULL` 且未发布线路不计入，取最近 5 条 |

### 4.2 实测结果（开发库 `travel_agency`，2026-10-07）

| 指标 | 实测值 |
|---|---|
| 用户数量（deleted=0） | 6 |
| 有效线路（PUBLISHED） | 10 |
| 可报名团期（OPEN 且未过出发日） | 15 |
| 今日订单 | 0 |
| 待确认订单 | 1 |
| 待处理退款 | 1 |
| 总报名人数 | 8 |
| 订单金额（PAID） | 20360.00 |

近 7 天订单趋势（仅 2026-10-04 有订单）：订单 6 单、报名 8 人、金额 20360.00。
热门线路前 3：成都熊猫与都江堰 4 日（106）、北京中轴线文化 4 日（81）、北疆喀纳斯全景 6 日（73）。
热门目的地：北京 2 人、乌鲁木齐·阿勒泰 1 人、厦门 1 人、广州 1 人。
团期报名情况（前 3）：厦门鼓浪屿（10-09，余 0）、杭州西湖（10-11，余 16）、成都熊猫（10-14，余 10）。

**核对结论**：
- 各标量与明细口径均可由 SQL 复算，数值一致；无数据时（如今日订单 0）正常展示为 0 / 空状态。
- ⚠️ **演示数据一致性提示**：「热门线路」使用 `travel_route.valid_booking_count`，而 `test-data.sql` 对该列**直接预置了较大数值**（如 106），
  与库中真实订单（`CONFIRMED/TRAVELLING/COMPLETED` 共 3 单）不一致；而「热门目的地」按真实订单计算（个位数）。
  这是**演示数据的固有特征**（非功能缺陷），录入新订单并确认/退款后会自然收敛。演示/答辩时应知晓两种数量级差异的来源。

**复算命令示例**：

```sql
SELECT COUNT(*) FROM departure WHERE status='OPEN' AND start_date >= CURDATE();
SELECT COALESCE(SUM(total_amount),0) FROM travel_order WHERE payment_status='PAID';
SELECT COALESCE(SUM(adult_count+child_count),0) FROM travel_order WHERE status NOT IN ('CANCELLED','REFUNDED');
```

## 5. 本机实测结果

在本机真实执行（JDK 21 + Maven，MySQL 9.7.1）：

| 命令 | 结果 |
|---|---|
| 后端 `mvn -o -ntp test`（`TRAVEL_MYSQL_TEST=true`） | ✅ **571 passed / 0 failed / 0 errors / 0 skipped，BUILD SUCCESS** |
| 新增测试实际执行 | ✅ `DepartureReminderServiceTest`(4)、`DepartureReminderIntegrationTest`(1)、`GuideUpcomingContractIntegrationTest`(2)、`DepartureAdminServiceTest`(48)、`GuideTripContractIntegrationTest`(9)、`GuideTripsView.spec.js`(11) |
| 前端 `npx vitest run` | 共 **239 用例 / 24 个文件**。CI（Node 24）为全绿；本机 Node 25 下为 **229 passed / 10 failed**，失败全部集中在 `MapPreview.spec.js` / `MapPreview.lifecycle.spec.js` 的 `window.localStorage.clear is not a function` —— Node 25 默认解除 `--experimental-webstorage` 限制后自带的 `localStorage` 全局覆盖了 jsdom 的 Storage，与本次改动无关（本分支前端只动了 `views/guide/`） |
| 前端 `npm run build` | ✅ 构建成功 |
| 契约 `npm run contract:validate` | ✅ All good |
| 数据库初始化与迁移 | ✅ 见 [DATABASE_DESIGN §5](DATABASE_DESIGN.md)（独立实例验证，幂等） |

> 说明：集成测试需数据库（`TRAVEL_MYSQL_TEST=true`）；CI 的 `Backend checks` 会在临时 MySQL 中初始化数据库并运行同一套测试。
> 仍需人工确认的只有**运行时界面/端到端**部分：登录导游工作台核对 `upcoming` 与 `scope=UPCOMING` 一致；
> 在带团详情页点「开始行程」，确认团期变为 `TRAVELLING`、订单级联为在途、按钮切换为「标记行程已结束」；
> 后台改团期状态后受影响用户在「消息」页可收到「团期状态更新」；把某订单团期设为 3 天内出发并置为已确认后，
> 触发一次提醒任务，确认收到且重复触发不重复。

## 6. 变更文件清单

- 后端（新增）：`domain/service/DepartureReminderService.java`、`domain/entity/DepartureReminder.java`、`domain/mapper/DepartureReminderMapper.java`、`domain/dto/UpcomingReminderTarget.java`
- 后端（修改）：`DepartureService`、`GuideController`、`HomeService`、`RouteService`、`AttractionService`、`AdminRouteService`、`DepartureMapper`、`TravelOrderMapper`、`MessageMapper`、`application.yml`
- SQL：`sql/schema.sql`、`sql/migrations/011-add-departure-reminder.sql`、`sql/migrations/README.md`
- 前端：`views/guide/GuideDashboardView.vue`（文案与口径）、`views/guide/GuideTripsView.vue`（接通「开始行程」按钮，并按状态显示操作入口）
- 契约：`docs/openapi.yaml`（guide `scope` 说明）
- 测试：`DepartureReminderServiceTest`、`DepartureReminderIntegrationTest`、`GuideUpcomingContractIntegrationTest`、`DepartureAdminServiceTest`（扩充）、`GuideTripContractIntegrationTest`（扩充）、`DepartureStateConcurrencyIntegrationTest`（清理站内消息外键）、`views/guide/__tests__/GuideTripsView.spec.js`
- 部署/文档：`deploy/nginx.https.conf`、`deploy/docker-compose.https.yml`、`docs/DEPLOYMENT.md`、`docs/DATABASE_DESIGN.md`、`docs/DATA_SOURCES.md`、`docs/MODULE_RESOURCES.md`、`docs/ACCEPTANCE_REPORT_MEMBER_C.md`
