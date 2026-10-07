package com.travelagency.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.enums.DepartureStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.domain.dto.DepartureView;
import com.travelagency.domain.dto.GuideDashboardView;
import com.travelagency.domain.dto.GuideDepartureDetailView;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.OrderTraveler;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.OrderTravelerMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.service.AdminRouteService;
import com.travelagency.domain.service.DepartureService;
import com.travelagency.domain.service.OrderService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/guide")
@PreAuthorize("hasAnyRole('GUIDE','ADMIN')")
public class GuideController {

    private final GuideMapper guideMapper;
    private final DepartureMapper departureMapper;
    private final TravelOrderMapper orderMapper;
    private final OrderTravelerMapper orderTravelerMapper;
    private final AdminRouteService adminRouteService;
    private final DepartureService departureService;

    public GuideController(
            GuideMapper guideMapper,
            DepartureMapper departureMapper,
            TravelOrderMapper orderMapper,
            OrderTravelerMapper orderTravelerMapper,
            AdminRouteService adminRouteService,
            DepartureService departureService) {
        this.guideMapper = guideMapper;
        this.departureMapper = departureMapper;
        this.orderMapper = orderMapper;
        this.orderTravelerMapper = orderTravelerMapper;
        this.adminRouteService = adminRouteService;
        this.departureService = departureService;
    }

    /**
     * 导游工作台概览，对齐契约 GET /guide/dashboard（GuideDashboardEnvelope）：
     * data = { upcoming, current, history }，元素为契约 Departure。
     *
     * <p>此前返回的键名是 {@code active}（契约是 {@code current}）且元素是数据库实体，
     * 前端 {@code data.current.length} 会直接取不到值。</p>
     */
    @GetMapping("/dashboard")
    public ApiResponse<GuideDashboardView> dashboard() {
        List<DepartureView> departures = departureService.listOfGuide(currentGuide().id);
        // "今天"取库内日期，与列表查询的 CURRENT_DATE() 保持同一时区口径。
        LocalDate today = departureMapper.databaseToday();
        List<DepartureView> current = departures.stream()
                .filter(departure -> DepartureStatus.TRAVELLING.equals(departure.status())).toList();
        List<DepartureView> history = departures.stream()
                .filter(departure -> DepartureStatus.FINISHED.equals(departure.status())).toList();
        // upcoming 与列表 scope=UPCOMING 共用同一份定义：排除行程中、已完成、已取消，
        // 且出发日期不早于当天。此前工作台只排除状态、没有日期限制，会把过期未出发的团期也列进来。
        List<DepartureView> upcoming = departures.stream()
                .filter(departure -> DepartureService.isUpcoming(departure.status(), departure.startDate(), today))
                .toList();
        return ApiResponse.ok(new GuideDashboardView(upcoming, current, history));
    }

    /**
     * 查询当前导游负责的团期，对齐契约 GET /guide/departures（DeparturePageEnvelope）。
     *
     * <p>scope 为契约可选参数：CURRENT / HISTORY 按团期状态过滤；UPCOMING 采用与工作台一致的定义 ——
     * 排除行程中、已完成、已取消，且出发日期不早于当天（此前只限制日期，会把取消、完成、
     * 行程中的团期一并混入）。</p>
     */
    @GetMapping("/departures")
    public ApiResponse<PageResponse<DepartureView>> departures(
            @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Guide guide = currentGuide();
        if (scope == null || scope.isBlank()) {
            return ApiResponse.ok(departureService.page(null, guide.id, null, null, null, page, size));
        }
        String value = scope.trim().toUpperCase();
        return switch (value) {
            case "CURRENT" -> ApiResponse.ok(
                    departureService.page(null, guide.id, DepartureStatus.TRAVELLING, null, null, page, size));
            case "HISTORY" -> ApiResponse.ok(
                    departureService.page(null, guide.id, DepartureStatus.FINISHED, null, null, page, size));
            case "UPCOMING" -> ApiResponse.ok(departureService.pageUpcoming(null, guide.id, page, size));
            default -> throw new BusinessException(422, "VALIDATION_ERROR",
                    "scope 只能是 UPCOMING、CURRENT 或 HISTORY");
        };
    }

    /**
     * 获取本人负责的团期与行程详情，对齐契约 GET /guide/departures/{departureId}
     * （GuideDepartureDetailEnvelope）：data = { departure, route, itinerary }。
     *
     * <p>此前用 {@code Map} 拼装且用 {@code passengers} 顶替 {@code itinerary}：
     * 既违反 additionalProperties:false，也导致导游拿不到每日行程。
     * 游客名单已由 {@code /passengers} 独立提供，这里不再重复返回。</p>
     */
    @GetMapping("/departures/{id}")
    public ApiResponse<GuideDepartureDetailView> detail(@PathVariable Long id) {
        Departure departure = ownedDeparture(id);
        return ApiResponse.ok(new GuideDepartureDetailView(
                departureService.toView(departure),
                adminRouteService.routeView(departure.routeId),
                adminRouteService.itineraryDays(departure.routeId)));
    }

    @GetMapping("/departures/{id}/passengers")
    public ApiResponse<List<Map<String, Object>>> passengers(@PathVariable Long id) {
        ownedDeparture(id);
        return ApiResponse.ok(passengerList(id));
    }

    /**
     * 导游开始行程，对齐契约 POST /guide/departures/{departureId}/start。
     *
     * <p>契约要求返回更新后的团期（DepartureEnvelope），前端 {@code guideApi.start} 已按
     * {@code POST .../start} 调用；此前该端点缺失，按钮必然 404。</p>
     */
    @PostMapping("/departures/{id}/start")
    public ApiResponse<DepartureView> start(@PathVariable Long id) {
        ownedDeparture(id);
        return ApiResponse.ok(departureService.start(id));
    }

    /**
     * 导游结束行程，对齐契约 POST /guide/departures/{departureId}/complete，
     * 返回更新后的团期（DepartureEnvelope）。对应前端带团详情页的"标记行程已结束"按钮。
     */
    @PostMapping("/departures/{id}/complete")
    public ApiResponse<DepartureView> complete(@PathVariable Long id) {
        ownedDeparture(id);
        return ApiResponse.ok(departureService.complete(id));
    }

    private Guide currentGuide() {
        Guide guide = guideMapper.selectOne(new QueryWrapper<Guide>().eq("user_id", CurrentUser.required().userId()));
        if (guide == null) {
            throw new BusinessException(403, "当前账号未绑定导游资料");
        }
        return guide;
    }

    /**
     * 取本人负责的团期。
     *
     * <p>区分两种情况以对齐契约：团期不存在返回 404，存在但不属于本人返回 403
     * （此前统一返回 403，无法区分"没有这个团期"和"没有权限"）。</p>
     */
    private Departure ownedDeparture(Long id) {
        Departure departure = departureMapper.selectById(id);
        if (departure == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "团期不存在");
        }
        if (!Objects.equals(departure.guideId, currentGuide().id)) {
            throw new BusinessException(403, "ACCESS_DENIED", "只能查看自己负责的团期");
        }
        return departure;
    }

    /**
     * 本人团期的游客名单。
     *
     * <p>先一次性取出该团期的有效订单，再用一条 {@code IN (orderIds)} 批量取全部出行人快照，
     * 在内存里按订单分组回填 —— 取代此前"每张订单各查一次出行人"的 N+1。订单与出行人各自
     * 显式按主键升序，返回顺序与接口语义保持不变；脱敏规则仍走 {@link OrderService#maskId(String)}。</p>
     *
     * <p>范围严格限定在本人团期（调用方已通过 {@link #ownedDeparture} 校验归属）与有效订单
     * （排除待支付 / 已取消 / 已退款），与工作台、通知的口径一致。</p>
     */
    private List<Map<String, Object>> passengerList(Long departureId) {
        List<TravelOrder> orders = orderMapper.selectList(new QueryWrapper<TravelOrder>()
                .eq("departure_id", departureId)
                .notIn("status", "WAIT_PAY", "CANCELLED", "REFUNDED")
                .orderByAsc("id"));
        if (orders.isEmpty()) {
            return List.of();
        }
        List<Long> orderIds = orders.stream().map(order -> order.id).toList();
        Map<Long, List<OrderTraveler>> travelersByOrder = orderTravelerMapper.selectList(
                        new QueryWrapper<OrderTraveler>().in("order_id", orderIds).orderByAsc("order_id", "id"))
                .stream()
                .collect(Collectors.groupingBy(traveler -> traveler.orderId, LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> result = new ArrayList<>();
        for (TravelOrder order : orders) {
            for (OrderTraveler traveler : travelersByOrder.getOrDefault(order.id, List.of())) {
                result.add(passenger(order, traveler));
            }
        }
        return result;
    }

    private Map<String, Object> passenger(TravelOrder order, OrderTraveler traveler) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", order.orderNo);
        result.put("name", traveler.name);
        result.put("phone", traveler.phone);
        result.put("emergencyName", traveler.emergencyName);
        result.put("emergencyPhone", traveler.emergencyPhone);
        result.put("idNo", OrderService.maskId(traveler.idNo));
        return result;
    }
}
