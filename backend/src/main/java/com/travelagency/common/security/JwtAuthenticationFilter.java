package com.travelagency.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.travelagency.domain.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenProvider tokenProvider;
    private final SysUserMapper userMapper;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, SysUserMapper userMapper) {
        this.tokenProvider = tokenProvider;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            try {
                JwtTokenProvider.Claims claims = tokenProvider.parse(token);
                var user = userMapper.selectById(claims.userId());
                if (user == null || !Integer.valueOf(1).equals(user.status)
                        || Integer.valueOf(1).equals(user.deleted)) {
                    throw new IllegalArgumentException("Account is unavailable");
                }
                UserPrincipal principal = new UserPrincipal(claims.userId(), claims.username(), claims.roles(), true);
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (IllegalArgumentException ex) {
                log.debug("Ignored invalid bearer token: {}", ex.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }
}
