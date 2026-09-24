package com.travelagency.domain.dto;

import com.travelagency.domain.entity.ConsultationReply;

import java.time.LocalDateTime;

/**
 * 咨询回复对外视图，对齐契约 ConsultationReply（additionalProperties: false）。
 * 不直接序列化实体：实体带 consultationId 且没有契约要求的 staffName。
 */
public record ConsultationReplyView(
        Long id,
        Long staffId,
        String staffName,
        String content,
        LocalDateTime createdAt) {

    public static ConsultationReplyView from(ConsultationReply reply, String staffName) {
        if (reply == null) {
            return null;
        }
        return new ConsultationReplyView(reply.id, reply.staffId, staffName, reply.content, reply.createdAt);
    }
}
