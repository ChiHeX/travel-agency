# 资源与导游模块设计说明（成员 C）

> 覆盖**线路 / 行程 / 景点 / 酒店 / 地点指南**资源模块与**导游工作台**模块的设计、接口、
> 前端对接点与联调结论。用于支持成员 E 完成团期、导游、资源页面联调，并作为模块维护文档。
>
> 契约以 [docs/openapi.yaml](openapi.yaml) 为准，全局规则见 [docs/API.md](API.md)，
> 架构与状态机见 [docs/ARCHITECTURE.md](ARCHITECTURE.md)。

## 1. 模块范围

| 子模块 | 后端 | 前端 |
|---|---|---|
| 线路与每日行程 | `AdminRouteService` / `AdminRouteController`、公开 `RouteService` | 线路管理、行程编排页 |
| 景点 | `AttractionService` / `AdminAttractionController`、公开 `AttractionController` | 景点管理、景点详情 |
| 酒店 | `HotelService` / `AdminHotelController`、公开 `HotelController` | 酒店资料管理、酒店详情 |
| 地点指南 | `PlaceGuideService` / `AdminPlaceGuideController` | 指南编辑、地图联动 |
| 团期 | `DepartureService` / `AdminDepartureController` | 团期管理（E 主责页面） |
| 导游工作台 | `GuideController` + `DepartureService` | 工作台、带团详情、游客名单 |

## 2. 关键业务规则（由后端保证）

- **线路/行程**：线路状态 `DRAFT / PUBLISHED / OFFLINE`；每日行程按 `day_number` 唯一、行程项按 `sort_no` 排序；住宿安排四种类型 `HOTEL / STANDARD / NONE / PENDING`，**只有 HOTEL 才关联酒店**，`STANDARD` 必须填写住宿标准（`ck_day_accommodation` 约束 + 服务层校验）。
- **景点**：维护名称/城市/地址/经纬度/简介/来源说明；坐标成对校验（`CoordinatePairComplete`），半截坐标加固为 `NULL`（`009` 迁移）。
- **酒店**：资料带 `version` 乐观锁，两位工作人员先后保存时后保存方收到冲突提示而非静默覆盖；图片仅登记外链，按 `sort_order` 展示（最多 10 张）。
- **团期**：`DRAFT → OPEN` 才能售卖；终态不可回退；名额由下单/支付/退款链路维护；导游同一时间不能带两个重叠团期；后台改期名额/改挂/乐观锁均以条件 `UPDATE` + 影响行数判定。
- **导游**：只能查看/操作**本人负责**的团期（不存在 404、非本人 403）；游客名单只返回本人团期的有效订单出行人，证件号脱敏（最小必要信息，PRD §40）。

## 3. 后端接口

### 3.1 资源（后台，`STAFF/ADMIN`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/POST | `/api/admin/routes` | 线路列表 / 新增 |
| GET/PUT | `/api/admin/routes/{routeId}` | 线路详情 / 修改 |
| PATCH | `/api/admin/routes/{routeId}/status` | 上架/下架 |
| GET/POST | `/api/admin/routes/{routeId}/itinerary-days` | 行程天列表 / 新增 |
| PUT/DELETE | `/api/admin/itinerary-days/{dayId}` | 修改 / 删除行程天 |
| GET/POST | `/api/admin/itinerary-days/{dayId}/items` | 行程项列表 / 新增 |
| PUT/DELETE | `/api/admin/itinerary-items/{itemId}` | 修改 / 删除行程项 |
| GET/POST | `/api/admin/attractions` | 景点列表 / 新增 |
| PUT/DELETE | `/api/admin/attractions/{attractionId}` | 修改 / 删除 |
| GET/POST | `/api/admin/hotels` | 酒店列表 / 新增 |
| GET/PUT/DELETE | `/api/admin/hotels/{hotelId}` | 详情 / 修改 / 删除 |
| GET/POST | `/api/admin/place-guides` | 指南列表 / 新增 |
| GET/PUT | `/api/admin/place-guides/{id}` | 详情 / 修改 |
| PATCH | `/api/admin/place-guides/{id}/status` | 发布/下线 |
| GET/POST | `/api/admin/departures` | 团期列表 / 新增 |
| GET/PUT | `/api/admin/departures/{departureId}` | 详情 / 修改 |
| PATCH | `/api/admin/departures/{departureId}/status` | 改状态（触发**团期状态变化通知**） |

### 3.2 导游（`GUIDE/ADMIN`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/guide/dashboard` | 工作台：`{upcoming, current, history}` |
| GET | `/api/guide/departures?scope=UPCOMING\|CURRENT\|HISTORY` | 本人团期分页 |
| GET | `/api/guide/departures/{id}` | 团期 + 线路 + 行程 |
| GET | `/api/guide/departures/{id}/passengers` | 游客名单（脱敏） |
| POST | `/api/guide/departures/{id}/start` | 开始行程（触发通知 + 订单级联 `CONFIRMED→TRAVELLING`） |
| POST | `/api/guide/departures/{id}/complete` | 结束行程（触发通知 + 订单级联 `→COMPLETED`） |

**`scope=UPCOMING` 统一口径**：排除 `TRAVELLING / FINISHED / CANCELLED`，且 `start_date >= 当天`。
与工作台 `upcoming` 使用同一份定义（`DepartureService.UPCOMING_EXCLUDED_STATUSES` + `isUpcoming`），
日期以**库内日期**为准（`CURRENT_DATE()` / `departureMapper.databaseToday()`）。

## 4. 前端对接点

- 统一封装：`src/api/modules.js`。导游调用集中在 `guideApi`（`dashboard` / `departures` / `detail` / `passengers` / `start` / `complete`）；后台资源调用在 `adminApi`。
- 页面：`views/guide/GuideDashboardView.vue`（工作台）、`views/guide/GuideTripsView.vue`（团期）、带团详情页；后台资源页集中在 `views/admin/`。
- 工作台卡片「即将出发班期」的文案说明为**「尚未出发的团期」**，与后端 `UPCOMING` 口径一致（此前误写为「未来 7 天内出团」）。

## 5. 联调结论（C-10）

| 项 | 状态 | 说明 |
|---|---|---|
| 后端开始行程接口与前端按钮接通 | ✅ | `guideApi.start/complete` ↔ `POST /api/guide/departures/{id}/start|complete`，契约返回更新后的团期 |
| 团期列表分页可用 | ✅ | `GET /api/guide/departures` 返回分页信封；`scope` 非法值返回 422 |
| 分类（scope）可用 | ✅ | `UPCOMING / CURRENT / HISTORY` 三分类，定义见 §3.2 |
| 资源列表分页/筛选 | ✅ | 线路、景点、酒店、指南、团期均返回分页信封，支持契约声明的筛选参数 |
| 后端测试 | 见 [ACCEPTANCE_REPORT_MEMBER_C.md](ACCEPTANCE_REPORT_MEMBER_C.md) | 单元测试随 `mvn test`；集成测试需 `TRAVEL_MYSQL_TEST=true` |

> **集成测试需数据库**：`GuideTripContractIntegrationTest`、`GuideUpcomingContractIntegrationTest`、`DepartureAdminContractIntegrationTest` 等需在临时 MySQL 中运行（CI 的 `Backend checks` 已配置），本地按 README「验证命令」执行。

## 6. 文章接口维护承接（需团队确认）

- 攻略文章接口为 `GET /api/articles`、`GET /api/articles/{id}`（公开）与 `/api/admin/articles/**`（后台）。
- 按此前建议分工，**文章接口维护可由成员 C 承接**，但**需在团队内确认后**再纳入职责范围；
  在此之前，本模块文档只登记接口位置，不改变现有归属。
