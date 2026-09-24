package com.travelagency.auth.dto;

/**
 * 登录/注册会话，对齐契约 AuthSession：
 * required [accessToken, tokenType, expiresIn, user]，additionalProperties=false。
 */
public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserView user) {
}
