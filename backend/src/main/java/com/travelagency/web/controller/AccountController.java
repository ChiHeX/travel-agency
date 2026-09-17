package com.travelagency.web.controller;

import com.travelagency.auth.dto.ProfileRequest;
import com.travelagency.auth.dto.UserView;
import com.travelagency.auth.service.AuthService;
import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.security.CurrentUser;
import jakarta.validation.Valid;
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
}
