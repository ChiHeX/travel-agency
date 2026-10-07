# 部署说明（含 HTTPS 演示部署）

> 面向 `travel-agency` 的演示/生产部署。默认仓库只提供 HTTP 配置（`deploy/nginx.conf`），
> 本文补齐 **HTTPS 入口**，并给出前后端、地图底图与支付宝回调的可访问性验收步骤。
>
> 关键约束（务必遵守）：**不提交任何密钥/证书；不重置已有数据库或 Docker 数据卷。**
> 详见 [CONTRIBUTING §14](../CONTRIBUTING.md)。

## 1. 部署拓扑

```text
        浏览器
          │  https://<域名>            https://<域名>/api/…（同源）
          ▼
┌───────────────────────────────┐        ┌──────────────────────────┐
│ frontend 容器                  │  /api  │ backend 容器              │
│ nginx：SPA 静态资源 + 反向代理  │ ─────▶ │ Spring Boot REST API      │
│ 监听 80（跳转/ACME）/443（TLS） │        │ 监听 8080（仅 compose 内网）│
└───────────────────────────────┘        └──────────────────────────┘
                                                     │
                                                     ▼
                                          ┌──────────────────────────┐
                                          │ mysql 容器（数据卷持久化）│
                                          │ 不发布任何宿主机端口       │
                                          └──────────────────────────┘
```

- **对外暴露的端口只有 80 与 443**，且都由 `frontend` 提供：443 提供页面并反代 `/api` 到 `backend`。
- **数据库不发布端口**：`deploy/docker-compose.yml` 不为 `mysql` 声明 `ports`，HTTPS 叠加配置也不补任何数据库端口，
  数据库只存在于 compose 网络内部，`backend` 通过服务名 `mysql:3306` 访问。
  本地开发需要从宿主机连库时用 `deploy/docker-compose.db.yml`（只绑定 `127.0.0.1`）；
  整套环境里想连库就用 `docker compose exec mysql mysql ...`。
- **数据库口令由环境变量注入**：基础编排的默认值只是本地演示口令，HTTPS 叠加配置用 `:?` 强制要求
  `MYSQL_ROOT_PASSWORD` 与 `DB_PASSWORD`，缺失直接报错退出，不会带着仓库里的口令上线。
- **地图底图**由浏览器直接请求天地图/OSM（客户端侧），不走后端；HTTPS 下必须使用 HTTPS 瓦片地址，并让地图 Key 的域名白名单包含对外域名。
- **支付宝异步回调**由支付宝服务器直接请求后端公网地址，因此 `ALIPAY_NOTIFY_URL` 必须是公网可达的 HTTPS 完整地址。

## 2. 前置条件

| 项 | 要求 |
|---|---|
| 主机 | 已安装 Docker 与 Compose 插件；对外放行 80、443；**不需要**对外放行 3306 |
| 域名 | 已解析到主机公网 IP（A 记录） |
| 证书 | `fullchain.pem` + `privkey.pem`，覆盖对外域名（Let's Encrypt 或机构证书） |
| 密钥 | `JWT_SECRET`（≥32 字节随机串）、`MYSQL_ROOT_PASSWORD`、`DB_PASSWORD`；启用真实支付宝时另需沙箱/生产密钥 |
| 天地图 | 浏览器端 Key，域名白名单包含对外域名 |

## 3. 目录与文件

```text
deploy/
├─ docker-compose.yml              基础编排（mysql / backend / frontend，HTTP；数据库不发布端口）
├─ docker-compose.db.yml           仅数据库（本地开发，端口绑定 127.0.0.1）
├─ docker-compose.https.yml        HTTPS 叠加编排（本文使用）
├─ nginx.conf                      纯 HTTP 配置（含 ACME 挑战目录，首次签发用这一份）
├─ nginx.https.conf                HTTPS 配置（80 跳转 + 443 TLS，同样含 ACME 挑战目录）
├─ certs/                          ← 运行时挂载的证书目录（不要提交）
│  ├─ fullchain.pem
│  └─ privkey.pem
└─ certbot-webroot/                ← ACME 挑战文件目录（宿主机写入，容器只读挂载；不要提交）
```

## 4. 部署步骤

### 4.1 准备证书

**方式一：已有证书。**

```bash
mkdir -p deploy/certs
cp /path/to/fullchain.pem deploy/certs/
cp /path/to/privkey.pem   deploy/certs/
```

**方式二：用 Let's Encrypt（certbot）签发。** 推荐 `--webroot`，因为 80 端口由 `frontend` 容器占用，
webroot 不需要停服。`nginx.conf` 已经配置了 `/.well-known/acme-challenge/`，
compose 也把宿主机的 `deploy/certbot-webroot` 挂到了容器内的 `/var/www/certbot`：

```bash
# 1) 先把纯 HTTP 编排跑起来（80 端口与 ACME 目录就绪；首次启动同时初始化数据库）
mkdir -p deploy/certbot-webroot
docker compose -f deploy/docker-compose.yml up -d

# 2) 在宿主机签发（certbot 写 deploy/certbot-webroot，由容器原样提供）
sudo certbot certonly --webroot -w "$PWD/deploy/certbot-webroot" -d travel.example.com

# 3) 把签发结果【复制】到 deploy/certs/ —— 不要用软链接，原因见下
sudo install -m 644 /etc/letsencrypt/live/travel.example.com/fullchain.pem deploy/certs/fullchain.pem
sudo install -m 600 /etc/letsencrypt/live/travel.example.com/privkey.pem   deploy/certs/privkey.pem
```

如果希望用 `--standalone`（certbot 自己占用 80），必须先让出端口，否则会报 `Address already in use`：

```bash
docker compose -f deploy/docker-compose.yml stop frontend
sudo certbot certonly --standalone -d travel.example.com
docker compose -f deploy/docker-compose.yml start frontend
# 之后同样把 fullchain.pem / privkey.pem 复制到 deploy/certs/
```

> ⚠️ **必须复制，不能软链接。** 容器只挂载了 `deploy/certs`，
> 指向宿主机 `/etc/letsencrypt/live/...` 的软链接在容器内是断链，
> nginx 会以 `cannot load certificate ... No such file or directory` 启动失败。

**续期**：`deploy/certs/` 里是副本，certbot 续期只更新 `/etc/letsencrypt`，需要重新复制并让 nginx 生效：

```bash
sudo certbot renew --webroot -w "$PWD/deploy/certbot-webroot" \
  --deploy-hook "install -m 644 \$RENEWED_LINEAGE/fullchain.pem $PWD/deploy/certs/fullchain.pem && \
                 install -m 600 \$RENEWED_LINEAGE/privkey.pem   $PWD/deploy/certs/privkey.pem && \
                 docker compose -f $PWD/deploy/docker-compose.yml -f $PWD/deploy/docker-compose.https.yml \
                   exec -T frontend nginx -s reload"
```

证书与私钥属于敏感信息，**不得提交到仓库**（`.gitignore` 已忽略 `deploy/certs/` 与 `deploy/certbot-webroot/`）。

### 4.2 设置环境变量

```bash
export PUBLIC_ORIGIN="https://travel.example.com"          # 对外域名（必填）
export JWT_SECRET="$(openssl rand -base64 48)"             # ≥32 字节；切勿提交
export MYSQL_ROOT_PASSWORD="$(openssl rand -base64 24)"    # 数据库 root 口令（必填）
export DB_PASSWORD="$(openssl rand -base64 24)"            # 数据库应用账号 travel 的口令（必填）
# 仅在启用真实支付宝沙箱时设置，且值不要写进仓库：
# export ALIPAY_ENABLED=true
# export ALIPAY_APP_ID=...
# export ALIPAY_APP_PRIVATE_KEY=...
# export ALIPAY_PUBLIC_KEY=...
# export ALIPAY_NOTIFY_URL="https://travel.example.com/api/payments/alipay/notify"
```

- `PUBLIC_ORIGIN` 会作为 `CORS_ALLOWED_ORIGINS` 注入后端；`ALIPAY_NOTIFY_URL` 必须与 `PUBLIC_ORIGIN` 同域。
- `DB_PASSWORD` 同时用于 `mysql`（`MYSQL_PASSWORD`，即 `travel` 账号）与 `backend`（`DB_PASSWORD`），两者必须一致。
- **已有数据卷不会被改口令**：`MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD` 只在数据卷首次初始化时生效。
  对已经跑过的库，请把 `DB_PASSWORD` 设为**库内 `travel` 账号当前的密码**，
  否则 `backend` 会连不上；确实要换口令时先在库内改（`ALTER USER 'travel'@'%' IDENTIFIED BY '...'`）再改环境变量。

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
| **数据库未对外发布** | `docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.https.yml port mysql 3306` | **无输出**（端口未发布） |
| **宿主机未监听 3306** | `ss -ltn \| grep 3306` | 无输出 |
| 地图底图 | 打开一条线路详情，切换「地图源」 | 天地图/OSM 瓦片正常加载；控制台无混合内容（Mixed Content）报错 |
| 支付宝回调 | `curl -X POST https://travel.example.com/api/payments/alipay/notify` | 可达并返回业务响应（未带签名的请求会被拒绝，属预期） |
| 证书链 | `openssl s_client -connect travel.example.com:443 -servername travel.example.com </dev/null` | 返回完整证书链 |
| ACME 挑战可达（续期前） | `curl -I http://travel.example.com/.well-known/acme-challenge/ping` | `404`（落在 ACME 目录，而不是返回 SPA 的 `200 index.html`） |

> 未配置 `VITE_TIANDITU_KEY` 时前端会提示切换到 OSM，这是预期行为；HTTPS 下 OSM 同样走 HTTPS。

## 6. 安全与合规要点

- **不提交密钥**：`JWT_SECRET`、数据库口令、支付宝私钥/公钥、`ALIPAY_CALLBACK_SECRET`、天地图 Key、TLS 私钥一律通过环境变量或运行时挂载注入。
- **数据库不出网**：数据库不发布宿主机端口，口令由环境变量注入，HTTPS 叠加配置对缺失口令直接失败。
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
| `error while interpolating ... MYSQL_ROOT_PASSWORD` / `DB_PASSWORD` | 叠加配置要求显式设置这两个变量（见 §4.2）；这是刻意的，避免用演示口令上线 |
| nginx 启动失败提示 `cannot load certificate` | 证书路径/文件名不对，或未挂载 `deploy/certs`；若用的是软链接，改为复制真实文件 |
| certbot `--webroot` 校验失败（`Invalid response ... 200`） | 80 端口没跑 `frontend` 容器，或 `deploy/certbot-webroot` 没挂上；确认 `http://<域名>/.well-known/acme-challenge/<token>` 返回的是文件内容而不是 index.html |
| certbot `--standalone` 报 `Address already in use` | 80 被 `frontend` 容器占用，先 `stop frontend`（见 §4.1） |
| 页面可开但接口 502 | `backend` 未就绪或崩溃，查 `logs backend`；确认 `JWT_SECRET` 已设置（缺失会启动即失败） |
| backend 日志报 `Access denied for user 'travel'` | `DB_PASSWORD` 与库内 `travel` 账号的口令不一致；已有数据卷不会被环境变量改口令（见 §4.2） |
| 浏览器报 Mixed Content | 地图 Key 或回调地址使用了 `http://`，改为 `https://` |
| 支付宝回调收不到 | 域名未公网可达，或 `ALIPAY_NOTIFY_URL` 与实际入口不一致；确认 `ALIPAY_APP_ID` 与 `ALIPAY_PUBLIC_KEY` 同时配置 |
| 升级后接口报 `Unknown column` | 存量库未执行最新迁移，按 `sql/migrations/README.md` 补齐 |
