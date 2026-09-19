package com.travelagency;

import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Favorite;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.Hotel;
import com.travelagency.domain.entity.Review;
import com.travelagency.domain.entity.RouteItineraryDay;
import com.travelagency.domain.entity.RouteItineraryItem;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.FavoriteMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.HotelMapper;
import com.travelagency.domain.mapper.ReviewMapper;
import com.travelagency.domain.mapper.RouteItineraryDayMapper;
import com.travelagency.domain.mapper.RouteItineraryItemMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 游客侧线路浏览契约集成测试（真 MySQL + 真实 HTTP 层）。
 *
 * <p>补的是 {@code CONTRIBUTING.md} §11 要求的核心业务流程里，此前<b>唯一没有任何测试覆盖</b>
 * 的两项：「线路浏览」与「团期选择」。别的环节（注册登录、下单、支付、确认、取消退款、
 * 完成、评价）都已有契约测试，只有这两个环节零覆盖——而它们恰好是游客进入系统的第一步。</p>
 *
 * <p><b>为什么必须走真库</b>：本类钉住的都是"契约字段在不在、名字对不对"的问题。
 * 这类缺陷在纯 Mockito 单测里完全抓不到（单测直接断言 service 返回值，绕过了整个
 * Controller → Jackson 序列化链），编译也不会报错，只有真实 HTTP 响应才能暴露。</p>
 *
 * <p><b>回归背景</b>：修复前 {@code GET /routes/{routeId}} 直出持久化实体，
 * {@code departures[]} 缺少契约必填的计算字段 {@code availableSeats}，
 * 导致前端线路详情页与报名页把所有团期判定为"不可报名"——
 * 游客从网页端无法完成报名（接口层却完全正常，所以长期没被发现）。</p>
 *
 * <p><b>每个用例的顺序约定</b>：预期返回错误状态的调用一律排在方法末尾。
 * {@code @Transactional} 测试里内层 {@code @Transactional} 方法抛异常后会把参与事务
 * 标记为 rollback-only，此后若再调用另一个内层事务方法并正常返回，Spring 会抛
 * {@code UnexpectedRollbackException}——那是测试脚手架的噪声，不是业务缺陷。</p>
 *
 * <p>需要数据库：{@code $env:TRAVEL_MYSQL_TEST = "true"}。整个类在事务内执行，
 * 结束时统一回滚，不会给本地库留下任何数据。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long",
        "app.integrations.alipay.callback-secret=route-browse-callback-secret-32-bytes"
})
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class RouteBrowseContractIntegrationTest {

    /** 契约 RouteSummary / Route 的字段集（additionalProperties: false，多一个字段都算违约）。 */
    private static final Set<String> SUMMARY_FIELDS = Set.of(
            "id", "name", "departureCity", "destination", "durationDays", "description", "coverUrl",
            "minAdultPrice", "nextDepartureDate", "availableSeats", "ratingAvg", "ratingCount",
            "validBookingCount", "status", "favorite");
    private static final Set<String> SUMMARY_REQUIRED = Set.of(
            "id", "name", "departureCity", "destination", "durationDays",
            "ratingAvg", "ratingCount", "validBookingCount", "status");
    private static final Set<String> ROUTE_FIELDS = Set.of(
            "id", "name", "departureCity", "destination", "durationDays", "description", "coverUrl",
            "minAdultPrice", "nextDepartureDate", "availableSeats", "ratingAvg", "ratingCount",
            "validBookingCount", "status", "favorite", "included", "excluded", "bookingNotice",
            "createdAt", "updatedAt");
    private static final Set<String> DEPARTURE_FIELDS = Set.of(
            "id", "routeId", "routeName", "guideName", "startDate", "endDate", "adultPrice",
            "childPrice", "maxPeople", "reservedPeople", "confirmedPeople", "availableSeats",
            "guideId", "status", "createdAt", "updatedAt");
    private static final Set<String> DEPARTURE_REQUIRED = Set.of(
            "id", "routeId", "startDate", "endDate", "adultPrice", "childPrice", "maxPeople",
            "reservedPeople", "confirmedPeople", "availableSeats", "status", "createdAt", "updatedAt");
    private static final Set<String> ITINERARY_DAY_FIELDS = Set.of(
            "id", "routeId", "dayNumber", "title", "description", "transportation", "meals",
            "hotelId", "hotelName", "items");
    private static final Set<String> ITINERARY_DAY_REQUIRED =
            Set.of("id", "routeId", "dayNumber", "title", "items");
    private static final Set<String> REVIEW_FIELDS = Set.of(
            "id", "orderNo", "routeId", "userNickname", "rating", "content", "status", "createdAt");
    private static final Set<String> PAGE_FIELDS =
            Set.of("items", "page", "size", "total", "totalPages");

    private static final String OWNER_NICKNAME = "线路浏览测试用户";

    @Autowired WebApplicationContext context;
    @Autowired SysUserMapper users;
    @Autowired GuideMapper guides;
    @Autowired HotelMapper hotels;
    @Autowired TravelRouteMapper routes;
    @Autowired DepartureMapper departures;
    @Autowired RouteItineraryDayMapper days;
    @Autowired RouteItineraryItemMapper items;
    @Autowired ReviewMapper reviews;
    @Autowired FavoriteMapper favorites;
    @Autowired TravelOrderMapper orders;
    @Autowired JwtTokenProvider tokens;
    @Autowired JsonMapper json;

    private MockMvc mvc;
    private String uniq;
    private String city;
    private String destination;

    private SysUser owner;
    private String ownerToken;

    /** 有在售团期的线路：成人价最低。 */
    private TravelRoute cheap;
    /** 有在售团期的线路：成人价最高、评分与报名人次最高。 */
    private TravelRoute rich;
    /** 只有已关闭团期的线路：没有可售团期，用于验证"无价线路"的排序与字段归零行为。 */
    private TravelRoute soldOut;
    /** 草稿线路：任何公开查询都不应看到。 */
    private TravelRoute draft;

    /** 团期归属的导游，供各用例继续追加团期。 */
    private Guide guide;

    private Long openDepartureId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        uniq = randomToken(8);
        city = "契约测试出发城" + uniq;
        destination = "契约测试目的地" + uniq;

        owner = user(OWNER_NICKNAME);
        ownerToken = bearer(owner, "USER");

        guide = guide();

        // 取"下个月 1 号"作基准：保证团期恒在未来，且 cheap / rich 落在同一月份，
        // 使 departureMonth 的断言与当天日期无关（不会因为接近月末而跨月翻车）。
        LocalDate firstOpen = LocalDate.now().withDayOfMonth(1).plusMonths(3);
        cheap = route("低价线路", 3, new BigDecimal("4.10"), 10, "PUBLISHED");
        openDepartureId = departure(cheap.id, guide.id, firstOpen, new BigDecimal("1000.00"), "OPEN", 30, 16, 10).id;

        rich = route("高价线路", 4, new BigDecimal("4.90"), 30, "PUBLISHED");
        departure(rich.id, guide.id, firstOpen.plusDays(1), new BigDecimal("3000.00"), "OPEN", 20, 0, 0);

        soldOut = route("售罄线路", 5, new BigDecimal("4.50"), 20, "PUBLISHED");
        // 只有一个已关闭团期：minAdultPrice / nextDepartureDate / availableSeats 三项都应为空。
        departure(soldOut.id, guide.id, firstOpen.plusMonths(2), new BigDecimal("2000.00"), "CLOSED", 10, 0, 0);

        draft = route("草稿线路-" + uniq, 6, new BigDecimal("5.00"), 99, "DRAFT");
        departure(draft.id, guide.id, firstOpen, new BigDecimal("500.00"), "OPEN", 10, 0, 0);
    }

    // ------------------------------------------------------------------
    // 列表：分页信封与 RouteSummary 形状
    // ------------------------------------------------------------------

    @Test
    @DisplayName("线路列表使用分页信封，items 为契约 RouteSummary（含 availableSeats / nextDepartureDate / favorite）")
    void routeListIsPagedAndItemsFollowRouteSummaryContract() throws Exception {
        JsonNode data = read(get("/api/routes").param("page", "1").param("size", "5"));

        assertEquals(PAGE_FIELDS, fieldNames(data), "列表必须是分页信封，且只含契约分页字段");
        assertEquals(1, data.get("page").asInt(), "page 必须回显请求值");
        assertEquals(5, data.get("size").asInt(), "size 必须回显请求值");

        JsonNode item = data.get("items").get(0);
        // 直出实体时这里会多出 createdBy / deleted / included / excluded 等 7 个契约外字段。
        assertFieldsExactly(item, SUMMARY_FIELDS, SUMMARY_REQUIRED, "列表 items[]");
        assertTrue(item.has("availableSeats"), "列表项必须带 availableSeats：线路卡片用它显示余位");
        assertTrue(item.has("nextDepartureDate"), "列表项必须带 nextDepartureDate");
        assertFalse(item.has("included"), "RouteSummary 不应包含 Route 独有字段 included");
        assertFalse(item.has("createdBy"), "内部字段 createdBy 不得外泄");
        assertFalse(item.has("deleted"), "内部字段 deleted 不得外泄");
    }

    @Test
    @DisplayName("列表筛选条件生效，且草稿 / 未上架线路不出现在公开结果里")
    void routeListAppliesFiltersAndHidesUnpublishedRoutes() throws Exception {
        JsonNode items = read(get("/api/routes").param("destination", destination).param("size", "20"))
                .get("items");
        assertEquals(3, items.size(), "只应返回本用例创建的 3 条已上架线路");
        assertFalse(containsRoute(items, draft.id), "草稿线路不得出现在公开列表中");

        assertEquals(3, read(get("/api/routes").param("departureCity", city).param("size", "20"))
                .get("items").size(), "departureCity 精确筛选应命中 3 条");

        JsonNode byDuration = read(get("/api/routes").param("destination", destination)
                .param("durationDays", "4").param("size", "20")).get("items");
        assertEquals(1, byDuration.size(), "durationDays=4 应只剩高价线路");
        assertEquals(String.valueOf(rich.id), byDuration.get(0).get("id").asString());

        JsonNode byPrice = read(get("/api/routes").param("destination", destination)
                .param("minPrice", "2000.00").param("maxPrice", "4000.00").param("size", "20")).get("items");
        assertEquals(1, byPrice.size(), "价格区间应只剩在售成人价 3000 的高价线路");
        assertEquals(String.valueOf(rich.id), byPrice.get(0).get("id").asString());

        JsonNode hasDeparture = read(get("/api/routes").param("destination", destination)
                .param("hasDeparture", "true").param("size", "20")).get("items");
        assertEquals(2, hasDeparture.size(), "hasDeparture=true 应排除只有已关闭团期的线路");
        assertFalse(containsRoute(hasDeparture, soldOut.id), "没有可售团期的线路不应带 hasDeparture=true 命中");

        int month = LocalDate.now().withDayOfMonth(1).plusMonths(3).getMonthValue();
        JsonNode byMonth = read(get("/api/routes").param("destination", destination)
                .param("departureMonth", String.valueOf(month)).param("size", "20")).get("items");
        assertEquals(2, byMonth.size(), "departureMonth 只应命中在该月有 OPEN 团期的线路（已关闭的不算）");
        assertFalse(containsRoute(byMonth, soldOut.id), "已关闭团期不应被 departureMonth 命中");
    }

    @Test
    @DisplayName("列表 sort 按契约枚举真正生效：无在售团期的线路恒定排在最后，非法值返回 400")
    void routeListSortIsHonouredAndUnknownSortIsRejected() throws Exception {
        List<Long> asc = idsOf(read(get("/api/routes").param("destination", destination)
                .param("sort", "minAdultPrice,asc").param("size", "20")));
        assertEquals(List.of(cheap.id, rich.id, soldOut.id), asc,
                "minAdultPrice,asc：1000 → 3000 → 无在售团期(排最后)");

        List<Long> desc = idsOf(read(get("/api/routes").param("destination", destination)
                .param("sort", "minAdultPrice,desc").param("size", "20")));
        assertEquals(List.of(rich.id, cheap.id, soldOut.id), desc, "minAdultPrice,desc：3000 → 1000 → 无在售团期");

        List<Long> byBooking = idsOf(read(get("/api/routes").param("destination", destination)
                .param("sort", "validBookingCount,desc").param("size", "20")));
        assertEquals(List.of(rich.id, soldOut.id, cheap.id), byBooking,
                "validBookingCount,desc：30 → 20 → 10");

        List<Long> byRating = idsOf(read(get("/api/routes").param("destination", destination)
                .param("sort", "ratingAvg,desc").param("size", "20")));
        assertEquals(List.of(rich.id, soldOut.id, cheap.id), byRating, "ratingAvg,desc：4.90 → 4.50 → 4.10");

        // 契约只声明这 4 个取值，其余一律 400。修复前该参数被静默忽略——不报错，也不排序。
        readError(get("/api/routes").param("destination", destination).param("sort", "minAdultPrice"), 400);
    }

    // ------------------------------------------------------------------
    // 详情：团期可报名性 / 行程 / 评价 / 收藏
    // ------------------------------------------------------------------

    @Test
    @DisplayName("线路详情的每个团期都带 availableSeats，报名页据此判断能否下单（P0 回归）")
    void routeDetailExposesAvailableSeatsForBookableDepartures() throws Exception {
        // 补两个"不该出现"的团期，把过滤条件真正测到：
        // 已过出发日的 OPEN、以及未来但已 CLOSED 的。二者都不得出现在公开详情里。
        departure(cheap.id, guide.id, LocalDate.now().minusDays(10), new BigDecimal("1500.00"),
                "OPEN", 30, 0, 0);
        departure(cheap.id, guide.id, LocalDate.now().withDayOfMonth(1).plusMonths(3),
                new BigDecimal("1200.00"), "CLOSED", 30, 0, 0);

        JsonNode data = read(get("/api/routes/" + cheap.id));

        assertFieldsExactly(data, Set.of("route", "departures", "itinerary", "reviews", "favorite"),
                Set.of("route", "departures", "itinerary", "reviews", "favorite"), "RouteDetail");

        JsonNode list = data.get("departures");
        assertEquals(1, list.size(),
                "只应返回仍可报名的团期：已关闭的与已过出发日的都必须过滤掉");
        JsonNode departure = list.get(0);
        assertFieldsExactly(departure, DEPARTURE_FIELDS, DEPARTURE_REQUIRED, "departures[]");
        assertEquals(openDepartureId.toString(), departure.get("id").asString());

        assertEquals(4, departure.get("availableSeats").asInt(),
                "availableSeats 必须由 maxPeople - reservedPeople - confirmedPeople 算出（30-16-10）");
        assertFalse(departure.has("version"), "实体字段 version 属于契约外字段，不得外泄");
        assertEquals(cheap.name, departure.get("routeName").asString(), "团期应带回线路名");

        JsonNode route = data.get("route");
        assertFieldsExactly(route, ROUTE_FIELDS, SUMMARY_REQUIRED, "RouteDetail.route");
        assertEquals(4, route.get("availableSeats").asInt(), "线路级 availableSeats 取最近可售团期");
        assertEquals(1000.00, route.get("minAdultPrice").asDouble(), 0.001, "详情也要给在售最低价");
        assertTrue(route.has("favorite"), "RouteDetail.route 必须带 favorite");
    }

    @Test
    @DisplayName("线路详情的每日行程是契约扁平结构，并带回酒店名与行程项")
    void routeDetailItineraryIsFlatAndCarriesHotelName() throws Exception {
        Hotel hotel = hotel("契约测试酒店" + uniq);
        RouteItineraryDay day = itineraryDay(cheap.id, 1, "上海 · 昆明", hotel.id);
        itineraryItem(day.id, 1, "ATTRACTION", "石林");
        itineraryItem(day.id, 2, "MEAL", "过桥米线");

        JsonNode data = read(get("/api/routes/" + cheap.id));
        JsonNode itinerary = data.get("itinerary");
        assertEquals(1, itinerary.size());
        JsonNode first = itinerary.get(0);

        assertFieldsExactly(first, ITINERARY_DAY_FIELDS, ITINERARY_DAY_REQUIRED, "itinerary[]");
        // 修复前是 { day: {...}, items: [...] } 的包装结构，前端读 item.dayNumber / item.title
        // 全部得到 undefined，页面会渲染成"D"和空标题。
        assertFalse(first.has("day"), "itinerary[] 元素不得再包一层 day 对象");
        assertEquals(1, first.get("dayNumber").asInt());
        assertEquals("上海 · 昆明", first.get("title").asString());
        assertEquals(hotel.id.toString(), first.get("hotelId").asString());
        assertEquals(hotel.name, first.get("hotelName").asString(), "契约要求带回酒店名");
        assertEquals(2, first.get("items").size());
        assertEquals("石林", first.get("items").get(0).get("name").asString());
    }

    @Test
    @DisplayName("详情内嵌评价为契约 Review 形状（camelCase + userNickname），且只返回可见评价")
    void routeDetailEmbeddedReviewsFollowReviewContract() throws Exception {
        review(visibleOrder(), owner, cheap.id, 5, "行程安排合理，导游讲解认真。");
        // 被隐藏的评价不得出现在公开详情里（同一契约的独立端点也是这个语义）。
        SysUser other = user("评价他人");
        review(order(other), other, cheap.id, 1, "这条评价已被隐藏。", "HIDDEN");

        JsonNode data = read(get("/api/routes/" + cheap.id));
        JsonNode list = data.get("reviews");
        assertEquals(1, list.size(), "只应返回 VISIBLE 评价");

        JsonNode first = list.get(0);
        assertFieldsExactly(first, REVIEW_FIELDS, REVIEW_FIELDS, "reviews[]");
        // 修复前这里用 selectMaps 直出数据库行，键是 route_id / created_at 这类列名，
        // 前端读 userNickname / createdAt 全为 undefined，评价区作者与时间显示空白。
        assertEquals(OWNER_NICKNAME, first.get("userNickname").asString());
        assertTrue(first.get("createdAt").asString().length() > 0, "createdAt 不得为空");
        assertFalse(first.has("user_id"), "snake_case 原始列名不得外泄");
        assertFalse(first.has("created_at"), "snake_case 原始列名不得外泄");
    }

    @Test
    @DisplayName("详情 favorite 未登录恒 false，登录后反映真实收藏状态")
    void routeDetailFavoriteReflectsCurrentUser() throws Exception {
        assertFalse(read(get("/api/routes/" + cheap.id)).get("favorite").asBoolean(),
                "未登录访问公开详情时 favorite 必须为 false 而不是缺字段");

        addFavorite(owner, cheap.id);
        assertTrue(read(get("/api/routes/" + cheap.id).header("Authorization", ownerToken))
                .get("favorite").asBoolean(), "已收藏用户打开详情应看到 favorite=true");

        assertFalse(read(get("/api/routes/" + rich.id).header("Authorization", ownerToken))
                .get("favorite").asBoolean(), "未收藏的线路 favorite 应为 false");
    }

    @Test
    @DisplayName("我的收藏是分页信封且 items 为 RouteSummary（收藏页据此渲染）")
    void favoritesListIsPagedAndItemsFollowRouteSummaryContract() throws Exception {
        addFavorite(owner, cheap.id);
        addFavorite(owner, rich.id);

        JsonNode data = read(get("/api/favorites").header("Authorization", ownerToken)
                .param("page", "1").param("size", "10"));

        assertEquals(PAGE_FIELDS, fieldNames(data), "契约把 GET /favorites 定义为 RoutePageEnvelope");
        assertEquals(2, data.get("total").asInt());
        JsonNode item = data.get("items").get(0);
        assertFieldsExactly(item, SUMMARY_FIELDS, SUMMARY_REQUIRED, "收藏列表 items[]");
        assertTrue(item.get("favorite").asBoolean(), "收藏列表里的线路 favorite 必为 true");
        assertTrue(containsRoute(data.get("items"), cheap.id), "收藏列表应包含刚收藏的线路");
        assertFalse(item.has("createdBy"), "内部字段不得外泄");
    }

    // ------------------------------------------------------------------
    // 负例：一律排在各自方法末尾
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未上架与不存在的线路一律 404，不泄露草稿内容")
    void routeDetailHidesMissingDraftAndOfflineRoutes() throws Exception {
        // 先跑一次成功请求，确认正常线路可访问，再收尾跑负例。
        read(get("/api/routes/" + cheap.id));

        readError(get("/api/routes/99999999"), 404);
        readError(get("/api/routes/" + draft.id), 404);
        readError(get("/api/routes/" + draft.id + "/reviews"), 404);
    }

    // ------------------------------------------------------------------
    // HTTP 辅助
    // ------------------------------------------------------------------

    /** 执行请求并返回契约信封里的 data，同时校验信封本身的完整性。 */
    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();
        JsonNode envelope = parse(response);
        assertEquals("OK", envelope.get("code").asString(), "响应信封 code 必须为 OK");
        assertEquals(0, envelope.get("errors").size(), "成功响应的 errors 必须为空数组");
        assertFalse(envelope.get("traceId").asString().isBlank(), "信封必须携带 traceId");
        return envelope.get("data");
    }

    /** 执行请求并断言错误状态码 + 契约错误信封。 */
    private void readError(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MockHttpServletResponse response = mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();
        JsonNode envelope = parse(response);
        assertTrue(envelope.get("data").isNull(), "错误响应的 data 必须为 null");
        assertFalse(envelope.get("code").asString().isBlank(), "错误响应必须带业务错误码");
        assertFalse(envelope.get("message").asString().isBlank(), "错误响应必须带可读消息");
    }

    /** MockHttpServletResponse 默认按 ISO-8859-1 解码，中文会乱码，必须按 UTF-8 读字节。 */
    private JsonNode parse(MockHttpServletResponse response) throws Exception {
        return json.readTree(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.LinkedHashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    /** 契约是 additionalProperties: false：多字段与少必填字段都算违约，两个方向都要断言。 */
    private static void assertFieldsExactly(JsonNode node, Set<String> allowed, Set<String> required, String label) {
        Set<String> actual = fieldNames(node);
        Set<String> unexpected = new java.util.TreeSet<>(actual);
        unexpected.removeAll(allowed);
        assertTrue(unexpected.isEmpty(), label + " 出现契约外字段（additionalProperties: false）：" + unexpected);
        for (String field : required) {
            assertTrue(actual.contains(field), label + " 缺少契约必填字段：" + field);
        }
    }

    private static List<Long> idsOf(JsonNode data) {
        List<Long> ids = new ArrayList<>();
        data.get("items").forEach(item -> ids.add(Long.valueOf(item.get("id").asString())));
        return ids;
    }

    private static boolean containsRoute(JsonNode items, Long routeId) {
        for (JsonNode item : items) {
            if (item.get("id").asString().equals(String.valueOf(routeId))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 测试数据
    // ------------------------------------------------------------------

    private SysUser user(String nickname) {
        SysUser user = new SysUser();
        user.username = "route_browse_" + randomToken(12);
        user.nickname = nickname;
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        users.insert(user);
        return user;
    }

    private String bearer(SysUser user, String role) {
        return "Bearer " + tokens.createToken(user.id, user.username, Set.of(role));
    }

    private Guide guide() {
        Guide guide = new Guide();
        guide.userId = user("线路浏览测试导游").id;
        guide.name = "测试导游";
        guide.phone = "13800000000";
        guide.status = "ACTIVE";
        guides.insert(guide);
        return guide;
    }

    private Hotel hotel(String name) {
        Hotel hotel = new Hotel();
        hotel.name = name;
        hotel.address = "测试地址";
        hotel.status = 1;
        hotels.insert(hotel);
        return hotel;
    }

    private TravelRoute route(String name, int durationDays, BigDecimal ratingAvg, int bookingCount, String status) {
        TravelRoute route = new TravelRoute();
        route.name = name;
        route.departureCity = city;
        route.destination = destination;
        route.durationDays = durationDays;
        route.description = "线路浏览契约测试线路";
        route.coverUrl = "https://example.com/cover.jpg";
        route.included = "行程内交通与住宿";
        route.excluded = "个人消费";
        route.bookingNotice = "请携带有效身份证件";
        route.status = status;
        route.ratingAvg = ratingAvg;
        route.ratingCount = 3;
        route.validBookingCount = bookingCount;
        route.deleted = 0;
        routes.insert(route);
        return route;
    }

    private Departure departure(Long routeId, Long guideId, LocalDate startDate, BigDecimal adultPrice,
                                String status, int maxPeople, int reservedPeople, int confirmedPeople) {
        Departure departure = new Departure();
        departure.routeId = routeId;
        departure.startDate = startDate;
        departure.endDate = startDate.plusDays(5);
        departure.adultPrice = adultPrice;
        departure.childPrice = adultPrice.subtract(new BigDecimal("500.00"));
        departure.maxPeople = maxPeople;
        departure.reservedPeople = reservedPeople;
        departure.confirmedPeople = confirmedPeople;
        departure.guideId = guideId;
        departure.status = status;
        departure.version = 0;
        departures.insert(departure);
        return departure;
    }

    private RouteItineraryDay itineraryDay(Long routeId, int dayNumber, String title, Long hotelId) {
        RouteItineraryDay day = new RouteItineraryDay();
        day.routeId = routeId;
        day.dayNumber = dayNumber;
        day.title = title;
        day.description = "抵达并入住酒店。";
        day.transportation = "飞机、旅游巴士";
        day.meals = "晚餐";
        day.hotelId = hotelId;
        days.insert(day);
        return day;
    }

    private void itineraryItem(Long dayId, int sortNo, String itemType, String name) {
        RouteItineraryItem item = new RouteItineraryItem();
        item.dayId = dayId;
        item.sortNo = sortNo;
        item.itemType = itemType;
        item.name = name;
        item.description = name + " 的说明";
        items.insert(item);
    }

    private void review(TravelOrder order, SysUser author, Long routeId, int rating, String content) {
        review(order, author, routeId, rating, content, "VISIBLE");
    }

    private void review(TravelOrder order, SysUser author, Long routeId, int rating, String content, String status) {
        Review review = new Review();
        review.orderId = order.id;
        review.userId = author.id;
        review.routeId = routeId;
        review.rating = rating;
        review.content = content;
        review.status = status;
        reviews.insert(review);
    }

    /** review.order_id 唯一，因此可见评价与隐藏评价必须挂在两张不同订单上。 */
    private TravelOrder visibleOrder() {
        return order(owner);
    }

    private TravelOrder order(SysUser author) {
        TravelOrder order = new TravelOrder();
        order.orderNo = "TA-ROUTE-" + randomToken(12).toUpperCase();
        order.userId = author.id;
        order.routeId = cheap.id;
        order.departureId = openDepartureId;
        order.contactName = "测试联系人";
        order.contactPhone = "13800138000";
        order.adultCount = 1;
        order.childCount = 0;
        order.adultUnitPrice = new BigDecimal("1000.00");
        order.childUnitPrice = new BigDecimal("500.00");
        order.totalAmount = new BigDecimal("1000.00");
        order.status = "COMPLETED";
        order.paymentStatus = "PAID";
        order.completedAt = LocalDateTime.now();
        orders.insert(order);
        return order;
    }

    private void addFavorite(SysUser user, Long routeId) {
        Favorite favorite = new Favorite();
        favorite.userId = user.id;
        favorite.routeId = routeId;
        favorites.insert(favorite);
    }

    private static String randomToken(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }
}
