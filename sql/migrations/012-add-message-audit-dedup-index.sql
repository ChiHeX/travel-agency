-- 012 为 sys_message 补「审核异常通知去重」索引
--
-- 背景：PRD §29 要求报名审核异常要有站内通知，而「同一订单 + 同一异常原因只通知一次」的判据是
-- 一次按 (user_id, type, title) 的精确匹配查询（OrderService.notifyAuditAnomalyOnce）。
-- sys_message 原先只有 idx_message_user_read (user_id, read_flag) —— 该索引能定位到用户，
-- 但 type 与 title 只能回表逐行比较。工作人员对有问题的订单会反复点确认，这条查询在单用户的
-- 消息量增长后仍是可避免的扫描。
--
-- 说明：这里只加普通索引，不加唯一键。去重是**业务判据**而不是库约束 —— 判重查询跑在
-- `confirm` 的订单行锁之内（SELECT ... FOR UPDATE 已把同一订单的确认串行化），不存在并发
-- 重复插入，不需要唯一键来当那道闸门；普通索引在这里只解决查询效率。
-- 唯一键在这个维度上也不成立：它必须包含 title，而 title 是「订单 {orderNo} 报名审核异常：
-- {原因短标签}」，不同订单、不同原因的标题本就不同，加了也不会互相冲突。
--
-- 面向存量库；全新库由 sql/schema.sql 直接建索引（CONTRIBUTING §12 要求两处同步）。
-- MySQL 没有 ADD INDEX IF NOT EXISTS，这里按 sql/migrations/README.md 的模板动态执行 DDL，
-- 保证脚本可重复执行。

SET @ddl := (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'sys_message'
        AND INDEX_NAME = 'idx_message_user_type_title'
    ),
    'SELECT ''index already exists'' AS skipped',
    'ALTER TABLE sys_message ADD INDEX idx_message_user_type_title (user_id, type, title)'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
