package com.travelagency.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
            throw new BusinessException("用户名已存在");
        }
        SysUser user = new SysUser();
        user.username = request.username().trim();
        user.passwordHash = passwordEncoder.encode(request.password());
        user.nickname = request.nickname().trim();
        user.phone = request.phone();
        user.email = request.email();
        user.status = 1;
        user.deleted = 0;
        userMapper.insert(user);

        SysRole userRole = roleMapper.selectOne(new QueryWrapper<SysRole>().eq("code", RoleCode.USER));
        if (userRole != null) {
            SysUserRole relation = new SysUserRole();
            relation.userId = user.id;
            relation.roleId = userRole.id;
            userRoleMapper.insert(relation);
        }
        return issueToken(user);
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
        SysUser user = userMapper.selectById(principal.userId());
        if (user == null || user.deleted != null && user.deleted == 1
                || user.status == null || user.status != 1) {
            throw new BusinessException(401, "账号不存在或已停用");
        }
        return toView(user, rolesFor(user.id));
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
