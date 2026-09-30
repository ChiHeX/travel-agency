package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 导游资料修改请求，对齐契约 {@code GuideUpdateRequest}（{@code additionalProperties: false}）。
 *
 * <p>账号名与状态都不在这里：账号名是登录凭据、创建后不可改，状态走独立的
 * {@code PATCH /admin/guides/{guideId}/status}（仅 ADMIN）。多提交这些字段会被严格模式判 400。</p>
 *
 * <p><b>长度按 Unicode 码点计数</b>（{@link CodePointLength}），与 {@link GuideAccountRequest}
 * 同口径：契约的 {@code maxLength} 数的是字符，{@code @Size} 数的是 UTF-16 码元，
 * 后者会把契约允许的 emoji 姓名误判成超长。</p>
 */
public record GuideUpdateRequest(
        @NotBlank @CodePointLength(max = 64, message = "导游姓名最多 64 个字符") String name,

        @NotBlank @CodePointLength(min = 3, max = 20, message = "联系电话长度应为 3-20 个字符") String phone,

        @CodePointLength(max = 1000, message = "个人简介最多 1000 个字符") String intro) {
}
