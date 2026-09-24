package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.alipay.AlipayGatewayClient;
import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.enums.PaymentStatus;
import com.travelagency.common.enums.RefundStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.exception.RefundPendingConfirmationException;
import com.travelagency.domain.dto.RefundView;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.Payment;
import com.travelagency.domain.entity.Refund;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.MessageMapper;
import com.travelagency.domain.mapper.OrderTravelerMapper;
import com.travelagency.domain.mapper.PaymentMapper;
import com.travelagency.domain.mapper.RefundMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.entity.Message;
import com.travelagency.domain.entity.OrderTraveler;
import com.travelagency.domain.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * 「出款结果未确认」这一状态的<b>事务语义</b>实测：首次审批拿到未确认之后，
 * 退款单必须<b>持久</b>停在 {@code PROCESSING}，拒绝被拦住，重试用同一请求号收敛成功。
 *
 * <p><b>为什么必须是非事务测试</b>：本类要证明的恰恰是「事务<b>提交</b>了」。如果类上加
 * {@code @Transactional}，测试自己的外层事务会把 service 的事务包进去，service 内层只是
 * 参与、并不真正提交；等到断言时读到的状态在<b>修复前后完全一样</b>，这条用例就变成了
 * 「看起来在断言状态、其实什么也没区分」的假证据。所以本类：
 * <ul>
 *   <li>不加 {@code @Transactional}，每次调用都是真实、独立、自动提交的事务；</li>
 *   <li>关键断言再额外走一条<b>全新 JDBC 连接</b>读库（见 {@link #statusOnFreshConnection}），
 *       确保读到的是别的事务已经提交的持久态，而不是当前会话内的未提交改动；</li>
 *   <li>自行造数据，并在 {@code @AfterEach} 按外键倒序逐条删除。</li>
 * </ul>
 *
 * <p><b>出款那一跳用 spy 替换</b>：退款是同步接口，走真实现会真的出网。spy 只在
 * {@code refund(...)} 上返回我们指定的结果，其余行为保持真实。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=refund-pending-confirmation-test-jwt-secret-32b")
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class RefundPendingConfirmationIntegrationTest {

    /** 只用于通过出款配置齐全性校验（网关地址 + APPID + 应用私钥三项），不是真实沙箱账号。 */
    private static final String TEST_APP_ID = "9021000168641134";
    private static final KeyPair APP_KEY_PAIR = keyPair();
    private static final String COMMENT = "同意退款";

    /**
     * 注入一份齐全的出款配置。配置不齐时 {@code processRefund} 会在出款闸门处抛
     * 409 {@code REFUND_NOT_CONFIGURED}，根本走不到出款那一跳 —— 那样测的是配置而不是本次要改的事务语义。
     */
    @DynamicPropertySource
    static void alipayCredentials(DynamicPropertyRegistry registry) {
        registry.add("app.integrations.alipay.app-id", () -> TEST_APP_ID);
        registry.add("app.integrations.alipay.app-private-key",
                () -> base64(APP_KEY_PAIR.getPrivate().getEncoded()));
    }

    @MockitoSpyBean AlipayGatewayClient alipayGatewayClient;

    @Autowired OrderService orderService;
    @Autowired DataSource dataSource;
    @Autowired TravelRouteMapper routes;
    @Autowired DepartureMapper departures;
    @Autowired GuideMapper guides;
    @Autowired SysUserMapper users;
    @Autowired TravelOrderMapper orders;
    @Autowired OrderTravelerMapper orderTravelers;
    @Autowired PaymentMapper payments;
    @Autowired RefundMapper refunds;
    @Autowired MessageMapper messages;

    private Long routeId;
    private Long departureId;
    private Long guideId;
    private Long guideUserId;
    private Long userId;
    private Long orderId;
    private Long refundId;

    /** 每次出款调用收到的 {@code out_request_no}，用来证明「重试用的还是同一个请求号」。 */
    private final List<String> requestedNos = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        TravelRoute route = new TravelRoute();
        route.name = "待确认退款回归线路";
        route.departureCity = "上海";
        route.destination = "云南";
        route.durationDays = 3;
        route.status = "PUBLISHED";
        route.ratingAvg = new BigDecimal("0.00");
        route.ratingCount = 0;
        route.validBookingCount = 0;
        route.deleted = 0;
        routes.insert(route);
        routeId = route.id;

        SysUser owner = newUser("rpu_");
        userId = owner.id;
        SysUser guideOwner = newUser("rpg_");
        guideUserId = guideOwner.id;

        Guide guide = new Guide();
        guide.userId = guideUserId;
        guide.name = "待确认退款测试导游";
        guide.phone = "13800000001";
        guide.status = "ACTIVE";
        guides.insert(guide);
        guideId = guide.id;

        Departure departure = new Departure();
        departure.routeId = routeId;
        departure.startDate = LocalDate.now().plusDays(30);
        departure.endDate = LocalDate.now().plusDays(33);
        departure.adultPrice = new BigDecimal("1800.00");
        departure.childPrice = new BigDecimal("1200.00");
        departure.maxPeople = 10;
        departure.reservedPeople = 0;
        // 订单已确认 ⇒ 占的是 confirmed_people（见 OrderService#releaseCapacity）。
        departure.confirmedPeople = 1;
        departure.guideId = guideId;
        departure.status = "OPEN";
        departure.version = 0;
        departures.insert(departure);
        departureId = departure.id;

        TravelOrder order = new TravelOrder();
        order.orderNo = "RP" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        order.userId = userId;
        order.routeId = routeId;
        order.departureId = departureId;
        order.contactName = "待确认游客";
        order.contactPhone = "13800000002";
        order.adultCount = 1;
        order.childCount = 0;
        order.adultUnitPrice = new BigDecimal("1800.00");
        order.childUnitPrice = new BigDecimal("1200.00");
        order.totalAmount = new BigDecimal("1800.00");
        order.status = OrderStatus.CONFIRMED;
        order.paymentStatus = PaymentStatus.PAID;
        order.paidAt = LocalDateTime.now().minusDays(1);
        order.confirmedAt = LocalDateTime.now().minusDays(1);
        orders.insert(order);
        orderId = order.id;

        Payment payment = new Payment();
        payment.orderId = orderId;
        payment.paymentNo = "RP-PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        payment.channel = "ALIPAY";
        payment.amount = new BigDecimal("1800.00");
        payment.status = PaymentStatus.PAID;
        payment.paidAt = LocalDateTime.now().minusDays(1);
        payments.insert(payment);

        Refund refund = new Refund();
        refund.orderId = orderId;
        refund.userId = userId;
        refund.amount = new BigDecimal("1800.00");
        refund.reason = "行程有变，申请退款";
        refund.originalOrderStatus = OrderStatus.CONFIRMED;
        refund.status = RefundStatus.APPLYING;
        refunds.insert(refund);
        refundId = refund.id;

        requestedNos.clear();
        // 首次审批：出款结果「未确认」；之后的每一次（重试确认）：确定成功。
        // 用 doAnswer(...).when(spy) 而不是 when(spy.refund(...)) —— 后者在打桩阶段就会真调一次。
        doAnswer(invocation -> {
            requestedNos.add(invocation.getArgument(1));
            return requestedNos.size() == 1
                    ? AlipayGatewayClient.RefundResult.unconfirmed("模拟：退款请求与随后的查询都没能确认结果")
                    : AlipayGatewayClient.RefundResult.succeeded(
                            "2027030122001400000000000002", invocation.getArgument(0));
        }).when(alipayGatewayClient)
                .refund(anyString(), anyString(), any(BigDecimal.class), anyString());
    }

    @AfterEach
    void tearDown() {
        // 按外键依赖倒序清理：订单子表 → 订单 → 站内信 → 团期 → 线路 → 导游 → 用户
        if (orderId != null) {
            orderTravelers.delete(new QueryWrapper<OrderTraveler>().eq("order_id", orderId));
            payments.delete(new QueryWrapper<Payment>().eq("order_id", orderId));
            refunds.delete(new QueryWrapper<Refund>().eq("order_id", orderId));
            orders.deleteById(orderId);
        }
        if (userId != null) {
            messages.delete(new QueryWrapper<Message>().eq("user_id", userId));
        }
        if (departureId != null) {
            departures.deleteById(departureId);
        }
        if (routeId != null) {
            routes.deleteById(routeId);
        }
        if (guideId != null) {
            guides.deleteById(guideId);
        }
        if (userId != null) {
            users.deleteById(userId);
        }
        if (guideUserId != null) {
            users.deleteById(guideUserId);
        }
    }

    @Test
    @DisplayName("首次出款未确认 → 新事务读到持久 PROCESSING → 拒绝被拦 → 同一请求号重试成功")
    void unconfirmedPayoutKeepsRefundInProcessingUntilRetryConfirms() throws Exception {
        // ---------- ① 首次审批：出款结果未确认 ----------
        RefundPendingConfirmationException unconfirmed = assertThrows(
                RefundPendingConfirmationException.class,
                () -> orderService.approveRefund(refundId, COMMENT, userId));
        assertEquals(503, unconfirmed.getStatus());
        assertEquals("REFUND_RESULT_UNCONFIRMED", unconfirmed.getCode());
        assertEquals(1, requestedNos.size());
        assertEquals("RF" + refundId, requestedNos.get(0), "出款请求号必须由退款单 id 派生");

        // ---------- ② 另一条连接（新事务）读到的是已提交的 PROCESSING ----------
        // 若 noRollbackFor 没生效（事务回滚），这里读到的会是 APPLYING —— 本用例的核心断言。
        assertEquals(RefundStatus.PROCESSING, statusOnFreshConnection(refundId),
                "「结果未确认」必须让退款单持久停在 PROCESSING，而不是回滚成 APPLYING");
        Refund persisted = refunds.selectById(refundId);
        assertEquals(RefundStatus.PROCESSING, persisted.status);
        assertEquals(userId, persisted.reviewedBy, "审核留痕必须一起提交");
        assertNotNull(persisted.reviewedAt);

        // 未确认时「不可回退的对外事实」一处都不能发生：订单、支付单、名额全都不动。
        assertEquals(OrderStatus.CONFIRMED, orders.selectById(orderId).status);
        assertEquals(PaymentStatus.PAID, orders.selectById(orderId).paymentStatus);
        assertEquals(PaymentStatus.PAID, paymentOf(orderId).status);
        assertEquals(1, confirmedPeople(), "出款没确认之前不得释放名额");
        assertEquals(1, requestedNos.size(), "尚未重试，出款只调用过一次");

        // ---------- ③ 拒绝必须被拦住（409 同码），且订单不动 ----------
        BusinessException rejectBlocked = assertThrows(BusinessException.class,
                () -> orderService.rejectRefund(refundId, "申请原因需要进一步核实", userId));
        assertEquals(409, rejectBlocked.getStatus());
        assertEquals("REFUND_RESULT_UNCONFIRMED", rejectBlocked.getCode());
        assertEquals(RefundStatus.PROCESSING, refunds.selectById(refundId).status, "被拦的拒绝不得改动退款单");
        assertEquals(RefundStatus.PROCESSING, statusOnFreshConnection(refundId));
        assertEquals(OrderStatus.CONFIRMED, orders.selectById(orderId).status, "被拦的拒绝不得恢复订单状态");

        // ---------- ④ 重试确认：同一请求号，这次拿到确定成功 ----------
        RefundView confirmed = orderService.approveRefund(refundId, "重试确认", userId);
        assertEquals(RefundStatus.REFUNDED, confirmed.status());
        assertEquals(RefundStatus.REFUNDED, statusOnFreshConnection(refundId));

        // 两次出款用的是同一个 out_request_no ⇒ 支付宝按它幂等，重试不会重复出款。
        assertEquals(2, requestedNos.size());
        assertEquals(requestedNos.get(0), requestedNos.get(1), "重试必须复用同一出款请求号");
        assertEquals("RF" + refundId, requestedNos.get(1));

        // 到这里钱确定退出去了，才开始落状态与释放名额（且只释放一次：1 → 0，不会变负）。
        assertEquals(OrderStatus.REFUNDED, orders.selectById(orderId).status);
        assertEquals(PaymentStatus.REFUNDED, orders.selectById(orderId).paymentStatus);
        assertEquals(PaymentStatus.REFUNDED, paymentOf(orderId).status);
        assertEquals(0, confirmedPeople());

        // ---------- ⑤ 收敛后的单子再审批必须被拒，名额不会被重复释放 ----------
        BusinessException alreadyDone = assertThrows(BusinessException.class,
                () -> orderService.approveRefund(refundId, "再审一次", userId));
        assertEquals(409, alreadyDone.getStatus());
        assertEquals(2, requestedNos.size(), "已 REFUNDED 的单子不得再触发出款");
        assertEquals(0, confirmedPeople(), "名额只能释放一次");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 用一条<b>全新</b>的 JDBC 连接读退款单状态。
     *
     * <p>本类没有 {@code @Transactional}，因此这条连接与刚刚那个 service 事务毫无关系；
     * 只有上一个事务真的提交了（{@code noRollbackFor} 生效），这里才可能读到 {@code PROCESSING}。
     * 这正是「持久态」与「只是一次未提交的会话内改动」之间的分界。</p>
     */
    private String statusOnFreshConnection(Long id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT status FROM refund WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new AssertionError("退款单 " + id + " 不存在");
                }
                return rs.getString(1);
            }
        }
    }

    private int confirmedPeople() {
        Departure departure = departures.selectById(departureId);
        return departure.confirmedPeople == null ? 0 : departure.confirmedPeople;
    }

    private Payment paymentOf(Long id) {
        return payments.selectOne(new QueryWrapper<Payment>().eq("order_id", id));
    }

    private SysUser newUser(String prefix) {
        SysUser user = new SysUser();
        // username 是 VARCHAR(32)：前缀（4）+ 20 位随机后缀，留足余量。
        user.username = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        user.nickname = "待确认退款测试";
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        users.insert(user);
        return user;
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String base64(byte[] value) {
        return java.util.Base64.getEncoder().encodeToString(value);
    }
}
