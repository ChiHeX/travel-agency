package com.travelagency.domain.dto;

import com.travelagency.domain.entity.Message;

import java.time.LocalDateTime;

/**
 * 站内消息对外视图，对齐契约 Message（additionalProperties: false）。
 *
 * <p>不直接序列化实体：实体字段是 readFlag(0/1) 且含有 userId，
 * 而契约要求布尔字段 read、并且不包含 userId / updatedAt。</p>
 */
public record MessageView(
        Long id,
        String type,
        String title,
        String content,
        boolean read,
        LocalDateTime readAt,
        LocalDateTime createdAt) {

    public static MessageView from(Message message) {
        if (message == null) {
            return null;
        }
        return new MessageView(
                message.id,
                message.type,
                message.title,
                message.content,
                Integer.valueOf(1).equals(message.readFlag),
                message.readAt,
                message.createdAt);
    }
}
