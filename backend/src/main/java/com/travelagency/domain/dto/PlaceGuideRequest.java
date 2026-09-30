package com.travelagency.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.CodePointLength;
import java.util.List;

/**
 * 地点指南新增/修改请求，对齐契约 {@code PlaceGuideUpsertRequest}。
 *
 * <p><b>文本长度按 Unicode 码点计数</b>（{@link CodePointLength}）：契约的 {@code maxLength}
 * 是 JSON Schema 口径（数的是字符/码点），{@code @Size} 数的是 UTF-16 码元。
 * 注意 {@code places} 是<b>列表</b>，它的 2–50 是元素个数，必须继续用 {@code @Size}。</p>
 */
public record PlaceGuideRequest(
        @NotBlank(message = "标题不能为空")
        @CodePointLength(min = 2, max = 200, message = "标题长度应为 2-200 个字符") String title,
        @CodePointLength(max = 500, message = "摘要不能超过 500 个字符") String summary,
        @NotBlank(message = "所属城市不能为空")
        @CodePointLength(max = 64, message = "所属城市不能超过 64 个字符") String city,
        @CodePointLength(max = 128, message = "目的地不能超过 128 个字符") String destination,
        @CodePointLength(max = 500, message = "封面地址不能超过 500 个字符") String coverUrl,
        @NotEmpty(message = "至少需要一个地点")
        @Size(min = 2, max = 50, message = "地点数量应为 2-50 个") List<@Valid Place> places) {
    public record Place(
            @NotNull(message = "景点不能为空") Long attractionId,
            @CodePointLength(max = 500, message = "地点说明不能超过 500 个字符") String note) {
    }
}
