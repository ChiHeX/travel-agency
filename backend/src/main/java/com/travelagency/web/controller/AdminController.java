package com.travelagency.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.auth.dto.UserView;
import com.travelagency.auth.service.AuthService;
import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.enums.AccountStatus;
import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.enums.RefundStatus;
import com.travelagency.common.enums.RoleCode;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.domain.dto.AccountStatusUpdateRequest;
import com.travelagency.domain.dto.DepartureView;
import com.travelagency.domain.dto.GuideAccountRequest;
import com.travelagency.domain.dto.GuideView;
import com.travelagency.domain.dto.GuideUpdateRequest;
import com.travelagency.domain.dto.OperationLogView;
import com.travelagency.domain.dto.OrderDetailResponse;
import com.travelagency.domain.dto.OrderSummaryView;
import com.travelagency.domain.dto.OrderView;
import com.travelagency.domain.dto.RefundDecisionRequest;
import com.travelagency.domain.dto.RefundView;
import com.travelagency.domain.dto.ReviewStatusUpdateRequest;
import com.travelagency.domain.dto.ReviewView;
import com.travelagency.domain.dto.StaffAccountRequest;
import com.travelagency.domain.dto.StaffUpdateRequest;
import com.travelagency.domain.dto.StaffView;
import com.travelagency.domain.dto.StatusRequest;
import com.travelagency.domain.entity.Attraction;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.Hotel;
import com.travelagency.domain.entity.OperationLog;
import com.travelagency.domain.entity.Refund;
import com.travelagency.domain.entity.Staff;
import com.travelagency.domain.entity.SysRole;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.SysUserRole;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.mapper.AttractionMapper;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.HotelMapper;
import com.travelagency.domain.mapper.OperationLogMapper;
import com.travelagency.domain.mapper.RefundMapper;
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
import org.springframework.dao.DuplicateKeyException;
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
import java.util.Set;
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

    // 线路与行程管理端点已迁移到 AdminRouteController：此前这里的实现与契约不一致
    // （返回实体、状态码不是 201/204、行程缺少 items/hotelName、删除不级联），
    // 迁移后 AdminController 只保留其它后台模块的接口。

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

    /**
     * 确认已支付订单报名。
     *
     * <p>契约的 200 响应是 OrderEnvelope（data 为确认后的订单），此前返回 void 导致 data 为 null。</p>
     */
    @PostMapping("/orders/{orderNo}/confirm")
    public ApiResponse<OrderView> confirmOrder(@PathVariable String orderNo) {
        OrderView order = orderService.confirm(orderNo, CurrentUser.required().userId());
        log("订单", "CONFIRM", "ORDER", orderNo, "SUCCESS", "确认报名");
        return ApiResponse.ok(order);
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

    /**
     * 拒绝退款申请。
     *
     * <p>契约的 200 响应是 RefundEnvelope（data 为退款对象），此前返回 void 导致 data 为 null。</p>
     */
    @PostMapping("/refunds/{id}/reject")
    public ApiResponse<RefundView> rejectRefund(
            @PathVariable Long id, @Valid @RequestBody RefundDecisionRequest request) {
        RefundView refund = orderService.rejectRefund(id, request.comment(), CurrentUser.required().userId());
        log("退款", "REJECT", "REFUND", id, "SUCCESS", request.comment());
        return ApiResponse.ok(refund);
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

    /**
     * 后台用户分页查询，对齐契约 GET /admin/users（UserPageEnvelope）。
     *
     * <p>此前直出 {@code AdminUserView}：{@code status} 被序列化成整数 {@code 1/0}、
     * 契约 required 的 {@code roles} 整个缺失、头像字段名还是 {@code avatar}（契约是 {@code avatarUrl}）。
     * 前端 {@code AdminUsersView} 用 {@code row.status === 'ACTIVE'} 判断状态，整数永远不相等，
     * 于是所有账号都被渲染成「已冻结」。这里改为复用 {@link UserView}（与契约 User 一一对应）。</p>
     *
     * <p>契约在 {@code page}/{@code size} 之外还声明了可选的 {@code keyword} 与 {@code status}；
     * 早先的实现只接前两个，多传的筛选参数被 Spring 直接丢掉、既不报错也不生效（静默失效），
     * 与本文件 {@code /admin/guides}、{@code /admin/attractions} 等同族端点的口径也不一致。
     * {@code status} 走 {@link #statusValue(String)} 还原成 {@code sys_user.status} 的 1/0；
     * 契约枚举之外的取值按请求校验规则回 422，不再静默落到「停用」。</p>
     */
    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResponse<UserView>> users(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        QueryWrapper<SysUser> query = new QueryWrapper<SysUser>().eq("deleted", 0);
        if (status != null && !status.isBlank()) {
            query.eq("status", statusValue(status));
        }
        appendKeyword(query, keyword, "username", "nickname", "real_name", "phone");
        Page<SysUser> result = userMapper.selectPage(pageOf(page, size), query.orderByDesc("created_at"));
        Map<Long, Set<String>> roles = authService.rolesOfAll(
                result.getRecords().stream().map(user -> user.id).toList());
        List<UserView> records = result.getRecords().stream()
                .map(user -> authService.toView(user, roles.getOrDefault(user.id, Set.of(RoleCode.USER))))
                .toList();
        return ApiResponse.ok(new PageResponse<>(records, (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages()));
    }

    /**
     * 后台用户详情，对齐契约 GET /admin/users/{userId}（UserEnvelope，不存在或已删除 → 404）。
     *
     * <p>此前只有列表映射，前端 {@code adminApi.user(userId)} 调用时命中缺失的 GET 映射而 404。</p>
     */
    @GetMapping("/users/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<UserView> userDetail(@PathVariable Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || Integer.valueOf(1).equals(user.deleted)) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "用户不存在");
        }
        return ApiResponse.ok(authService.toView(user, authService.rolesFor(user.id)));
    }

    /**
     * 启用或停用用户账号，对齐契约 PATCH /admin/users/{userId}/status
     * （请求体 AccountStatusUpdateRequest，200 响应是 UserEnvelope）。
     *
     * <p>此前接的是自由文本 {@code StatusRequest} 且直接 {@code Integer.parseInt(request.status())}：
     * 契约与前端 {@code adminApi.updateUserStatus} 传的都是 {@code ACTIVE}/{@code DISABLED}，
     * 一调即抛 NumberFormatException 变成 500；而且响应体与契约的 UserEnvelope 不符（data 恒为 null）。
     * 现在枚举值由 DTO 上的约束挡住（非法值 → 422），状态到 1/0 的转换只做一次，并把更新后的用户返回。
     * 目标账号不存在时不再静默成功（旧实现 update 0 行也回 200），统一按契约全局口径判 404。</p>
     */
    @PatchMapping("/users/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ApiResponse<UserView> updateUserStatus(@PathVariable Long id,
                                                 @Valid @RequestBody AccountStatusUpdateRequest request) {
        SysUser user = requireLockedUser(id);
        user.status = statusValue(request.status());
        userMapper.update(null, new UpdateWrapper<SysUser>().eq("id", id).set("status", user.status));
        log("用户", "STATUS", "USER", id, "SUCCESS", "账号状态变更为 " + request.status());
        return ApiResponse.ok(authService.toView(user, authService.rolesFor(user.id)));
    }

    /**
     * 后台工作人员分页查询，对齐契约 GET /admin/staff（StaffPageEnvelope）。
     *
     * <p>此前直出 {@link Staff} 实体：契约 required 的 {@code username}、{@code realName}、
     * {@code status} 都取自 {@code sys_user}，实体里一个都没有 —— 既缺必填字段，
     * 也违反「Controller 不得直接暴露 Entity」（docs/API.md §14）。</p>
     *
     * <p>契约在 {@code page}/{@code size} 之外还声明了可选的 {@code keyword} 与 {@code status}，
     * 早先只接前两个，多传的筛选参数被静默忽略。这里两个参数都<b>跨表</b>：
     * {@code username}/{@code realName}/{@code status} 在 {@code sys_user}，
     * {@code employeeNo}/{@code department}/{@code position} 在 {@code staff}。
     * 跨表条件交给 {@link #staffFilter(String, String)} 里的 {@code EXISTS} 子查询在库内完成，
     * 筛选与分页仍是同一条 SQL；{@code status} 与 {@code keyword} 同时给出时取交集。</p>
     */
    @GetMapping("/staff")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResponse<StaffView>> staff(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        Page<Staff> result = staffMapper.selectPage(pageOf(page, size),
                staffFilter(keyword, status).orderByDesc("created_at"));
        Map<Long, SysUser> accounts = accountsOf(result.getRecords().stream().map(staff -> staff.userId).toList());
        List<StaffView> items = result.getRecords().stream()
                .map(staff -> StaffView.from(staff, accounts.get(staff.userId)))
                .toList();
        return ApiResponse.ok(new PageResponse<>(items, (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages()));
    }

    /**
     * 创建工作人员账号，对齐契约 POST /admin/staff
     * （StaffCreateRequest → 201 + Location + StaffEnvelope，重复工号/账号 → 409，校验失败 → 422）。
     *
     * <p>此前用的是缺 {@code employeeNo} 的请求体（服务端自动生成 {@code "EMP" + user.id}），
     * 契约里冻结的请求体根本发不进来（多出的字段被全局 FAIL_ON_UNKNOWN_PROPERTIES 判 400），
     * 成功状态码也错用 200 且不带 Location。</p>
     *
     * <p>工号唯一性<b>先查再写</b>，而不是等唯一键报错再回滚：撞号时账号还没插库，
     * 不会出现「账号建好了但没有档案」的中间态。唯一键冲突仍留一道 catch 兜并发。</p>
     */
    @PostMapping("/staff")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<StaffView>> createStaff(@Valid @RequestBody StaffAccountRequest request) {
        requireEmployeeNoFree(request.employeeNo(), null);
        SysUser user = createAccount(request.username(), request.password(), request.realName(),
                request.phone(), RoleCode.STAFF);
        Staff staff = new Staff();
        staff.userId = user.id;
        staff.employeeNo = request.employeeNo();
        staff.department = request.department();
        staff.position = request.position();
        try {
            staffMapper.insert(staff);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(409, "RESOURCE_STATE_CONFLICT", "员工工号已存在");
        }
        Staff saved = staffMapper.selectById(staff.id);
        log("员工", "CREATE", "STAFF", saved.id, "SUCCESS", "创建工作人员");
        return ResponseEntity.created(URI.create("/api/admin/staff/" + saved.id))
                .body(ApiResponse.ok(StaffView.from(saved, user)));
    }

    /**
     * 修改工作人员资料，对齐契约 PUT /admin/staff/{staffId}（StaffUpdateRequest → StaffEnvelope）。
     *
     * <p>姓名与手机号落在 {@code sys_user}，工号/部门/岗位落在 {@code staff}，一次事务写完。</p>
     */
    @PutMapping("/staff/{staffId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ApiResponse<StaffView> updateStaff(@PathVariable Long staffId,
                                             @Valid @RequestBody StaffUpdateRequest request) {
        Staff staff = requireLockedStaff(staffId);
        SysUser account = requireStaffAccount(staff);
        requireEmployeeNoFree(request.employeeNo(), staffId);
        try {
            staffMapper.update(null, new UpdateWrapper<Staff>().eq("id", staffId)
                    .set("employee_no", request.employeeNo())
                    .set("department", request.department())
                    .set("position", request.position()));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(409, "RESOURCE_STATE_CONFLICT", "员工工号已存在");
        }
        UpdateWrapper<SysUser> accountUpdate = new UpdateWrapper<SysUser>().eq("id", account.id)
                .set("real_name", request.realName())
                .set("phone", request.phone());
        // 创建账号时昵称被初始化成姓名（见 createAccount）。只有当昵称还是那个初始值、
        // 或本来就为空时才跟着改，避免把用户自己在「个人资料」里改过的昵称覆盖掉。
        if (account.nickname == null || account.nickname.isBlank() || account.nickname.equals(account.realName)) {
            accountUpdate.set("nickname", request.realName());
        }
        userMapper.update(null, accountUpdate);
        log("员工", "UPDATE", "STAFF", staffId, "SUCCESS", "修改工作人员资料");
        return ApiResponse.ok(staffDetail(staffId));
    }

    /**
     * 启用或停用工作人员账号，对齐契约 PATCH /admin/staff/{staffId}/status（StaffEnvelope，失败 409）。
     *
     * <p>{@code staff} 表没有自己的状态列，账号可用性只落在 {@code sys_user.status}（1/0），
     * 所以这里只写 sys_user，并在返回的 {@link StaffView} 里把 1/0 还原成契约 AccountStatus。
     * 与 {@code GuideService#updateStatus} 同构：账号缺失或已删除 → 409，而不是让状态卡在半途。</p>
     */
    @PatchMapping("/staff/{staffId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ApiResponse<StaffView> updateStaffStatus(@PathVariable Long staffId,
                                                   @Valid @RequestBody AccountStatusUpdateRequest request) {
        Staff staff = requireLockedStaff(staffId);
        SysUser account = requireStaffAccount(staff);
        account.status = statusValue(request.status());
        userMapper.update(null, new UpdateWrapper<SysUser>().eq("id", account.id).set("status", account.status));
        log("员工", "STATUS", "STAFF", staffId, "SUCCESS", "账号状态变更为 " + request.status());
        return ApiResponse.ok(StaffView.from(staffMapper.selectById(staffId), account));
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

    /**
     * 契约 AccountStatus（{@code ACTIVE}/{@code DISABLED}）→ {@code sys_user.status}（1/0）。
     *
     * <p>枚举之外的取值必须<b>拒绝</b>，不能落进默认分支：早先写成
     * {@code ACTIVE.equals(status) ? 1 : 0}，于是任何非 ACTIVE 的非空值（拼错的 {@code DISABLE}、
     * 小写 {@code active}、别的枚举）都被静默当成 0 = 停用 —— 调用方以为在按状态筛选，
     * 实际拿到的却是另一批数据。契约把这个查询参数声明为 {@code AccountStatus} 枚举，
     * 按请求校验规则回 422，与 {@code AdminRouteService#page} 对 {@code RouteStatus} 的口径一致
     * （{@code GET /admin/routes?status=NOT_A_STATUS} → 422 {@code VALIDATION_ERROR}）。</p>
     *
     * <p>请求体路径（{@code AccountStatusUpdateRequest}）已由 {@code @Pattern} 挡在 422，走不到这里；
     * 缺省与空串由调用方判空后跳过，语义是「不过滤」。</p>
     */
    private static int statusValue(String status) {
        String value = status == null ? "" : status.trim();
        if (AccountStatus.ACTIVE.equals(value)) {
            return 1;
        }
        if (AccountStatus.DISABLED.equals(value)) {
            return 0;
        }
        throw new BusinessException(422, "VALIDATION_ERROR", "账号状态只能是 ACTIVE 或 DISABLED");
    }

    /**
     * 拼 {@code GET /admin/staff} 的筛选条件（契约的 {@code keyword} + {@code status}）。
     *
     * <p>两个参数都<b>跨表</b>：{@code status} 只在 {@code sys_user}，
     * {@code keyword} 则同时覆盖 {@code sys_user}（username/real_name/phone）与
     * {@code staff}（employee_no/department/position）。{@code QueryWrapper} 是单表的，
     * 所以跨表部分交给 {@code EXISTS} 子查询在库内完成：筛选与分页仍是同一条 SQL，
     * 不把命中的账号 id 拉回 Java 再拼 {@code IN (…)}（那会让 SQL 参数与代价随账号总数增长）。</p>
     *
     * <p><b>为什么 status 必须是独立的 AND 条件</b>：早先的实现先把命中的账号 id 查回 Java，
     * 再把它与 staff 自有列的 LIKE 放进<b>同一个 OR 组</b>：
     * {@code (employee_no LIKE ? OR department LIKE ? OR position LIKE ? OR user_id IN (?))}，
     * 而 status 只体现在 {@code user_id IN (?)} 那一支 ⇒ 只要部门（或工号、岗位）命中关键字，
     * 这个 OR 分支就绕过了状态：{@code status=ACTIVE&keyword=测试部} 会把<b>已停用</b>的员工也返回。
     * 现在 status 是独立的 {@code EXISTS}（AND 挂最外层），任何关键字分支都绕不过它。</p>
     *
     * <p>包级可见（非 private）：供同包单测 {@code AdminStaffFilterSqlTest} 直接断言生成的 SQL 形状 ——
     * 上面两点都是形状问题，真实请求的响应体看不出来，只有钉住 SQL 才能防止日后改回「先查 id 再 IN」。</p>
     */
    static QueryWrapper<Staff> staffFilter(String keyword, String status) {
        QueryWrapper<Staff> query = new QueryWrapper<>();
        boolean hasStatus = status != null && !status.isBlank();
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        if (!hasStatus && !hasKeyword) {
            return query;
        }
        if (hasStatus) {
            // 硬过滤：状态压在 staff.user_id 关联的账号上，关键字条件怎么拼都绕不过它。
            // {0} 是 MyBatis-Plus 的占位符，会绑成 JDBC 参数，不是字符串拼接。
            query.apply("EXISTS (SELECT 1 FROM sys_user u WHERE u.id = staff.user_id"
                    + " AND u.deleted = 0 AND u.status = {0})", statusValue(status));
        }
        if (hasKeyword) {
            String kw = keyword.trim();
            // 宽匹配：staff 自有列 OR 关联账号列，整体放进一个 and(...) 与上面的硬过滤取交集。
            query.and(w -> w
                    .like("employee_no", kw)
                    .or().like("department", kw)
                    .or().like("position", kw)
                    .or().apply("EXISTS (SELECT 1 FROM sys_user u WHERE u.id = staff.user_id"
                            + " AND u.deleted = 0"
                            + " AND (u.username LIKE {0} OR u.real_name LIKE {0} OR u.phone LIKE {0}))",
                            "%" + kw + "%"));
        }
        return query;
    }

    /** 锁行取用户：并发改状态时不拿旧快照去覆盖刚写入的结果。不存在或已软删 → 404。 */
    private SysUser requireLockedUser(Long id) {
        SysUser user = userMapper.selectOne(new QueryWrapper<SysUser>()
                .eq("id", id).eq("deleted", 0).last("FOR UPDATE"));
        if (user == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "用户不存在");
        }
        return user;
    }

    /** 锁行取工作人员档案，不存在 → 404。 */
    private Staff requireLockedStaff(Long id) {
        Staff staff = staffMapper.selectOne(new QueryWrapper<Staff>().eq("id", id).last("FOR UPDATE"));
        if (staff == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "工作人员不存在");
        }
        return staff;
    }

    /**
     * 校验员工工号未被占用。{@code staff.employee_no} 上有唯一键（uk_staff_employee_no），
     * 但先查一次能给出明确的 409 而不是把 {@code DataIntegrityViolationException} 变成 500，
     * 也让创建流程在插账号之前就失败，不留「有账号没档案」的中间态。
     *
     * @param selfId 修改场景传入自身 id（工号保持不变时不算冲突），创建场景传 {@code null}
     */
    private void requireEmployeeNoFree(String employeeNo, Long selfId) {
        Staff existing = staffMapper.selectOne(new QueryWrapper<Staff>().eq("employee_no", employeeNo));
        if (existing != null && !existing.id.equals(selfId)) {
            throw new BusinessException(409, "RESOURCE_STATE_CONFLICT", "员工工号已存在");
        }
    }

    /**
     * 取工作人员关联的有效账号。{@code staff.user_id} 有外键且非空，所以「查不到」只可能是账号被软删，
     * 此时既不能改资料也不能改状态（否则返回的视图缺契约 required 的字段）→ 409。
     * 与 {@code GuideService#updateStatus} 对「导游尚未关联有效账号」的处理同构。
     */
    private SysUser requireStaffAccount(Staff staff) {
        SysUser account = staff.userId == null ? null : userMapper.selectById(staff.userId);
        if (account == null || Integer.valueOf(1).equals(account.deleted)) {
            throw new BusinessException(409, "STAFF_ACCOUNT_CONFLICT", "工作人员尚未关联有效账号");
        }
        return account;
    }

    /** 回查单条工作人员及其账号，用于写操作后返回契约 Staff 视图。 */
    private StaffView staffDetail(Long staffId) {
        Staff staff = staffMapper.selectById(staffId);
        if (staff == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "工作人员不存在");
        }
        return StaffView.from(staff, staff.userId == null ? null : userMapper.selectById(staff.userId));
    }

    /** 批量取账号，避免工作人员列表逐行查询造成 N+1。 */
    private Map<Long, SysUser> accountsOf(List<Long> userIds) {
        List<Long> ids = distinctIds(userIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(user -> user.id, user -> user, (a, b) -> a));
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
