# API 契约

本文档规定 `travel-agency` 前后端 HTTP API 的全局约定。[OpenAPI 定义](openapi.yaml) 规定每个模块的 Method、URL、参数、模型、响应、权限和示例。两者共同构成接口设计、实现、调用、联调和测试的唯一契约来源。

当前后端仅为项目脚手架，现有 Controller、DTO、响应包装和状态码不构成兼容依据；后续正式实现必须向本文档对齐。PRD 负责定义业务需求和流程，本文档负责定义对外接口表达。

规范优先级如下：

1. `PRD.md` 决定产品范围、业务规则和验收目标；
2. 本文档决定所有接口共同遵循的表达规则；
3. `openapi.yaml` 决定单个接口可被程序校验的精确结构。

发现三者矛盾时必须先修改并评审文档，不得由前端或后端自行选择一种实现。

## 1. 契约原则

每个接口必须明确：

- HTTP Method 和 URL；
- 路径参数、查询参数和 Request Body；
- 成功响应、错误响应和 HTTP 状态码；
- 字段名称、数据类型、必填性、取值范围和空值语义；
- 登录要求、角色权限和数据范围；
- 幂等性、并发约束和主要业务错误。

前端不得根据数据库表结构猜测接口字段，后端不得直接把数据库 Entity 当作稳定的外部契约。

## 2. 基础约定

- 业务接口统一使用 `/api` 前缀。
- 请求和响应默认使用 UTF-8 编码的 JSON。
- JSON 字段统一使用 `camelCase`。
- URL 使用小写复数名词表示资源，例如 `/api/routes` 和 `/api/orders`。
- 路径末尾不添加 `/`。
- 路径参数用于标识资源；筛选、搜索、排序和分页使用查询参数。
- 上传、下载和第三方回调可以按接口需要约定其他媒体类型。

### 2.1 通用数据类型

| 业务类型 | JSON 表达 | 示例 | 说明 |
|---|---|---|---|
| 主键、关联 ID | string | `"9007199254740993"` | 避免 JavaScript 整数精度丢失 |
| 金额 | string | `"1999.00"` | 十进制定点字符串，固定两位小数 |
| 普通整数 | number | `12` | 人数、天数、页码等非主键数据 |
| 布尔值 | boolean | `true` | 不使用 `0`、`1` 或字符串代替 |
| 日期 | string | `"2026-09-07"` | `YYYY-MM-DD` |
| 时间点 | string | `"2026-09-07T14:30:00+08:00"` | RFC 3339，必须包含时区偏移 |
| 枚举 | string | `"PENDING_PAYMENT"` | 使用大写英文常量 |

后端金额计算必须使用 `BigDecimal`。调用方不得自行使用浮点运算计算应付金额、退款金额等最终业务结果。

数组没有数据时返回 `[]`，不得返回 `null`。可空字段必须在具体接口中明确说明；未约定为可空的字段不得返回 `null`。

## 3. HTTP 方法与 URL

| 方法 | 用途 | 成功状态码 | 示例 |
|---|---|---|---|
| `GET` | 查询资源，不改变业务状态 | `200` | `GET /api/routes/{routeId}` |
| `POST` | 创建资源 | `201` | `POST /api/orders` |
| `POST` | 执行业务命令 | `200` | `POST /api/orders/{orderNo}/cancel` |
| `PUT` | 完整更新资源的可编辑内容 | `200` | `PUT /api/account/profile` |
| `PATCH` | 局部更新资源 | `200` | `PATCH /api/admin/routes/{routeId}/status` |
| `DELETE` | 删除或逻辑删除资源 | `204` | `DELETE /api/travelers/{travelerId}` |

创建资源成功时应通过 `Location` 响应头返回新资源地址。`204 No Content` 响应不得包含响应体。

无法自然表达为资源增删改查的业务动作可以使用命令式子路径，例如：

```text
POST /api/orders/{orderNo}/pay
POST /api/orders/{orderNo}/cancel
POST /api/admin/orders/{orderNo}/confirm
```

不得使用 `GET` 执行支付、取消、审核、删除或状态变更。

## 4. 请求契约

- Request Body 必须使用明确的 DTO，不使用无约束的 `Map` 代替稳定契约。
- 必填字段、长度、格式、范围和枚举值必须由后端校验。
- 前端校验只用于改善交互，不能替代服务端校验。
- 客户端不得提交由服务端生成或最终决定的字段，例如主键、最终金额、权限和最终业务状态。
- 更新接口必须明确允许修改的字段，不得将任意客户端对象直接覆盖到数据库实体。
- 未知 JSON 字段应作为请求错误处理，避免拼写错误被静默忽略。

### 4.1 分页与排序参数

需要分页的列表接口统一使用：

| 参数 | 类型 | 默认值 | 约束 |
|---|---|---|---|
| `page` | integer | `1` | 从 `1` 开始 |
| `size` | integer | `20` | 范围 `1`～`100` |
| `sort` | string | 由接口约定 | 格式为 `field,asc` 或 `field,desc` |

每个接口必须列出允许筛选和排序的字段，不得把客户端字段名直接拼接到 SQL。

### 4.2 密码字段的长度口径（设置类与验证类，两套规则）

密码字段分两类，**口径不同，不得互相套用**：

**① 设置类**（创建或修改口令）：`POST /auth/register`、`POST /admin/guides`、`POST /admin/staff`
的 `password`，以及 `PUT /account/password` 的 `newPassword` —— **8～72 个字符**，并额外受
**UTF-8 编码不超过 72 字节** 的约束。「字符」按 Unicode 码点计（与 JSON Schema 的 `maxLength`、
后端 `@CodePointLength` 同口径）。两个上限单位不同，只在纯 ASCII 密码下等价：

| 输入 | 字符数 | UTF-8 字节数 | 结果 |
|---|---|---|---|
| `DemoPass123!` | 12 | 12 | 通过 |
| `汉` × 24 | 24 | 72 | 通过（恰好到上限） |
| `汉` × 25 | 25 | 75 | **422** `VALIDATION_ERROR` |
| `😀` × 19 | 38 | 76 | **422** `VALIDATION_ERROR` |
| `a` × 73 | 73 | 73 | **422**（同时超字符数与字节数） |

**② 验证类**（核对已有口令）：`POST /auth/login` 的 `password`、`PUT /account/password` 的
`currentPassword` —— **只要求非空且 UTF-8 不超过 72 字节，不套用 8 字符下限**，判定方式是
**哈希匹配**（长度不合法时自然不匹配）。

为什么验证类不能套用设置规则：历史口令是按 **UTF-16 码元** 口径创建并保存的（当时的校验是
`@Size(min = 8)`，一个 emoji 记 2 个码元），例如 `😀😀😀😀` 只有 4 个字符（码点）却有 8 个码元，
当时能注册、现在也仍能登录。改密接口若在核对原密码之前就以「不足 8 个字符」回 422，这些账号
会既改不了密码、又没有管理员重置入口（见下），等于被永久锁死在旧口令上。因此：

- `POST /auth/login` 的不匹配一律 401 `AUTHENTICATION_REQUIRED`（不因长度暴露账号是否存在）；
- `PUT /account/password` 的原密码不匹配回 422 `VALIDATION_ERROR`「原密码不正确」——
  该端点刻意不用 401：前端 axios 拦截器把任何 401 当成登录失效，用户打错一次原密码就会被强制登出。

两类共有的守卫：

- 字节上限来自 BCrypt：口令超过 72 字节时 `BCryptPasswordEncoder#encode` 会抛
  `IllegalArgumentException`，若只按字符数校验（`@Size`/`maxLength`），中文/emoji 密码会绕过
  校验直达加密层，最终以 **500** 返回；超过 72 字节的输入也不可能是任何已存口令，因此验证类同样保留该上限。
- 超限一律以 **422** 拒绝，**不做静默截断**：截断会让「用户设置的密码」与
  「实际参与校验的字节」不一致。
- 校验失败时**不得产生任何副作用**：创建类接口不落 `sys_user`/`staff` 行，改密接口不更新
  `password_hash`。
- **改密只能由本人发起**：`PUT /account/password` 必须提供原密码，且只作用于当前登录账号
  （该端点没有「目标账号」参数）。契约**不提供**管理员修改他人密码的端点，管理端唯一能设定密码的
  时机是建档时填写的初始密码（`POST /admin/guides`、`POST /admin/staff`，见 PRD §38、§43）——
  正因为没有管理员重置这条退路，验证类才必须保留旧口令的自助升级路径。
  在资料维护端点（如 `PUT /admin/guides/{guideId}`、`PUT /admin/staff/{staffId}`）提交 `password`
  属于契约外字段，按 **400** 拒绝，且整个请求不产生任何写入。

### 4.3 经纬度必须成对提交

涉及地图坐标的请求体，`longitude` 与 `latitude` **要么都提供，要么都不提供**，只给其中一个
按 **422 `VALIDATION_ERROR`** 拒绝（在 `errors[]` 中指向 `longitude`）：

| 请求模型 | 端点 |
|---|---|
| `AttractionUpsertRequest` | `POST /admin/attractions`、`PUT /admin/attractions/{attractionId}` |
| `HotelCreateRequest` / `HotelUpdateRequest` | `POST /admin/hotels`、`PUT /admin/hotels/{hotelId}` |
| `ItineraryItemRequest` | `POST /admin/itinerary-days/{dayId}/items`、`PUT /admin/itinerary-items/{itemId}` |

原因：用户端地图只在经纬度**都非空**时落点。只填一个的坐标会被静默丢弃，运营以为录入了位置、
地图上却什么都没有，且没有任何提示 —— 与其让数据悄悄失效，不如在写入前明确拒绝。
契约用 `CoordinatePairRule` 表达该约束（**字段缺失与显式 `null` 等价**，都表示"未提供坐标"，
两者都未提供是合法的；"提供了经度却没有提供纬度"才是要拒绝的情况）。

两个字段的取值范围仍各自独立校验（经度 ±180、纬度 ±90）。行程项两个坐标都未提供时，会**整对**
继承所关联景点的坐标（见 `AdminRouteService`）；景点坐标不成对（历史数据）时不继承，避免把
"半截坐标"复制进行程项。行程项坐标是写入时的快照：景点坐标之后被修改**不会**回填已存在的行程项，
需要重新保存该行程项才会继承新坐标。

## 5. 统一响应

除 `204 No Content`、文件下载和明确约定的第三方回调外，所有接口使用同一响应结构。

成功响应：

```json
{
  "code": "OK",
  "message": "success",
  "data": {},
  "errors": [],
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

错误响应：

```json
{
  "code": "VALIDATION_ERROR",
  "message": "请求参数校验失败",
  "data": null,
  "errors": [
    {
      "field": "contactPhone",
      "code": "INVALID_FORMAT",
      "message": "手机号格式不正确"
    }
  ],
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `code` | string | 是 | 稳定的机器可读结果码 |
| `message` | string | 是 | 面向用户或开发者的安全摘要 |
| `data` | any/null | 是 | 成功数据；失败时为 `null` |
| `errors` | array | 是 | 详细错误；没有详细错误时为 `[]` |
| `traceId` | string | 是 | 请求链路标识，用于日志关联和问题排查 |

成功响应的 `code` 固定为 `OK`。错误码使用 `UPPER_SNAKE_CASE`，必须稳定且具有业务含义，例如：

```text
VALIDATION_ERROR
AUTHENTICATION_REQUIRED
ACCESS_DENIED
RESOURCE_NOT_FOUND
ORDER_STATE_CONFLICT
DEPARTURE_CAPACITY_INSUFFICIENT
PAYMENT_SIGNATURE_INVALID
PAYMENT_NOT_CONFIGURED
```

前端必须根据 HTTP 状态码和 `code` 处理分支，不得依赖可变的 `message` 文案。`message` 和 `errors` 不得包含异常堆栈、SQL、密钥或敏感个人信息。

## 6. 分页响应

分页结果放在统一响应的 `data` 中：

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "items": [],
    "page": 1,
    "size": 20,
    "total": 0,
    "totalPages": 0
  },
  "errors": [],
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

`items` 必须是数组。`page` 和 `size` 表示当前请求，`total` 表示符合条件的总记录数，`totalPages` 表示总页数。

## 7. HTTP 状态码

| 状态码 | 使用场景 |
|---|---|
| `200 OK` | 查询、更新或业务命令成功 |
| `201 Created` | 资源创建成功 |
| `204 No Content` | 删除成功且无需返回内容 |
| `400 Bad Request` | JSON、参数类型或请求格式错误 |
| `401 Unauthorized` | 未登录、Token 缺失、无效或过期 |
| `403 Forbidden` | 已登录但角色、资源归属或数据权限不足 |
| `404 Not Found` | 目标资源不存在或对当前用户不可见 |
| `409 Conflict` | 并发冲突、重复提交或资源状态冲突 |
| `422 Unprocessable Content` | 请求格式正确，但违反业务规则或字段语义校验失败 |
| `429 Too Many Requests` | 请求频率超过限制 |
| `500 Internal Server Error` | 未预期的服务端错误 |
| `503 Service Unavailable` | 必要的数据库或第三方服务暂时不可用 |

HTTP 状态码表示结果类别，`code` 表示可供程序判断的具体原因，两者必须一致。不得用 `200` 包装失败结果。

## 8. 认证与授权

需要登录的接口使用 JWT Bearer Token：

```http
Authorization: Bearer <token>
```

公开接口至少包括注册、登录、健康检查和公开内容查询。支付回调在传输层允许匿名访问，但必须完成官方签名验证、金额核对和幂等处理。

未明确标记为公开的接口默认要求登录。后台接口要求相应的 `ADMIN` 或 `STAFF` 权限，导游接口要求 `GUIDE` 或管理权限；具体要求必须记录在单个接口契约中。

角色权限只是第一层校验。后端还必须验证资源归属和数据范围，例如用户只能操作自己的订单，导游只能访问自己负责的团期。

所有 `401`、`403` 和其他错误响应必须遵循第 5 节的统一错误结构。

## 9. 幂等与并发

- 支付回调必须按第三方交易号和业务订单号实现幂等处理。
- 创建订单、发起支付等可能因重试产生重复结果的接口，应支持 `Idempotency-Key` 请求头；是否必填由单个接口契约明确。
- 同一用户和同一幂等键的重复请求必须返回同一业务结果，不得重复扣减名额、创建支付或产生退款。
- 团期名额必须由后端通过事务和条件更新控制，不得依赖前端显示的剩余数量。
- 状态变更必须校验当前状态；并发导致的状态冲突返回 `409 Conflict`。

## 10. 数据与安全

- 密码、密码哈希、JWT 密钥、支付密钥和第三方 API 密钥不得出现在响应中。
- 身份证件号等敏感信息按最小必要原则返回，并在普通响应中脱敏。
- 历史订单返回下单时保存的价格和出行人快照，不使用后来修改的实时数据替换。
- 支付结果必须以服务端验签后的回调为准，不能以浏览器跳转为准。
- 日志不得记录完整 Token、密码、私钥或完整身份证件号。
- 服务端异常必须转换为统一错误响应，不得向客户端返回堆栈或内部实现细节。

## 11. 前端调用约定

- 所有请求集中定义在 `frontend/src/api/`，页面不得重复拼接基础地址和通用请求头。
- 默认基础地址为 `/api`，开发环境通过 Vite 代理到后端。
- 请求拦截器统一附加 Bearer Token。
- 响应拦截器统一处理响应包装、身份失效和通用错误。
- 页面根据稳定的 `code` 处理业务分支，并保留 `traceId` 以便反馈和排障。
- 页面必须处理加载中、空数据、请求失败和登录失效状态。

### 11.1 后端未完成时的契约 Mock

前端可使用 `openapi.yaml` 启动契约驱动 Mock。该 Mock 只负责按契约生成请求和响应，用于页面开发、请求层验证和并行协作；它不保存数据，也不代表数据库、权限、事务、库存、支付或状态机已经实现。

在两个终端中分别执行：

```powershell
cd frontend
npm install
npm run mock:api
```

```powershell
cd frontend
npm run dev:mock
```

Mock 模式的 `/api` 请求由 Vite 转发到本机 `4010` 端口。普通 `npm run dev` 仍转发到真实后端 `8080` 端口，生产构建不会启用 Mock。契约 Mock 生成的登录用户带有全部角色，仅用于浏览各角色页面，不代表真实授权规则。

## 12. 契约变更流程

接口进入联调后，不得单方面修改：

- HTTP Method 或 URL；
- 参数位置、字段名称、类型、必填性、空值语义或枚举值；
- 响应结构、分页结构、结果码或 HTTP 状态码；
- 登录、角色或数据权限；
- 幂等规则、业务状态流转和主要错误条件。

确需变更时：

1. 先更新 `openapi.yaml`；如果全局规则变化，同时更新本文档，并说明兼容影响。
2. 通知并确认受影响的前端、后端和测试成员。
3. 同步修改后端实现、前端调用和相关测试。
4. 完成联调后再合并到共享分支。

能兼容旧调用方时，优先新增可选字段或新接口。删除字段、改变字段含义或改变类型属于破坏性变更，不得静默实施。

### 12.1 补齐写入端点的 422 声明（本次变更）

- **内容**：为 14 个写入端点补上 `422 ValidationFailed` 声明：
  `PUT /admin/attractions/{attractionId}`、`PUT /admin/itinerary-items/{itemId}`、
  `PUT /admin/guides/{guideId}`、`PUT /admin/staff/{staffId}`、`PUT /admin/articles/{articleId}`、
  `POST /admin/refunds/{refundId}/approve`、`POST /admin/consultations/{consultationId}/replies`、
  `POST /favorites`，以及 `PATCH /admin/guides/{guideId}/status`、
  `PATCH /admin/staff/{staffId}/status`、`PATCH /admin/users/{userId}/status`、
  `PATCH /admin/departures/{departureId}/status`、`PATCH /admin/reviews/{reviewId}/status`、
  `PATCH /admin/articles/{articleId}/status`。
- **依据**：这些端点在字段语义校验失败时**本来就**返回 422 —— 请求体带 `@Valid`，字段约束失败由
  `GlobalExceptionHandler` 统一映射成 422 `VALIDATION_ERROR`；6 个状态端点的取值另外由
  `@Pattern`（账号 / 团期状态）或服务端枚举白名单（评价 / 攻略 / 导游 / 指南状态）挡住，同样回 422。
  缺的只是契约文本，`docs/openapi.yaml` 原先只列了 `200` / `404` / `409`。
- **兼容影响**：只声明「变更前就已经会返回」的错误响应。成功响应、字段、类型、必填性、权限与状态
  流转均未变；调用方按 422 展示 `message` / `errors[]` 即可，无需修改任何调用代码。
- **确认状态**：本项由 `#46`（后台导游管理）的收尾记录发起 —— 该记录把「`PUT /admin/guides/{guideId}`
  缺 422 声明」列为范围外、需另开变更处理，并指向 `#42` 登记的同类清单。**未记录前端、后端、
  测试成员的分别确认**，不得据此声称三方已分别确认；如需成员级确认，请在合并前补记。
- **仍未声明（同类缺口，建议另开契约变更处理）**：
  - 各写入端点在请求体为**非法 JSON 或含契约外字段**时返回的 `400`（`additionalProperties: false`
    由全局严格模式拒绝）：`docs/openapi.yaml` 目前只有一个端点声明了 400，其余均未逐端点列出；
  - `POST /favorites` 的成功响应形状：实现返回 `200` 且 `data` 为 `null`，契约声明的是
    `201` + `Location` + `FavoriteEnvelope`（属响应契约不一致，与 422 无关）；
  - `POST /payments/alipay/notify`：实现按**表单参数**接收支付宝回调并以 `text/plain` 应答，
    契约描述的是 JSON 请求体（第三方回调，属接收格式不一致）。

### 12.2 酒店展示与住宿安排（本次变更）

- **背景**：用户端线路详情的每日行程此前只能显示一句"住宿：酒店名称"，酒店地址、封面、
  图片、简介、设施、入住退房时间都没有出口；而且"当天到底怎么安排住宿"完全由
  `hotelId` 是否为空来表达，无法区分"只定了住宿标准""还没确认""当天不含住宿"。
  本次为「用户端住宿展示 + 线路下的酒店公开详情」补齐契约（PR #51）。
- **新增**：
  - 路径 `GET /routes/{routeId}/hotels/{hotelId}`（`PublicHotelDetailEnvelope`，`security: []`）；
  - 模型 `HotelSummary`、`PublicHotelDetail`、`HotelImage`、`HotelImageUpsert`、
    `ClockTime`、`ImageUrl`，以及枚举 `AccommodationType`、`HotelFacility`。
- **扩展**：
  - `Hotel` / `HotelCreateRequest` / `HotelUpdateRequest` 增加 `city`、`coverUrl`、`images`、
    `starRating`、`facilities`、`checkInTime`、`checkOutTime`；
  - `ItineraryDay` / `ItineraryDayRequest` 增加 `accommodationType`、`accommodationStandard`、
    `roomType`、`breakfastIncluded`、`accommodationNote`；`ItineraryDay` 另增可空摘要 `hotel`；
  - `GET /admin/hotels` 增加 `city` 查询参数（精确匹配）。
- **为什么酒店详情挂在 `/routes/{routeId}/hotels/{hotelId}` 下面**：酒店在本项目里只是线路行程资源
  （PRD §10），做成公开的 `/hotels/{hotelId}` 等于把后台维护的、可能与任何线路都无关的酒店资料
  整表对外开放。以"这条已发布线路的行程确实安排了它"为门槛，才能既支撑详情页，又不扩大公开面。
- **不新增的内容（明确不做）**：酒店评分、评价数量、销量、房型价格、酒店库存与酒店下单。
  本项目没有酒店评价体系，因此 `starRating` 只表示**官方星级**，与网站评分、"几钻"无关；
  没有可靠依据时必须为 `null`，不得用其它评分凑数。酒店与用户端都不提供"酒店有早餐服务"
  与"本线路含早餐"之间的互相推断：前者是 `Hotel.facilities` 的 `BREAKFAST_SERVICE`，
  后者是当天的 `breakfastIncluded`。
- **错误码与权限**：新端点无需登录；不满足"线路已发布 + 酒店启用 + 确实被该线路行程引用"时
  **统一返回 404 `RESOURCE_NOT_FOUND`**（不区分具体原因，避免把后台酒店的停用状态与存在性探测出来）。
  后台酒店与每日行程端点的权限不变，仍为 `STAFF` / `ADMIN`。
- **兼容影响（逐项）**：
  - **破坏性**：`HotelCreateRequest` / `HotelUpdateRequest` 新增**必填** `city`
    （`minLength: 1`，`maxLength: 64`）。这是本次唯一需要调用方改代码的地方：
    变更前不传 `city` 的建档/修改请求会得到 422 `VALIDATION_ERROR`。
    这样做是为了让"展示城市 + 后台按城市筛选"有可靠数据；存量数据由迁移脚本补成**空串**
    （表示尚未录入城市），**不编造城市名**。因此后台打开一条存量酒店资料并保存时，
    需要先把城市补录进去（这是有意为之的一次性补录，而不是把空值当成一个合法城市）。
    受影响调用方只有本项目后台酒店管理页，已在本 PR 同步修改（表单增加"城市"字段并提交）。
  - **兼容**：`ItineraryDayRequest.accommodationType` 为**可选**：未提交时按 `hotelId` 推断
    （非空 → `HOTEL`，为空 → `PENDING`），与存量数据迁移规则一致，**不会推断成 `NONE`**；
    不使用新字段的既有调用方行为不变。新增的 422 只出现在显式提交了互相矛盾的住宿安排时
    （`HOTEL` 却没有酒店、`STANDARD` 没写住宿标准、`NONE` / `PENDING` 却带了酒店）。
  - **兼容**：`ItineraryDay` 只新增字段（含新增的**必填** `accommodationType`），
    `hotelId` / `hotelName` 原样保留，旧调用方无需修改；新字段由迁移脚本回填后才对外提供
    （见下一条）。
  - **需要先跑迁移**：`route_itinerary_day` 新增 `accommodation_type` 等列，
    `ItineraryDay.accommodationType` 因此成为必填响应字段。`sql/migrations/010-add-hotel-accommodation.sql`
    会按"有酒店 → `HOTEL`、无酒店 → `PENDING`"回填存量行，并在回填之后补上约束
    `ck_day_accommodation`（住宿类型必须与酒店关联、住宿标准自洽）；**未执行迁移就升级应用**会因
    缺列直接报 `Unknown column`，而不是静默给出错误类型。约束挡的是绕过服务层的写入：
    `accommodation_type` 有默认值，只写 `hotel_id` 而不写类型会静默产出一行
    "关联了酒店、类型却是待确认"的自相矛盾数据（MySQL 8.0.16 之前只解析 CHECK 而不执行，
    此时退化为服务层保证）。
  - **兼容**：`Hotel` 只新增字段；`Hotel.version` 乐观锁语义未变（修改仍需回传版本，冲突仍为
    409 `HOTEL_VERSION_CONFLICT`），历史订单的价格与出行人快照不受酒店资料修改影响。
- **确认状态**：契约已随本 PR 更新，并已按 §13 的工作流完成"先生成契约、再实现"的顺序；
  **未记录前端、后端、测试成员的分别确认**，不得据此声称三方已分别确认。如需成员级确认，
  请在合并前补记。

### 12.3 Dashboard 的团期报名情况与热门目的地口径（本次变更）

- **背景**：PRD §32 把「团期报名情况」列为后台工作台图表，但 `DashboardData` 里没有对应字段，
  实现也就无从提供；同一节要求的热门数据里，PRD §27 明确「热门目的地**按有效报名游客数量**统计」，
  而实现是 `SUM(travel_route.valid_booking_count)` —— 那一列在确认报名时 `+1`、退款完成时 `-1`，
  存的是**订单条数**，于是「热门目的地」实际按订单数排行（1 单 6 人的目的地会排在 2 单 4 人的后面）。
- **新增**：
  - 模型 `DepartureEnrollment`：`departureId`、`routeId`、`routeName`、`startDate`、`maxPeople`、
    `reservedPeople`、`confirmedPeople`、`remainingSeats`；
  - `DashboardData` 增加必填数组 `departureEnrollment`：未来最近 5 个**尚未出发且仍在销售**
    （`OPEN` 可报名 + `FULL` 名额已满）的团期，按出发日期升序，没有时为空数组。
    取 `FULL` 是有意的：满团正是运营最需要看到的一档；`DRAFT`（尚未发布）与 `CLOSED`（已停止销售）
    不属于"报名情况"，未发布线路下的团期也不计入。
  - `remainingSeats` 由 `maxPeople - reservedPeople - confirmedPeople` 得出，**下限为 0**：
    名额校验只作用于写入路径，历史脏数据里已占用可能超过名额，此时必须给 0 而不是负数。
- **语义修正（非结构变更）**：`DestinationStatistic.validBookingCount` 的取值口径从「订单条数」
  改为「**有效报名游客数量**（`adult_count + child_count`）」。字段名、类型、必填性、位置都没变，
  但**含义变了**，属于 §12 列出的"改变字段含义"，因此在这里显式登记：
  - "有效报名"的集合与线路侧的 `valid_booking_count` **完全一致** —— 已确认（含出行中/已完成）
    且未完成退款的订单；因此退款申请中的订单仍计入（退款完成才回退），而由"待确认"发起的退款申请
    不计入（那类订单从来没被 `+1` 过，靠 `refund.original_order_status` 区分）。
  - 热门线路仍按同一集合的**订单条数**排行（PRD：按有效报名订单统计），即两处"热门"共用同一套
    "有效"定义、只是度量不同。
- **兼容影响**：
  - `departureEnrollment` 是**新增**字段：JSON 调用方忽略未知字段即可，不需要改代码；
    契约 Mock（§11.1）会按新的 `openapi.yaml` 自动带上它。这是本次唯一的响应结构变化。
  - `GET /home` 与 `GET /admin/dashboard` 的「热门目的地」**数值与排序会变**（变成人数口径），
    字段与类型不变，但展示数量的调用方必须将单位同步为「人次」。受影响调用方只有本项目：
    用户端首页/搜索页的"热门目的地"入口（按名称展示与跳转，无需调整单位）与后台工作台排行
    （数量单位改为「人次」）。两处共用 `TravelRouteMapper.popularDestinations()`
    这一次查询，不会出现两套"热门目的地"定义。
  - 无数据库结构变更、无需迁移脚本。
- **确认状态**：随 Dashboard 统计口径和 PRD §27/§32 的对齐一并提出，已随对应 PR 评审。
  **未记录前端、后端、测试成员的分别确认**，不得据此声称三方已分别确认；如需成员级确认，
  请在合并前补记。

### 12.4 报名审核复核与交易审计口径（本次变更，成员 B）

- **背景**：
  - `POST /admin/orders/{orderNo}/confirm` 此前只检查订单状态、团期状态与容量，**没有重新核对**
    订单支付状态与出行人快照 —— 支付未到账、或快照条数与订单登记人数不符、或实名信息缺失的订单，
    只要状态是 `PAID_WAIT_CONFIRM` 就能进入 `CONFIRMED` 并占用名额、回填线路统计（PRD §11.1）。
  - 该端点原先是无条件读订单再更新：两个工作人员同时确认同一张订单时，两边都会读到
    `PAID_WAIT_CONFIRM`，于是已确认人数与有效报名数各加两次、用户收到两条通知。
  - 确认、退款审核的 `operation_log` 留痕原先写在 Controller 里，**排在 Service 事务结束之后**：
    业务已提交但留痕写入失败的窗口里，会出现"改了数据却没有操作记录"。
- **契约声明变化（仅描述，无结构与状态码集合变化）**：`POST /admin/orders/{orderNo}/confirm`
  的 `409` 从共用 `Conflict` 示例改为显式列出该端点会返回的结果码 ——
  `ORDER_STATE_CONFLICT`（订单状态不符 / 团期不可用 / 已被他人确认）、
  `DEPARTURE_CAPACITY_INSUFFICIENT`（名额不足）、
  `ORDER_AUDIT_ANOMALY`（**新增的错误条件**，报名业务复核未通过）。
  响应 schema 仍为 `ErrorEnvelope`，`409` 本来就在冻结的状态码表内，未新增状态码。
- **复核规则（未通过即 `409 ORDER_AUDIT_ANOMALY`）**：
  1. 订单 `paymentStatus` 与关联支付单 `status` 都必须是 `PAID`，否则 `PAYMENT_NOT_SETTLED`；
  2. 出行人快照条数必须等于 `adult_count + child_count`，否则 `TRAVELER_SNAPSHOT_MISMATCH`；
  3. 每条快照的姓名、证件类型、证件号码都必须非空，否则 `TRAVELER_IDENTITY_INCOMPLETE`。
  具体原因在响应的 `message` 里说明；`code` 恒为 `ORDER_AUDIT_ANOMALY`。
- **复核失败时的状态与副作用**：订单**留在** `PAID_WAIT_CONFIRM`，不改名额、不改线路有效报名数、
  不发"报名已确认"通知；只做两件事 —— 写一条审计留痕（`module=订单`、
  `operationType=AUDIT_ANOMALY`、`result=FAILURE`）与发一条站内通知
  （`type=ORDER_AUDIT_ANOMALY`）。这正是 PRD §29 要求的"报名审核异常"站内通知。
- **通知去重与脱敏**：
  - 同一订单 + **同一异常原因**只通知一次（标题为「订单 {orderNo} 报名审核异常：{原因短标签}」，
    按 `user_id + type + title` 精确匹配判重）；原因发生变化时会再通知一次，不把新问题静默吞掉。
  - 接口 `message` 与通知正文只包含"第 N 位出行人缺少哪个字段"，**不含姓名与证件号**
    （§10 与 PRD §53.1/§53.2 的脱敏要求）。
- **并发语义（可验收）**：同一订单的并发确认只有**一次**生效 —— 状态迁移走
  `WHERE id=? AND status='PAID_WAIT_CONFIRM'` 的条件更新，未抢到该行的请求返回
  `409 ORDER_STATE_CONFLICT`，且**不执行**名额迁移、统计回填与通知。实测数据见 12.4 末尾。
- **审计口径**：确认报名、退款审核通过、退款审核拒绝、**出款结果待确认**、评价可见状态变更
  的留痕全部改在 Service 的业务事务内写入（Controller 不再各自记录）。
  `OperationLog.result` 的枚举**未变**，仍是 `SUCCESS` / `FAILURE`：
  "出款结果待确认"用 `operationType=APPROVE_UNCONFIRMED` 区分而**不标成 FAILURE** ——
  标 FAILURE 会被读成"退款被拒、订单已恢复"，而这一刻钱可能已经退出去。
  被并发闸门挡下、未真正推进状态的请求不写留痕（它没有产生任何操作）。
  **"同事务"是可验收的**：审计写入失败时业务修改一并回滚，不会出现"接口报错、数据已经改掉"。
  该性质由 `OrderAuditRollbackIntegrationTest` 用真实库验证（`operation_log.operator_id`
  的外键失败注入审计写失败，断言订单状态、`confirmed_at`、团期名额、线路统计与通知全部回滚）。
- **数据库**：新增迁移 `sql/migrations/012-add-message-audit-dedup-index.sql`，
  为 `sys_message` 加 `idx_message_user_type_title (user_id, type, title)` 支撑上面的判重查询。
  **只加普通索引，不加唯一键**：去重是业务判据而不是库约束 —— 判重查询跑在
  `WHERE id=? AND status='PAID_WAIT_CONFIRM'` 那次条件更新所在的同一事务、同一把订单行锁之内，
  同一订单不会并发进入复核，不需要唯一键当并发闸门；唯一键也必须包含 `title`，
  而 `title` 是「订单 {orderNo} 报名审核异常：{原因短标签}」，不同订单、不同原因的标题本就不同。
  未执行只影响该查询效率，不影响正确性；`sql/schema.sql` 已同步。**无列变更。**
- **兼容影响**：无字段增删改、无枚举变化。新增的是**错误条件**，按稳定 `code` 分支的调用方
  无需改动；只按 `409` 笼统处理"审核失败"的调用方可继续工作，但建议按 `code` 区分
  `ORDER_AUDIT_ANOMALY`（应提示"该订单资料有问题，请核对出行人信息"而不是"请重试"）。
  审核异常回 `409` 而非 `401`，不受前端"任何 401 即登出"的拦截器影响。
- **实测**：真实 MySQL 并发确认 8 线程 / 同一订单 —— 成功 1 次、被拒 7 次，
  团期 `reservedPeople=0`、`confirmedPeople=2`，线路 `valid_booking_count` 恰好 +1，
  审计留痕恰好 1 条（用例见 `DepartureCapacityConcurrencyIntegrationTest`）。
- **确认状态**：由成员 B 在交易模块收口中发起。前端（成员 E）联调尚未回执，
  **不得声称前端已确认**；页面需按 `code` 区分上述两类 409。

### 12.5 热门线路改为按有效报名订单实时统计（本次变更）

- **背景**：PRD §27 要求「热门线路按有效报名订单统计」、§32 要求 Dashboard「数据全部从数据库实时或
  统计查询产生」，但实现里「热门线路」是按 `travel_route.valid_booking_count` 这一物化计数列排序的，
  而该列在演示库中被预置了远高于真实订单的基数（如 `106`），于是排行与真实订单不符，且不会自动收敛。
- **实现变更（非结构变更）**：
  - 新增 `TravelRouteMapper#popularRouteCounts(limit)`：直接聚合 `travel_order`，按线路统计「有效报名」
    **订单条数** 并实时排行取前 N 条；
  - `RouteService#popularRoutes(limit, currentUserId)` 统一封装，供 `GET /home` 与
    `GET /admin/dashboard` 共用；返回的 `RouteSummary.validBookingCount` 用真实订单条数覆盖，
    保证展示数值与排行口径一致；
  - 「有效报名」集合与热门目的地（`popularDestinations()`）完全一致，只是度量从人数换成订单条数。
- **兼容影响**：
  - 字段、类型、必填性均未变；`popularRoutes[].validBookingCount` 的含义明确为「有效报名订单条数」
    （注意与 `popularDestinations[].validBookingCount` 的「游客人数」区分，二者同名不同度量）；
  - 数值与排序会随真实订单变化（不再固定），数量可能比此前小；
  - 无数据库结构变更、无需迁移脚本。`sql/test-data.sql` 不再预置 `travel_route.valid_booking_count`，
    改为在订单插入后按权威口径重算，演示数据与真实订单一致。
- **确认状态**：随 PRD §27/§32 收口提出，已随对应 PR 评审。**未记录前端、后端、测试成员的分别确认**，
  不得据此声称三方已分别确认；如需成员级确认，请在合并前补记。

## 13. 模块契约工作流

每个模块都按以下顺序推进，不能等后端写完后再反推接口：

```text
PRD 场景与规则
    ↓
OpenAPI 路径、模型、权限和错误
    ↓ 双方评审并冻结
前端基于契约 Mock 开发  ║  后端基于契约实现
    ↓                    ║    ↓
前端交互测试             ║  后端契约与业务测试
    └──────────────真实接口联调──────────────┘
```

模块契约使用以下状态；进度记录在 Issue、任务看板或 PR 中，不在契约文件里维护完成百分比：

| 状态 | 含义 | 允许开展的工作 |
|---|---|---|
| `DRAFT` | 产品场景或字段仍在讨论 | 原型和契约讨论，不开始正式实现 |
| `REVIEWED` | 前端、后端已确认可实现性 | 可完善示例和测试用例 |
| `FROZEN` | 当前迭代不再单方面变更 | 前后端可以独立实现 |
| `IMPLEMENTED` | 后端实现并通过契约测试 | 切换真实接口联调 |
| `VERIFIED` | 前后端、权限和业务流程均通过 | 模块达到本迭代完成条件 |

新增模块接口时，应直接在 `openapi.yaml` 中增加对应 Tag、Path、Request/Response Schema、权限、幂等头和主要错误响应。描述复杂业务规则时引用 PRD 或架构文档，不在多个文件复制字段表。

当前 `openapi.yaml` 根节点的 `x-contract-status` 为 `FROZEN`，表示其中已定义的接口可供前端和后端并行实现；它不表示后端已经实现。

### 13.1 本地模拟支付的兼容扩展

新增已认证的 `GET /payments/options` 和 `POST /payments/local/{orderNo}`，精确契约见 OpenAPI。
后者仅在显式启用 `local-payment` 且无 `prod` / `production` profile 时注册；默认环境返回 404。
金额由订单快照读取，无请求体，以订单号实现重复/并发请求幂等，权限仍由后端校验。
原 `/orders/{orderNo}/pay` 配置缺失时继续拒绝。

`Payment.channel` 增加 `LOCAL_SIMULATION`；此类测试数据只能用于本地开发库，
不可当作真实经营数据。模拟支付复用业务入账；第三方回调仍执行官方验签。
模拟支付订单申请或审核退款返回 409 `LOCAL_PAYMENT_REFUND_UNSUPPORTED`，不会调用支付宝出款。
前后端、契约及测试随本功能同步修改。

## 14. 实现与验收要求

后端实现每个接口时必须逐项核对 OpenAPI 中的 `operationId`、路径、方法、请求模型、成功状态码、响应模型、权限、幂等要求和错误响应。Controller 使用独立 DTO / VO，不得直接暴露 Entity；Java `Long` 主键必须按字符串输出，金额必须由 `BigDecimal` 计算并按十进制字符串输出。

后端测试至少覆盖正常响应、参数校验、未登录、权限或资源归属、业务状态冲突，以及订单名额和支付退款等关键分支。接口返回结构应通过契约校验；不得为了让已有实现通过而未经评审地放宽契约。

前端从契约 Mock 切换到真实后端时，只允许更换代理目标，不应修改页面字段映射。如果必须修改调用代码，通常说明真实实现与冻结契约不一致，应先定位并修正契约或后端实现后再联调。
