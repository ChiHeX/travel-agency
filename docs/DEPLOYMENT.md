# 部署说明（含 HTTPS 演示部署）

> 面向 `travel-agency` 的演示/生产部署。默认仓库只提供 HTTP 配置（`deploy/nginx.conf`），
> 本文补齐 **HTTPS 入口**，并给出前后端、地图底图与支付宝回调的可访问性验收步骤。
>
> 关键约束（务必遵守）：**不提交任何密钥/证书；不重置已有数据库或 Docker 数据卷。**
> 详见 [CONTRIBUTING §14](../CONTRIBUTING.md) 与 `deploy/docker-compose.db.yml` 的说明。

## 1. 部署拓扑

```text
        浏览器
          │  https://<域名>            https://<域名>/api/…（同源）
          ▼
┌───────────────────────────────┐        ┌──────────────────────────┐
│ frontend 容器                  │  /api  │ backend 容器              │
│ nginx：SPA 静态资源 + 反向代理  │ ─────▶ │ Spring Boot REST API      │
│ 监听 80（跳转）/443（TLS）      │        │ 监听 8080（仅内网）       │
└───────────────────────────────┘        └──────────────────────────┘
                                                     │
                                                     ▼
                                          ┌──────────────────────────┐
                                          │ mysql 容器（数据卷持久化）│
                                          └──────────────────────────┘
```

- `frontend` 是唯一对外入口：443 提供页面并反代 `/api` 到 `backend`。
- **地图底图**由浏览器直接请求天地图/OSM（客户端侧），不走后端；HTTPS 下必须使用 HTTPS 瓦片地址，并让地图 Key 的域名白名单包含对外域名。
- **支付宝异步回调**由支付宝服务器直接请求后端公网地址，因此 `ALIPAY_NOTIFY_URL` 必须是公网可达的 HTTPS 完整地址。

## 2. 前置条件

| 项 | 要求 |
|---|---|
| 主机 | 已安装 Docker 与 Compose 插件；对外放行 80、443 |
| 域名 | 已解析到主机公网 IP（A 记录） |
| 证书 | `fullchain.pem` + `privkey.pem`，覆盖对外域名（Let's Encrypt 或机构证书） |
| 密钥 | `JWT_SECRET`（≥32 字节随机串）；启用真实支付宝时另需沙箱/生产密钥 |
| 天地图 | 浏览器端 Key，域名白名单包含对外域名 |

## 3. 目录与文件

```text
deploy/
├─ docker-compose.yml              基础编排（mysql / backend / frontend，HTTP）
├─ docker-compose.db.yml           仅数据库（本地开发）
├─ docker-compose.https.yml        HTTPS 叠加编排（本文使用）
├─ nginx.conf                      默认纯 HTTP 配置（本地无证书演示）
├─ nginx.https.conf                HTTPS 配置（80 跳转 + 443 TLS）
└─ certs/                          ← 运行时挂载的证书目录（不要提交）
   ├─ fullchain.pem
   └─ privkey.pem
```

## 4. 部署步骤

### 4.1 放置证书

```bash
mkdir -p deploy/certs
# 方式一：已有证书
cp /path/to/fullchain.pem deploy/certs/
cp /path/to/privkey.pem   deploy/certs/
```

**方式二：用 Let's Encrypt（certbot）签发。** 首次签发时先让 80 端口可达（可临时只启 80），
或用 `--webroot` 指向 `deploy/certbot-webroot`：

```bash
sudo certbot certonly --webroot -w "$PWD/deploy/certbot-webroot" -d travel.example.com
# 再把签发结果软链/复制到 deploy/certs/
```

证书与私钥属于敏感信息，**不得提交到仓库**（`.gitignore` 已忽略 `deploy/certs` 一类路径；新增前请确认）。

### 4.2 设置环境变量

```bash
export PUBLIC_ORIGIN="https://travel.example.com"     # 对外域名（必填）
export JWT_SECRET="$(openssl rand -base64 48)"        # ≥32 字节；切勿提交
# 仅在启用真实支付宝沙箱时设置，且值不要写进仓库：
# export ALIPAY_ENABLED=true
# export ALIPAY_APP_ID=...
# export ALIPAY_APP_PRIVATE_KEY=...
# export ALIPAY_PUBLIC_KEY=...
# export ALIPAY_NOTIFY_URL="https://travel.example.com/api/payments/alipay/notify"
```

`PUBLIC_ORIGIN` 会作为 `CORS_ALLOWED_ORIGINS` 注入后端；`ALIPAY_NOTIFY_URL` 必须与 `PUBLIC_ORIGIN` 同域。

### 4.3 启动

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.https.yml up -d --build
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.https.yml ps
```

- 首次启动时若数据卷为空，`sql/schema.sql`、`sql/test-data.sql` 会自动导入。
- **已有数据卷不会被重置**：`up`/`down` 都不会删除卷；**不要使用 `down -v`**（会清空数据库）。
- 存量库升级请先按 [sql/migrations/README.md](../sql/migrations/README.md) 执行尚未应用的迁移（当前最新为 `011-add-departure-reminder.sql`）。

### 4.4 查看日志

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.https.yml logs -f frontend backend
```

## 5. 部署验收

| 检查项 | 命令/操作 | 期望 |
|---|---|---|
| HTTP 跳转 | `curl -I http://travel.example.com` | `301` 且 `Location: https://…` |
| HTTPS 页面 | 浏览器打开 `https://travel.example.com` | 正常加载 SPA，`200` |
| 后端健康 | `curl https://travel.example.com/api/health` | `{"code":"OK",…}` |
| 接口连通 | `curl https://travel.example.com/api/routes` | 返回线路分页信封 |
| 地图底图 | 打开一条线路详情，切换「地图源」 | 天地图/OSM 瓦片正常加载；控制台无混合内容（Mixed Content）报错 |
| 支付宝回调 | `curl -X POST https://travel.example.com/api/payments/alipay/notify` | 可达并返回业务响应（未带签名的请求会被拒绝，属预期） |
| 证书链 | `openssl s_client -connect travel.example.com:443 -servername travel.example.com </dev/null` | 返回完整证书链 |

> 未配置 `VITE_TIANDITU_KEY` 时前端会提示切换到 OSM，这是预期行为；HTTPS 下 OSM 同样走 HTTPS。

## 6. 安全与合规要点

- **不提交密钥**：`JWT_SECRET`、支付宝私钥/公钥、`ALIPAY_CALLBACK_SECRET`、天地图 Key、TLS 私钥一律通过环境变量或运行时挂载注入。
- **回环限制**：`local-payment` 本地模拟支付 profile 会把后端绑定到回环地址，**不要**在公网部署中启用。
- **回调验签**：异步通知只认验签结果，浏览器跳转不作为支付成功依据（见 README「支付宝沙箱」）。
- **数据卷**：数据库数据保存在命名卷 `travel_agency_mysql_data` 中，升级只做增量迁移，不整库重建。

## 7. 停止与回滚

```bash
# 停止（保留数据卷）
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.https.yml down

# 回退到纯 HTTP 演示
docker compose -f deploy/docker-compose.yml down
docker compose -f deploy/docker-compose.yml up -d --build
```

## 8. 常见问题

| 现象 | 排查 |
|---|---|
| nginx 启动失败提示 `cannot load certificate` | 证书路径/文件名不对，或未挂载 `deploy/certs` 到 `/etc/nginx/certs` |
| 页面可开但接口 502 | `backend` 未就绪或崩溃，查 `logs backend`；确认 `JWT_SECRET` 已设置（缺失会启动即失败） |
| 浏览器报 Mixed Content | 地图 Key 或回调地址使用了 `http://`，改为 `https://` |
| 支付宝回调收不到 | 域名未公网可达，或 `ALIPAY_NOTIFY_URL` 与实际入口不一致；确认 `ALIPAY_APP_ID` 与 `ALIPAY_PUBLIC_KEY` 同时配置 |
| 升级后接口报 `Unknown column` | 存量库未执行最新迁移，按 `sql/migrations/README.md` 补齐 |
