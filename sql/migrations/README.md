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
| `003-add-kunming-demo-waypoint.sql` | 为已有的彩云之南演示线路补充昆明抵达示意点，避免地图只显示大理至丽江 |
| `004-add-single-location-demo-route.sql` | 新增只有大理古城一个地图地点的演示线路和测试团期，用于检查单点地图展示 |
| `005-add-order-created-at-index.sql` | 为 `travel_order.created_at` 补索引，支撑后台工作台的今日订单数与订单趋势查询 |
| `006-add-order-traveler-type.sql` | 补齐存量库的出行人类型快照列，恢复历史儿童类型，修复下单时的 500 错误 |
| `007-add-place-guides.sql` | 新增多地点指南及景点关联表 |
| `008-add-hotel-version.sql` | 补齐存量库的 `hotel.version` 乐观锁版本号列。**升级应用前必须先执行**：`Hotel` 实体的列清单包含该列，未执行时酒店列表、详情、创建、修改全部报 `Unknown column 'version' in 'field list'`（已实测复现 —— 不只是修改接口受影响）。执行两次安全（幂等） |
| `009-harden-coordinate-data.sql` | 地图坐标数据加固：把"半截坐标"（只填经度或只填纬度）统一清成 NULL —— 这类坐标在用户端地图上无法落点、会被静默丢弃，保留只会让运营以为已经录入。只清"半截"的行，成对坐标与两列都为空的行不受影响。**不写入任何坐标值**：演示酒店的坐标只定义在种子数据 `test-data.sql` 里，而它对已存在的同名酒店整条跳过（`WHERE NOT EXISTS`），重复导入补不了存量酒店的坐标 —— 存量酒店请在后台「酒店合作资料」页补录（早先按名称补演示酒店坐标的写法已删除：`hotel.name` 没有唯一约束，同名多条时重跑会逐条补下去，既不幂等也不保证补的是演示线路引用的那家）。执行两次安全（幂等） |
| `010-add-hotel-accommodation.sql` | 酒店展示资料与每日行程的住宿安排：`hotel` 补 `city` / `cover_url` / `star_rating` / `facilities` / `check_in_time` / `check_out_time` 与 `idx_hotel_city`，新建 `hotel_image` 表，`route_itinerary_day` 补 `accommodation_type` / `accommodation_standard` / `room_type` / `breakfast_included` / `accommodation_note` 与 `idx_day_hotel`。**升级应用前必须先执行**：`Hotel` 与 `RouteItineraryDay` 实体的列清单已包含这些新列，未执行时酒店与每日行程接口会报 `Unknown column`。回填只做一件事：`hotel_id` 非空的当天行程设为 `HOTEL`，未关联酒店的保持默认 `PENDING` —— **不会回填成 `NONE`**（"缺少酒店关联"不等于"当天不含住宿"），也不改动任何行程内容。最后再加约束 `ck_day_accommodation`（住宿类型必须与酒店关联/住宿标准自洽；在回填之后添加，因此存量行不会被拦下）—— 它挡的是绕过服务层的写入，因为 `accommodation_type` 有默认值，只写 `hotel_id` 而不写类型会静默产出自相矛盾的一行；MySQL 8.0.16 之前只解析 CHECK 而不执行，此时退化为服务层保证。存量酒店的 `city` 补成空串（表示尚未录入城市），不按名称猜城市，请在后台补录。执行两次安全（幂等） |

中文 SQL 文件须以 UTF-8 保存并原样传给客户端。`test-data.sql` 显式使用 `SET NAMES utf8mb4`，避免客户端默认字符集将 UTF-8 字节误当成 latin1；它不能修复在文件传输之前已被错误转码的文本。
存量乱码使用 `002` 定向修复，不要重新导入整套演示数据。脚本不会改动订单、游客快照、账号或正常中文；第二次执行不会再次转换已修复内容。
