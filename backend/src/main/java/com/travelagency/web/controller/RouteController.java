package com.travelagency.web.controller;

import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.common.security.UserPrincipal;
import com.travelagency.common.validation.KeywordRules;
import com.travelagency.domain.dto.ReviewView;
import com.travelagency.domain.dto.RouteDetailView;
import com.travelagency.domain.dto.RouteSummaryView;
import com.travelagency.domain.service.OrderService;
import com.travelagency.domain.service.RouteService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/routes")
@Validated
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
     *
     * <p>数值型筛选参数按契约边界校验：{@code durationDays >= 1}、{@code departureMonth} 1..12、
     * 价格非负且最多两位小数。价格这里刻意比契约的 {@code Money} 模式（固定两位小数字符串）
     * <b>宽松一档</b>：现有搜索页与收藏的链接会传 {@code minPrice=100} 这种不带小数的写法，
     * 严格套模式会把可用页面变成 422。若要收紧到与 {@code Money} 完全一致，需同时把前端与
     * URL 入参规范成两位小数，再改这里的约束。</p>
     */
    @GetMapping
    public ApiResponse<PageResponse<RouteSummaryView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "12") long size,
            @RequestParam(required = false)
            @CodePointLength(max = KeywordRules.MAX_CHARS, message = KeywordRules.LENGTH_MESSAGE) String keyword,
            @RequestParam(required = false)
            @CodePointLength(max = 64, message = "departureCity 长度不能超过 64 个字符") String departureCity,
            @RequestParam(required = false)
            @CodePointLength(max = 128, message = "destination 长度不能超过 128 个字符") String destination,
            @RequestParam(required = false)
            @DecimalMin(value = "0.00", message = "minPrice 不能为负数")
            @Digits(integer = 9, fraction = 2, message = "minPrice 最多两位小数")
            BigDecimal minPrice,
            @RequestParam(required = false)
            @DecimalMin(value = "0.00", message = "maxPrice 不能为负数")
            @Digits(integer = 9, fraction = 2, message = "maxPrice 最多两位小数")
            BigDecimal maxPrice,
            @RequestParam(required = false)
            @Min(value = 1, message = "durationDays 不能小于 1") Integer durationDays,
            @RequestParam(required = false)
            @Min(value = 1, message = "departureMonth 只能是 1 到 12")
            @Max(value = 12, message = "departureMonth 只能是 1 到 12") Integer departureMonth,
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
