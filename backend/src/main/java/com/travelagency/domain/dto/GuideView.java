package com.travelagency.domain.dto;

import com.travelagency.domain.entity.Guide;

import java.time.LocalDateTime;

/**
 * 导游对外视图，对齐契约 Guide（additionalProperties: false）。
 *
 * <p>契约把 {@code username} 列为 required，而持久化实体 {@code Guide} 只有 {@code userId}
 * 关联 {@code sys_user}，自身不含账号名。直出实体会让响应缺少 required 字段，
 * 因此这里拍平映射，由调用方批量查回账号名避免 N+1。</p>
 */
public record GuideView(
        Long id,
        Long userId,
        String username,
        String name,
        String phone,
        String intro,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static GuideView from(Guide guide, String username) {
        if (guide == null) {
            return null;
        }
        return new GuideView(guide.id, guide.userId, username, guide.name, guide.phone,
                guide.intro, guide.status, guide.createdAt, guide.updatedAt);
    }
}
