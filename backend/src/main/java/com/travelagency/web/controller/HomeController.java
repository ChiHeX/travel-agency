package com.travelagency.web.controller;

import com.travelagency.common.api.ApiResponse;
import com.travelagency.domain.dto.HomeView;
import com.travelagency.domain.service.HomeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {
    private final HomeService homeService;

    public HomeController(HomeService homeService) {
        this.homeService = homeService;
    }

    @GetMapping("/api/home")
    public ApiResponse<HomeView> home() {
        return ApiResponse.ok(homeService.get());
    }
}
