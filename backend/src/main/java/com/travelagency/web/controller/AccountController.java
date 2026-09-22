package com.travelagency.web.controller;

import com.travelagency.auth.dto.PasswordChangeRequest;
import com.travelagency.auth.dto.ProfileRequest;
import com.travelagency.auth.dto.UserView;
import com.travelagency.auth.service.AuthService;
import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AuthService authService;

    public AccountController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/profile")
    public ApiResponse<UserView> profile() {
        return ApiResponse.ok(authService.view(CurrentUser.required()));
    }

    @PutMapping("/profile")
    public ApiResponse<UserView> update(@Valid @RequestBody ProfileRequest request) {
        return ApiResponse.ok(authService.updateProfile(CurrentUser.required(), request));
    }

    /**
     * 修改当前用户密码，对齐契约 PUT /account/password。
     *
     * <p>契约的成功响应是 204 无响应体（这个端点是唯一不套 ApiResponse 信封的账户接口），
     * 因此返回 {@code ResponseEntity.noContent()} 而不是 {@code ApiResponse.ok()}，
     * 与 {@code TravelerController#delete} 的写法一致。
     * 前端 {@code authApi.changePassword} 已经把 204 当作成功处理。</p>
     */
    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChangeRequest request) {
        authService.changePassword(CurrentUser.required(), request);
        return ResponseEntity.noContent().build();
    }
}
