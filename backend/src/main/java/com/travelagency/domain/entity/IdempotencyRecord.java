package com.travelagency.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.travelagency.common.model.BaseEntity;

/**
 * 幂等记录，对应契约要求的 Idempotency-Key 请求头。
 *
 * <p>唯一键 (user_id, scope, idem_key) 是并发场景下的原子闸门：同一用户在同一业务动作下
 * 携带同一个幂等键时，只有一个请求能插入成功并真正执行业务；其余请求会撞唯一键，
 * 从而读取首次执行产生的业务单号并返回同一结果，而不是重复下单、重复占名额。</p>
 *
 * <p>记录与业务数据在同一事务内提交：业务失败时幂等记录一并回滚，允许调用方安全重试。</p>
 */
@TableName("idempotency_record")
public class IdempotencyRecord extends BaseEntity {
    public Long userId;
    /** 业务动作标识，如 CREATE_ORDER / APPLY_REFUND。 */
    public String scope;
    public String idemKey;
    public String resourceType;
    /** 首次执行产生的业务单号，用于重放时返回同一结果。 */
    public String resourceNo;
}
