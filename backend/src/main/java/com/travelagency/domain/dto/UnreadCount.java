package com.travelagency.domain.dto;

/**
 * 未读消息数，对齐契约 UnreadCountEnvelope.data（{ count: integer, minimum: 0 }）。
 *
 * <p>此处刻意使用原始类型 {@code int} 而不是 {@code Long}：全局 JSON 配置会把包装类型
 * {@code Long} 序列化成字符串（用于避免前端 Long 主键精度丢失），而计数类字段在契约中
 * 明确要求是 JSON 整数，因此必须避开该规则。</p>
 */
public record UnreadCount(int count) {
}
