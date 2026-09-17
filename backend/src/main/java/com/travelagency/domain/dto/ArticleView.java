package com.travelagency.domain.dto;

import com.travelagency.domain.entity.TravelGuideArticle;

import java.time.LocalDateTime;

/**
 * 攻略对外视图，对齐契约 Article（additionalProperties: false）。
 *
 * <p>契约要求 {@code authorName}（编辑姓名）与 {@code publishedAt} 等字段，
 * 而持久化实体只有 {@code authorId} 且不含作者名，因此这里做一次拍平映射。</p>
 */
public record ArticleView(
        Long id,
        String title,
        String summary,
        String content,
        String city,
        String destination,
        Long attractionId,
        String coverUrl,
        String status,
        Long authorId,
        String authorName,
        LocalDateTime publishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static ArticleView from(TravelGuideArticle article, String authorName) {
        if (article == null) {
            return null;
        }
        return new ArticleView(
                article.id,
                article.title,
                article.summary,
                article.content,
                article.city,
                article.destination,
                article.attractionId,
                article.coverUrl,
                article.status,
                article.authorId,
                authorName,
                article.publishedAt,
                article.createdAt,
                article.updatedAt);
    }
}
