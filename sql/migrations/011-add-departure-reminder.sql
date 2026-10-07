-- 011 新增 departure_reminder 表
--
-- 背景：PRD §29 要求实现"即将出发"站内消息提醒。提醒由定时任务按订单发送，
-- 必须满足"任务重复执行不重复发消息"——否则每一次调度、每一个部署实例都可能给同一位游客重复推送。
-- 用一张发送记录表承载幂等：
--   唯一键 (order_id, remind_type) 是原子闸门：同一订单的同一类提醒只有一条记录能插入成功，
--   只有插入成功的那一次才继续写入 sys_message；重复执行读到唯一键冲突后直接跳过。
--
-- 面向存量库；全新库由 sql/schema.sql 直接建表，本脚本用 IF NOT EXISTS 保证两边都安全。
-- 可重复执行（第二次执行因对象已存在而跳过，不会中断构建流程）。

CREATE TABLE IF NOT EXISTS departure_reminder (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    remind_type VARCHAR(32) NOT NULL,
    sent_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_departure_reminder_order_type (order_id, remind_type),
    CONSTRAINT fk_departure_reminder_order FOREIGN KEY (order_id) REFERENCES travel_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
