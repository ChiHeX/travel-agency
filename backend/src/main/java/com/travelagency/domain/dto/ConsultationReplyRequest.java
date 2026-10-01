package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

public record ConsultationReplyRequest(
        @NotBlank(message = "回复内容不能为空") @CodePointLength(max = 2000, message = "回复内容不能超过 2000 字") String content) {
}
