-- 005 为 travel_order.created_at 补索引
--
-- 背景：后台工作台 GET /admin/dashboard 的 todayOrderCount 与 orderTrend 都按 created_at
-- 过滤/分组（trend 用 GROUP BY DATE(created_at)），而 travel_order 此前只有 uk_order_no、
-- idx_order_user_status、idx_order_departure —— created_at 上没有索引，趋势查询只能全表扫描。
-- 工作台是高频页面，订单表增长后这条扫描会变成稳定的固定开销。
--
-- 面向存量库；全新库由 sql/schema.sql 直接建索引（CONTRIBUTING §12 要求两处同步）。
-- MySQL 没有 ADD INDEX IF NOT EXISTS，这里按 sql/migrations/README.md 的模板动态执行 DDL，
-- 保证脚本可重复执行。

SET @ddl := (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'travel_order'
        AND INDEX_NAME = 'idx_order_created_at'
    ),
    'SELECT ''index already exists'' AS skipped',
    'ALTER TABLE travel_order ADD INDEX idx_order_created_at (created_at)'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
