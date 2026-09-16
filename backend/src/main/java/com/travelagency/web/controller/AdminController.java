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
import com.travelagency.domain.dto.GuideAccountRequest;
import com.travelagency.domain.dto.OrderDetailResponse;
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
import com.travelagency.domain.service.RouteService;
import jakarta.validation.Valid;
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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            AuthService authService) {
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

    @GetMapping("/departures")
    public ApiResponse<List<Departure>> departures(
            @RequestParam(required = false) Long routeId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(departureService.list(routeId, status));
    }

    @PostMapping("/departures")
    public ApiResponse<Departure> createDeparture(@RequestBody Departure departure) {
        Departure saved = departureService.save(departure);
        log("团期", "CREATE", "DEPARTURE", saved.id, "SUCCESS", "创建团期");
        return ApiResponse.ok(saved);
    }

    @PutMapping("/departures/{id}")
    public ApiResponse<Departure> updateDeparture(@PathVariable Long id, @RequestBody Departure departure) {
        departure.id = id;
        Departure saved = departureService.save(departure);
        log("团期", "UPDATE", "DEPARTURE", id, "SUCCESS", "编辑团期");
        return ApiResponse.ok(saved);
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

    @GetMapping("/attractions")
    public ApiResponse<List<Attraction>> attractions() {
        return ApiResponse.ok(attractionMapper.selectList(new QueryWrapper<Attraction>().orderByDesc("created_at")));
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

    @GetMapping("/hotels")
    public ApiResponse<List<Hotel>> hotels() {
        return ApiResponse.ok(hotelMapper.selectList(new QueryWrapper<Hotel>().orderByDesc("created_at")));
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

    @GetMapping("/guides")
    public ApiResponse<List<Guide>> guides() {
        return ApiResponse.ok(guideMapper.selectList(new QueryWrapper<Guide>().orderByDesc("created_at")));
    }

    @PostMapping("/guides")
    public ApiResponse<Guide> createGuide(@RequestBody Guide guide) {
        if (guide.status == null) {
            guide.status = "ACTIVE";
        }
        guideMapper.insert(guide);
        return ApiResponse.ok(guide);
    }

    @PutMapping("/guides/{id}")
    public ApiResponse<Guide> updateGuide(@PathVariable Long id, @RequestBody Guide guide) {
        guide.id = id;
        guideMapper.updateById(guide);
        return ApiResponse.ok(guide);
    }

    @PatchMapping("/guides/{id}/status")
    public ApiResponse<Void> updateGuideStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        guideMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Guide>()
                .eq("id", id).set("status", request.status()));
        return ApiResponse.ok();
    }

    @GetMapping("/orders")
    public ApiResponse<PageResponse<TravelOrder>> orders(
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
        return ApiResponse.ok(PageResponse.from(orderMapper.selectPage(
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

    @PostMapping("/refunds/{id}/approve")
    public ApiResponse<Void> approveRefund(
            @PathVariable Long id, @RequestBody(required = false) RefundDecisionRequest request) {
        String comment = request == null ? null : request.comment();
        orderService.approveRefund(id, comment, CurrentUser.required().userId());
        log("退款", "APPROVE", "REFUND", id, "SUCCESS", comment);
        return ApiResponse.ok();
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

    @PostMapping("/guides/account")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ApiResponse<Guide> createGuideAccount(@Valid @RequestBody GuideAccountRequest request) {
        SysUser user = createAccount(request.username(), request.password(), request.name(), request.phone(), RoleCode.GUIDE);
        Guide guide = new Guide();
        guide.userId = user.id;
        guide.name = request.name();
        guide.phone = request.phone();
        guide.intro = request.intro();
        guide.status = "ACTIVE";
        guideMapper.insert(guide);
        return ApiResponse.ok(guide);
    }

    @GetMapping("/logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<OperationLog>> logs() {
        return ApiResponse.ok(operationLogMapper.selectList(new QueryWrapper<OperationLog>().orderByDesc("created_at").last("LIMIT 200")));
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
}
