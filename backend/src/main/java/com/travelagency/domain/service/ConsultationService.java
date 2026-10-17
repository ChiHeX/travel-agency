package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.entity.Consultation;
import com.travelagency.domain.entity.ConsultationReply;
import com.travelagency.domain.mapper.ConsultationMapper;
import com.travelagency.domain.mapper.ConsultationReplyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsultationService {
    private final ConsultationMapper consultations;
    private final ConsultationReplyMapper replies;

    public ConsultationService(ConsultationMapper consultations, ConsultationReplyMapper replies) {
        this.consultations = consultations;
        this.replies = replies;
    }

    @Transactional
    public void deleteClosed(Long id, Long userId) {
        Consultation consultation = consultations.selectOne(new QueryWrapper<Consultation>()
                .eq("id", id).eq("user_id", userId).last("FOR UPDATE"));
        if (consultation == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "咨询不存在");
        }
        if (!"CLOSED".equals(consultation.status)) {
            throw new BusinessException(409, "CONSULTATION_STATE_CONFLICT", "只能删除已关闭的咨询");
        }
        replies.delete(new QueryWrapper<ConsultationReply>().eq("consultation_id", id));
        consultations.deleteById(id);
    }
}
