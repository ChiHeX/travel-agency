package com.travelagency.domain.dto;

import com.travelagency.domain.entity.Consultation;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 咨询对外视图，对齐契约 Consultation（additionalProperties: false）。
 *
 * <p>契约把回复数组内嵌在咨询对象上（{@code replies}），因此这里拍平成一层，
 * 供前端按 item.title / item.replies 直接消费；此前后端返回的是
 * {@code {consultation:{...}, replies:[...]}} 的嵌套结构，与契约不符。</p>
 */
public record ConsultationView(
        Long id,
        Long userId,
        String userNickname,
        String title,
        String content,
        String status,
        List<ConsultationReplyView> replies,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static ConsultationView from(Consultation consultation, String userNickname,
                                        List<ConsultationReplyView> replies) {
        if (consultation == null) {
            return null;
        }
        return new ConsultationView(
                consultation.id,
                consultation.userId,
                userNickname,
                consultation.title,
                consultation.content,
                consultation.status,
                replies == null ? List.of() : replies,
                consultation.createdAt,
                consultation.updatedAt);
    }
}
