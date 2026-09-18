package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.enums.DepartureStatus;
import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.enums.PaymentStatus;
import com.travelagency.common.enums.RefundStatus;
import com.travelagency.common.enums.RouteStatus;
import com.travelagency.common.enums.TravelerType;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.security.UserPrincipal;
import com.travelagency.domain.dto.CreateOrderRequest;
import com.travelagency.domain.dto.DepartureView;
import com.travelagency.domain.dto.OrderDetailResponse;
import com.travelagency.domain.dto.OrderSummaryView;
import com.travelagency.domain.dto.OrderTravelerView;
import com.travelagency.domain.dto.OrderView;
import com.travelagency.domain.dto.PaymentStartResponse;
import com.travelagency.domain.dto.PaymentView;
import com.travelagency.domain.dto.RefundRequest;
import com.travelagency.domain.dto.RefundView;
import com.travelagency.domain.dto.ReviewRequest;
import com.travelagency.domain.dto.ReviewView;
import com.travelagency.domain.dto.RouteSummaryView;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.IdempotencyRecord;
import com.travelagency.domain.entity.Message;
import com.travelagency.domain.entity.OrderTraveler;
import com.travelagency.domain.entity.Payment;
import com.travelagency.domain.entity.Refund;
import com.travelagency.domain.entity.Review;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.entity.Traveler;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.IdempotencyRecordMapper;
import com.travelagency.domain.mapper.MessageMapper;
import com.travelagency.domain.mapper.OrderTravelerMapper;
import com.travelagency.domain.mapper.PaymentMapper;
import com.travelagency.domain.mapper.RefundMapper;
import com.travelagency.domain.mapper.ReviewMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import com.travelagency.domain.mapper.TravelerMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final TravelOrderMapper orderMapper;
    private final DepartureMapper departureMapper;
    private final TravelRouteMapper routeMapper;
    private final GuideMapper guideMapper;
    private final OrderTravelerMapper orderTravelerMapper;
    private final PaymentMapper paymentMapper;
    private final RefundMapper refundMapper;
    private final ReviewMapper reviewMapper;
    private final MessageMapper messageMapper;
    private final SysUserMapper sysUserMapper;
    private final IdempotencyRecordMapper idempotencyRecordMapper;
    private final TravelerMapper travelerMapper;

    /** 下单动作的幂等作用域，与 idempotency_record.scope 对应。 */
    private static final String SCOPE_CREATE_ORDER = "CREATE_ORDER";
    /** 申请退款动作的幂等作用域。 */
    private static final String SCOPE_APPLY_REFUND = "APPLY_REFUND";

    @Value("${app.integrations.alipay.gateway-url:https://openapi-sandbox.dl.alipaydev.com/gateway.do}")
    private String alipayGatewayUrl;

    public OrderService(
            TravelOrderMapper orderMapper,
            DepartureMapper departureMapper,
            TravelRouteMapper routeMapper,
            GuideMapper guideMapper,
            OrderTravelerMapper orderTravelerMapper,
            PaymentMapper paymentMapper,
            RefundMapper refundMapper,
            ReviewMapper reviewMapper,
            MessageMapper messageMapper,
            SysUserMapper sysUserMapper,
            IdempotencyRecordMapper idempotencyRecordMapper,
            TravelerMapper travelerMapper) {
        this.orderMapper = orderMapper;
        this.departureMapper = departureMapper;
        this.routeMapper = routeMapper;
        this.guideMapper = guideMapper;
        this.orderTravelerMapper = orderTravelerMapper;
        this.paymentMapper = paymentMapper;
        this.refundMapper = refundMapper;
        this.reviewMapper = reviewMapper;
        this.messageMapper = messageMapper;
        this.sysUserMapper = sysUserMapper;
        this.idempotencyRecordMapper = idempotencyRecordMapper;
        this.travelerMapper = travelerMapper;
    }

    @Transactional
    public OrderView create(Long userId, CreateOrderRequest request) {
        return create(userId, request, null);
    }

    /**
     * 下单，支持契约要求的 Idempotency-Key 请求头。
     *
     * <p>同一用户携带同一幂等键重复提交时只会真正下单一次：首次请求先抢占幂等记录，业务成功后回填订单号；
     * 后续重放请求读到同一记录后直接返回首次生成的订单，不会重复占用团期名额、也不会产生第二张支付单。</p>
     */
    @Transactional
    public OrderView create(Long userId, CreateOrderRequest request, String idempotencyKey) {
        boolean idempotent = idempotencyKey != null && !idempotencyKey.isBlank();
        if (idempotent) {
            IdempotencyRecord replay = claimIdempotencyKey(userId, SCOPE_CREATE_ORDER, idempotencyKey);
            if (replay != null) {
                TravelOrder existing = orderMapper.selectOne(new QueryWrapper<TravelOrder>()
                        .eq("order_no", replay.resourceNo));
                if (existing == null) {
                    throw new BusinessException(409, "IDEMPOTENT_REQUEST_IN_PROGRESS",
                            "相同幂等键的请求正在处理中，请稍后重试");
                }
                return loadOrderView(existing);
            }
        }

        int participantCount = request.adultCount() + request.childCount();
        if (participantCount <= 0) {
            throw new BusinessException(422, "VALIDATION_ERROR", "至少选择一位成人或儿童");
        }
        if (request.travelers().size() != participantCount) {
            throw new BusinessException(422, "VALIDATION_ERROR", "出行人数量必须与成人和儿童人数一致");
        }
        // 出行人类型要么全部显式指定，要么全部不指定走下标兜底推断。
        // 部分指定会让快照类型变得不确定，此时直接拒绝，而不是静默按位置猜错。
        List<String> explicitTypes = request.travelers().stream()
                .map(CreateOrderRequest.TravelerSnapshotRequest::travelerType)
                .toList();
        boolean anyExplicit = explicitTypes.stream().anyMatch(type -> type != null && !type.isBlank());
        boolean allExplicit = explicitTypes.stream().allMatch(type -> type != null && !type.isBlank());
        if (anyExplicit && !allExplicit) {
            throw new BusinessException(422, "VALIDATION_ERROR", "出行人类型需要全部指定或全部不指定");
        }
        if (allExplicit) {
            long adults = explicitTypes.stream().filter(TravelerType.ADULT::equals).count();
            if (adults != request.adultCount()) {
                throw new BusinessException(422, "VALIDATION_ERROR", "标记为成人的出行人数量与成人人数不一致");
            }
        }
        validateSourceTravelers(userId, request.travelers());
        Departure departure = departureMapper.selectById(request.departureId());
        if (departure == null || !DepartureStatus.OPEN.equals(departure.status)) {
            throw new BusinessException(409, "ORDER_STATE_CONFLICT", "团期已关闭或不存在");
        }
        int reserved = valueOrZero(departure.reservedPeople);
        int confirmed = valueOrZero(departure.confirmedPeople);
        int max = valueOrZero(departure.maxPeople);
        if (reserved + confirmed + participantCount > max) {
            throw new BusinessException(409, "DEPARTURE_CAPACITY_INSUFFICIENT", "团期剩余名额不足");
        }

        UpdateWrapper<Departure> reserve = new UpdateWrapper<>();
        reserve.eq("id", departure.id)
                .eq("status", DepartureStatus.OPEN)
                .apply("COALESCE(reserved_people, 0) + COALESCE(confirmed_people, 0) + {0} <= max_people", participantCount)
                .setSql("reserved_people = COALESCE(reserved_people, 0) + " + participantCount);
        if (departureMapper.update(null, reserve) != 1) {
            throw new BusinessException(409, "DEPARTURE_CAPACITY_INSUFFICIENT", "名额刚刚被其他用户占用，请重新选择团期");
        }

        TravelOrder order = new TravelOrder();
        order.orderNo = generateOrderNo();
        order.userId = userId;
        order.routeId = departure.routeId;
        order.departureId = departure.id;
        order.contactName = request.contactName();
        order.contactPhone = request.contactPhone();
        order.contactEmail = request.contactEmail();
        order.adultCount = request.adultCount();
        order.childCount = request.childCount();
        order.adultUnitPrice = defaultAmount(departure.adultPrice);
        order.childUnitPrice = defaultAmount(departure.childPrice);
        order.totalAmount = order.adultUnitPrice.multiply(BigDecimal.valueOf(request.adultCount()))
                .add(order.childUnitPrice.multiply(BigDecimal.valueOf(request.childCount())))
                .setScale(2, RoundingMode.HALF_UP);
        order.status = OrderStatus.WAIT_PAY;
        order.paymentStatus = PaymentStatus.UNPAID;
        order.remark = request.remark();
        orderMapper.insert(order);

        int adultCount = request.adultCount();
        int index = 0;
        for (CreateOrderRequest.TravelerSnapshotRequest requestTraveler : request.travelers()) {
            OrderTraveler snapshot = new OrderTraveler();
            snapshot.orderId = order.id;
            // 所有权已在创建订单前校验；这里只记录可选来源，个人信息仍保存为下单时快照。
            snapshot.travelerId = requestTraveler.sourceTravelerId();
            // 优先采用调用方显式指定的类型；未指定时按「前 adultCount 位为成人，其余为儿童」兜底推断。
            snapshot.travelerType = allExplicit
                    ? requestTraveler.travelerType()
                    : (index < adultCount ? TravelerType.ADULT : TravelerType.CHILD);
            snapshot.name = requestTraveler.name();
            snapshot.gender = requestTraveler.gender();
            snapshot.birthDate = requestTraveler.birthDate();
            snapshot.idType = requestTraveler.idType();
            snapshot.idNo = requestTraveler.idNo();
            snapshot.phone = requestTraveler.phone();
            snapshot.emergencyName = requestTraveler.emergencyName();
            snapshot.emergencyPhone = requestTraveler.emergencyPhone();
            orderTravelerMapper.insert(snapshot);
            index++;
        }

        Payment payment = new Payment();
        payment.orderId = order.id;
        payment.paymentNo = "PAY" + order.orderNo;
        payment.channel = "ALIPAY_SANDBOX";
        payment.amount = order.totalAmount;
        payment.status = PaymentStatus.UNPAID;
        paymentMapper.insert(payment);
        // 重新读取以带回 created_at / updated_at 等数据库默认值，契约 Order 要求这两个字段必填。
        TravelOrder saved = orderMapper.selectById(order.id);
        if (idempotent) {
            recordIdempotencyResource(userId, SCOPE_CREATE_ORDER, idempotencyKey, "ORDER", order.orderNo);
        }
        TravelOrder result = saved == null ? order : saved;
        return OrderView.from(result, routeMapper.selectById(order.routeId), departure);
    }

    /**
     * 校验请求引用的常用出行人全部存在且属于当前用户，避免跨用户关联污染订单快照。
     * 统一返回 404，避免通过错误差异枚举其他用户的资源。
     */
    private void validateSourceTravelers(
            Long userId, List<CreateOrderRequest.TravelerSnapshotRequest> travelers) {
        Set<Long> sourceTravelerIds = travelers.stream()
                .map(CreateOrderRequest.TravelerSnapshotRequest::sourceTravelerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (sourceTravelerIds.isEmpty()) {
            return;
        }
        Long ownedCount = travelerMapper.selectCount(new QueryWrapper<Traveler>()
                .in("id", sourceTravelerIds)
                .eq("user_id", userId));
        if (ownedCount == null || ownedCount != sourceTravelerIds.size()) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "常用出行人不存在");
        }
    }

    /**
     * 抢占幂等键。
     *
     * <p>返回 {@code null} 表示抢占成功、调用方应继续执行业务；返回已有记录表示这是重放请求。
     * 并发场景下唯一键 (user_id, scope, idem_key) 会阻塞后到的插入，等首个事务提交后再抛出
     * 重复键冲突，因此这里能可靠区分「重放」与「仍在处理中」。</p>
     */
    private IdempotencyRecord claimIdempotencyKey(Long userId, String scope, String idemKey) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.userId = userId;
        record.scope = scope;
        record.idemKey = idemKey;
        try {
            idempotencyRecordMapper.insert(record);
            return null;
        } catch (DuplicateKeyException conflict) {
            IdempotencyRecord existing = idempotencyRecordMapper.selectOne(new QueryWrapper<IdempotencyRecord>()
                    .eq("user_id", userId).eq("scope", scope).eq("idem_key", idemKey));
            if (existing == null || existing.resourceNo == null) {
                throw new BusinessException(409, "IDEMPOTENT_REQUEST_IN_PROGRESS",
                        "相同幂等键的请求正在处理中，请稍后重试");
            }
            return existing;
        }
    }

    /** 业务成功后把产生的单号回填到幂等记录，供重放请求返回同一结果。 */
    private void recordIdempotencyResource(Long userId, String scope, String idemKey, String type, String resourceNo) {
        idempotencyRecordMapper.update(null, new UpdateWrapper<IdempotencyRecord>()
                .eq("user_id", userId).eq("scope", scope).eq("idem_key", idemKey)
                .set("resource_type", type).set("resource_no", resourceNo));
    }

    /** 按订单实体装配契约 OrderView（补齐线路与团期）。 */
    private OrderView loadOrderView(TravelOrder order) {
        return OrderView.from(order, routeMapper.selectById(order.routeId),
                departureMapper.selectById(order.departureId));
    }

    /**
     * 当前用户订单分页查询，对齐契约 GET /orders（page/size + status）。
     */
    public PageResponse<OrderSummaryView> listMine(Long userId, String status, int page, int size) {
        QueryWrapper<TravelOrder> query = new QueryWrapper<TravelOrder>().eq("user_id", userId);
        if (status != null && !status.isBlank()) {
            query.eq("status", status);
        }
        query.orderByDesc("created_at");
        Page<TravelOrder> result = orderMapper.selectPage(new Page<>(normalizePage(page), normalizeSize(size)), query);
        return toSummaryPage(result);
    }

    /**
     * 把订单实体分页转成契约 OrderSummary 分页，一次性批量补齐 routeName / departureStartDate，避免 N+1 查询。
     */
    public PageResponse<OrderSummaryView> toSummaryPage(Page<TravelOrder> result) {
        List<TravelOrder> records = result.getRecords();
        Map<Long, TravelRoute> routes = batchRoutes(records);
        Map<Long, Departure> departures = batchDepartures(records);
        List<OrderSummaryView> items = records.stream()
                .map(order -> OrderSummaryView.from(order, routes.get(order.routeId), departures.get(order.departureId)))
                .toList();
        return new PageResponse<>(items, (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages());
    }

    public OrderDetailResponse detail(String orderNo, UserPrincipal requester) {
        TravelOrder order = findByNo(orderNo);
        boolean staff = requester.roles().stream().anyMatch(role ->
                "STAFF".equals(role) || "ADMIN".equals(role) || "ROLE_STAFF".equals(role) || "ROLE_ADMIN".equals(role));
        if (!staff && !order.userId.equals(requester.userId())) {
            throw new BusinessException(403, "ACCESS_DENIED", "无权查看该订单");
        }
        return toDetail(order);
    }

    @Transactional
    public PaymentStartResponse startPayment(String orderNo, Long userId) {
        TravelOrder order = findByNo(orderNo);
        ensureOwner(order, userId);
        if (!OrderStatus.WAIT_PAY.equals(order.status)) {
            throw new BusinessException(409, "ORDER_STATE_CONFLICT", "当前订单状态不允许支付");
        }
        Payment payment = paymentFor(order.id);
        payment.status = PaymentStatus.PENDING;
        paymentMapper.updateById(payment);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);
        return new PaymentStartResponse(order.orderNo, payment.paymentNo, payment.channel,
                order.totalAmount, alipayGatewayUrl, expiresAt);
    }

    /**
     * 取消待支付订单并释放名额。
     *
     * <p>契约 {@code POST /orders/{orderNo}/cancel} 的 200 响应是 {@code OrderEnvelope}，
     * 即 data 为取消后的订单对象；此前实现返回 void，实际响应 data 为 null。</p>
     */
    @Transactional
    public OrderView cancel(String orderNo, Long userId) {
        TravelOrder order = findByNo(orderNo);
        ensureOwner(order, userId);
        if (!OrderStatus.WAIT_PAY.equals(order.status)) {
            throw new BusinessException(409, "ORDER_STATE_CONFLICT", "仅待支付订单可以直接取消，已支付订单请申请退款");
        }
        order.status = OrderStatus.CANCELLED;
        order.cancelledAt = LocalDateTime.now();
        orderMapper.updateById(order);
        releaseReserved(order);
        return loadOrderView(order);
    }

    /**
     * This method is called only after the payment adapter has verified Alipay's signature.
     * It is idempotent so a repeated notification cannot advance the order twice.
     */
    @Transactional
    public void markPaid(String orderNo, String tradeNo) {
        markPaid(orderNo, tradeNo, null);
    }

    /**
     * 支付回调入账，可携带回调声明的支付金额用于核对。
     *
     * <p>幂等由「条件更新」这一原子闸门保证：只有把支付单从非 PAID 成功改成 PAID 的那一次回调
     * 才会继续推进订单并发送通知。并发重复投递时，后到的回调影响行数为 0，直接返回，
     * 因此不会重复发通知，也不会把订单推进两次。</p>
     *
     * @param callbackAmount 回调声明的金额；为 null 表示调用方已完成金额核对
     */
    @Transactional
    public void markPaid(String orderNo, String tradeNo, BigDecimal callbackAmount) {
        TravelOrder order = findByNo(orderNo);
        Payment payment = paymentFor(order.id);
        if (PaymentStatus.PAID.equals(payment.status)) {
            return;
        }
        if (!OrderStatus.WAIT_PAY.equals(order.status)) {
            throw new BusinessException(409, "ORDER_STATE_CONFLICT", "订单当前状态不接受支付回调");
        }
        if (callbackAmount != null && !amountEquals(callbackAmount, order.totalAmount)) {
            throw new BusinessException(409, "PAYMENT_AMOUNT_MISMATCH", "回调金额与订单应付金额不一致");
        }
        // 原子闸门：并发回调只有一个能把支付单从非 PAID 推进到 PAID
        int claimed = paymentMapper.update(null, new UpdateWrapper<Payment>()
                .eq("id", payment.id)
                .ne("status", PaymentStatus.PAID)
                .set("status", PaymentStatus.PAID)
                .set("third_party_trade_no", tradeNo)
                .set("paid_at", LocalDateTime.now()));
        if (claimed != 1) {
            // 已被并发回调抢先处理，本次属于重复投递，不再推进订单
            return;
        }
        payment.status = PaymentStatus.PAID;
        payment.thirdPartyTradeNo = tradeNo;
        payment.paidAt = LocalDateTime.now();
        paymentMapper.updateById(payment);

        order.paymentStatus = PaymentStatus.PAID;
        order.status = OrderStatus.PAID_WAIT_CONFIRM;
        order.paidAt = LocalDateTime.now();
        orderMapper.updateById(order);
        notify(order.userId, "支付成功", "订单 " + order.orderNo + " 已支付，等待旅行社确认报名。", "PAYMENT_SUCCESS");
    }

    /** 金额按数值比较，避免 "2500.0" 与 "2500.00" 因标度不同被误判为不一致。 */
    private static boolean amountEquals(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return false;
        }
        return left.compareTo(right) == 0;
    }

    /**
     * 确认已支付订单的报名，返回确认后的订单。
     *
     * <p>契约 {@code POST /admin/orders/{orderNo}/confirm} 的 200 响应是 {@code OrderEnvelope}，
     * 即 data 为确认后的订单对象；此前实现返回 void，实际响应 data 为 null 且类型不符
     * （与 {@code cancel}、{@code approveRefund} 同类的遗漏）。</p>
     */
    @Transactional
    public OrderView confirm(String orderNo, Long operatorId) {
        TravelOrder order = findByNo(orderNo);
        if (!OrderStatus.PAID_WAIT_CONFIRM.equals(order.status)) {
            throw new BusinessException(409, "ORDER_STATE_CONFLICT", "只有待确认订单可以审核");
        }
        Departure departure = departureMapper.selectById(order.departureId);
        if (departure == null || !DepartureStatus.OPEN.equals(departure.status)) {
            throw new BusinessException(409, "ORDER_STATE_CONFLICT", "团期已关闭，无法确认报名");
        }
        int participantCount = participants(order);
        UpdateWrapper<Departure> confirm = new UpdateWrapper<>();
        confirm.eq("id", departure.id)
                .eq("status", DepartureStatus.OPEN)
                .apply("COALESCE(confirmed_people, 0) + {0} <= max_people", participantCount)
                .setSql("reserved_people = GREATEST(COALESCE(reserved_people, 0) - " + participantCount + ", 0)")
                .setSql("confirmed_people = COALESCE(confirmed_people, 0) + " + participantCount);
        if (departureMapper.update(null, confirm) != 1) {
            throw new BusinessException(409, "DEPARTURE_CAPACITY_INSUFFICIENT", "团期名额已不足，暂不能确认报名");
        }
        order.status = OrderStatus.CONFIRMED;
        order.confirmedAt = LocalDateTime.now();
        orderMapper.updateById(order);
        routeMapper.update(null, new UpdateWrapper<TravelRoute>()
                .eq("id", order.routeId)
                .setSql("valid_booking_count = COALESCE(valid_booking_count, 0) + 1"));
        notify(order.userId, "报名已确认", "订单 " + order.orderNo + " 已通过旅行社审核。", "ORDER_CONFIRMED");
        // 回查线路与团期，返回契约 OrderEnvelope 要求的订单对象（不能是空 data）
        return loadOrderView(order);
    }

    @Transactional
    public RefundView applyRefund(String orderNo, Long userId, RefundRequest request) {
        return applyRefund(orderNo, userId, request, null);
    }

    /**
     * 申请退款，支持契约要求的 Idempotency-Key 请求头。
     *
     * <p>两道并发防线：① 幂等键保证同一用户的重复提交只生成一条申请；
     * ② 读取订单时加行锁（{@code SELECT ... FOR UPDATE}）把同一订单上的并发申请串行化，
     * 后到者能看到前一条 APPLYING 申请并收到 409，而不是各自插入一条“处理中”退款单。</p>
     */
    @Transactional
    public RefundView applyRefund(String orderNo, Long userId, RefundRequest request, String idempotencyKey) {
        boolean idempotent = idempotencyKey != null && !idempotencyKey.isBlank();
        if (idempotent) {
            IdempotencyRecord replay = claimIdempotencyKey(userId, SCOPE_APPLY_REFUND, idempotencyKey);
            if (replay != null) {
                Refund replayed = replay.resourceNo == null ? null
                        : refundMapper.selectById(Long.valueOf(replay.resourceNo));
                if (replayed == null) {
                    throw new BusinessException(409, "IDEMPOTENT_REQUEST_IN_PROGRESS",
                            "相同幂等键的请求正在处理中，请稍后重试");
                }
                TravelOrder replayOrder = orderMapper.selectById(replayed.orderId);
                return RefundView.from(replayed, replayOrder == null ? orderNo : replayOrder.orderNo);
            }
        }

        TravelOrder order = findByNoForUpdate(orderNo);
        ensureOwner(order, userId);
        if (!(OrderStatus.PAID_WAIT_CONFIRM.equals(order.status)
                || OrderStatus.CONFIRMED.equals(order.status))) {
            throw new BusinessException(409, "ORDER_STATE_CONFLICT", "当前订单状态不允许申请退款");
        }
        Refund existing = refundMapper.selectOne(new QueryWrapper<Refund>()
                .eq("order_id", order.id).in("status", RefundStatus.APPLYING, RefundStatus.PROCESSING));
        if (existing != null) {
            throw new BusinessException(409, "REFUND_ALREADY_APPLYING", "该订单已有处理中退款申请");
        }
        Refund refund = new Refund();
        refund.orderId = order.id;
        refund.userId = userId;
        refund.amount = order.totalAmount;
        refund.reason = request.reason();
        refund.originalOrderStatus = order.status;
        refund.status = RefundStatus.APPLYING;
        refundMapper.insert(refund);
        order.status = OrderStatus.REFUND_APPLYING;
        orderMapper.updateById(order);
        if (idempotent) {
            recordIdempotencyResource(userId, SCOPE_APPLY_REFUND, idempotencyKey, "REFUND", String.valueOf(refund.id));
        }
        // 回查以带回 created_at / updated_at，契约 Refund 要求这两个字段必填。
        Refund saved = refundMapper.selectById(refund.id);
        return RefundView.from(saved == null ? refund : saved, order.orderNo);
    }

    @Transactional
    public void processRefund(Long refundId, String action, String comment, Long reviewerId) {
        if (!"APPROVE".equalsIgnoreCase(action) && !"REJECT".equalsIgnoreCase(action)) {
            throw new BusinessException(422, "VALIDATION_ERROR", "审核动作只能是 APPROVE 或 REJECT");
        }
        Refund refund = refundMapper.selectById(refundId);
        if (refund == null || !RefundStatus.APPLYING.equals(refund.status)) {
            throw new BusinessException(409, "REFUND_STATE_CONFLICT", "退款申请不存在或已处理");
        }
        TravelOrder order = orderMapper.selectById(refund.orderId);
        if (order == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "关联订单不存在");
        }
        // 原子闸门：并发审批同一退款单时，只有一个请求能把 APPLYING 抢成 PROCESSING。
        // 否则两个请求都会通过上面的状态检查，导致名额被释放两次、线路有效报名数被回退两次。
        int claimed = refundMapper.update(null, new UpdateWrapper<Refund>()
                .eq("id", refundId)
                .eq("status", RefundStatus.APPLYING)
                .set("status", RefundStatus.PROCESSING)
                .set("reviewed_by", reviewerId)
                .set("reviewed_at", LocalDateTime.now())
                .set("review_comment", comment));
        if (claimed != 1) {
            throw new BusinessException(409, "REFUND_STATE_CONFLICT", "退款申请已被其他审核人处理");
        }
        refund.reviewedBy = reviewerId;
        refund.reviewedAt = LocalDateTime.now();
        refund.reviewComment = comment;
        if ("APPROVE".equalsIgnoreCase(action)) {
            refund.status = RefundStatus.PROCESSING;
            refundMapper.updateById(refund);
            releaseCapacity(order, refund.originalOrderStatus);
            refund.status = RefundStatus.REFUNDED;
            refundMapper.updateById(refund);
            order.status = OrderStatus.REFUNDED;
            order.paymentStatus = PaymentStatus.REFUNDED;
            orderMapper.updateById(order);
            if (OrderStatus.CONFIRMED.equals(refund.originalOrderStatus)
                    || OrderStatus.TRAVELLING.equals(refund.originalOrderStatus)) {
                routeMapper.update(null, new UpdateWrapper<TravelRoute>()
                        .eq("id", order.routeId)
                        .setSql("valid_booking_count = GREATEST(COALESCE(valid_booking_count, 0) - 1, 0)"));
            }
            Payment payment = paymentFor(order.id);
            payment.status = PaymentStatus.REFUNDED;
            paymentMapper.updateById(payment);
            notify(order.userId, "退款审核通过", "订单 " + order.orderNo + " 的退款已处理完成。", "REFUND_APPROVED");
        } else {
            // action 已在方法入口校验为 APPROVE / REJECT 之一，走到这里只能是 REJECT
            refund.status = RefundStatus.REJECTED;
            refundMapper.updateById(refund);
            order.status = refund.originalOrderStatus;
            orderMapper.updateById(order);
            notify(order.userId, "退款申请未通过", "订单 " + order.orderNo + " 的退款申请未通过。", "REFUND_REJECTED");
        }
    }

    @Transactional
    public ReviewView review(String orderNo, Long userId, ReviewRequest request) {
        TravelOrder order = findByNo(orderNo);
        ensureOwner(order, userId);
        if (!OrderStatus.COMPLETED.equals(order.status)) {
            throw new BusinessException(409, "ORDER_STATE_CONFLICT", "行程完成后才可以评价");
        }
        Review existing = reviewMapper.selectOne(new QueryWrapper<Review>().eq("order_id", order.id));
        if (existing != null) {
            throw new BusinessException(409, "REVIEW_ALREADY_EXISTS", "每个订单只能评价一次");
        }
        Review review = new Review();
        review.orderId = order.id;
        review.userId = userId;
        review.routeId = order.routeId;
        review.rating = request.rating();
        review.content = request.content();
        review.status = "VISIBLE";
        reviewMapper.insert(review);
        refreshRouteRating(order.routeId);
        // 回查以带回 created_at / updated_at，契约 Review 要求 createdAt 必填。
        Review saved = reviewMapper.selectById(review.id);
        return ReviewView.from(saved == null ? review : saved, order.orderNo, nicknameOf(userId));
    }

    // ------------------------------------------------------------------
    // 退款（后台）
    // ------------------------------------------------------------------

    /**
     * 后台退款分页查询，对齐契约 GET /admin/refunds。
     */
    public PageResponse<RefundView> listRefunds(String status, int page, int size) {
        QueryWrapper<Refund> query = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            query.eq("status", status);
        }
        query.orderByDesc("created_at");
        Page<Refund> result = refundMapper.selectPage(new Page<>(normalizePage(page), normalizeSize(size)), query);
        Map<Long, String> orderNos = orderNoMap(result.getRecords().stream().map(r -> r.orderId).toList());
        List<RefundView> items = result.getRecords().stream()
                .map(r -> RefundView.from(r, orderNos.get(r.orderId)))
                .toList();
        return new PageResponse<>(items, (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages());
    }

    /**
     * 后台退款详情，对齐契约 GET /admin/refunds/{refundId}。
     */
    public RefundView refundDetail(Long refundId) {
        Refund refund = refundMapper.selectById(refundId);
        if (refund == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "退款申请不存在");
        }
        TravelOrder order = orderMapper.selectById(refund.orderId);
        return RefundView.from(refund, order == null ? null : order.orderNo);
    }

    /**
     * 同意退款申请，返回审核后的退款记录。
     * 契约 {@code POST /admin/refunds/{refundId}/approve} 的 200 响应是 RefundEnvelope，
     * 即 data 为退款对象；此前实现返回 void，实际响应 data 为 null 且类型不符。
     */
    @Transactional
    public RefundView approveRefund(Long refundId, String comment, Long reviewerId) {
        processRefund(refundId, "APPROVE", comment, reviewerId);
        return refundDetail(refundId);
    }

    /**
     * 拒绝退款申请，返回审核后的退款记录。
     *
     * <p>契约 {@code POST /admin/refunds/{refundId}/reject} 的 200 响应是 {@code RefundEnvelope}，
     * 即 data 为退款对象；此前实现返回 void，实际响应 data 为 null 且类型不符。</p>
     */
    @Transactional
    public RefundView rejectRefund(Long refundId, String comment, Long reviewerId) {
        if (comment == null || comment.isBlank()) {
            throw new BusinessException(422, "VALIDATION_ERROR", "拒绝退款必须填写审核意见");
        }
        processRefund(refundId, "REJECT", comment, reviewerId);
        return refundDetail(refundId);
    }

    // ------------------------------------------------------------------
    // 评价（公开 + 后台）
    // ------------------------------------------------------------------

    /**
     * 公开线路可见评价分页，对齐契约 GET /routes/{routeId}/reviews。
     */
    public PageResponse<ReviewView> listRouteReviews(Long routeId, int page, int size) {
        // 线路不存在、已删除或未发布时不应对外暴露评价列表，契约要求返回 404。
        TravelRoute route = routeMapper.selectById(routeId);
        if (route == null || (route.deleted != null && route.deleted == 1)
                || !RouteStatus.PUBLISHED.equals(route.status)) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "线路不存在或未发布");
        }
        QueryWrapper<Review> query = new QueryWrapper<Review>()
                .eq("route_id", routeId).eq("status", "VISIBLE").orderByDesc("created_at");
        Page<Review> result = reviewMapper.selectPage(new Page<>(normalizePage(page), normalizeSize(size)), query);
        return toReviewPage(result);
    }

    /**
     * 线路管理详情使用的线路评价列表。
     *
     * <p>与 {@link #listRouteReviews} 的区别：后者是公开接口语义（仅 PUBLISHED 线路 + VISIBLE 评价，
     * 且必须分页）；后台查看线路时草稿和已下架线路也要能看到评价，并且需要看到被隐藏评价的状态，
     * 因此这里不做线路状态与评价可见性过滤。</p>
     */
    public List<ReviewView> routeReviewsForAdmin(Long routeId) {
        List<Review> reviews = reviewMapper.selectList(new QueryWrapper<Review>()
                .eq("route_id", routeId).orderByDesc("created_at"));
        Map<Long, String> orderNos = orderNoMap(reviews.stream().map(r -> r.orderId).toList());
        // 契约把 Review.userNickname 列为必填且不可空；用户未设置昵称时退回登录名，
        // 避免后台线路详情返回 null 导致契约校验失败。
        Map<Long, String> nicknames = displayNameMap(reviews.stream().map(r -> r.userId).toList());
        return reviews.stream()
                .map(r -> ReviewView.from(r, orderNos.get(r.orderId), nicknames.get(r.userId)))
                .toList();
    }

    /**
     * 后台评价分页查询，对齐契约 GET /admin/reviews。
     */
    public PageResponse<ReviewView> listReviews(String status, int page, int size) {
        QueryWrapper<Review> query = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            query.eq("status", status);
        }
        query.orderByDesc("created_at");
        Page<Review> result = reviewMapper.selectPage(new Page<>(normalizePage(page), normalizeSize(size)), query);
        return toReviewPage(result);
    }

    /**
     * 调整评价可见状态（VISIBLE / HIDDEN），并重算线路平均分。
     */
    @Transactional
    public ReviewView updateReviewStatus(Long reviewId, String status, Long operatorId) {
        if (!"VISIBLE".equals(status) && !"HIDDEN".equals(status)) {
            throw new BusinessException(422, "VALIDATION_ERROR", "评价状态只能是 VISIBLE 或 HIDDEN");
        }
        Review review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "评价不存在");
        }
        review.status = status;
        reviewMapper.updateById(review);
        refreshRouteRating(review.routeId);
        TravelOrder order = orderMapper.selectById(review.orderId);
        return ReviewView.from(review, order == null ? null : order.orderNo, nicknameOf(review.userId));
    }

    public TravelOrder findByNo(String orderNo) {
        TravelOrder order = orderMapper.selectOne(new QueryWrapper<TravelOrder>().eq("order_no", orderNo));
        if (order == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "订单不存在");
        }
        return order;
    }

    /**
     * 按订单号读取并加行锁（{@code SELECT ... FOR UPDATE}），把同一订单上的并发写操作串行化。
     * 必须在事务内调用；用于申请退款等「先检查后写入」的流程，避免检查与写入之间被并发插入。
     */
    private TravelOrder findByNoForUpdate(String orderNo) {
        TravelOrder order = orderMapper.selectOne(new QueryWrapper<TravelOrder>()
                .eq("order_no", orderNo).last("FOR UPDATE"));
        if (order == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "订单不存在");
        }
        return order;
    }

    public Payment paymentFor(Long orderId) {
        Payment payment = paymentMapper.selectOne(new QueryWrapper<Payment>().eq("order_id", orderId));
        if (payment == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "订单支付记录不存在");
        }
        return payment;
    }

    private OrderDetailResponse toDetail(TravelOrder order) {
        List<OrderTravelerView> travelers = orderTravelerMapper.selectList(new QueryWrapper<OrderTraveler>()
                        .eq("order_id", order.id).orderByAsc("id"))
                .stream()
                .map(OrderTravelerView::from)
                .toList();
        Payment payment = paymentFor(order.id);
        payment.callbackPayload = null;
        PaymentView paymentView = PaymentView.from(payment, order.orderNo);
        List<RefundView> refunds = refundMapper.selectList(new QueryWrapper<Refund>()
                        .eq("order_id", order.id).orderByDesc("created_at"))
                .stream()
                .map(r -> RefundView.from(r, order.orderNo))
                .toList();
        Review review = reviewMapper.selectOne(new QueryWrapper<Review>()
                .eq("order_id", order.id).orderByDesc("created_at").last("LIMIT 1"));
        ReviewView reviewView = review == null ? null
                : ReviewView.from(review, order.orderNo, nicknameOf(review.userId));
        TravelRoute route = routeMapper.selectById(order.routeId);
        Departure departure = departureMapper.selectById(order.departureId);
        String routeName = route == null ? null : route.name;
        return new OrderDetailResponse(OrderView.from(order, route, departure),
                RouteSummaryView.from(route),
                DepartureView.from(departure, routeName, guideNameOf(departure)),
                travelers, paymentView, refunds, reviewView);
    }

    /** 团期所属导游姓名，供 DepartureView 补齐契约必填的 guideName；无团期或无导游时返回 null。 */
    private String guideNameOf(Departure departure) {
        if (departure == null || departure.guideId == null) {
            return null;
        }
        Guide guide = guideMapper.selectById(departure.guideId);
        return guide == null ? null : guide.name;
    }

    private Map<Long, TravelRoute> batchRoutes(List<TravelOrder> orders) {
        List<Long> ids = orders.stream().map(order -> order.routeId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return routeMapper.selectList(new QueryWrapper<TravelRoute>().in("id", ids)).stream()
                .collect(Collectors.toMap(route -> route.id, route -> route, (a, b) -> a));
    }

    private Map<Long, Departure> batchDepartures(List<TravelOrder> orders) {
        List<Long> ids = orders.stream().map(order -> order.departureId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return departureMapper.selectList(new QueryWrapper<Departure>().in("id", ids)).stream()
                .collect(Collectors.toMap(departure -> departure.id, departure -> departure, (a, b) -> a));
    }

    private PageResponse<ReviewView> toReviewPage(Page<Review> result) {
        Map<Long, String> orderNos = orderNoMap(result.getRecords().stream().map(r -> r.orderId).toList());
        Map<Long, String> nicknames = nicknameMap(result.getRecords().stream().map(r -> r.userId).toList());
        List<ReviewView> items = result.getRecords().stream()
                .map(r -> ReviewView.from(r, orderNos.get(r.orderId), nicknames.get(r.userId)))
                .toList();
        return new PageResponse<>(items, (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages());
    }

    private Map<Long, String> orderNoMap(Collection<Long> orderIds) {
        List<Long> ids = distinctIds(orderIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return orderMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(o -> o.id, o -> o.orderNo, (a, b) -> a));
    }

    private Map<Long, String> nicknameMap(Collection<Long> userIds) {
        List<Long> ids = distinctIds(userIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return sysUserMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(u -> u.id, u -> u.nickname, (a, b) -> a));
    }

    /** 批量取展示名：昵称为空时退回登录名，保证契约要求的 userNickname 不为 null。 */
    private Map<Long, String> displayNameMap(Collection<Long> userIds) {
        List<Long> ids = distinctIds(userIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return sysUserMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(u -> u.id,
                        u -> u.nickname == null || u.nickname.isBlank() ? u.username : u.nickname,
                        (a, b) -> a));
    }

    private String nicknameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(userId);
        return user == null ? null : user.nickname;
    }

    private static List<Long> distinctIds(Collection<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    private void ensureOwner(TravelOrder order, Long userId) {
        if (!order.userId.equals(userId)) {
            throw new BusinessException(403, "ACCESS_DENIED", "无权操作该订单");
        }
    }

    private void releaseReserved(TravelOrder order) {
        releaseCapacity(order, OrderStatus.PAID_WAIT_CONFIRM);
    }

    private void releaseCapacity(TravelOrder order, String originalStatus) {
        int count = participants(order);
        if (OrderStatus.PAID_WAIT_CONFIRM.equals(originalStatus)) {
            departureMapper.update(null, new UpdateWrapper<Departure>()
                    .eq("id", order.departureId)
                    .setSql("reserved_people = GREATEST(COALESCE(reserved_people, 0) - " + count + ", 0)"));
        } else if (OrderStatus.CONFIRMED.equals(originalStatus) || OrderStatus.TRAVELLING.equals(originalStatus)) {
            departureMapper.update(null, new UpdateWrapper<Departure>()
                    .eq("id", order.departureId)
                    .setSql("confirmed_people = GREATEST(COALESCE(confirmed_people, 0) - " + count + ", 0)"));
        }
    }

    /**
     * 重算线路评分。
     *
     * <p>落库改用「单条 UPDATE + 子查询」，由数据库一次性算出统计值，取代原先的
     * 「读评价列表 → 本地求和 → updateById 写回」。后者在并发评价或并发调整可见状态时，
     * 两个事务会各自把基于旧快照算出的结果写回，导致 rating_count / rating_avg 长期偏离真实值。</p>
     */
    private void refreshRouteRating(Long routeId) {
        if (routeId == null) {
            return;
        }
        List<Review> reviews = reviewMapper.selectList(new QueryWrapper<Review>()
                .eq("route_id", routeId).eq("status", "VISIBLE"));
        TravelRoute route = routeMapper.selectById(routeId);
        if (route == null) {
            return;
        }
        // 同步内存对象，便于调用方与测试直接读取
        route.ratingCount = reviews.size();
        route.ratingAvg = reviews.isEmpty() ? BigDecimal.ZERO : reviews.stream()
                .map(review -> BigDecimal.valueOf(review.rating))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(reviews.size()), 2, RoundingMode.HALF_UP);
        routeMapper.update(null, new UpdateWrapper<TravelRoute>()
                .eq("id", routeId)
                .setSql("rating_count = (SELECT COUNT(*) FROM review WHERE route_id = " + routeId
                        + " AND status = 'VISIBLE')")
                .setSql("rating_avg = (SELECT COALESCE(ROUND(AVG(rating), 2), 0) FROM review WHERE route_id = "
                        + routeId + " AND status = 'VISIBLE')"));
    }

    private void notify(Long userId, String title, String content, String type) {
        Message message = new Message();
        message.userId = userId;
        message.title = title;
        message.content = content;
        message.type = type;
        message.readFlag = 0;
        messageMapper.insert(message);
    }

    private static int participants(TravelOrder order) {
        return valueOrZero(order.adultCount) + valueOrZero(order.childCount);
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static BigDecimal defaultAmount(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private static int normalizePage(int page) {
        return Math.max(page, 1);
    }

    private static int normalizeSize(int size) {
        if (size < 1) {
            return 20;
        }
        return Math.min(size, 100);
    }

    private static String generateOrderNo() {
        return "TA" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    public static String maskId(String idNo) {
        if (idNo == null || idNo.isBlank()) {
            return "";
        }
        if (idNo.length() <= 6) {
            return "******";
        }
        return idNo.substring(0, 3) + "***********" + idNo.substring(idNo.length() - 3);
    }
}
