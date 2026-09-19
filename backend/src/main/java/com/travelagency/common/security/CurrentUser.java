package com.travelagency.common.security;

import com.travelagency.common.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static UserPrincipal required() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new BusinessException(401, "请先登录");
        }
        return principal;
    }

    /**
     * 可选身份：未登录（或匿名）时返回 {@code null}。
     *
     * <p>用于"公开可访问、但登录后要多返回个性化字段"的端点，例如 {@code GET /routes/{routeId}}
     * 的 {@code favorite}。这类端点走 {@code permitAll}，未带令牌也必须正常响应，
     * 因此不能使用会抛 401 的 {@link #required()}。</p>
     */
    public static UserPrincipal optional() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return null;
        }
        return principal;
    }

    public static boolean hasAnyRole(String... roles) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        for (String role : roles) {
            if (authentication.getAuthorities().stream().anyMatch(a ->
                    a.getAuthority().equals(role) || a.getAuthority().equals("ROLE_" + role))) {
                return true;
            }
        }
        return false;
    }
}
