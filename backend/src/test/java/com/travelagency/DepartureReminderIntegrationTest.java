package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.DepartureReminder;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.Message;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.DepartureReminderMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.MessageMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import com.travelagency.domain.service.DepartureReminderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 即将出发提醒（C-01）数据库集成测试。
 *
 * <p>验证：符合出行条件（已确认报名）的订单进入提醒窗口后收到一条站内消息；取消、未支付的订单
 * 不发送；定时任务重复执行不重复发消息（唯一键幂等）。需要数据库：
 * {@code $env:TRAVEL_MYSQL_TEST = "true"}。用例在事务内执行，结束后自动回滚。</p>
 *
 * <p>断言按"本测试创建的用户/订单"限定，不依赖库中其它演示数据的数量。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long",
        "app.reminder.upcoming-days=3"})
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class DepartureReminderIntegrationTest {

    @Autowired DepartureReminderService reminderService;
    @Autowired TravelRouteMapper routes;
    @Autowired GuideMapper guides;
    @Autowired SysUserMapper users;
    @Autowired DepartureMapper departures;
    @Autowired TravelOrderMapper orders;
    @Autowired MessageMapper messages;
    @Autowired DepartureReminderMapper reminders;

    private Long routeId;
    private Long guideId;
    private Long departureId;
    private Long confirmedUserId;
    private Long confirmedOrderId;
    private Long cancelledUserId;
    private Long waitPayUserId;

    @BeforeEach
    void setUp() {
        TravelRoute route = new TravelRoute();
        route.name = "提醒契约线路";
        route.departureCity = "上海";
        route.destination = "云南";
        route.durationDays = 6;
        route.status = "PUBLISHED";
        route.ratingAvg = new BigDecimal("0.00");
        route.ratingCount = 0;
        route.validBookingCount = 0;
        route.deleted = 0;
        routes.insert(route);
        routeId = route.id;

        SysUser guideUser = user();
        Guide guide = new Guide();
        guide.userId = guideUser.id;
        guide.name = "提醒测试导游";
        guide.phone = "13800000000";
        guide.status = "ACTIVE";
        guides.insert(guide);
        guideId = guide.id;

        // 出发日期落在 [今天, 今天 + 3 天] 窗口内，符合提醒条件。
        Departure departure = new Departure();
        departure.routeId = routeId;
        departure.startDate = LocalDate.now().plusDays(2);
        departure.endDate = departure.startDate.plusDays(5);
        departure.adultPrice = new BigDecimal("2999.00");
        departure.childPrice = new BigDecimal("1999.00");
        departure.maxPeople = 20;
        departure.reservedPeople = 0;
        departure.confirmedPeople = 1;
        departure.guideId = guideId;
        departure.status = "OPEN";
        departure.version = 0;
        departures.insert(departure);
        departureId = departure.id;

        confirmedUserId = user().id;
        confirmedOrderId = order(confirmedUserId, "CONFIRMED").id;

        cancelledUserId = user().id;
        order(cancelledUserId, "CANCELLED");

        waitPayUserId = user().id;
        order(waitPayUserId, "WAIT_PAY");
    }

    @Test
    void onlyConfirmedOrdersAreRemindedAndOnlyOnce() {
        int firstRun = reminderService.sendUpcomingReminders();
        assertTrue(firstRun >= 1, "至少本测试的已确认订单应被提醒");

        assertEquals(1L, messageCount(confirmedUserId), "已确认订单应收到一条即将出发提醒");
        assertNotNull(reminderOf(confirmedOrderId), "应写入提醒发送记录");
        assertEquals(0L, messageCount(cancelledUserId), "已取消订单不应收到提醒");
        assertEquals(0L, messageCount(waitPayUserId), "未支付订单不应收到提醒");

        int secondRun = reminderService.sendUpcomingReminders();
        assertEquals(0, secondRun, "重复执行不应再发送");
        assertEquals(1L, messageCount(confirmedUserId), "重复执行不应重复发消息");
    }

    private long messageCount(Long userId) {
        Long count = messages.selectCount(new QueryWrapper<Message>()
                .eq("user_id", userId).eq("type", DepartureReminderService.TYPE_UPCOMING));
        return count == null ? 0L : count;
    }

    private DepartureReminder reminderOf(Long orderId) {
        return reminders.selectOne(new QueryWrapper<DepartureReminder>()
                .eq("order_id", orderId).eq("remind_type", DepartureReminderService.TYPE_UPCOMING));
    }

    private TravelOrder order(Long userId, String status) {
        TravelOrder order = new TravelOrder();
        order.orderNo = "RM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        order.userId = userId;
        order.routeId = routeId;
        order.departureId = departureId;
        order.contactName = "提醒测试联系人";
        order.contactPhone = "13800000001";
        order.adultCount = 1;
        order.childCount = 0;
        order.adultUnitPrice = new BigDecimal("2999.00");
        order.childUnitPrice = new BigDecimal("1999.00");
        order.totalAmount = new BigDecimal("2999.00");
        order.status = status;
        order.paymentStatus = "CONFIRMED".equals(status) ? "PAID" : "UNPAID";
        orders.insert(order);
        return order;
    }

    private SysUser user() {
        SysUser user = new SysUser();
        user.username = "reminder_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        user.nickname = "提醒测试用户";
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        users.insert(user);
        return user;
    }
}
