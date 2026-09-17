package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 攻略新增/修改请求，对齐契约 ArticleUpsertRequest 的字段长度约束。
 *
 * <p>{@code status} 额外加了枚举校验：此前任意字符串都能写进 status，而
 * {@code PATCH /admin/articles/{id}/status} 反而做了校验，两侧行为不一致；
 * 一旦写入非法状态，攻略会同时从公开列表和后台筛选里消失。为空时由服务端按 DRAFT 处理。</p>
 */
public record ArticleRequest(
        @NotBlank(message = "攻略标题不能为空")
        @Size(max = 200, message = "攻略标题不能超过 200 个字符") String title,
        @Size(max = 500, message = "攻略摘要不能超过 500 个字符") String summary,
        @NotBlank(message = "攻略内容不能为空") String content,
        @Size(max = 64) String city,
        @Size(max = 128) String destination,
        Long attractionId,
        @Size(max = 500) String coverUrl,
        @Pattern(regexp = "DRAFT|PUBLISHED|OFFLINE", message = "攻略状态仅支持 DRAFT、PUBLISHED 或 OFFLINE")
        String status) {
}
