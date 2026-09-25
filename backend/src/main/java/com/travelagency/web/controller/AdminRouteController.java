package com.travelagency.web.controller;

import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.common.validation.KeywordRules;
import com.travelagency.domain.dto.ItineraryDayRequest;
import com.travelagency.domain.dto.ItineraryDayView;
import com.travelagency.domain.dto.ItineraryItemRequest;
import com.travelagency.domain.dto.ItineraryItemView;
import com.travelagency.domain.dto.RouteDetailView;
import com.travelagency.domain.dto.RouteStatusUpdateRequest;
import com.travelagency.domain.dto.RouteSummaryView;
import com.travelagency.domain.dto.RouteUpsertRequest;
import com.travelagency.domain.dto.RouteView;
import com.travelagency.domain.service.AdminRouteService;
import jakarta.validation.Valid;
import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import java.util.List;

/**
 * 旅行社后台线路管理接口（线路 + 每日行程 + 行程项目），对齐契约 Admin Routes 一组端点。
 *
 * <p>此前这些端点分散在 {@link AdminController}，且与契约不一致：
 * <ul>
 *   <li>创建/修改线路直接接收并返回 {@code TravelRoute} 实体（200 + 实体，而非 201 + Location + Route 视图），
 *       客户端因此可以提交 {@code status}、评分等应由服务端决定的字段；</li>
 *   <li>{@code PATCH /admin/routes/{id}/status} 接受任意 {@code StatusRequest} 并返回 {@code data:null}，
 *       契约要求返回更新后的 Route，且状态只能是 PUBLISHED / OFFLINE；</li>
 *   <li>行程接口返回 {@code RouteItineraryDay} / {@code RouteItineraryItem} 实体（多带 createdAt/updatedAt 等
 *       契约未声明字段，且缺少 {@code items}、{@code hotelName}），无法被契约校验通过；</li>
 *   <li>删除每日行程不会级联删除其行程项目，删除接口也统一返回 200 而不是契约的 204。</li>
 * </ul></p>
 *
 * <p>权限：整个 {@code /api/admin/**} 要求 ADMIN 或 STAFF（见 SecurityConfig），
 * 本控制器再显式声明一次，避免脱离路径前缀被单独复用时丢失校验。</p>
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAnyRole('ADMIN','STAFF')")
@Validated
public class AdminRouteController {

    private final AdminRouteService adminRouteService;

    public AdminRouteController(AdminRouteService adminRouteService) {
        this.adminRouteService = adminRouteService;
    }

    // ------------------------------------------------------------------
    // 线路管理
    // ------------------------------------------------------------------

    /**
     * 分页查询全部线路，对齐契约 GET /admin/routes（keyword + status 筛选）。
     *
     * <p>{@code keyword} 的契约上限由 {@link KeywordRules} 统一提供；同组的 {@code status} 不在这里
     * 加校验注解：契约枚举已由 {@code AdminRouteService.page} 拒绝并返回同一种 422 形状，
     * 控制器再叠一套只会让同一个参数出现两种错误口径。</p>
     */
    @GetMapping("/routes")
    public ApiResponse<PageResponse<RouteSummaryView>> routes(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false)
            @CodePointLength(max = KeywordRules.MAX_CHARS, message = KeywordRules.LENGTH_MESSAGE) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(adminRouteService.page(page, size, keyword, status));
    }

    /** 创建线路草稿，对齐契约 POST /admin/routes（201 + Location + RouteEnvelope）。 */
    @PostMapping("/routes")
    public ResponseEntity<ApiResponse<RouteView>> createRoute(@Valid @RequestBody RouteUpsertRequest request) {
        RouteView route = adminRouteService.create(request, CurrentUser.required().userId());
        return ResponseEntity.created(URI.create("/api/admin/routes/" + route.id())).body(ApiResponse.ok(route));
    }

    /** 获取线路管理详情，对齐契约 GET /admin/routes/{routeId}（RouteDetailEnvelope）。 */
    @GetMapping("/routes/{routeId}")
    public ApiResponse<RouteDetailView> routeDetail(@PathVariable Long routeId) {
        return ApiResponse.ok(adminRouteService.detail(routeId));
    }

    /** 修改线路基本资料，对齐契约 PUT /admin/routes/{routeId}。 */
    @PutMapping("/routes/{routeId}")
    public ApiResponse<RouteView> updateRoute(
            @PathVariable Long routeId, @Valid @RequestBody RouteUpsertRequest request) {
        RouteView route = adminRouteService.update(routeId, request, CurrentUser.required().userId());
        return ApiResponse.ok(route);
    }

    /** 上架或下架线路，对齐契约 PATCH /admin/routes/{routeId}/status。 */
    @PatchMapping("/routes/{routeId}/status")
    public ApiResponse<RouteView> updateRouteStatus(
            @PathVariable Long routeId, @Valid @RequestBody RouteStatusUpdateRequest request) {
        RouteView route = adminRouteService.updateStatus(routeId, request.status(), CurrentUser.required().userId());
        return ApiResponse.ok(route);
    }

    // ------------------------------------------------------------------
    // 每日行程管理
    // ------------------------------------------------------------------

    /** 获取线路每日行程，对齐契约 GET /admin/routes/{routeId}/itinerary-days。 */
    @GetMapping("/routes/{routeId}/itinerary-days")
    public ApiResponse<List<ItineraryDayView>> itineraryDays(@PathVariable Long routeId) {
        return ApiResponse.ok(adminRouteService.itineraryDays(routeId));
    }

    /** 新增每日行程，对齐契约 POST /admin/routes/{routeId}/itinerary-days（201 + Location）。 */
    @PostMapping("/routes/{routeId}/itinerary-days")
    public ResponseEntity<ApiResponse<ItineraryDayView>> createItineraryDay(
            @PathVariable Long routeId, @Valid @RequestBody ItineraryDayRequest request) {
        ItineraryDayView day = adminRouteService.createDay(routeId, request, CurrentUser.required().userId());
        return ResponseEntity.created(URI.create("/api/admin/itinerary-days/" + day.id()))
                .body(ApiResponse.ok(day));
    }

    /** 修改每日行程，对齐契约 PUT /admin/itinerary-days/{dayId}。 */
    @PutMapping("/itinerary-days/{dayId}")
    public ApiResponse<ItineraryDayView> updateItineraryDay(
            @PathVariable Long dayId, @Valid @RequestBody ItineraryDayRequest request) {
        ItineraryDayView day = adminRouteService.updateDay(dayId, request, CurrentUser.required().userId());
        return ApiResponse.ok(day);
    }

    /** 删除每日行程及其项目，对齐契约 DELETE /admin/itinerary-days/{dayId}（204）。 */
    @DeleteMapping("/itinerary-days/{dayId}")
    public ResponseEntity<Void> deleteItineraryDay(@PathVariable Long dayId) {
        adminRouteService.deleteDay(dayId, CurrentUser.required().userId());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // 行程项目管理
    // ------------------------------------------------------------------

    /** 获取某日行程项目，对齐契约 GET /admin/itinerary-days/{dayId}/items。 */
    @GetMapping("/itinerary-days/{dayId}/items")
    public ApiResponse<List<ItineraryItemView>> itineraryItems(@PathVariable Long dayId) {
        return ApiResponse.ok(adminRouteService.items(dayId));
    }

    /** 新增行程项目，对齐契约 POST /admin/itinerary-days/{dayId}/items（201 + Location）。 */
    @PostMapping("/itinerary-days/{dayId}/items")
    public ResponseEntity<ApiResponse<ItineraryItemView>> createItineraryItem(
            @PathVariable Long dayId, @Valid @RequestBody ItineraryItemRequest request) {
        ItineraryItemView item = adminRouteService.createItem(dayId, request, CurrentUser.required().userId());
        return ResponseEntity.created(URI.create("/api/admin/itinerary-items/" + item.id()))
                .body(ApiResponse.ok(item));
    }

    /** 修改行程项目，对齐契约 PUT /admin/itinerary-items/{itemId}。 */
    @PutMapping("/itinerary-items/{itemId}")
    public ApiResponse<ItineraryItemView> updateItineraryItem(
            @PathVariable Long itemId, @Valid @RequestBody ItineraryItemRequest request) {
        ItineraryItemView item = adminRouteService.updateItem(itemId, request, CurrentUser.required().userId());
        return ApiResponse.ok(item);
    }

    /** 删除行程项目，对齐契约 DELETE /admin/itinerary-items/{itemId}（204）。 */
    @DeleteMapping("/itinerary-items/{itemId}")
    public ResponseEntity<Void> deleteItineraryItem(@PathVariable Long itemId) {
        adminRouteService.deleteItem(itemId, CurrentUser.required().userId());
        return ResponseEntity.noContent().build();
    }
}
