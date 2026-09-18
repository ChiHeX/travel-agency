package com.travelagency.domain.service;

import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.ReviewRequest;
import com.travelagency.domain.dto.ReviewView;
import com.travelagency.domain.entity.Review;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成员 B 交易模块：行程完成后评价与唯一性约束。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceReviewTest {

    @Mock
    private TravelOrderMapper orderMapper;
    @Mock
    private DepartureMapper departureMapper;
    @Mock
    private TravelRouteMapper routeMapper;
    @Mock
    private GuideMapper guideMapper;
    @Mock
    private OrderTravelerMapper orderTravelerMapper;
    @Mock
    private PaymentMapper paymentMapper;
    @Mock
    private RefundMapper refundMapper;
    @Mock
    private ReviewMapper reviewMapper;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private IdempotencyRecordMapper idempotencyRecordMapper;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderMapper, departureMapper, routeMapper, guideMapper,
                orderTravelerMapper, paymentMapper, refundMapper, reviewMapper, messageMapper, sysUserMapper,
                idempotencyRecordMapper);
    }

    private static TravelOrder order(long id, String status) {
        TravelOrder o = new TravelOrder();
        o.id = id;
        o.orderNo = "TA20270301000001ABCD1234";
        o.userId = 9L;
        o.routeId = 100L;
        o.departureId = 7L;
        o.adultCount = 2;
        o.childCount = 0;
        o.totalAmount = new BigDecimal("2000.00");
        o.status = status;
        return o;
    }

    private static Review existingReview(long id, long orderId, long routeId, int rating) {
        Review r = new Review();
        r.id = id;
        r.orderId = orderId;
        r.userId = 9L;
        r.routeId = routeId;
        r.rating = rating;
        r.content = "很好";
        r.status = "VISIBLE";
        return r;
    }

    private static TravelRoute route(long id) {
        TravelRoute r = new TravelRoute();
        r.id = id;
        r.name = "彩云之南经典 6 日跟团游";
        r.ratingCount = 0;
        r.ratingAvg = BigDecimal.ZERO;
        return r;
    }

    // ---------------------------------------------------------------- 唯一性

    @Test
    @DisplayName("评价唯一性：同一订单第二次评价被拒绝")
    void rejectsSecondReviewForSameOrder() {
        TravelOrder o = order(55L, OrderStatus.COMPLETED);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(reviewMapper.selectOne(any())).thenReturn(existingReview(90L, 55L, 100L, 5));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.review(o.orderNo, 9L, new ReviewRequest(4, "再评一次")));

        assertEquals(409, ex.getStatus());
        assertEquals("REVIEW_ALREADY_EXISTS", ex.getCode());
        verify(reviewMapper, never()).insert(any(Review.class));
    }

    @Test
    @DisplayName("行程未完成时不能评价")
    void rejectsReviewWhenOrderNotCompleted() {
        TravelOrder o = order(56L, OrderStatus.CONFIRMED);
        when(orderMapper.selectOne(any())).thenReturn(o);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.review(o.orderNo, 9L, new ReviewRequest(5, "提前评价")));

        assertEquals(409, ex.getStatus());
        assertEquals("ORDER_STATE_CONFLICT", ex.getCode());
        verify(reviewMapper, never()).insert(any(Review.class));
    }

    @Test
    @DisplayName("非订单所有者不能评价")
    void rejectsReviewByNonOwner() {
        TravelOrder o = order(57L, OrderStatus.COMPLETED);
        when(orderMapper.selectOne(any())).thenReturn(o);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.review(o.orderNo, 999L, new ReviewRequest(5, "越权评价")));

        assertEquals(403, ex.getStatus());
        assertEquals("ACCESS_DENIED", ex.getCode());
    }

    // ---------------------------------------------------------------- 正常路径

    @Test
    @DisplayName("首次评价成功，并重算线路平均分与评价数")
    void createsReviewAndRefreshesRouteRating() {
        TravelOrder o = order(58L, OrderStatus.COMPLETED);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(reviewMapper.selectOne(any())).thenReturn(null);

        AtomicReference<Review> saved = new AtomicReference<>();
        when(reviewMapper.insert(any(Review.class))).thenAnswer(inv -> {
            Review r = inv.getArgument(0);
            r.id = 91L;
            saved.set(r);
            return 1;
        });
        when(reviewMapper.selectById(91L)).thenAnswer(inv -> saved.get());

        // 本条 + 一条历史评价 → 平均 (5 + 3) / 2 = 4.00
        when(reviewMapper.selectList(any()))
                .thenReturn(List.of(existingReview(90L, 40L, 100L, 5), existingReview(91L, 58L, 100L, 3)));

        TravelRoute travelRoute = route(100L);
        when(routeMapper.selectById(100L)).thenReturn(travelRoute);

        SysUser user = new SysUser();
        user.id = 9L;
        user.nickname = "宣阳";
        when(sysUserMapper.selectById(9L)).thenReturn(user);

        ReviewView view = orderService.review(o.orderNo, 9L, new ReviewRequest(3, "总体不错"));

        assertEquals(3, view.rating());
        assertEquals("VISIBLE", view.status());
        assertEquals("宣阳", view.userNickname());
        assertEquals(o.orderNo, view.orderNo());

        // 线路评分被重算：内存对象同步更新，落库走单条原子 UPDATE（子查询统计）
        assertEquals(2, travelRoute.ratingCount);
        assertEquals(new BigDecimal("4.00"), travelRoute.ratingAvg);
        verify(routeMapper).update(any(), any());
    }
}
