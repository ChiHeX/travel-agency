package com.travelagency.web.controller;

import com.travelagency.auth.dto.ProfileRequest;
import com.travelagency.auth.dto.UserView;
import com.travelagency.auth.service.AuthService;
import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.mapper.SysUserMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final SysUserMapper userMapper;
    private final AuthService authService;

    public AccountController(SysUserMapper userMapper, AuthService authService) {
        this.userMapper = userMapper;
        this.authService = authService;
    }

    @GetMapping("/profile")
    public ApiResponse<UserView> profile() {
        return ApiResponse.ok(authService.view(CurrentUser.required()));
    }

    @PutMapping("/profile")
    public ApiResponse<UserView> update(@Valid @RequestBody ProfileRequest request) {
        SysUser user = userMapper.selectById(CurrentUser.required().userId());
        if (user == null) {
            throw new BusinessException(404, "账号不存在");
        }
        user.nickname = request.nickname();
        user.phone = request.phone();
        user.email = request.email();
        user.realName = request.realName();
        user.avatar = request.avatarUrl();
        userMapper.updateById(user);
        return ApiResponse.ok(authService.view(CurrentUser.required()));
    }
}
