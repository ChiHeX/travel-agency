# 宝塔已有 MySQL 5.7 的部署

适用于已安装宝塔 Nginx、MySQL 5.7.40 和 Java 21 或以上的小内存 Linux 服务器。
复用现有数据库，不启动仓库 Docker Compose 中的 MySQL，也不在服务器编译前后端。
这里只提供兼容部署路径，不改变 MySQL 5.7 已停止常规维护的事实。

## 1. 保留数据库并确认服务

在宝塔数据库页面启动已有 MySQL。若此前尝试卸载后无法启动，先检查宝塔任务日志和
数据库错误日志，恢复原服务后再部署；不要删除旧数据目录，也不要把新版本指向旧目录。
用项目账号查询 `SELECT VERSION()` 和 `SHOW TABLES`，确认连接的是原来的 `travel_agency`。
已有当前完整基线和演示数据的数据库不需要重新导入；旧版本库才执行尚未应用的增量迁移。

## 2. 在开发电脑打包

JDK 至少 21。在 `backend` 目录运行：

```bash
mvn -ntp -Pmysql57 clean package
```

`mysql57` 是 Maven 构建 profile，将 `mysql.version` 设为 `8.0.33`；运行时不需要开启
Spring 的同名 profile。默认构建仍使用 Spring Boot 管理的新版驱动。
该驱动版本对 MySQL 5.7 的支持见
[MySQL 官方发布说明](https://docs.oracle.com/cd/E17952_01/connector-j-relnotes-en/news-8-0-33.html)。

产物为 `backend/target/travel-agency-backend-0.0.1-SNAPSHOT.jar`。
前端在 `frontend` 目录执行 `npm ci` 和 `npm run build`，产物为 `frontend/dist/`。
打包前配置地图浏览器 Key；前端 API 基地址保持 `/api`。

## 3. 上传与配置后端

将后端 JAR 上传到 `/opt/travel-agency/app.jar`；前端只上传 `dist/` 内的内容到
宝塔新建站点目录，例如 `/www/wwwroot/travel-agency-site/`。站点根目录不要指向源码仓库，
不要上传数据库密码、JWT 密钥或后端本机配置到站点目录。

在宝塔 Java 项目管理或进程守护管理中配置：

- 工作目录：`/opt/travel-agency`
- Java：服务器上已安装的 Java 25（或 JDK 21 以上）的可执行文件绝对路径
- 程序：`/opt/travel-agency/app.jar`
- JVM 参数：`-Xms64m -Xmx256m -XX:+UseSerialGC`
- 启动参数：`--server.address=127.0.0.1 --server.port=18081`
- 开机启动和异常重启：启用

选择管理器时确认环境变量可配置并会随服务重启保留。通过管理器的环境变量配置以下值，
不要将密码直接写在命令行参数中：

```dotenv
DB_URL=jdbc:mysql://127.0.0.1:3306/travel_agency?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false
DB_USERNAME=travel
DB_PASSWORD=原数据库账号的密码
JWT_SECRET=至少32字节的随机密钥
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=4
SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=1
```

例如 `openssl rand -hex 32` 可生成 JWT 密钥，生成一次后保存，不要每次启动重新生成。
不要启用 `local-payment`；公网访问的服务不提供本机模拟支付。
256 MB 只是低负载起点，JVM 总内存还包括非堆内存；启动和实际操作时观察 `free -h`、
进程 RSS 与 Swap，若内存不足需减少其他负载或扩容，不能承诺固定内存可承载生产流量。

启动后在服务器验证：

```bash
curl -i http://127.0.0.1:18081/api/health
```

## 4. 宝塔站点配置

由宝塔 Nginx 提供前端，域名或 IP 绑定到站点，网站根目录指向上述前端目录。
在该站点的 `server` 块中配置以下 location；已有同名 location 时编辑原块，避免重复：

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:18081;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}

location / {
    try_files $uri $uri/ /index.html;
}
```

`proxy_pass` 后没有末尾 `/`，保留后端要求的 `/api` 前缀。
先在面板检查 Nginx 配置并保存，再在浏览器验证页面、页面刷新、登录、线路和订单接口。
域名的 HTTPS 证书由宝塔管理。阿里云安全组只需为站点开放 80/443，不开放 3306/18081。

## 5. 兼容修改的边界

- 出发提醒改用通用 `FOR UPDATE`，仍在发送前当前读复核订单和团期并锁定到事务结束。
  相比原来的 `FOR UPDATE OF o, d`，也会锁定关联线路，线路编辑可能等待提醒事务结束。
- 迁移 `006` 用临时表记录同订单内的出行人 ID 次序，再回填儿童类型，不使用窗口函数。
  对出行人极多的历史库，自连接统计会增加迁移时间；在备份后、停写期间执行。
- MySQL 5.7 会解析但不执行 `CHECK`。经应用接口写入时住宿规则由服务层验证，
  不应把手工 SQL 或外部写入当作同样受数据库约束保护。
- 初始化兼容并不等于服务器验收完成，部署后还需验证登录、预订、取消、退款和提醒。

## 6. 本次验证

2026-10-27，在本机独立 MySQL 5.7.44 / JDK 21 环境中完成：

- 全量 `schema.sql` 和 `test-data.sql` 初始化成功。
- `TRAVEL_MYSQL_TEST=true` 配合 `mvn -Pmysql57 verify`：603 项测试全部通过，0 跳过；
  包括预订、支付/退款规则和出发提醒的并发状态复核。
- `001` 至 `013` 全部迁移按顺序执行两遍，无 SQL 错误。
- `006` 额外夹具验证 ID 不连续、多个订单交错、零成人、成人数量超过快照数量、
  保留已有儿童类型和重复执行，预期结果一致。
- 前端 `npm run build` 成功；产物存在体积提示，不阻止构建。

服务器实际版本是 5.7.40，本次未连接该服务器，也未在其上运行验收。
