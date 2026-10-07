package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 上报「报名审核异常」的请求体，对齐契约 OrderReviewExceptionRequest。
 *
 * <p>PRD §13 规定审核出现异常时"由工作人员联系用户处理"，§29 把「订单审核异常」列为通知场景。
 * 工作人员用这段说明告诉用户订单卡在哪里、需要补充什么，内容会原样进入站内消息，
 * 因此不能为空：空说明等于把用户晾在"待确认"上却不说原因。</p>
 *
 * <p>长度按 Unicode 码点计数（{@link CodePointLength}），与契约 {@code maxLength} 的口径一致。</p>
 */
public record OrderReviewExceptionRequest(
        @NotBlank(message = "请填写需要用户处理或补充的内容")
        @CodePointLength(max = 500, message = "审核说明不能超过 500 字") String reason) {
}
