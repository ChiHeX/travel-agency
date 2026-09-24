package com.travelagency.auth.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 当前用户，对齐契约 User：
 * required [id, username, nickname, roles, status, createdAt]，
 * 头像字段在契约中名为 avatarUrl，additionalProperties=false 因此不得出现 avatar。
 */
public record UserView(
        Long id,
        String username,
        String nickname,
        String realName,
        String phone,
        String email,
        String avatarUrl,
        List<String> roles,
        String status,
        LocalDateTime createdAt) {
}
