package com.travelagency.web.controller;

import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.domain.dto.PlaceGuideView;
import com.travelagency.domain.service.PlaceGuideService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/place-guides")
public class PlaceGuideController {
    private final PlaceGuideService service;

    public PlaceGuideController(PlaceGuideService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<PlaceGuideView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) Long authorId) {
        return ApiResponse.ok(service.list(page, size, city, destination, authorId, true));
    }

    @GetMapping("/cities")
    public ApiResponse<List<PlaceGuideService.City>> cities() {
        return ApiResponse.ok(service.cities());
    }

    @GetMapping("/publishers")
    public ApiResponse<List<PlaceGuideService.Publisher>> publishers() {
        return ApiResponse.ok(service.publishers());
    }

    @GetMapping("/{id}")
    public ApiResponse<PlaceGuideView> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id, true));
    }
}
