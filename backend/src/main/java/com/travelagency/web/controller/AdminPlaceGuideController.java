package com.travelagency.web.controller;

import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.domain.dto.PlaceGuideRequest;
import com.travelagency.domain.dto.PlaceGuideView;
import com.travelagency.domain.dto.StatusRequest;
import com.travelagency.domain.service.PlaceGuideService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/admin/place-guides")
@PreAuthorize("hasAnyRole('ADMIN','STAFF')")
public class AdminPlaceGuideController {
    private final PlaceGuideService service;

    public AdminPlaceGuideController(PlaceGuideService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<PlaceGuideView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(service.list(page, size, null, null, null, false));
    }

    @GetMapping("/{id}")
    public ApiResponse<PlaceGuideView> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id, false));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PlaceGuideView>> create(@Valid @RequestBody PlaceGuideRequest request) {
        PlaceGuideView view = service.create(request, CurrentUser.required().userId());
        return ResponseEntity.created(URI.create("/api/admin/place-guides/" + view.id()))
                .body(ApiResponse.ok(view));
    }

    @PutMapping("/{id}")
    public ApiResponse<PlaceGuideView> update(@PathVariable Long id,
                                              @Valid @RequestBody PlaceGuideRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<PlaceGuideView> updateStatus(@PathVariable Long id,
                                                    @Valid @RequestBody StatusRequest request) {
        return ApiResponse.ok(service.updateStatus(id, request.status()));
    }
}
