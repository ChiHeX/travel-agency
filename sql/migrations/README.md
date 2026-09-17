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
