package com.travelagency;

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

    /** 契约 DashboardData：11 个字段，全部必填（additionalProperties: false）。 */
    private static final Set<String> DATA_FIELDS = Set.of(
            "userCount", "publishedRouteCount", "openDepartureCount", "todayOrderCount",
            "pendingConfirmCount", "pendingRefundCount", "participantCount", "grossOrderAmount",
            "orderTrend", "popularRoutes", "popularDestinations");

    /** 契约里声明为 integer 的计数字段。 */
    private static final List<String> COUNT_FIELDS = List.of(
            "userCount", "publishedRouteCount", "openDepartureCount", "todayOrderCount",
            "pendingConfirmCount", "pendingRefundCount", "participantCount");

    private static final Set<String> METRIC_FIELDS =
            Set.of("date", "orderCount", "participantCount", "orderAmount");

    private static final Set<String> DESTINATION_FIELDS = Set.of("destination", "validBookingCount");

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
    @DisplayName("响应字段集与契约一致：data/popularRoutes/popularDestinations 都不多不少")
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

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(String url) {
        return get(url).with(user("admin-dashboard-admin").roles("ADMIN"));
    }

    private JsonNode dashboard(String url) throws Exception {
        MockHttpServletResponse response = mvc.perform(authed(url)).andExpect(status().isOk())
                .andReturn().getResponse();
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    /** orderTrend 必须是「长度 = days、按日期升序、逐日连续、末位是今天」，即缺失日期被补零。 */
    private void assertTrend(JsonNode data, int days) {
        JsonNode trend = data.get("orderTrend");
        assertEquals(days, trend.size(), "orderTrend 长度应等于 days");

        LocalDate today = LocalDate.now();
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
