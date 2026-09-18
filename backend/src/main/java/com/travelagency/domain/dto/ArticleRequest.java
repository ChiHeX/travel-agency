package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 攻略新增/修改请求，对齐契约 ArticleUpsertRequest 的字段长度约束。
 */
public record ArticleRequest(
        @NotBlank(message = "攻略标题不能为空")
        @Size(min = 2, max = 200, message = "攻略标题长度应为 2-200 个字符") String title,
        @Size(max = 500, message = "攻略摘要不能超过 500 个字符") String summary,
        @NotBlank(message = "攻略内容不能为空") @Size(max = 100000) String content,
        @Size(max = 64) String city,
        @Size(max = 128) String destination,
        Long attractionId,
        @Size(max = 500) String coverUrl) {
}
