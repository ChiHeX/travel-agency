package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

public record ConsultationRequest(
        @NotBlank(message = "问题标题不能为空") @CodePointLength(max = 100, message = "问题标题不能超过 100 字") String title,
        @NotBlank(message = "问题内容不能为空") @CodePointLength(max = 2000, message = "问题内容不能超过 2000 字") String content) {
}
