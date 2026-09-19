package com.travelagency.web.controller;

import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.common.security.UserPrincipal;
import com.travelagency.domain.dto.ReviewView;
import com.travelagency.domain.dto.RouteDetailView;
import com.travelagency.domain.dto.RouteSummaryView;
import com.travelagency.domain.service.OrderService;
import com.travelagency.domain.service.RouteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/routes")
public class RouteController {

    private final RouteService routeService;
    private final OrderService orderService;

    public RouteController(RouteService routeService, OrderService orderService) {
        this.routeService = routeService;
        this.orderService = orderService;
    }

    /**
     * 线路搜索与筛选，对齐契约 {@code GET /routes}。
     *
     * <p>响应 items 必须是契约 {@code RouteSummary}：前端线路卡片与订单页都依赖
     * {@code availableSeats} / {@code nextDepartureDate}，直出实体时这两个字段缺失，
     * 会让"余位"永远显示为空。{@code favorite} 需要登录态，未登录恒为 false。</p>
     */
    @GetMapping
    public ApiResponse<PageResponse<RouteSummaryView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "12") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String departureCity,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Integer durationDays,
            @RequestParam(required = false) Integer departureMonth,
            @RequestParam(defaultValue = "false") boolean hasDeparture,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(routeService.pagePublic(page, size, keyword, departureCity,
                destination, minPrice, maxPrice, durationDays, departureMonth, hasDeparture,
                sort, currentUserId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<RouteDetailView> detail(@PathVariable Long id) {
        return ApiResponse.ok(routeService.detail(id, currentUserId()));
    }

    /**
     * 公开线路可见评价分页，对齐契约 GET /routes/{routeId}/reviews（无需登录，仅 VISIBLE）。
     */
    @GetMapping("/{id}/reviews")
    public ApiResponse<PageResponse<ReviewView>> reviews(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(orderService.listRouteReviews(id, page, size));
    }

    /**
     * 本组端点公开可访问，登录与否只影响 {@code favorite} 字段，因此取可选身份而非强制登录。
     */
    private static Long currentUserId() {
        UserPrincipal principal = CurrentUser.optional();
        return principal == null ? null : principal.userId();
    }
}
