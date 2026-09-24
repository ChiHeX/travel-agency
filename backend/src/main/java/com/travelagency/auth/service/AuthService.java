package com.travelagency.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.travelagency.auth.dto.PasswordChangeRequest;
import com.travelagency.auth.dto.ProfileRequest;
import org.springframework.dao.DuplicateKeyException;
import com.travelagency.auth.dto.AuthResponse;
import com.travelagency.auth.dto.LoginRequest;
import com.travelagency.auth.dto.RegisterRequest;
import com.travelagency.auth.dto.UserView;
import com.travelagency.common.enums.AccountStatus;
import com.travelagency.common.enums.RoleCode;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.common.security.UserPrincipal;
import com.travelagency.domain.entity.SysRole;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.SysUserRole;
import com.travelagency.domain.mapper.SysRoleMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.SysUserRoleMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    public AuthService(
            SysUserMapper userMapper,
            SysRoleMapper roleMapper,
            SysUserRoleMapper userRoleMapper,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider tokenProvider) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        SysUser existing = userMapper.selectOne(new QueryWrapper<SysUser>()
                .eq("username", request.username()).eq("deleted", 0));
        if (existing != null) {
            throw new BusinessException(409, "RESOURCE_CONFLICT", "用户名已存在");
        }
        SysUser user = new SysUser();
        user.username = request.username().trim();
        user.passwordHash = passwordEncoder.encode(request.password());
        user.nickname = request.nickname().trim();
        user.phone = request.phone();
        user.email = request.email();
        user.status = 1;
        user.deleted = 0;
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(409, "RESOURCE_CONFLICT", "用户名已存在");
        }

        SysRole userRole = roleMapper.selectOne(new QueryWrapper<SysRole>().eq("code", RoleCode.USER));
        if (userRole != null) {
            SysUserRole relation = new SysUserRole();
            relation.userId = user.id;
            relation.roleId = userRole.id;
            userRoleMapper.insert(relation);
        }
        // 回查以带回 created_at / updated_at：这两个字段由数据库默认值生成，
        // insert 后的内存对象里仍是 null，直接返回会让契约 AuthSession.user 的 createdAt 缺失。
        SysUser saved = userMapper.selectById(user.id);
        return issueToken(saved == null ? user : saved);
    }

    public AuthResponse login(LoginRequest request) {
        SysUser user = userMapper.selectOne(new QueryWrapper<SysUser>()
                .eq("username", request.username()).eq("deleted", 0));
        if (user == null || user.status == null || user.status != 1
                || !passwordEncoder.matches(request.password(), user.passwordHash)) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        return issueToken(user);
    }

    public UserView view(UserPrincipal principal) {
        return toView(requireActiveUser(principal.userId()), rolesFor(principal.userId()));
    }

    /**
     * 修改当前用户密码，对齐契约 PUT /account/password（成功即 204，无响应体）。
     *
     * <p>「原密码错误」刻意用 422 而不是 401：前端 axios 响应拦截器把任何 401 都当作
     * 登录态失效并派发 {@code travel-auth-expired} 事件（frontend/src/api/request.js），
     * 若这里回 401，用户只是打错一次原密码就会被强制登出，SecurityView 上的错误提示
     * 永远看不到。契约对 401 的定义也是「未登录、Token 缺失、无效或过期」，与本场景无关；
     * 422 的定义是「请求格式正确，但违反业务规则或字段语义校验失败」，正好对应。</p>
     *
     * <p>「新密码与原密码相同」同理判 422：SecurityView 已在提交前拦一次，
     * 后端仍要判，因为它不是唯一调用方。</p>
     */
    @Transactional
    public void changePassword(UserPrincipal principal, PasswordChangeRequest request) {
        SysUser user = requireActiveUser(principal.userId());
        if (!passwordEncoder.matches(request.currentPassword(), user.passwordHash)) {
            throw new BusinessException(422, "VALIDATION_ERROR", "原密码不正确");
        }
        if (request.newPassword().equals(request.currentPassword())) {
            throw new BusinessException(422, "VALIDATION_ERROR", "新密码不能与原密码相同");
        }
        userMapper.update(null, new UpdateWrapper<SysUser>().eq("id", user.id)
                .set("password_hash", passwordEncoder.encode(request.newPassword())));
    }

    /**
     * 取「存在且启用」的账号，否则按契约以 401 拒绝。
     * 与 {@code JwtAuthenticationFilter} 的口径保持一致：已停用或已删除的账号不视为有效身份。
     */
    private SysUser requireActiveUser(Long userId) {
        SysUser user = userId == null ? null : userMapper.selectById(userId);
        if (user == null || user.status == null || user.status != 1
                || (user.deleted != null && user.deleted == 1)) {
            throw new BusinessException(401, "账号不存在或已停用");
        }
        return user;
    }

    @Transactional
    public UserView updateProfile(UserPrincipal principal, ProfileRequest request) {
        view(principal);
        // PUT replaces all editable fields, including explicit SQL NULL values.
        userMapper.update(null, new UpdateWrapper<SysUser>()
                .eq("id", principal.userId())
                .set("nickname", request.nickname())
                .set("phone", request.phone())
                .set("email", request.email())
                .set("real_name", request.realName())
                .set("avatar", request.avatarUrl()));
        return view(principal);
    }

    public Set<String> rolesFor(Long userId) {
        List<SysUserRole> links = userRoleMapper.selectList(new QueryWrapper<SysUserRole>().eq("user_id", userId));
        Set<String> roles = new LinkedHashSet<>();
        for (SysUserRole link : links) {
            SysRole role = roleMapper.selectById(link.roleId);
            if (role != null) {
                roles.add(role.code);
            }
        }
        if (roles.isEmpty()) {
            roles.add(RoleCode.USER);
        }
        return roles;
    }

    /**
     * 批量取角色，供后台用户列表使用：逐行调用 {@link #rolesFor(Long)} 会退化成 N+1。
     *
     * <p>与 {@code rolesFor} 同口径：没有分配任何角色的账号回落到 {@code USER}。
     * 传入的每个 id 都会出现在返回的 map 里，调用方不必再判空。</p>
     */
    public Map<Long, Set<String>> rolesOfAll(Collection<Long> userIds) {
        List<Long> ids = userIds == null ? List.of()
                : userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<String>> result = new LinkedHashMap<>();
        for (Long id : ids) {
            result.put(id, new LinkedHashSet<>());
        }
        List<SysUserRole> links = userRoleMapper.selectList(new QueryWrapper<SysUserRole>().in("user_id", ids));
        Set<Long> roleIds = links.stream().map(link -> link.roleId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, String> codes = roleIds.isEmpty() ? Map.of()
                : roleMapper.selectByIds(roleIds).stream()
                        .collect(Collectors.toMap(role -> role.id, role -> role.code, (a, b) -> a));
        for (SysUserRole link : links) {
            Set<String> roles = result.get(link.userId);
            String code = codes.get(link.roleId);
            if (roles != null && code != null) {
                roles.add(code);
            }
        }
        for (Map.Entry<Long, Set<String>> entry : result.entrySet()) {
            if (entry.getValue().isEmpty()) {
                entry.getValue().add(RoleCode.USER);
            }
        }
        return result;
    }

    public UserView toView(SysUser user, Set<String> roles) {
        return new UserView(user.id, user.username, user.nickname, user.realName,
                user.phone, user.email, user.avatar, roles.stream().toList(),
                AccountStatus.of(user.status), user.createdAt);
    }

    private AuthResponse issueToken(SysUser user) {
        Set<String> roles = rolesFor(user.id);
        String token = tokenProvider.createToken(user.id, user.username, roles);
        return new AuthResponse(token, "Bearer", tokenProvider.expireSeconds(), toView(user, roles));
    }
}
