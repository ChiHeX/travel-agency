package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.entity.Consultation;
import com.travelagency.domain.entity.ConsultationReply;
import com.travelagency.domain.mapper.ConsultationMapper;
import com.travelagency.domain.mapper.ConsultationReplyMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsultationServiceTest {
    private final ConsultationMapper consultations = mock(ConsultationMapper.class);
    private final ConsultationReplyMapper replies = mock(ConsultationReplyMapper.class);
    private final ConsultationService service = new ConsultationService(consultations, replies);

    @Test
    void refusesMissingOrUnownedConsultation() {
        var error = assertThrows(BusinessException.class, () -> service.deleteClosed(1L, 2L));
        assertEquals(404, error.getStatus());
        verifyNoInteractions(replies);
        verify(consultations, never()).deleteById(anyLong());
    }

    @Test
    void refusesOpenConsultation() {
        Consultation consultation = new Consultation();
        consultation.status = "REPLIED";
        when(consultations.selectOne(any(Wrapper.class))).thenReturn(consultation);
        var error = assertThrows(BusinessException.class, () -> service.deleteClosed(1L, 2L));
        assertEquals("CONSULTATION_STATE_CONFLICT", error.getCode());
        verifyNoInteractions(replies);
        verify(consultations, never()).deleteById(anyLong());
    }

    @Test
    void deletesRepliesBeforeClosedConsultation() {
        Consultation consultation = new Consultation();
        consultation.status = "CLOSED";
        when(consultations.selectOne(any(Wrapper.class))).thenReturn(consultation);
        service.deleteClosed(1L, 2L);
        var ordered = inOrder(replies, consultations);
        ordered.verify(replies).delete(any(Wrapper.class));
        ordered.verify(consultations).deleteById(1L);
    }
}
