package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.travelagency.common.enums.RoleCode;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.GuideAccountRequest;
import com.travelagency.domain.dto.GuideUpdateRequest;
import com.travelagency.domain.dto.GuideView;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.SysRole;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.SysUserRole;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.SysRoleMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.SysUserRoleMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuideService {
    private final GuideMapper guides;
    private final SysUserMapper users;
    private final SysRoleMapper roles;
    private final SysUserRoleMapper userRoles;
    private final PasswordEncoder passwords;

    public GuideService(GuideMapper guides, SysUserMapper users, SysRoleMapper roles,
                        SysUserRoleMapper userRoles, PasswordEncoder passwords) {
        this.guides = guides;
        this.users = users;
        this.roles = roles;
        this.userRoles = userRoles;
        this.passwords = passwords;
    }

    @Transactional
    public GuideView create(GuideAccountRequest request) {
        if (users.selectCount(new QueryWrapper<SysUser>().eq("username", request.username())) > 0) {
            throw new BusinessException(409, "USERNAME_ALREADY_EXISTS", "账号已存在");
        }
        SysRole role = roles.selectOne(new QueryWrapper<SysRole>().eq("code", RoleCode.GUIDE));
        if (role == null) {
            throw new BusinessException(503, "SERVICE_UNAVAILABLE", "导游角色尚未初始化");
        }
        SysUser user = new SysUser();
        user.username = request.username();
        user.passwordHash = passwords.encode(request.password());
        user.nickname = request.name();
        user.realName = request.name();
        user.phone = request.phone();
        user.status = 1;
        user.deleted = 0;
        try {
            users.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(409, "USERNAME_ALREADY_EXISTS", "账号已存在");
        }
        SysUserRole relation = new SysUserRole();
        relation.userId = user.id;
        relation.roleId = role.id;
        userRoles.insert(relation);
        Guide guide = new Guide();
        guide.userId = user.id;
        guide.name = request.name();
        guide.phone = request.phone();
        guide.intro = request.intro();
        guide.status = "ACTIVE";
        guides.insert(guide);
        return view(guides.selectById(guide.id));
    }

    @Transactional
    public GuideView update(Long id, GuideUpdateRequest request) {
        requireLocked(id);
        guides.update(null, new UpdateWrapper<Guide>().eq("id", id)
                .set("name", request.name()).set("phone", request.phone()).set("intro", request.intro()));
        return view(guides.selectById(id));
    }

    @Transactional
    public GuideView updateStatus(Long id, String status) {
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new BusinessException(422, "VALIDATION_ERROR", "导游状态仅支持 ACTIVE 或 DISABLED");
        }
        Guide guide = requireLocked(id);
        if (guide.userId == null || users.selectById(guide.userId) == null) {
            throw new BusinessException(409, "GUIDE_ACCOUNT_CONFLICT", "导游尚未关联有效账号");
        }
        users.update(null, new UpdateWrapper<SysUser>().eq("id", guide.userId)
                .set("status", "ACTIVE".equals(status) ? 1 : 0));
        guides.update(null, new UpdateWrapper<Guide>().eq("id", id).set("status", status));
        return view(guides.selectById(id));
    }

    private Guide requireLocked(Long id) {
        Guide guide = guides.selectOne(new QueryWrapper<Guide>().eq("id", id).last("FOR UPDATE"));
        if (guide == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "导游不存在");
        }
        return guide;
    }

    private GuideView view(Guide guide) {
        SysUser user = guide.userId == null ? null : users.selectById(guide.userId);
        return GuideView.from(guide, user == null ? null : user.username);
    }
}
