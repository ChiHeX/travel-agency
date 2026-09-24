package com.travelagency.domain.dto;

import com.travelagency.domain.entity.Review;

import java.time.LocalDateTime;

/**
 * 评价对外视图，对齐契约 Review。orderNo、userNickname 需联查补充。
 */
public record ReviewView(
        Long id,
        String orderNo,
        Long routeId,
        String userNickname,
        Integer rating,
        String content,
        String status,
        LocalDateTime createdAt) {

    public static ReviewView from(Review review, String orderNo, String userNickname) {
        return new ReviewView(review.id, orderNo, review.routeId, userNickname,
                review.rating, review.content, review.status, review.createdAt);
    }
}
