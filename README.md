# Travel Agency

基于 Vue 3 + Spring Boot 4 的旅行社在线跟团游系统，覆盖用户端、旅行社管理后台和导游工作台。

## 技术栈

- 后端：Java 21、Spring Boot 4.0.8、Maven、MyBatis-Plus 3.5.17、MySQL 9.7
- 前端：Vue 3、JavaScript、Vite、Vue Router、Pinia、Element Plus、npm、Leaflet
- 第三方适配：OpenStreetMap 底图、支付宝沙箱回调适配点

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

主地图无需 API Key。地图使用 Leaflet 加载 OpenStreetMap 公共瓦片；线路详情加载后，在主地图上显示行程数据中的景点坐标和顺序连线。没有坐标时仅显示底图，详情面板提示录入坐标。请为新录入的坐标使用 WGS-84，并核对已有坐标的来源；若已有数据是高德 GCJ-02 坐标，直接显示在 OpenStreetMap 底图上可能产生偏移。公共瓦片仅供符合 [OpenStreetMap 使用政策](https://operations.osmfoundation.org/policies/tiles/) 的交互浏览使用，地图中必须保留 OpenStreetMap 贡献者署名。

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
```

JWT 签名密钥是后端启动必填项，本地可放在上述 Git 忽略的配置文件中，部署环境应通过 `JWT_SECRET` 注入。密钥必须至少有 32 个 UTF-8 字节；缺失或过短时应用会在启动阶段失败（fail-fast），不会退回到源码中的占位密钥。

### 支付宝沙箱

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
