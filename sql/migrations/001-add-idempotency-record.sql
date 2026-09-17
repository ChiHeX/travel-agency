-- 001 新增 idempotency_record 表
--
-- 背景：契约把 Idempotency-Key 定为 POST /orders 与 POST /orders/{orderNo}/refunds 的必填请求头，
-- 服务端需要一张表来记录「哪个用户、在哪个业务动作下、用过哪个幂等键、产生了哪个业务单号」。
-- 唯一键 (user_id, scope, idem_key) 是并发下的原子闸门：重复提交只有一个请求能插入成功。
--
-- 面向存量库；全新库由 sql/schema.sql 直接建表，本脚本用 IF NOT EXISTS 保证两边都安全。
-- 可重复执行。

CREATE TABLE IF NOT EXISTS idempotency_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    scope VARCHAR(64) NOT NULL,
    idem_key VARCHAR(128) NOT NULL,
    resource_type VARCHAR(32),
    resource_no VARCHAR(64),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_idempotency_user_scope_key (user_id, scope, idem_key),
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
