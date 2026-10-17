package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.travelagency.common.enums.DepartureStatus;
import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.enums.PaymentStatus;
import com.travelagency.common.enums.RefundStatus;
import com.travelagency.common.enums.RouteStatus;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Refund;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.RefundMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约 {@code GET /admin/dashboard}（{@code DashboardData}）的响应形状与 {@code days} 参数。
 *
 * <p>此前实现只返回 8 个标量字段：缺 {@code orderTrend} / {@code popularRoutes} /
 * {@code popularDestinations} 三个必填字段，且计数字段用的是 {@code long} ——
 * 全局 JacksonConfig 会把 {@code Long} 序列化成字符串，于是契约里的 {@code integer}
 * 实际发出的是 {@code "15"}；{@code participantCount} 更是 {@code SUM()} 的 DECIMAL
 * 结果，发出 {@code "34.00"} 这种金额串。字符串插值在前端渲染上看不出差别，所以一直没暴露。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long",
        "app.integrations.alipay.callback-secret=admin-dashboard-callback-secret"
})
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class AdminDashboardContractIntegrationTest {

    private static final String MONEY = "^(0|[1-9][0-9]*)\\.[0-9]{2}$";

    /** 契约 DashboardData：12 个字段，全部必填（additionalProperties: false）。 */
    private static final Set<String> DATA_FIELDS = Set.of(
            "userCount", "publishedRouteCount", "openDepartureCount", "todayOrderCount",
            "pendingConfirmCount", "pendingRefundCount", "participantCount", "grossOrderAmount",
            "orderTrend", "popularRoutes", "popularDestinations", "departureEnrollment");

    /** 契约里声明为 integer 的计数字段。 */
    private static final List<String> COUNT_FIELDS = List.of(
            "userCount", "publishedRouteCount", "openDepartureCount", "todayOrderCount",
            "pendingConfirmCount", "pendingRefundCount", "participantCount");

    private static final Set<String> METRIC_FIELDS =
            Set.of("date", "orderCount", "participantCount", "orderAmount");

    private static final Set<String> DESTINATION_FIELDS = Set.of("destination", "validBookingCount");

    private static final Set<String> ENROLLMENT_FIELDS = Set.of(
            "departureId", "routeId", "routeName", "startDate", "maxPeople", "reservedPeople",
            "confirmedPeople", "remainingSeats");

    /** 契约 RouteSummary 的全部字段（复用 /routes 的装配，字段集应完全一致）。 */
    private static final Set<String> ROUTE_SUMMARY_FIELDS = Set.of(
            "id", "name", "departureCity", "destination", "durationDays", "description", "coverUrl",
            "minAdultPrice", "nextDepartureDate", "availableSeats", "ratingAvg", "ratingCount",
            "validBookingCount", "status", "favorite");

    private static final Set<String> ROUTE_SUMMARY_REQUIRED = Set.of(
            "id", "name", "departureCity", "destination", "durationDays", "ratingAvg", "ratingCount",
            "validBookingCount", "status");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JsonMapper json;

    /** 与生产同源：工作台的"今天"取自库内日期，测试不再用 JVM 时区自行推导期望值。 */
    @Autowired
    private TravelOrderMapper orderMapper;

    @Autowired
    private DepartureMapper departureMapper;

    @Autowired
    private TravelRouteMapper routeMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private RefundMapper refundMapper;

    /** 生成用例内唯一的账号名 / 订单号，避免与演示数据或其他用例相撞。 */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("days 缺省为 7：orderTrend 是连续 7 个自然日、升序、以今天结尾")
    void defaultWindowIsSevenDenseDays() throws Exception {
        assertTrend(dashboard("/api/admin/dashboard"), 7);
    }

    @Test
    @DisplayName("days=30：窗口拉长到 30 天")
    void thirtyDayWindow() throws Exception {
        assertTrend(dashboard("/api/admin/dashboard?days=30"), 30);
    }

    @Test
    @DisplayName("7 个计数字段是 JSON 数字而不是字符串，金额是 2 位小数字符串")
    void scalarTypesFollowContract() throws Exception {
        for (String field : COUNT_FIELDS) {
            mvc.perform(authed("/api/admin/dashboard"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data." + field).isNumber());
        }
        mvc.perform(authed("/api/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grossOrderAmount").value(matchesPattern(MONEY)));
    }

    @Test
    @DisplayName("响应字段集与契约一致：data/popularRoutes/popularDestinations/departureEnrollment 都不多不少")
    void fieldSetsMatchContract() throws Exception {
        JsonNode data = dashboard("/api/admin/dashboard");
        assertFieldsExactly(data, DATA_FIELDS, DATA_FIELDS, "DashboardData");
        for (JsonNode item : data.get("orderTrend")) {
            assertFieldsExactly(item, METRIC_FIELDS, METRIC_FIELDS, "DashboardMetric");
        }
        for (JsonNode item : data.get("popularDestinations")) {
            assertFieldsExactly(item, DESTINATION_FIELDS, DESTINATION_FIELDS, "DestinationStatistic");
        }
        for (JsonNode item : data.get("popularRoutes")) {
            assertFieldsExactly(item, ROUTE_SUMMARY_FIELDS, ROUTE_SUMMARY_REQUIRED, "RouteSummary");
        }
        for (JsonNode item : data.get("departureEnrollment")) {
            assertFieldsExactly(item, ENROLLMENT_FIELDS, ENROLLMENT_FIELDS, "DepartureEnrollment");
        }
    }

    /**
     * 嵌套对象里的计数字段同样必须是 JSON 数字。
     *
     * <p>本模块曾经的违约正是"计数被序列化成字符串"：顶层 7 个字段有断言，但
     * {@code DashboardMetric} / {@code DestinationStatistic} / {@code RouteSummary} 里的
     * {@code orderCount}、{@code participantCount}、{@code validBookingCount}、{@code ratingCount}
     * 之前只校验了字段名。契约把它们都声明为 {@code integer}，这里逐项断言类型，
     * 避免以后有人把其中任一改成 {@code Long}（或经 {@code Map} 装箱）时静默回归。</p>
     *
     * <p>{@code DepartureEnrollment} 是同一类风险的新增字段：名字里都带 People，
     * 很容易被写成 {@code long} 或 {@code Integer}，所以在有数据时按类型逐项断言；
     * 没有符合条件的团期时数组为空，此时另由 {@code departureEnrollmentListsUpcomingSales} 造数据覆盖。</p>
     */
    @Test
    @DisplayName("嵌套结构里的计数字段也是数字：orderTrend / popularDestinations / popularRoutes / departureEnrollment")
    void nestedCountFieldsAreNumbers() throws Exception {
        JsonNode data = dashboard("/api/admin/dashboard");

        JsonNode trend = data.get("orderTrend");
        assertFalse(trend.isEmpty(), "orderTrend 至少有 days 个补零点，不应为空");
        for (JsonNode point : trend) {
            assertTrue(point.get("orderCount").isNumber(), "orderTrend[].orderCount 应是数字：" + point);
            assertTrue(point.get("participantCount").isNumber(), "orderTrend[].participantCount 应是数字：" + point);
            assertTrue(point.get("orderAmount").isTextual(), "orderTrend[].orderAmount 应是 Money 字符串：" + point);
        }

        for (JsonNode destination : data.get("popularDestinations")) {
            assertTrue(destination.get("validBookingCount").isNumber(),
                    "popularDestinations[].validBookingCount 应是数字：" + destination);
        }

        for (JsonNode route : data.get("popularRoutes")) {
            assertTrue(route.get("ratingCount").isNumber(), "popularRoutes[].ratingCount 应是数字：" + route);
            assertTrue(route.get("validBookingCount").isNumber(),
                    "popularRoutes[].validBookingCount 应是数字：" + route);
        }

        for (JsonNode row : data.get("departureEnrollment")) {
            for (String field : List.of("maxPeople", "reservedPeople", "confirmedPeople", "remainingSeats")) {
                assertTrue(row.get(field).isNumber(),
                        "departureEnrollment[]. " + field + " 应是数字：" + row);
            }
            assertTrue(row.get("departureId").isTextual(),
                    "departureEnrollment[].departureId 是契约 Id，应是字符串：" + row);
            assertTrue(row.get("routeId").isTextual(),
                    "departureEnrollment[].routeId 是契约 Id，应是字符串：" + row);
        }
    }

    @Test
    @DisplayName("days 取契约 enum 之外的任何值都回 422，且 errors[] 能定位到该参数")
    void daysOutsideEnumIsRejected() throws Exception {
        for (String bad : List.of("1", "15", "0", "-7", "abc")) {
            mvc.perform(authed("/api/admin/dashboard").param("days", bad))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors[0].field", endsWith("days")))
                    .andExpect(jsonPath("$.errors[0].message").value("days 只能是 7 或 30"));
        }
    }

    /**
     * {@code openDepartureCount} 是 PRD 的「可报名团期」：{@code OPEN} <b>且未过出发日期</b>。
     *
     * <p>只按 {@code status = OPEN} 计数会把「已经出发、但工作人员还没把状态推进到
     * TRAVELLING/FINISHED」的团期也算成可报名，而全站其它地方一律按
     * 「OPEN + {@code start_date >= 当天}」判定（下单校验与占名额的条件 UPDATE、
     * {@code RouteService} 的公开团期、{@code HomeService} 的近期团期），
     * 并且库里没有任何定时任务会自动收口过期团期 —— 它们会一直留在计数里。</p>
     *
     * <p>用「昨天 / 当天 / 未来」三条团期钉住边界：当天可报名，昨天不可报名。</p>
     */
    @Test
    @DisplayName("openDepartureCount 只统计未过出发日期的 OPEN 团期（当天仍可报名）")
    void openDepartureCountExcludesDepartedDepartures() throws Exception {
        LocalDate today = orderMapper.databaseToday();
        // 先把库里原有团期全部取消，让计数只反映本用例插入的几条（事务内执行，用例结束回滚）。
        departureMapper.update(null, new UpdateWrapper<Departure>().set("status", DepartureStatus.CANCELLED));

        TravelRoute route = publishedRoute("Dashboard open departure route", "Dashboard destination");

        departureMapper.insert(departure(route.id, today.minusDays(1), DepartureStatus.OPEN));
        departureMapper.insert(departure(route.id, today, DepartureStatus.OPEN));
        departureMapper.insert(departure(route.id, today.plusDays(10), DepartureStatus.OPEN));
        // 非 OPEN 的团期本来就不可报名，即使日期在未来也不计入。
        departureMapper.insert(departure(route.id, today.plusDays(3), DepartureStatus.FULL));

        assertEquals(2, dashboard("/api/admin/dashboard").get("openDepartureCount").asInt(),
                "只应统计 OPEN 且 start_date >= 库内当天的团期");
    }

    /**
     * 热门目的地按「有效报名游客数量」统计（PRD §27）：既不是订单条数，也不是线路上那个
     * {@code valid_booking_count}（那一列存的正是订单条数）。
     *
     * <p>造两个目的地：A 只有 1 张订单但是 5 人，B 有 3 张订单但共 3 人 —— 按订单数 B 在前，
     * 按人数 A 在前，断言 A 排在 B 前面，这个用例才能区分两种口径。</p>
     *
     * <p>同时钉住「有效报名」的边界：待确认（{@code PAID_WAIT_CONFIRM}）与已取消不计入；
     * 退款申请中的订单要看退款单上的 {@code original_order_status —— 由已确认发起的仍算有效
     * （退款完成才回退），由待确认发起的从来就没被计入过。</p>
     */
    @Test
    @DisplayName("popularDestinations 按有效报名游客数量统计：人数优先于订单数，且只认已确认的订单")
    void popularDestinationsCountPeopleNotOrders() throws Exception {
        // 把库里已有订单全部作废，让排行只反映本用例的数据（事务内执行，用例结束回滚）。
        orderMapper.update(null, new UpdateWrapper<TravelOrder>().set("status", OrderStatus.CANCELLED));
        SysUser buyer = buyer();

        TravelRoute peopleFirst = publishedRoute("Dashboard people-first route", "Dashboard people-first");
        TravelRoute ordersFirst = publishedRoute("Dashboard orders-first route", "Dashboard orders-first");
        Departure peopleFirstDeparture = departure(peopleFirst.id, orderMapper.databaseToday().plusDays(10),
                DepartureStatus.OPEN);
        Departure ordersFirstDeparture = departure(ordersFirst.id, orderMapper.databaseToday().plusDays(11),
                DepartureStatus.OPEN);
        departureMapper.insert(peopleFirstDeparture);
        departureMapper.insert(ordersFirstDeparture);

        // A：1 张已确认订单 5 人 + 1 张「由已确认发起、正在申请退款」的订单 2 人 = 7 人（退款完成前仍算有效）
        order(peopleFirst.id, peopleFirstDeparture.id, buyer.id, OrderStatus.CONFIRMED, 4, 1);
        TravelOrder refundingConfirmed = order(peopleFirst.id, peopleFirstDeparture.id, buyer.id,
                OrderStatus.REFUND_APPLYING, 1, 1);
        refund(refundingConfirmed.id, buyer.id, OrderStatus.CONFIRMED);
        // A 上不该计入的三种：待确认、由待确认发起的退款申请、已取消（人数故意给大，一旦计入就会翻转顺序）
        order(peopleFirst.id, peopleFirstDeparture.id, buyer.id, OrderStatus.PAID_WAIT_CONFIRM, 8, 1);
        TravelOrder refundingUnconfirmed = order(peopleFirst.id, peopleFirstDeparture.id, buyer.id,
                OrderStatus.REFUND_APPLYING, 8, 1);
        refund(refundingUnconfirmed.id, buyer.id, OrderStatus.PAID_WAIT_CONFIRM);
        order(peopleFirst.id, peopleFirstDeparture.id, buyer.id, OrderStatus.CANCELLED, 8, 1);
        // B：3 张已确认订单各 1 人 = 3 人（按订单数 3 > A 的 2，按人数 3 < A 的 7）
        for (int i = 0; i < 3; i++) {
            order(ordersFirst.id, ordersFirstDeparture.id, buyer.id, OrderStatus.CONFIRMED, 1, 0);
        }
        order(ordersFirst.id, ordersFirstDeparture.id, buyer.id, OrderStatus.PAID_WAIT_CONFIRM, 9, 0);

        JsonNode destinations = dashboard("/api/admin/dashboard").get("popularDestinations");
        int peopleFirstIndex = indexOfDestination(destinations, "Dashboard people-first");
        int ordersFirstIndex = indexOfDestination(destinations, "Dashboard orders-first");

        assertEquals(7, destinations.get(peopleFirstIndex).get("validBookingCount").asInt(),
                "应统计已确认（含退款申请中）订单的游客人数，排除待确认与已取消");
        assertEquals(3, destinations.get(ordersFirstIndex).get("validBookingCount").asInt());
        assertTrue(peopleFirstIndex < ordersFirstIndex,
                "应按人数排行：1 单 7 人的目的地要排在 3 单 3 人的目的地前面，实际顺序为 "
                        + destinations);
    }

    /**
     * {@code departureEnrollment} 是「团期报名情况」：未来尚未出发、仍在销售（OPEN/FULL）的
     * 最近 5 个团期，按出发日期升序，{@code remainingSeats} = {@code max - reserved - confirmed}。
     *
     * <p>除了 5 条应出现的团期，还故意插入 5 条"应当被排除"的：已过出发日期、{@code DRAFT}、
     * {@code CLOSED}、{@code CANCELLED}、未发布线路下的团期。<b>它们的日期都落在窗口之内</b>
     * （只比对应位置晚一个 id），所以任何一条过滤条件被去掉，都会有额外的行挤进前 5 条、
     * 把后面的行顶出去 —— 断言才能真的失败。早先把这些排除项放在窗口之后（第 6、7、8 天），
     * 去掉过滤也不影响前 5 条，等于没测。另外第 6 条有效团期用于验证 {@code LIMIT 5}
     * 与"已占用超过名额时 {@code remainingSeats} 钳在 0"。</p>
     */
    @Test
    @DisplayName("departureEnrollment：未来 OPEN/FULL 团期按日期升序最多 5 条，remainingSeats 有下限")
    void departureEnrollmentListsUpcomingSales() throws Exception {
        LocalDate today = orderMapper.databaseToday();
        // 库里已有团期全部取消，让窗口只反映本用例插入的数据（事务内执行，用例结束回滚）。
        departureMapper.update(null, new UpdateWrapper<Departure>().set("status", DepartureStatus.CANCELLED));
        TravelRoute route = publishedRoute("Dashboard enrollment route", "Dashboard enrollment destination");
        TravelRoute draftRoute = route("Dashboard enrollment draft route", "Dashboard enrollment draft",
                RouteStatus.DRAFT);

        Departure first = departure(route.id, today.plusDays(1), DepartureStatus.OPEN, 10, 2, 3);
        Departure second = departure(route.id, today.plusDays(2), DepartureStatus.OPEN, 10, 0, 10);
        Departure third = departure(route.id, today.plusDays(3), DepartureStatus.FULL, 8, 1, 7);
        Departure fourth = departure(route.id, today.plusDays(4), DepartureStatus.OPEN, 20, 5, 5);
        // 脏数据：已占用 18 人 > 名额 8 人，remainingSeats 必须是 0 而不是 -10。
        Departure overbooked = departure(route.id, today.plusDays(5), DepartureStatus.OPEN, 8, 9, 9);
        List.of(first, second, third, fourth, overbooked).forEach(departureMapper::insert);

        // 以下都应被排除；日期都插在上面对应团期的同一天（id 更大，因此排在它后面），
        // 一旦对应的过滤条件失效就会挤进前 5 条、改变结果。
        Departure draft = departure(route.id, today.plusDays(1), DepartureStatus.DRAFT);
        Departure closed = departure(route.id, today.plusDays(2), DepartureStatus.CLOSED);
        Departure cancelled = departure(route.id, today.plusDays(4), DepartureStatus.CANCELLED);
        Departure unpublishedRoute = departure(draftRoute.id, today.plusDays(3), DepartureStatus.OPEN);
        Departure departed = departure(route.id, today.minusDays(1), DepartureStatus.OPEN);
        // 第 6 条有效团期：验证窗口只有 5 条。
        Departure sixth = departure(route.id, today.plusDays(6), DepartureStatus.OPEN);
        List.of(draft, closed, cancelled, unpublishedRoute, departed, sixth).forEach(departureMapper::insert);

        JsonNode rows = dashboard("/api/admin/dashboard").get("departureEnrollment");
        List<String> startDates = new ArrayList<>();
        rows.forEach(row -> startDates.add(row.get("startDate").asString()));
        assertEquals(List.of(
                        today.plusDays(1).toString(), today.plusDays(2).toString(), today.plusDays(3).toString(),
                        today.plusDays(4).toString(), today.plusDays(5).toString()),
                startDates, "应只含未来 5 个仍在销售的团期，按出发日期升序");
        assertEquals(5, rows.get(0).get("remainingSeats").asInt(), "10 - 2 - 3 = 5");
        assertEquals(0, rows.get(1).get("remainingSeats").asInt(), "名额已满时剩余为 0");
        assertEquals(0, rows.get(2).get("remainingSeats").asInt(), "FULL 团期（受 max 限制）剩余为 0");
        assertEquals(10, rows.get(3).get("remainingSeats").asInt(), "20 - 5 - 5 = 10");
        assertEquals(0, rows.get(4).get("remainingSeats").asInt(), "已占用超过名额时为 0，不能是负数");
        assertEquals("Dashboard enrollment route", rows.get(0).get("routeName").asString());
        assertEquals(route.id.toString(), rows.get(0).get("routeId").asString());
        assertEquals(overbooked.id.toString(), rows.get(4).get("departureId").asString());
    }

    /**
     * 热门线路按「有效报名订单条数」实时统计（PRD §27），不受 {@code travel_route.valid_booking_count}
     * 物化计数列的预置值影响。
     *
     * <p>造两条线路，让物化计数与真实订单数<b>相反</b>：A 只有 2 张有效订单但计数列预置为 0，
     * B 只有 1 张有效订单但计数列预置为 999。按真实订单数 A 在前、按物化计数 B 在前 —— 断言 A 排在
     * B 前面、且各自展示的 {@code validBookingCount} 等于真实订单条数，才能证明排行与计数都来自
     * 订单聚合，而不是那一列。</p>
     *
     * <p>同时钉住「有效报名」的边界：待支付、待确认与已取消不计入；退款申请中但由已确认发起的仍计入。</p>
     */
    @Test
    @DisplayName("popularRoutes 按有效报名订单条数实时统计，不受物化计数列预置值影响")
    void popularRoutesCountValidOrdersNotMaterializedColumn() throws Exception {
        // 把库里已有订单作废、已有线路下架，让排行只反映本用例的数据（事务内执行，用例结束回滚）。
        orderMapper.update(null, new UpdateWrapper<TravelOrder>().set("status", OrderStatus.CANCELLED));
        routeMapper.update(null, new UpdateWrapper<TravelRoute>().set("deleted", 1));
        SysUser buyer = buyer();

        TravelRoute twoOrders = route("Dashboard hot two-orders route",
                "Dashboard hot destination A", RouteStatus.PUBLISHED);
        TravelRoute oneOrder = route("Dashboard hot one-order route",
                "Dashboard hot destination B", RouteStatus.PUBLISHED);
        // 物化计数列故意与真实订单数相反：若排行读这一列，顺序会翻转。
        routeMapper.update(null, new UpdateWrapper<TravelRoute>()
                .eq("id", twoOrders.id).set("valid_booking_count", 0));
        routeMapper.update(null, new UpdateWrapper<TravelRoute>()
                .eq("id", oneOrder.id).set("valid_booking_count", 999));

        LocalDate today = orderMapper.databaseToday();
        Departure depA = departure(twoOrders.id, today.plusDays(10), DepartureStatus.OPEN);
        Departure depB = departure(oneOrder.id, today.plusDays(11), DepartureStatus.OPEN);
        departureMapper.insert(depA);
        departureMapper.insert(depB);

        // A：2 张有效订单（1 张已确认 + 1 张「由已确认发起、正在申请退款」）+ 3 张不计入的。
        order(twoOrders.id, depA.id, buyer.id, OrderStatus.CONFIRMED, 1, 0);
        TravelOrder refundingConfirmed = order(twoOrders.id, depA.id, buyer.id,
                OrderStatus.REFUND_APPLYING, 1, 0);
        refund(refundingConfirmed.id, buyer.id, OrderStatus.CONFIRMED);
        order(twoOrders.id, depA.id, buyer.id, OrderStatus.WAIT_PAY, 5, 5);
        order(twoOrders.id, depA.id, buyer.id, OrderStatus.PAID_WAIT_CONFIRM, 5, 5);
        order(twoOrders.id, depA.id, buyer.id, OrderStatus.CANCELLED, 5, 5);
        // B：1 张有效订单。
        order(oneOrder.id, depB.id, buyer.id, OrderStatus.COMPLETED, 1, 0);

        JsonNode routes = dashboard("/api/admin/dashboard").get("popularRoutes");
        int twoOrdersIndex = indexOfRoute(routes, "Dashboard hot two-orders route");
        int oneOrderIndex = indexOfRoute(routes, "Dashboard hot one-order route");

        assertEquals(2, routes.get(twoOrdersIndex).get("validBookingCount").asInt(),
                "计数应为真实有效报名订单条数，而不是物化计数列的 0");
        assertEquals(1, routes.get(oneOrderIndex).get("validBookingCount").asInt(),
                "计数应为真实有效报名订单条数，而不是物化计数列的 999");
        assertTrue(twoOrdersIndex < oneOrderIndex,
                "应按有效报名订单条数排行：2 单的线路排在 1 单的前面，实际顺序为 " + routes);
    }

    private static int indexOfRoute(JsonNode routes, String name) {
        for (int i = 0; i < routes.size(); i++) {
            if (name.equals(routes.get(i).get("name").asString())) {
                return i;
            }
        }
        throw new AssertionError("popularRoutes 里没有线路「" + name + "」：" + routes);
    }

    /** 造一条已上架线路，只填契约/表结构要求非空的列。 */
    private TravelRoute publishedRoute(String name, String destination) {
        return route(name, destination, RouteStatus.PUBLISHED);
    }

    private TravelRoute route(String name, String destination, String status) {
        TravelRoute route = new TravelRoute();
        route.name = name;
        route.departureCity = "Dashboard city";
        route.destination = destination;
        route.durationDays = 1;
        route.status = status;
        route.ratingAvg = new BigDecimal("0.00");
        route.ratingCount = 0;
        route.validBookingCount = 0;
        route.deleted = 0;
        routeMapper.insert(route);
        return route;
    }

    private SysUser buyer() {
        SysUser user = new SysUser();
        user.username = "dashboard_buyer_" + SEQ.incrementAndGet();
        user.nickname = "工作台统计测试游客";
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        userMapper.insert(user);
        return user;
    }

    private TravelOrder order(Long routeId, Long departureId, Long userId, String status,
                              int adultCount, int childCount) {
        TravelOrder order = new TravelOrder();
        order.orderNo = "TA-DASHBOARD-" + SEQ.incrementAndGet();
        order.userId = userId;
        order.routeId = routeId;
        order.departureId = departureId;
        order.contactName = "测试联系人";
        order.contactPhone = "13800138000";
        order.adultCount = adultCount;
        order.childCount = childCount;
        order.adultUnitPrice = new BigDecimal("100.00");
        order.childUnitPrice = new BigDecimal("100.00");
        order.totalAmount = new BigDecimal("100.00").multiply(BigDecimal.valueOf(adultCount + childCount));
        order.status = status;
        order.paymentStatus = PaymentStatus.PAID;
        orderMapper.insert(order);
        return order;
    }

    /** 给订单挂一张"申请中"的退款单，{@code originalOrderStatus} 是申请前的业务状态。 */
    private void refund(Long orderId, Long userId, String originalOrderStatus) {
        Refund refund = new Refund();
        refund.orderId = orderId;
        refund.userId = userId;
        refund.amount = new BigDecimal("100.00");
        refund.reason = "工作台统计测试退款";
        refund.originalOrderStatus = originalOrderStatus;
        refund.status = RefundStatus.APPLYING;
        refundMapper.insert(refund);
    }

    private static int indexOfDestination(JsonNode destinations, String destination) {
        for (int i = 0; i < destinations.size(); i++) {
            if (destination.equals(destinations.get(i).get("destination").asString())) {
                return i;
            }
        }
        throw new AssertionError("popularDestinations 里没有目的地「" + destination + "」：" + destinations);
    }

    /** 建一条可插入的团期，只填契约/表结构要求非空的列。 */
    private static Departure departure(Long routeId, LocalDate startDate, String status) {
        return departure(routeId, startDate, status, 10, 0, 0);
    }

    private static Departure departure(Long routeId, LocalDate startDate, String status,
                                       int maxPeople, int reservedPeople, int confirmedPeople) {
        Departure departure = new Departure();
        departure.routeId = routeId;
        departure.startDate = startDate;
        departure.endDate = startDate.plusDays(1);
        departure.adultPrice = new BigDecimal("100.00");
        departure.childPrice = new BigDecimal("100.00");
        departure.maxPeople = maxPeople;
        departure.reservedPeople = reservedPeople;
        departure.confirmedPeople = confirmedPeople;
        departure.status = status;
        departure.version = 0;
        return departure;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(String url) {
        return get(url).with(user("admin-dashboard-admin").roles("ADMIN"));
    }

    private JsonNode dashboard(String url) throws Exception {
        MockHttpServletResponse response = mvc.perform(authed(url)).andExpect(status().isOk())
                .andReturn().getResponse();
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    /**
     * orderTrend 必须是「长度 = days、按日期升序、逐日连续、末位是今天」，即缺失日期被补零。
     *
     * <p>"今天"取库内日期（{@code SELECT CURDATE()}），与生产实现同一来源；
     * 不用 {@code LocalDate.now()}，否则在 JVM 时区与库会话时区不一致的环境（CI 常见 UTC）里，
     * 断言会跟着实现一起错，失去回归意义。</p>
     */
    private void assertTrend(JsonNode data, int days) {
        JsonNode trend = data.get("orderTrend");
        assertEquals(days, trend.size(), "orderTrend 长度应等于 days");

        LocalDate today = orderMapper.databaseToday();
        List<String> expected = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            expected.add(today.minusDays(i).toString());
        }
        List<String> actual = new ArrayList<>();
        for (JsonNode point : trend) {
            actual.add(point.get("date").asString());
        }
        assertEquals(expected, actual, "orderTrend 应是连续自然日、升序、以今天结尾");
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    /** 契约是 additionalProperties: false：多字段与少必填字段都算违约，两个方向都要断言。 */
    private static void assertFieldsExactly(JsonNode node, Set<String> allowed, Set<String> required,
                                            String label) {
        Set<String> actual = fieldNames(node);
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(allowed);
        assertTrue(unexpected.isEmpty(), label + " 出现契约外字段（additionalProperties: false）：" + unexpected);
        for (String field : required) {
            assertTrue(actual.contains(field), label + " 缺少契约必填字段：" + field);
        }
    }
}
