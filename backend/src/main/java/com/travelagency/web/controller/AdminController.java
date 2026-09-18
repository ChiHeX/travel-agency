package com.travelagency.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.auth.service.AuthService;
import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.enums.RefundStatus;
import com.travelagency.common.enums.RoleCode;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.domain.dto.AdminUserView;
import com.travelagency.domain.dto.DepartureView;
import com.travelagency.domain.dto.GuideAccountRequest;
import com.travelagency.domain.dto.GuideView;
import com.travelagency.domain.dto.GuideUpdateRequest;
import com.travelagency.domain.dto.OperationLogView;
import com.travelagency.domain.dto.OrderDetailResponse;
import com.travelagency.domain.dto.OrderSummaryView;
import com.travelagency.domain.dto.RefundDecisionRequest;
import com.travelagency.domain.dto.RefundView;
import com.travelagency.domain.dto.ReviewStatusUpdateRequest;
import com.travelagency.domain.dto.ReviewView;
import com.travelagency.domain.dto.StaffAccountRequest;
import com.travelagency.domain.dto.StatusRequest;
import com.travelagency.domain.entity.Attraction;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.Hotel;
import com.travelagency.domain.entity.OperationLog;
import com.travelagency.domain.entity.Refund;
import com.travelagency.domain.entity.RouteItineraryDay;
import com.travelagency.domain.entity.RouteItineraryItem;
import com.travelagency.domain.entity.Staff;
import com.travelagency.domain.entity.SysRole;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.SysUserRole;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.AttractionMapper;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.HotelMapper;
import com.travelagency.domain.mapper.OperationLogMapper;
import com.travelagency.domain.mapper.RefundMapper;
import com.travelagency.domain.mapper.RouteItineraryDayMapper;
import com.travelagency.domain.mapper.RouteItineraryItemMapper;
import com.travelagency.domain.mapper.StaffMapper;
import com.travelagency.domain.mapper.SysRoleMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.SysUserRoleMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.service.DepartureService;
import com.travelagency.domain.service.OrderService;
import com.travelagency.domain.service.GuideService;
import com.travelagency.domain.service.RouteService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
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

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAnyRole('ADMIN','STAFF')")
public class AdminController {

    private final RouteService routeService;
    private final DepartureService departureService;
    private final OrderService orderService;
    private final TravelOrderMapper orderMapper;
    private final DepartureMapper departureMapper;
    private final RefundMapper refundMapper;
    private final AttractionMapper attractionMapper;
    private final HotelMapper hotelMapper;
    private final GuideMapper guideMapper;
    private final RouteItineraryDayMapper dayMapper;
    private final RouteItineraryItemMapper itemMapper;
    private final OperationLogMapper operationLogMapper;
    private final SysUserMapper userMapper;
    private final StaffMapper staffMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final GuideService guideService;

    public AdminController(
            RouteService routeService,
            DepartureService departureService,
            OrderService orderService,
            TravelOrderMapper orderMapper,
            DepartureMapper departureMapper,
            RefundMapper refundMapper,
            AttractionMapper attractionMapper,
            HotelMapper hotelMapper,
            GuideMapper guideMapper,
            RouteItineraryDayMapper dayMapper,
            RouteItineraryItemMapper itemMapper,
            OperationLogMapper operationLogMapper,
            SysUserMapper userMapper,
            StaffMapper staffMapper,
            SysRoleMapper roleMapper,
            SysUserRoleMapper userRoleMapper,
            PasswordEncoder passwordEncoder,
            AuthService authService, GuideService guideService) {
        this.routeService = routeService;
        this.departureService = departureService;
        this.orderService = orderService;
        this.orderMapper = orderMapper;
        this.departureMapper = departureMapper;
        this.refundMapper = refundMapper;
        this.attractionMapper = attractionMapper;
        this.hotelMapper = hotelMapper;
        this.guideMapper = guideMapper;
        this.dayMapper = dayMapper;
        this.itemMapper = itemMapper;
        this.operationLogMapper = operationLogMapper;
        this.userMapper = userMapper;
        this.staffMapper = staffMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
        this.guideService = guideService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        long users = userMapper.selectCount(new QueryWrapper<SysUser>().eq("deleted", 0));
        long routes = routeService.pageAll(1, 1, null, "PUBLISHED").getTotal();
        long departures = departureMapper.selectCount(new QueryWrapper<Departure>().eq("status", "OPEN"));
        long todayOrders = orderMapper.selectCount(new QueryWrapper<TravelOrder>()
                .ge("created_at", LocalDate.now().atStartOfDay()));
        long pendingConfirm = orderMapper.selectCount(new QueryWrapper<TravelOrder>()
                .eq("status", OrderStatus.PAID_WAIT_CONFIRM));
        long pendingRefund = orderMapper.selectCount(new QueryWrapper<TravelOrder>()
                .eq("status", OrderStatus.REFUND_APPLYING));
        Object revenue = orderMapper.selectObjs(new QueryWrapper<TravelOrder>()
                .select("COALESCE(SUM(total_amount), 0)").eq("payment_status", "PAID"))
                .stream().findFirst().orElse(BigDecimal.ZERO);
        Object participants = orderMapper.selectObjs(new QueryWrapper<TravelOrder>()
                .select("COALESCE(SUM(adult_count + child_count), 0)")
                .notIn("status", OrderStatus.CANCELLED, OrderStatus.REFUNDED))
                .stream().findFirst().orElse(0);
        return ApiResponse.ok(Map.of(
                "userCount", users,
                "publishedRouteCount", routes,
                "openDepartureCount", departures,
                "todayOrderCount", todayOrders,
                "pendingConfirmCount", pendingConfirm,
                "pendingRefundCount", pendingRefund,
                "participantCount", participants,
                "grossOrderAmount", revenue));
    }

    @GetMapping("/routes")
    public ApiResponse<PageResponse<TravelRoute>> routes(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(PageResponse.from(routeService.pageAll(page, size, keyword, status)));
    }

    @GetMapping("/routes/{id}")
    public ApiResponse<?> routeDetail(@PathVariable Long id) {
        return ApiResponse.ok(routeService.adminDetail(id));
    }

    @PostMapping("/routes")
    public ApiResponse<TravelRoute> createRoute(@RequestBody TravelRoute route) {
        route.createdBy = CurrentUser.required().userId();
        TravelRoute saved = routeService.save(route);
        log("线路", "CREATE", "ROUTE", saved.id, "SUCCESS", "创建线路");
        return ApiResponse.ok(saved);
    }

    @PutMapping("/routes/{id}")
    public ApiResponse<TravelRoute> updateRoute(@PathVariable Long id, @RequestBody TravelRoute route) {
        route.id = id;
        TravelRoute saved = routeService.save(route);
        log("线路", "UPDATE", "ROUTE", id, "SUCCESS", "编辑线路");
        return ApiResponse.ok(saved);
    }

    @PatchMapping("/routes/{id}/status")
    public ApiResponse<Void> updateRouteStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        routeService.updateStatus(id, request.status());
        log("线路", "STATUS", "ROUTE", id, "SUCCESS", "线路状态变更为 " + request.status());
        return ApiResponse.ok();
    }

    /**
     * 后台团期分页查询，对齐契约 GET /admin/departures（分页信封 + routeId/guideId/status/日期区间筛选）。
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
        return ApiResponse.ok(departureService.page(routeId, guideId, status, startDateFrom, startDateTo, page, size));
    }

    /**
     * 团期管理详情，对齐契约 GET /admin/departures/{departureId}。
     * 此前缺少该映射，前端 api/modules.js 调用时会命中 PUT 的路径而返回 405。
     */
    @GetMapping("/departures/{id}")
    public ApiResponse<DepartureView> departureDetail(@PathVariable Long id) {
        return ApiResponse.ok(departureService.detail(id));
    }

    @PostMapping("/departures")
    public ApiResponse<DepartureView> createDeparture(@RequestBody Departure departure) {
        Departure saved = departureService.save(departure);
        log("团期", "CREATE", "DEPARTURE", saved.id, "SUCCESS", "创建团期");
        // 回查以带回 created_at / updated_at 等数据库默认值，并返回契约 Departure 视图。
        return ApiResponse.ok(departureService.detail(saved.id));
    }

    @PutMapping("/departures/{id}")
    public ApiResponse<DepartureView> updateDeparture(@PathVariable Long id, @RequestBody Departure departure) {
        departure.id = id;
        Departure saved = departureService.save(departure);
        log("团期", "UPDATE", "DEPARTURE", id, "SUCCESS", "编辑团期");
        return ApiResponse.ok(departureService.detail(saved.id));
    }

    @PatchMapping("/departures/{id}/status")
    public ApiResponse<Void> updateDepartureStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        departureService.changeStatus(id, request.status());
        log("团期", "STATUS", "DEPARTURE", id, "SUCCESS", "团期状态变更为 " + request.status());
        return ApiResponse.ok();
    }

    @GetMapping("/routes/{routeId}/itinerary-days")
    public ApiResponse<List<RouteItineraryDay>> itineraryDays(@PathVariable Long routeId) {
        return ApiResponse.ok(dayMapper.selectList(new QueryWrapper<RouteItineraryDay>()
                .eq("route_id", routeId).orderByAsc("day_number")));
    }

    @PostMapping("/routes/{routeId}/itinerary-days")
    public ApiResponse<RouteItineraryDay> createItineraryDay(
            @PathVariable Long routeId, @RequestBody RouteItineraryDay day) {
        day.routeId = routeId;
        dayMapper.insert(day);
        return ApiResponse.ok(day);
    }

    @PutMapping("/itinerary-days/{id}")
    public ApiResponse<RouteItineraryDay> updateItineraryDay(
            @PathVariable Long id, @RequestBody RouteItineraryDay day) {
        day.id = id;
        dayMapper.updateById(day);
        return ApiResponse.ok(day);
    }

    @DeleteMapping("/itinerary-days/{id}")
    public ApiResponse<Void> deleteItineraryDay(@PathVariable Long id) {
        dayMapper.deleteById(id);
        return ApiResponse.ok();
    }

    @GetMapping("/itinerary-days/{dayId}/items")
    public ApiResponse<List<RouteItineraryItem>> itineraryItems(@PathVariable Long dayId) {
        return ApiResponse.ok(itemMapper.selectList(new QueryWrapper<RouteItineraryItem>()
                .eq("day_id", dayId).orderByAsc("sort_no")));
    }

    @PostMapping("/itinerary-days/{dayId}/items")
    public ApiResponse<RouteItineraryItem> createItineraryItem(
            @PathVariable Long dayId, @RequestBody RouteItineraryItem item) {
        item.dayId = dayId;
        itemMapper.insert(item);
        return ApiResponse.ok(item);
    }

    @PutMapping("/itinerary-items/{id}")
    public ApiResponse<RouteItineraryItem> updateItineraryItem(
            @PathVariable Long id, @RequestBody RouteItineraryItem item) {
        item.id = id;
        itemMapper.updateById(item);
        return ApiResponse.ok(item);
    }

    @DeleteMapping("/itinerary-items/{id}")
    public ApiResponse<Void> deleteItineraryItem(@PathVariable Long id) {
        itemMapper.deleteById(id);
        return ApiResponse.ok();
    }

    /**
     * 后台景点分页查询，对齐契约 GET /admin/attractions（分页信封 + keyword 筛选）。
     */
    @GetMapping("/attractions")
    public ApiResponse<PageResponse<Attraction>> attractions(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword) {
        QueryWrapper<Attraction> query = new QueryWrapper<>();
        appendKeyword(query, keyword, "name", "intro", "city");
        return ApiResponse.ok(PageResponse.from(attractionMapper.selectPage(pageOf(page, size),
                query.orderByDesc("created_at"))));
    }

    @PostMapping("/attractions")
    public ApiResponse<Attraction> createAttraction(@RequestBody Attraction attraction) {
        attractionMapper.insert(attraction);
        return ApiResponse.ok(attraction);
    }

    @PutMapping("/attractions/{id}")
    public ApiResponse<Attraction> updateAttraction(@PathVariable Long id, @RequestBody Attraction attraction) {
        attraction.id = id;
        attractionMapper.updateById(attraction);
        return ApiResponse.ok(attraction);
    }

    @DeleteMapping("/attractions/{id}")
    public ApiResponse<Void> deleteAttraction(@PathVariable Long id) {
        attractionMapper.deleteById(id);
        return ApiResponse.ok();
    }

    /**
     * 后台酒店分页查询，对齐契约 GET /admin/hotels（分页信封 + keyword 筛选）。
     */
    @GetMapping("/hotels")
    public ApiResponse<PageResponse<Hotel>> hotels(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword) {
        QueryWrapper<Hotel> query = new QueryWrapper<>();
        appendKeyword(query, keyword, "name", "address", "intro");
        return ApiResponse.ok(PageResponse.from(hotelMapper.selectPage(pageOf(page, size),
                query.orderByDesc("created_at"))));
    }

    @PostMapping("/hotels")
    public ApiResponse<Hotel> createHotel(@RequestBody Hotel hotel) {
        hotelMapper.insert(hotel);
        return ApiResponse.ok(hotel);
    }

    @PutMapping("/hotels/{id}")
    public ApiResponse<Hotel> updateHotel(@PathVariable Long id, @RequestBody Hotel hotel) {
        hotel.id = id;
        hotelMapper.updateById(hotel);
        return ApiResponse.ok(hotel);
    }

    @DeleteMapping("/hotels/{id}")
    public ApiResponse<Void> deleteHotel(@PathVariable Long id) {
        hotelMapper.deleteById(id);
        return ApiResponse.ok();
    }

    /**
     * 后台导游分页查询，对齐契约 GET /admin/guides（分页信封 + status 筛选）。
     *
     * <p>返回 {@link GuideView}：契约 Guide 把 {@code username} 列为 required，
     * 而实体只有 {@code userId}，直出实体拿不到账号名。</p>
     */
    @GetMapping("/guides")
    public ApiResponse<PageResponse<GuideView>> guides(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        QueryWrapper<Guide> query = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            query.eq("status", status.trim());
        }
        appendKeyword(query, keyword, "name", "phone", "intro");
        Page<Guide> result = guideMapper.selectPage(pageOf(page, size), query.orderByDesc("created_at"));
        Map<Long, String> usernames = usernamesOf(result.getRecords().stream()
                .map(guide -> guide.userId).toList());
        List<GuideView> items = result.getRecords().stream()
                .map(guide -> GuideView.from(guide, usernames.get(guide.userId)))
                .toList();
        return ApiResponse.ok(new PageResponse<>(items, (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages()));
    }

    /**
     * 后台导游详情，对齐契约 GET /admin/guides/{guideId}。
     * 此前仅有列表映射，前端 adminApi.guide 调用时命中缺失的 GET 映射而 404。
     */
    @GetMapping("/guides/{guideId}")
    public ApiResponse<GuideView> guideDetail(@PathVariable Long guideId) {
        Guide guide = guideMapper.selectById(guideId);
        if (guide == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "导游不存在");
        }
        return ApiResponse.ok(GuideView.from(guide, usernameOf(guide.userId)));
    }

    @PostMapping("/guides")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<GuideView>> createGuide(@Valid @RequestBody GuideAccountRequest request) {
        GuideView guide = guideService.create(request);
        return ResponseEntity.created(URI.create("/api/admin/guides/" + guide.id())).body(ApiResponse.ok(guide));
    }

    @PutMapping("/guides/{id}")
    public ApiResponse<GuideView> updateGuide(@PathVariable Long id, @Valid @RequestBody GuideUpdateRequest request) {
        return ApiResponse.ok(guideService.update(id, request));
    }

    @PatchMapping("/guides/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<GuideView> updateGuideStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return ApiResponse.ok(guideService.updateStatus(id, request.status()));
    }

    @GetMapping("/orders")
    public ApiResponse<PageResponse<OrderSummaryView>> orders(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        QueryWrapper<TravelOrder> query = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            query.eq("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            query.and(wrapper -> wrapper.like("order_no", keyword)
                    .or().like("contact_name", keyword)
                    .or().like("contact_phone", keyword));
        }
        query.orderByDesc("created_at");
        return ApiResponse.ok(orderService.toSummaryPage(orderMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size), query)));
    }

    @GetMapping("/orders/{orderNo}")
    public ApiResponse<OrderDetailResponse> orderDetail(@PathVariable String orderNo) {
        return ApiResponse.ok(orderService.detail(orderNo, CurrentUser.required()));
    }

    @PostMapping("/orders/{orderNo}/confirm")
    public ApiResponse<Void> confirmOrder(@PathVariable String orderNo) {
        orderService.confirm(orderNo, CurrentUser.required().userId());
        log("订单", "CONFIRM", "ORDER", orderNo, "SUCCESS", "确认报名");
        return ApiResponse.ok();
    }

    @GetMapping("/refunds")
    public ApiResponse<PageResponse<RefundView>> refunds(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(orderService.listRefunds(status, page, size));
    }

    @GetMapping("/refunds/{id}")
    public ApiResponse<RefundView> refundDetail(@PathVariable Long id) {
        return ApiResponse.ok(orderService.refundDetail(id));
    }

    /**
     * 同意退款申请。
     *
     * <p>契约的 200 响应是 RefundEnvelope（data 为退款对象），此前返回 void 导致 data 为 null。
     * requestBody 是可选的，但一旦提供仍须校验，否则超长 comment 会绕过
     * RefundDecisionRequest 上的 @Size 约束（同文件 reject 端点原本就带 @Valid）。</p>
     */
    @PostMapping("/refunds/{id}/approve")
    public ApiResponse<RefundView> approveRefund(
            @PathVariable Long id, @Valid @RequestBody(required = false) RefundDecisionRequest request) {
        String comment = request == null ? null : request.comment();
        RefundView refund = orderService.approveRefund(id, comment, CurrentUser.required().userId());
        log("退款", "APPROVE", "REFUND", id, "SUCCESS", comment);
        return ApiResponse.ok(refund);
    }

    @PostMapping("/refunds/{id}/reject")
    public ApiResponse<Void> rejectRefund(
            @PathVariable Long id, @Valid @RequestBody RefundDecisionRequest request) {
        orderService.rejectRefund(id, request.comment(), CurrentUser.required().userId());
        log("退款", "REJECT", "REFUND", id, "SUCCESS", request.comment());
        return ApiResponse.ok();
    }

    @GetMapping("/reviews")
    public ApiResponse<PageResponse<ReviewView>> reviews(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(orderService.listReviews(status, page, size));
    }

    @PatchMapping("/reviews/{id}/status")
    public ApiResponse<ReviewView> updateReviewStatus(
            @PathVariable Long id, @Valid @RequestBody ReviewStatusUpdateRequest request) {
        ReviewView view = orderService.updateReviewStatus(id, request.status(), CurrentUser.required().userId());
        log("评价", "STATUS", "REVIEW", id, "SUCCESS", "评价状态变更为 " + request.status());
        return ApiResponse.ok(view);
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResponse<AdminUserView>> users(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        Page<SysUser> result = userMapper.selectPage(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                new QueryWrapper<SysUser>().eq("deleted", 0).orderByDesc("created_at"));
        List<AdminUserView> records = result.getRecords().stream()
                .map(user -> new AdminUserView(user.id, user.username, user.nickname, user.realName,
                        user.phone, user.email, user.avatar, user.status, user.createdAt))
                .toList();
        return ApiResponse.ok(new PageResponse<>(records, (int) result.getCurrent(), (int) result.getSize(), (int) result.getTotal(), (int) result.getPages()));
    }

    @PatchMapping("/users/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> updateUserStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<SysUser>()
                .eq("id", id).set("status", Integer.parseInt(request.status())));
        log("用户", "STATUS", "USER", id, "SUCCESS", "账号状态变更");
        return ApiResponse.ok();
    }

    @GetMapping("/staff")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResponse<Staff>> staff(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        Page<Staff> result = staffMapper.selectPage(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                new QueryWrapper<Staff>().orderByDesc("created_at"));
        return ApiResponse.ok(PageResponse.from(result));
    }

    @PostMapping("/staff")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ApiResponse<Staff> createStaff(@Valid @RequestBody StaffAccountRequest request) {
        SysUser user = createAccount(request.username(), request.password(), request.realName(), request.phone(), RoleCode.STAFF);
        Staff staff = new Staff();
        staff.userId = user.id;
        staff.employeeNo = "EMP" + user.id;
        staff.department = request.department();
        staff.position = request.position();
        staffMapper.insert(staff);
        return ApiResponse.ok(staff);
    }

    /**
     * 操作日志分页查询，对齐契约 GET /admin/logs（分页信封 + module/operatorId 筛选）。
     *
     * <p>返回 {@link OperationLogView}：实体缺契约 required 的 {@code operatorName}，
     * 且继承 {@code BaseEntity} 多带出 {@code updatedAt}，在 {@code additionalProperties: false}
     * 下两头都不合规。</p>
     */
    @GetMapping("/logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResponse<OperationLogView>> logs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) Long operatorId) {
        QueryWrapper<OperationLog> query = new QueryWrapper<>();
        if (module != null && !module.isBlank()) {
            query.eq("module", module.trim());
        }
        if (operatorId != null) {
            query.eq("operator_id", operatorId);
        }
        Page<OperationLog> result = operationLogMapper.selectPage(pageOf(page, size),
                query.orderByDesc("created_at"));
        Map<Long, String> operatorNames = displayNamesOf(result.getRecords().stream()
                .map(log -> log.operatorId).toList());
        List<OperationLogView> items = result.getRecords().stream()
                .map(log -> OperationLogView.from(log, operatorNames.get(log.operatorId)))
                .toList();
        return ApiResponse.ok(new PageResponse<>(items, (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages()));
    }

    private SysUser createAccount(String username, String password, String realName, String phone, String roleCode) {
        if (userMapper.selectOne(new QueryWrapper<SysUser>().eq("username", username).eq("deleted", 0)) != null) {
            throw new BusinessException("账号已存在");
        }
        SysUser user = new SysUser();
        user.username = username;
        user.passwordHash = passwordEncoder.encode(password);
        user.realName = realName;
        user.nickname = realName;
        user.phone = phone;
        user.status = 1;
        user.deleted = 0;
        userMapper.insert(user);
        SysRole role = roleMapper.selectOne(new QueryWrapper<SysRole>().eq("code", roleCode));
        if (role == null) {
            throw new BusinessException("系统角色未初始化：" + roleCode);
        }
        SysUserRole relation = new SysUserRole();
        relation.userId = user.id;
        relation.roleId = role.id;
        userRoleMapper.insert(relation);
        return user;
    }

    private void log(String module, String operationType, String objectType, Object objectId,
                     String result, String detail) {
        OperationLog log = new OperationLog();
        log.operatorId = CurrentUser.required().userId();
        log.module = module;
        log.operationType = operationType;
        log.objectType = objectType;
        log.objectId = String.valueOf(objectId);
        log.result = result;
        log.detail = detail;
        operationLogMapper.insert(log);
    }

    /** 归一化分页参数：page 下限 1，size 限制在 1..100，避免非法分页参数直接透传给数据库。 */
    private static <T> Page<T> pageOf(long page, long size) {
        return new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100));
    }

    /** 取单个账号的登录名，供 GuideView.username 使用。 */
    private String usernameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = userMapper.selectById(userId);
        return user == null ? null : user.username;
    }

    /** 批量取登录名，避免逐行查询造成 N+1。 */
    private Map<Long, String> usernamesOf(List<Long> userIds) {
        List<Long> ids = distinctIds(userIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(user -> user.id, user -> user.username, (a, b) -> a));
    }

    /** 批量取显示名（昵称优先，缺失时退回登录名），供 OperationLogView.operatorName 使用。 */
    private Map<Long, String> displayNamesOf(List<Long> userIds) {
        List<Long> ids = distinctIds(userIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(user -> user.id, AdminController::displayName, (a, b) -> a));
    }

    private static String displayName(SysUser user) {
        return user.nickname == null || user.nickname.isBlank() ? user.username : user.nickname;
    }

    private static List<Long> distinctIds(List<Long> ids) {
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    /** 对给定列做 OR 模糊匹配；keyword 为空时不追加任何条件。 */
    private static <T> void appendKeyword(QueryWrapper<T> query, String keyword, String... columns) {
        if (keyword == null || keyword.isBlank() || columns == null || columns.length == 0) {
            return;
        }
        String kw = keyword.trim();
        query.and(w -> {
            for (int i = 0; i < columns.length; i++) {
                if (i == 0) {
                    w.like(columns[i], kw);
                } else {
                    w.or().like(columns[i], kw);
                }
            }
        });
    }
}
