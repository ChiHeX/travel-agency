# 数据库迁移脚本

`sql/schema.sql` 是**全量基线**，面向全新数据库。它通篇使用 `CREATE TABLE IF NOT EXISTS`，
因此对一个**已经存在**的库重复执行**不会**补上后来新增的列或索引，也**不会报错**提醒你 ——
升级环境会静默停留在旧结构上，直到某个接口因为「Unknown column」而失败。

存量库（已经跑过早期 `schema.sql` 的开发库、演示库）的结构变更请放到本目录，
按 `NNN-描述.sql` 命名、按序号执行，并同步把变更补回 `sql/schema.sql`（CONTRIBUTING §12）。

## 执行方式

```bash
mysql -h localhost -u travel -p travel_agency < sql/migrations/001-add-idempotency-record.sql
```

## 约定

- 每个脚本必须**可重复执行**：第二次执行不能因为「对象已存在」而中断构建流程。
- MySQL 没有 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（那是 MariaDB 的扩展），
  补列请用下面的模板，通过 `information_schema` 判断后再动态执行 DDL。

## 补列模板（幂等）

```sql
SET @ddl := (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'travel_order'
        AND COLUMN_NAME = 'some_new_column'
    ),
    'SELECT ''column already exists'' AS skipped',
    'ALTER TABLE travel_order ADD COLUMN some_new_column VARCHAR(32) NULL'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
```

## 变更记录

| 脚本 | 说明 |
|---|---|
| `001-add-idempotency-record.sql` | 新增 `idempotency_record` 表，支撑下单与退款的 `Idempotency-Key` 幂等 |
| `002-repair-demo-text-encoding.sql` | 仅修复与原始演示文字精确匹配的乱码，保留线路、景点、酒店和行程的主键及关联；执行前备份这些内容表 |

中文 SQL 文件须以 UTF-8 保存并原样传给客户端。`test-data.sql` 显式使用 `SET NAMES utf8mb4`，避免客户端默认字符集将 UTF-8 字节误当成 latin1；它不能修复在文件传输之前已被错误转码的文本。
存量乱码使用 `002` 定向修复，不要重新导入整套演示数据。脚本不会改动订单、游客快照、账号或正常中文；第二次执行不会再次转换已修复内容。
