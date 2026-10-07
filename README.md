# Travel Agency

基于 Vue 3 + Spring Boot 4 的旅行社在线跟团游系统，覆盖用户端、旅行社管理后台和导游工作台。

## 技术栈

- 后端：Java 21、Spring Boot 4.0.8、Maven、MyBatis-Plus 3.5.17、MySQL 9.7
- 前端：Vue 3、JavaScript、Vite、Vue Router、Pinia、Element Plus、npm、Leaflet
- 第三方适配：天地图 / OpenStreetMap 可切换底图、支付宝沙箱回调适配点

## 目录

```text
travel-agency/
├─ backend/                 Spring Boot REST API
├─ frontend/                Vue 3 用户端 + 管理后台 SPA
├─ sql/                     schema.sql / test-data.sql
├─ deploy/                  Docker Compose、容器配置
└─ docs/                    PRD、架构、API 规范和 OpenAPI 契约
```

## 本地启动

### 1. 启动 MySQL

推荐使用项目提供的 Docker Compose 启动数据库。在项目根目录执行：

```bash
docker compose -f deploy/docker-compose.db.yml up -d
```

数据库容器名为 `travel-agency-mysql`，监听 `localhost:3306`。首次创建数据卷时，`sql/schema.sql` 和 `sql/test-data.sql` 会自动导入。

可使用以下命令确认或停止数据库服务：

```bash
docker compose -f deploy/docker-compose.db.yml ps
docker compose -f deploy/docker-compose.db.yml down
```

`down` 不会删除数据卷，因此数据库数据会保留；不要在不需要重置数据库时使用 `down -v`。

也可以使用已有的 MySQL 实例。请手动执行：

```text
sql/schema.sql
sql/test-data.sql
```

默认数据库名为 `travel_agency`，开发账号为 `travel`，密码为 `travel_password`。后端默认连接 `localhost:3306`；如使用自己的数据库配置，请通过 `DB_URL`、`DB_USERNAME` 和 `DB_PASSWORD` 覆盖。

### 2. 启动后端

请确认本机已安装并配置 JDK 21 和 Maven，并确保以下命令可用：

```powershell
java -version
mvn -version
```

也可以在 IntelliJ IDEA 中将 Project SDK 和 Maven Runner 分别设置为本机对应的版本。

```powershell
cd backend
mvn -ntp spring-boot:run
```

本地 JWT 密钥保存在 Git 忽略的 `backend/config/application-local.properties` 中，配置项为 `JWT_SECRET`。从仓库根目录或 `backend` 目录启动均会读取该文件；新开发环境需自行创建该文件并填写至少 32 个 UTF-8 字节的随机密钥。也可以通过 `JWT_SECRET` 环境变量覆盖。若本机命令不是 `mvn`，请替换为对应的 Maven 可执行命令；也可以在 IDEA 中直接运行 `com.travelagency.TravelAgencyApplication`。

API 地址：`http://localhost:8080`；健康检查：`GET /api/health`。

### 3. 启动前端

```powershell
cd frontend
npm install
Copy-Item .env.example .env
npm run dev
```

访问：`http://localhost:5173`。Vite 会将 `/api` 代理到 `http://localhost:8080`。

主地图使用 Leaflet，默认加载天地图矢量底图与中文地名注记，保留 OpenStreetMap（OSM）。地图右侧「地图源」可以随时切换，浏览器记住选择；切换保留当前视角、景点标记与行程连线。未配置天地图 Key 时显示提示，不请求无授权瓦片，可手动切换 OSM。

在 [天地图开放平台](https://lbs.tianditu.gov.cn/) 创建浏览器端应用、取得可调用地图瓦片服务的 Key，并按平台要求设置实际访问域名（本地联调包含 localhost）。在 `frontend/.env.local` 中填写：

```dotenv
VITE_TIANDITU_KEY=你的天地图Key
```

可选 `VITE_MAP_PROVIDER=osm` 将初始源设为 OSM（浏览器已保存的选择优先）。`.env.local` 已被 Git 忽略，真实 Key 不得提交。浏览器端 Key 会进入前端构建并随瓦片请求可见，应使用平台提供的域名限制，不能填服务端私密凭据。底图或注记加载失败会提示检查网络或地图服务授权，不自动跳转到可能同样无法访问的 OSM。

线路详情加载后，在主地图上显示行程数据中的景点坐标和顺序连线。没有坐标时仅显示底图，详情面板提示录入坐标。坐标继续使用 WGS-84；天地图使用与 Leaflet 默认投影一致的 Web Mercator（`vec_w` / `cva_w`）WMTS。已有高德 GCJ-02 坐标需核对来源，切换底图不会自动转换历史坐标。地图保留当前平台署名与来源链接；OSM 公共瓦片仅供符合 [OpenStreetMap 使用政策](https://operations.osmfoundation.org/policies/tiles/) 的交互浏览使用，不批量下载或预取。

演示管理员账号（执行 `test-data.sql` 后）：`admin / password`。该账号和所有 SQL 测试数据仅用于软件测试、课程演示，不代表真实旅行社经营数据。

### 前端独立开发（后端尚未实现时）

项目可以根据 `docs/openapi.yaml` 启动无状态的契约 Mock。分别打开两个终端：

```powershell
cd frontend
npm install
npm run mock:api
```

```powershell
cd frontend
npm run dev:mock
```

此时前端仍访问 `/api`，但请求会转发到 `http://localhost:4010`。Mock 只用于验证接口形状和开发页面，不保存修改、不执行业务规则，也不表示后端功能已经完成。需要真实联调时使用普通的 `npm run dev`。

## 项目范围

本项目面向跟团游预订与运营场景，服务游客、旅行社工作人员、导游和系统管理员。业务范围包括旅游线路与团期管理、出行人和订单处理、支付与退款、评价与咨询，以及相应的权限管理和运营协作。

## 重要配置

后端敏感配置通过环境变量覆盖，禁止将真实密钥提交到仓库：

```text
DB_URL / DB_USERNAME / DB_PASSWORD
JWT_SECRET
ALIPAY_SANDBOX / ALIPAY_ENABLED / ALIPAY_GATEWAY_URL
ALIPAY_APP_ID / ALIPAY_APP_PRIVATE_KEY / ALIPAY_PUBLIC_KEY
ALIPAY_NOTIFY_URL / ALIPAY_SELLER_ID
ALIPAY_CALLBACK_SECRET
DEPARTURE_REMINDER_ENABLED / DEPARTURE_REMINDER_DAYS / DEPARTURE_REMINDER_CRON
```

JWT 签名密钥是后端启动必填项，本地可放在上述 Git 忽略的配置文件中，部署环境应通过 `JWT_SECRET` 注入。密钥必须至少有 32 个 UTF-8 字节；缺失或过短时应用会在启动阶段失败（fail-fast），不会退回到源码中的占位密钥。

### 支付宝沙箱

日常开发可以使用本地模拟支付，免去配置支付宝密钥和公网回调的步骤。从 `backend` 目录启动：

```powershell
mvn -ntp spring-boot:run "-Dspring-boot.run.profiles=local-payment"
```

也可以在 IDEA 的 Active profiles 填写 `local-payment`。数据库和 JWT 配置仍按上文设置。
该 profile 将后端绑定到 `127.0.0.1`，改成非回环地址会拒绝启动。前端建议在本机启动
`npm run dev --host 127.0.0.1`，不要通过公网隧道或反向代理公开这一开发实例。

付款页将显示并优先选择“本地模拟支付”。点击“确认模拟付款”会写入本地数据库，订单进入待旅行社确认；
结果页、订单详情和通知明确标记测试来源，不发生资金交易。已经发起支付宝支付的订单不能切换为模拟支付。
重复提交同一模拟付款不会重复入账；其他账号不能代付。停止后端并移除该 profile 重启即可关闭模拟入口。
默认环境以及同时启用 `prod` / `production` 的环境不提供模拟接口，普通支付宝支付不会自动降级。

模拟订单不支持支付宝退款。需要测试退款、收银台、签名或异步回调时，请用下面的支付宝沙箱配置和新测试订单。

沙箱密钥从开放平台沙箱应用页获取（`应用信息 → 开发信息 → 接口加签方式`），登录即得，无需申请资质：

```powershell
$env:ALIPAY_APP_ID          = "沙箱应用 APPID"
$env:ALIPAY_APP_PRIVATE_KEY = "应用私钥（PKCS#8，可只填 Base64 主体）"
$env:ALIPAY_PUBLIC_KEY      = "支付宝公钥（用于验签通知）"
$env:ALIPAY_NOTIFY_URL      = "https://<公网可达域名>/api/payments/alipay/notify"
# $env:ALIPAY_SELLER_ID 可选，配置后回调会一并核对 seller_id
# $env:ALIPAY_GATEWAY_URL 默认已指向沙箱新版网关，无需设置
```

**密钥一律通过环境变量注入，禁止提交到仓库**（见 CONTRIBUTING §14）。

签名与验签统一走支付宝官方 SDK `com.alipay.sdk:alipay-sdk-java`（`AlipaySignature.rsaCheckV1`
与 `AlipayClient.pageExecute`），不再自写 RSA2 拼接 —— 这是 `docs/openapi.yaml` 对
`POST /payments/alipay/notify` 的硬性要求。

支付链路按配置自动选择路径，**配置不齐一律 fail-closed**（返回 409 与明确错误码，不会打成 500，
更不会给出一个「用户付得进去、系统收不到结果」的链接）：

| 配置情况 | `POST /orders/{orderNo}/pay` 发起支付 | `POST /payments/alipay/notify` 异步通知验签 |
| --- | --- | --- |
| `ALIPAY_APP_ID` + `ALIPAY_APP_PRIVATE_KEY` + `ALIPAY_PUBLIC_KEY` + `ALIPAY_NOTIFY_URL` **四项全配齐** | 生成真实沙箱收银台链接，`notify_url` 作为公共参数参与签名 | 官方 SDK **RSA2** 验签，始终核对 `app_id`；配了 `ALIPAY_SELLER_ID` 再核对 `seller_id` |
| 以上四项**缺任意一项** | **409 `PAYMENT_NOT_CONFIGURED`**，`message` 直接列出缺少的环境变量名；**不生成链接、不写 payment 行** | 见下面两行（走官方验签的前提是 APPID 与公钥齐备） |
| `ALIPAY_APP_ID` / `ALIPAY_PUBLIC_KEY` **只配了其一** | 409 `PAYMENT_NOT_CONFIGURED`（同样属于缺项） | **一律拒绝**（半配置按 fail-closed 处理，不会降级到 HMAC） |
| `ALIPAY_APP_ID` 与 `ALIPAY_PUBLIC_KEY` 都未配置 | 409 `PAYMENT_NOT_CONFIGURED` | 回退自建 HMAC（`ALIPAY_CALLBACK_SECRET`），未配置则一律拒绝 |

⚠️ 发起支付要求**四项齐全**：缺 `ALIPAY_NOTIFY_URL` 支付宝无处回传结果，缺 `ALIPAY_PUBLIC_KEY`
回调验不了签，两种情况下用户付了钱订单都会一直停在待支付。因此宁可明确拒绝并提示缺哪一项，
也不放行一个「能付款但收不到结果」的链接。**联调前请先把这四项配好。**

异步通知入口为 `POST /api/payments/alipay/notify`。回调**只认验签后的结果**，并核对商户、
订单号与金额，业务层只接受验签通过且金额一致的支付；浏览器跳转结果不作为支付成功依据。

> 本地自建 HMAC 那条路径只用于「手上还没有沙箱密钥」时把链路跑通，**不代表支付宝官方验签**；
> 联调与验收请务必把 `ALIPAY_APP_ID` 与 `ALIPAY_PUBLIC_KEY` 一起配上，让回调走官方 SDK 验签。

## 验证命令

```powershell
# backend（需确保本机 JDK 21 已配置，或 java 已加入 PATH）
cd backend
mvn -ntp test

# frontend
cd ..\frontend
npm install
npm run build
```

项目范围见 [docs/PRD.md](docs/PRD.md)，架构和状态机见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，API 全局规范与具体接口分别见 [docs/API.md](docs/API.md) 和 [docs/openapi.yaml](docs/openapi.yaml)。

其它交付文档：部署（含 HTTPS）见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)，数据库设计见 [docs/DATABASE_DESIGN.md](docs/DATABASE_DESIGN.md)，数据来源与许可见 [docs/DATA_SOURCES.md](docs/DATA_SOURCES.md)，资源与导游模块说明见 [docs/MODULE_RESOURCES.md](docs/MODULE_RESOURCES.md)。

## 站内消息与提醒

PRD §29 的站内消息覆盖：支付成功、报名确认、退款审核结果、**即将出发提醒**、**团期状态变化**。

- 即将出发提醒由定时任务 `DepartureReminderService` 发送：默认提前 `DEPARTURE_REMINDER_DAYS=3` 天，
  每天 `DEPARTURE_REMINDER_CRON`（默认 `0 0 9 * * *`）执行；只提醒符合出行条件的订单（已确认报名、团期未取消未完成），
  通过 `departure_reminder` 唯一键保证重复执行不重复发送。可用 `DEPARTURE_REMINDER_ENABLED=false` 关闭。
- 团期状态变化（后台改状态、导游开始/结束行程）会向该团期下有效订单的用户投递站内消息，与状态写入同一事务。
- 存量库升级前请先执行 `sql/migrations/011-add-departure-reminder.sql`（详见 [迁移说明](sql/migrations/README.md)）。
