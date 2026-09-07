# Travel Agency

基于 Vue 3 + Spring Boot 4 的旅行社在线跟团游系统，覆盖用户端、旅行社管理后台和导游工作台。

## 技术栈

- 后端：Java 25、Spring Boot 4.0.8、Maven、MyBatis-Plus 3.5.17、MySQL 9.7
- 前端：Vue 3、JavaScript、Vite、Vue Router、Pinia、Element Plus、npm
- 第三方适配：高德地图 JS API、支付宝沙箱回调适配点

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

请确认本机已安装并配置 JDK 25 和 Maven，并确保以下命令可用：

```powershell
java -version
mvn -version
```

也可以在 IntelliJ IDEA 中将 Project SDK 和 Maven Runner 分别设置为本机对应的版本。

```powershell
cd backend
mvn -ntp spring-boot:run
```

如果本机命令不是 `mvn`，请替换为对应的 Maven 可执行命令；也可以在 IDEA 中直接运行 `com.travelagency.TravelAgencyApplication`。

API 地址：`http://localhost:8080`；健康检查：`GET /api/health`。

### 3. 启动前端

```powershell
cd frontend
npm install
Copy-Item .env.example .env
npm run dev
```

访问：`http://localhost:5173`。Vite 会将 `/api` 代理到 `http://localhost:8080`。

高德地图可选配置：在 `frontend/.env` 中填写 `VITE_AMAP_KEY` 和 `VITE_AMAP_SECURITY_CODE`。未配置时页面保留地图来源说明和占位状态，不会伪造地图数据。

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
AMAP_ENABLED / AMAP_WEB_KEY
ALIPAY_SANDBOX / ALIPAY_ENABLED / ALIPAY_CALLBACK_SECRET
```

目标支付宝异步通知入口为 `POST /api/payments/alipay/notify`。后端正式实现时必须接入支付宝沙箱官方签名校验，并核对商户、订单号和金额，业务层只接受验签后的结果；浏览器跳转不能作为支付成功依据。

## 验证命令

```powershell
# backend（需确保本机 JDK 25 已配置，或 java 已加入 PATH）
cd backend
mvn -ntp test

# frontend
cd ..\frontend
npm install
npm run build
```

项目范围见 [docs/PRD.md](docs/PRD.md)，架构和状态机见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，API 全局规范与具体接口分别见 [docs/API.md](docs/API.md) 和 [docs/openapi.yaml](docs/openapi.yaml)。
