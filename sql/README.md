# SQL 使用与 MySQL 5.7 兼容

本目录所有 15 个 SQL 文件面向 MySQL 5.7.9 及以上版本（包含服务器上的 5.7.40），
也保留新版 MySQL 的执行路径。要求 InnoDB；`hotel.facilities` 使用 5.7.8 引入的原生 JSON。

## 全新数据库

用有建库/建表权限的账号按顺序执行：

```bash
mysql -u root -p < sql/schema.sql
mysql -u root -p < sql/test-data.sql
```

数据库名固定为 `travel_agency`。宝塔手动导入时也按此顺序。
`test-data.sql` 是演示种子，不是升级脚本；已有业务数据时不要重复导入，它会更新演示账号等数据，
部分没有自然唯一键的演示记录还可能重复插入。

## 已有数据库升级

先备份，在应用停写时按序执行尚未应用的 `migrations/*.sql`，具体见
[迁移说明](migrations/README.md)。不要靠重复导入 `schema.sql` 补列。
当前已按最新 `schema.sql` 初始化的数据库无需再执行迁移。

## 兼容处理

- 所有 SQL 显式设置连接为 `utf8mb4 / utf8mb4_unicode_ci`，避免宝塔导入工具的默认编码影响中文。
- 所有建表语句显式指定 `utf8mb4_unicode_ci` 和 `ROW_FORMAT=DYNAMIC`，避免继承新版默认排序规则，
  并配合 InnoDB 的大索引前缀。5.7 实例需开启 `innodb_large_prefix`、使用 Barracuda 文件格式，
  常规 16 KB InnoDB 页；这些是 MySQL 5.7.40 的常见默认设置，定制实例应先确认。
- `006` 不用窗口函数，以临时表和同订单内的 ID 顺序恢复出行人类型。
  执行账号需要 `CREATE TEMPORARY TABLES` 权限。
- `CHECK` 仅在 MySQL 8.0.16 及以上创建：基线用版本条件注释，`010` 按版本选择动态 DDL。
  MySQL 5.7 的住宿规则由应用层验证，直接手写 SQL 不受这项数据库约束保护。
- 兼容 SQL 不会自动切换 Java 数据库驱动，5.7 部署还要使用后端 Maven 的 `-Pmysql57` 构建。

## 可重复运行的验证

准备一个本机独立测试 MySQL，用有建库、建表、临时表和删除测试库权限的账号运行：

```bash
python sql/verify_mysql_compat.py --host 127.0.0.1 --port 13577 --user root
```

若需密码，使用客户端选项文件或 `MYSQL_PWD` 环境变量，不把密码写进脚本/命令参数。
可用 `--mysql` 指定客户端绝对路径。仅使用本机临时实例，不对生产库运行。
验证器会创建随机命名的测试库，并在正常/异常退出时清理；不会选择或清空现有 `travel_agency`。
它逐个用标准输入执行 SQL（遇错立即失败），验证全部文件、中文、JSON、重复建表、迁移重复执行，
以及旧版缺列结构的 `006` / `010` 升级和各版本的 CHECK 行为。

2026-10-27 已在本机隔离的 MySQL 5.7.44 与 9.7.1 上通过以上验证，覆盖全部 15 个 SQL 文件。
未连接或修改服务器上的 MySQL 5.7.40；服务器部署后仍需实际验收。
