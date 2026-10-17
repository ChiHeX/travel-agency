package com.travelagency.web.controller;

import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.common.security.UserPrincipal;
import com.travelagency.common.validation.KeywordRules;
import com.travelagency.common.validation.PriceRules;
import com.travelagency.domain.dto.PublicHotelDetailView;
import com.travelagency.domain.dto.ReviewView;
import com.travelagency.domain.dto.RouteDetailView;
import com.travelagency.domain.dto.RouteSummaryView;
import com.travelagency.domain.service.HotelService;
import com.travelagency.domain.service.OrderService;
import com.travelagency.domain.service.RouteService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/routes")
@Validated
public class RouteController {

    private final RouteService routeService;
    private final OrderService orderService;
    private final HotelService hotelService;

    public RouteController(RouteService routeService, OrderService orderService, HotelService hotelService) {
        this.routeService = routeService;
        this.orderService = orderService;
        this.hotelService = hotelService;
    }

    /**
     * 线路搜索与筛选，对齐契约 {@code GET /routes}。
     *
     * <p>响应 items 必须是契约 {@code RouteSummary}：前端线路卡片与订单页都依赖
     * {@code availableSeats} / {@code nextDepartureDate}，直出实体时这两个字段缺失，
     * 会让"余位"永远显示为空。{@code favorite} 需要登录态，未登录恒为 false。</p>
     *
     * <p>数值型筛选参数按契约边界校验：{@code durationDays >= 1}、{@code departureMonth} 1..12、
     * 价格按 {@code PriceFilter}（契约里与响应金额 {@code Money} 分开声明的那条）。价格用字符串参数
     * + {@link PriceRules#PATTERN} 做词法校验再解析，规则与理由见 {@link PriceRules}。</p>
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
            @Pattern(regexp = PriceRules.PATTERN, message = PriceRules.MIN_MESSAGE) String minPrice,
            @RequestParam(required = false)
            @Pattern(regexp = PriceRules.PATTERN, message = PriceRules.MAX_MESSAGE) String maxPrice,
            @RequestParam(required = false)
            @Min(value = 1, message = "durationDays 不能小于 1") Integer durationDays,
            @RequestParam(required = false)
            @Min(value = 1, message = "departureMonth 只能是 1 到 12")
            @Max(value = 12, message = "departureMonth 只能是 1 到 12") Integer departureMonth,
            @RequestParam(defaultValue = "false") boolean hasDeparture,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(routeService.pagePublic(page, size, keyword, departureCity,
                destination, PriceRules.parseOrNull(minPrice), PriceRules.parseOrNull(maxPrice),
                durationDays, departureMonth, hasDeparture, sort, currentUserId()));
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

    @GetMapping("/{id}/hotels/{hotelId}")
    public ApiResponse<PublicHotelDetailView> hotel(
            @PathVariable Long id, @PathVariable Long hotelId) {
        return ApiResponse.ok(hotelService.publicDetail(id, hotelId));
    }

    /**
     * 本组端点公开可访问，登录与否只影响 {@code favorite} 字段，因此取可选身份而非强制登录。
     */
    private static Long currentUserId() {
        UserPrincipal principal = CurrentUser.optional();
        return principal == null ? null : principal.userId();
    }
}
