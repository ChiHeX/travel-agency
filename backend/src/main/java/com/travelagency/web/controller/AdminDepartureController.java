package com.travelagency.web.controller;

import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.domain.dto.DepartureStatusUpdateRequest;
import com.travelagency.domain.dto.DepartureUpsertRequest;
import com.travelagency.domain.dto.DepartureView;
import com.travelagency.domain.service.DepartureService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
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
import java.time.LocalDate;

/**
 * 旅行社后台团期管理接口，对齐契约 Admin Departures 一组端点。
 *
 * <p>此前这些端点分散在 {@link AdminController}，且与冻结契约不一致：</p>
 * <ul>
 *   <li>创建/修改团期直接接收并返回 {@code Departure} 实体，客户端因此可以提交
 *       {@code status}、{@code reservedPeople}、{@code confirmedPeople}、{@code version}
 *       —— 这些字段能直接改写"剩余名额"与状态机；</li>
 *   <li>创建返回 200 且没有 {@code Location}，契约要求 201 + {@code Location}；</li>
 *   <li>{@code PATCH /admin/departures/{departureId}/status} 接自由文本 {@code StatusRequest}
 *       并返回 {@code data: null}，契约要求校验 {@code DepartureStatus} 并返回更新后的团期；</li>
 *   <li>字段语义错误（日期颠倒、引用不存在的线路/导游）返回 400，契约在这些端点声明的是 422 / 409。</li>
 * </ul>
 *
 * <p>权限：整个 {@code /api/admin/**} 要求 ADMIN 或 STAFF（见 SecurityConfig），
 * 本控制器再显式声明一次，避免脱离路径前缀被单独复用时丢失校验。</p>
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAnyRole('ADMIN','STAFF')")
@Validated
public class AdminDepartureController {

    private final DepartureService departureService;

    public AdminDepartureController(DepartureService departureService) {
        this.departureService = departureService;
    }

    /**
     * 分页查询团期，对齐契约 GET /admin/departures
     * （routeId / guideId / status / startDateFrom / startDateTo 筛选）。
     *
     * <p>{@code status} 的契约枚举由 {@code DepartureService.page} 统一校验并返回 422，
     * 这里不重复加注解，避免同一个参数出现两种错误口径。</p>
     */
    @GetMapping("/departures")
    public ApiResponse<PageResponse<DepartureView>> departures(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long routeId,
            @RequestParam(required = false) Long guideId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDateTo) {
        return ApiResponse.ok(
                departureService.page(routeId, guideId, status, startDateFrom, startDateTo, page, size));
    }

    /** 创建团期，对齐契约 POST /admin/departures（201 + Location + DepartureEnvelope）。 */
    @PostMapping("/departures")
    public ResponseEntity<ApiResponse<DepartureView>> createDeparture(
            @Valid @RequestBody DepartureUpsertRequest request) {
        DepartureView departure = departureService.create(request, CurrentUser.required().userId());
        return ResponseEntity.created(URI.create("/api/admin/departures/" + departure.id()))
                .body(ApiResponse.ok(departure));
    }

    /** 获取团期管理详情，对齐契约 GET /admin/departures/{departureId}。 */
    @GetMapping("/departures/{departureId}")
    public ApiResponse<DepartureView> departureDetail(@PathVariable Long departureId) {
        return ApiResponse.ok(departureService.detail(departureId));
    }

    /** 修改团期，对齐契约 PUT /admin/departures/{departureId}。 */
    @PutMapping("/departures/{departureId}")
    public ApiResponse<DepartureView> updateDeparture(
            @PathVariable Long departureId, @Valid @RequestBody DepartureUpsertRequest request) {
        DepartureView departure = departureService.update(departureId, request, CurrentUser.required().userId());
        return ApiResponse.ok(departure);
    }

    /** 修改团期运营状态，对齐契约 PATCH /admin/departures/{departureId}/status（返回更新后的团期）。 */
    @PatchMapping("/departures/{departureId}/status")
    public ApiResponse<DepartureView> updateDepartureStatus(
            @PathVariable Long departureId, @Valid @RequestBody DepartureStatusUpdateRequest request) {
        DepartureView departure =
                departureService.changeStatus(departureId, request.status(), CurrentUser.required().userId());
        return ApiResponse.ok(departure);
    }
}
