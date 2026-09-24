package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.domain.entity.Attraction;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Hotel;
import com.travelagency.domain.entity.RouteItineraryDay;
import com.travelagency.domain.entity.RouteItineraryItem;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.AttractionMapper;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.HotelMapper;
import com.travelagency.domain.mapper.RouteItineraryDayMapper;
import com.travelagency.domain.mapper.RouteItineraryItemMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台「线路管理 + 行程管理」契约与业务规则集成测试。
 *
 * <p>需要可用的 MySQL（先执行 sql/schema.sql、sql/test-data.sql），并通过环境变量开启：</p>
 *
 * <pre>
 * $env:TRAVEL_MYSQL_TEST = "true"
 * mvn -ntp test -Dtest=RouteAdminContractIntegrationTest
 * </pre>
 *
 * <p>覆盖范围：创建/修改/上下架的状态码与响应结构、行程与行程项目的完整 CRUD、
 * 契约约束（未知字段 400、参数校验 422、状态冲突 409、资源不存在 404、删除 204）、
 * 权限（未登录 401、普通用户 403）以及「上架后行程结构冻结」的业务规则。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class RouteAdminContractIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired TravelRouteMapper routes;
    @Autowired RouteItineraryDayMapper days;
    @Autowired RouteItineraryItemMapper items;
    @Autowired AttractionMapper attractions;
    @Autowired HotelMapper hotels;
    @Autowired DepartureMapper departures;
    @Autowired SysUserMapper users;
    @Autowired JwtTokenProvider tokens;
    @Autowired JsonMapper json;

    private MockMvc mvc;
    private String staffToken;
    private String userToken;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        staffToken = bearer(account(), "STAFF");
        userToken = bearer(account(), "USER");
    }

    // ------------------------------------------------------------------
    // 线路：创建 / 修改 / 详情
    // ------------------------------------------------------------------

    @Test
    void createsDraftRouteWithCreatedStatusAndLocation() throws Exception {
        var response = mvc.perform(post("/api/admin/routes").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(routeBody("契约线路-" + suffix(), 6)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.ratingAvg").value("0.00"))
                .andExpect(jsonPath("$.data.ratingCount").value(0))
                .andExpect(jsonPath("$.data.validBookingCount").value(0))
                .andReturn().getResponse();

        JsonNode data = json.readTree(response.getContentAsString()).get("data");
        String id = data.get("id").asString();
        assertEquals("/api/admin/routes/" + id, response.getHeader("Location"));
        // 契约 Route 的字段必须齐全，且不得出现实体独有字段（additionalProperties: false）。
        for (String field : List.of("id", "name", "departureCity", "destination", "durationDays", "status",
                "ratingAvg", "ratingCount", "validBookingCount", "included", "excluded", "bookingNotice",
                "createdAt", "updatedAt", "favorite")) {
            assertTrue(data.has(field), "响应缺少契约字段：" + field);
        }
        assertFalse(data.has("createdBy"), "响应不应暴露实体字段 createdBy");
        assertFalse(data.has("deleted"), "响应不应暴露实体字段 deleted");
    }

    @Test
    void rejectsServerOwnedFieldsAndInvalidInput() throws Exception {
        // status 属于服务端字段，客户端提交会被严格反序列化拒绝（400）。
        mvc.perform(post("/api/admin/routes").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody("注入状态线路", 3).replace("\"durationDays\": 3",
                                "\"durationDays\": 3, \"status\": \"PUBLISHED\"")))
                .andExpect(status().isBadRequest());
        // 名称过短、天数为 0 属于字段语义校验失败（422）。
        mvc.perform(post("/api/admin/routes").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(routeBody("短", 0)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void updatesRouteAndClearsNullableFields() throws Exception {
        String id = createRoute("待修改线路-" + suffix(), 4);
        String body = """
                {"name": "修改后线路", "departureCity": "北京", "destination": "新疆", "durationDays": 8,
                 "description": null, "coverUrl": null, "included": null, "excluded": null, "bookingNotice": null}
                """;
        mvc.perform(put("/api/admin/routes/" + id).header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("修改后线路"))
                .andExpect(jsonPath("$.data.destination").value("新疆"))
                .andExpect(jsonPath("$.data.durationDays").value(8))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        TravelRoute saved = routes.selectById(id);
        assertEquals("修改后线路", saved.name);
        assertNull(saved.description, "PUT 应当允许清空可空字段");
        assertNull(saved.coverUrl);
        assertEquals("DRAFT", saved.status, "修改基本资料不应改变上架状态");
    }

    @Test
    void missingRouteOperationsReturnNotFound() throws Exception {
        String missing = "9223372036854775807";
        mvc.perform(get("/api/admin/routes/" + missing).header("Authorization", staffToken))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/admin/routes/" + missing).header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(routeBody("不存在线路", 3)))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/admin/routes/" + missing + "/status").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 线路：上下架
    // ------------------------------------------------------------------

    @Test
    void publishRequiresItineraryAndOnlyAcceptsPublishOrOffline() throws Exception {
        String id = createRoute("待上架线路-" + suffix(), 3);

        // 没有任何每日行程时不允许上架（409）。
        mvc.perform(patch("/api/admin/routes/" + id + "/status").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROUTE_STATE_CONFLICT"));

        // 非法状态（DRAFT 不能通过上下架接口回退）返回 422。
        mvc.perform(patch("/api/admin/routes/" + id + "/status").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DRAFT\"}"))
                .andExpect(status().isUnprocessableContent());

        createDay(id, 1, "上海 → 昆明");
        mvc.perform(patch("/api/admin/routes/" + id + "/status").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        assertEquals("PUBLISHED", routes.selectById(id).status);

        mvc.perform(patch("/api/admin/routes/" + id + "/status").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OFFLINE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OFFLINE"));
    }

    @Test
    void publishedRouteFreezesItineraryStructure() throws Exception {
        String id = createRoute("已上架线路-" + suffix(), 3);
        String dayId = createDay(id, 1, "第一天");
        mvc.perform(patch("/api/admin/routes/" + id + "/status").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk());

        // 已上架线路不允许新增/删除每日行程，必须先下架。
        mvc.perform(post("/api/admin/routes/" + id + "/itinerary-days").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dayNumber\":2,\"title\":\"第二天\"}"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/itinerary-days/" + dayId).header("Authorization", staffToken))
                .andExpect(status().isConflict());
        // 但允许修正已存在的行程文字内容。
        mvc.perform(put("/api/admin/itinerary-days/" + dayId).header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayNumber\":1,\"title\":\"第一天（已修订）\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("第一天（已修订）"));

        mvc.perform(patch("/api/admin/routes/" + id + "/status").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OFFLINE\"}"))
                .andExpect(status().isOk());
        createDay(id, 2, "第二天");
    }

    // ------------------------------------------------------------------
    // 行程：每日行程与行程项目
    // ------------------------------------------------------------------

    @Test
    void managesItineraryDaysWithContractShapeAndCascadeDelete() throws Exception {
        String id = createRoute("行程管理线路-" + suffix(), 3);
        Hotel hotel = hotel();
        String dayBody = "{\"dayNumber\":1,\"title\":\"上海 → 昆明\",\"description\":\"抵达并入住\","
                + "\"transportation\":\"飞机\",\"meals\":\"晚餐\",\"hotelId\":\"" + hotel.id + "\"}";

        var response = mvc.perform(post("/api/admin/routes/" + id + "/itinerary-days")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(dayBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.hotelName").value(hotel.name))
                .andExpect(jsonPath("$.data.items").isArray())
                .andReturn().getResponse();
        String dayId = json.readTree(response.getContentAsString()).get("data").get("id").asString();
        assertEquals("/api/admin/itinerary-days/" + dayId, response.getHeader("Location"));

        // 同一线路重复的 dayNumber 冲突（409）。
        mvc.perform(post("/api/admin/routes/" + id + "/itinerary-days").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayNumber\":1,\"title\":\"重复天数\"}"))
                .andExpect(status().isConflict());
        // 引用不存在的酒店属于字段语义错误（422）。
        mvc.perform(post("/api/admin/routes/" + id + "/itinerary-days").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayNumber\":2,\"title\":\"第二天\",\"hotelId\":\"9223372036854775807\"}"))
                .andExpect(status().isUnprocessableContent());

        addItem(dayId, "大理古城");
        mvc.perform(get("/api/admin/routes/" + id + "/itinerary-days").header("Authorization", staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].dayNumber").value(1))
                .andExpect(jsonPath("$.data[0].hotelName").value(hotel.name))
                .andExpect(jsonPath("$.data[0].items[0].name").value("大理古城"))
                // 未关联景点时经纬度为 null（契约允许），这里确认序列化不会报错。
                .andExpect(jsonPath("$.data[0].items[0].longitude").value(nullValue()));

        // 修改天数序号冲突时返回 422，而不是让数据库唯一键报错变成 500。
        String secondDayId = createDay(id, 2, "第二天");
        mvc.perform(put("/api/admin/itinerary-days/" + secondDayId).header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayNumber\":1,\"title\":\"与第一天冲突\"}"))
                .andExpect(status().isUnprocessableContent());

        // 删除每日行程需要级联删除其行程项目（204 且响应体为空）。
        mvc.perform(delete("/api/admin/itinerary-days/" + dayId).header("Authorization", staffToken))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertNull(days.selectById(dayId));
        assertEquals(0L, items.selectCount(new QueryWrapper<RouteItineraryItem>().eq("day_id", dayId)).longValue());
        mvc.perform(delete("/api/admin/itinerary-days/" + dayId).header("Authorization", staffToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void managesItineraryItemsWithContractShape() throws Exception {
        String id = createRoute("行程项目线路-" + suffix(), 3);
        String dayId = createDay(id, 1, "第一天");
        Attraction attraction = attraction();
        String itemBody = "{\"sortNo\":1,\"itemType\":\"ATTRACTION\",\"name\":\"大理古城\","
                + "\"description\":\"游览古城\",\"attractionId\":\"" + attraction.id + "\"}";

        var response = mvc.perform(post("/api/admin/itinerary-days/" + dayId + "/items")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(itemBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sortNo").value(1))
                .andExpect(jsonPath("$.data.itemType").value("ATTRACTION"))
                // 契约把 Longitude / Latitude 定义为 JSON number，未填写时沿用景点坐标。
                .andExpect(jsonPath("$.data.longitude").value(attraction.longitude.doubleValue()))
                .andExpect(jsonPath("$.data.latitude").value(attraction.latitude.doubleValue()))
                .andReturn().getResponse();
        JsonNode data = json.readTree(response.getContentAsString()).get("data");
        String itemId = data.get("id").asString();
        assertEquals("/api/admin/itinerary-items/" + itemId, response.getHeader("Location"));
        assertFalse(data.has("createdAt"), "响应不应暴露实体字段 createdAt");
        assertFalse(data.has("dayId"), "响应不应暴露实体字段 dayId");

        // 排序号在同一日内重复 → 422。
        mvc.perform(post("/api/admin/itinerary-days/" + dayId + "/items").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortNo\":1,\"itemType\":\"MEAL\",\"name\":\"午餐\"}"))
                .andExpect(status().isUnprocessableContent());
        // 项目类型必须在契约枚举内 → 422。
        mvc.perform(post("/api/admin/itinerary-days/" + dayId + "/items").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortNo\":2,\"itemType\":\"SHOPPING\",\"name\":\"购物店\"}"))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(get("/api/admin/itinerary-days/" + dayId + "/items").header("Authorization", staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("大理古城"));

        mvc.perform(put("/api/admin/itinerary-items/" + itemId).header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortNo\":3,\"itemType\":\"ACTIVITY\",\"name\":\"洱海骑行\","
                                + "\"description\":null,\"attractionId\":null,"
                                + "\"longitude\":100.2,\"latitude\":25.6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sortNo").value(3))
                .andExpect(jsonPath("$.data.itemType").value("ACTIVITY"))
                .andExpect(jsonPath("$.data.attractionId").value(nullValue()));

        mvc.perform(delete("/api/admin/itinerary-items/" + itemId).header("Authorization", staffToken))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertNull(items.selectById(itemId));
        mvc.perform(delete("/api/admin/itinerary-items/" + itemId).header("Authorization", staffToken))
                .andExpect(status().isNotFound());

        // 不存在的每日行程 → 404
        mvc.perform(get("/api/admin/itinerary-days/9223372036854775807/items").header("Authorization", staffToken))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 列表与详情：契约结构
    // ------------------------------------------------------------------

    @Test
    void listRoutesReturnsSummaryPageAndValidatesStatusFilter() throws Exception {
        createRoute("列表契约线路-" + suffix(), 3);

        var response = mvc.perform(get("/api/admin/routes")
                        .header("Authorization", staffToken)
                        .param("page", "1").param("size", "5").param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(5))
                .andReturn().getResponse();

        JsonNode data = json.readTree(response.getContentAsString()).get("data");
        assertTrue(data.has("total"));
        assertTrue(data.has("totalPages"));
        JsonNode first = data.get("items").get(0);
        for (String field : List.of("id", "name", "departureCity", "destination", "durationDays",
                "ratingAvg", "ratingCount", "validBookingCount", "status")) {
            assertTrue(first.has(field), "列表项缺少契约字段：" + field);
        }
        assertFalse(first.has("included"), "RouteSummary 不应包含 Route 独有字段 included");

        mvc.perform(get("/api/admin/routes").header("Authorization", staffToken)
                        .param("status", "NOT_A_STATUS"))
                .andExpect(status().isUnprocessableContent());
    }

    /**
     * 线路详情必须返回完整契约结构。
     *
     * <p>同时覆盖两个"联查结果为空 + 关联键为 null"的回归场景：行程没有安排酒店（hotelId=null）、
     * 团期没有分配导游（guideId=null）。这两条路径曾经因为对 {@code Map.of()} 取 null 键
     * 触发 NullPointerException，导致详情接口 500。</p>
     */
    @Test
    void routeDetailReturnsWholeContractStructure() throws Exception {
        String id = createRoute("详情契约线路-" + suffix(), 3);
        String dayId = createDay(id, 1, "第一天");
        addItem(dayId, "石林");
        insertDepartureWithoutGuide(id);

        var response = mvc.perform(get("/api/admin/routes/" + id).header("Authorization", staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.route.id").value(id))
                .andExpect(jsonPath("$.data.route.name").isNotEmpty())
                .andExpect(jsonPath("$.data.departures[0].guideName").value(nullValue()))
                .andExpect(jsonPath("$.data.itinerary[0].dayNumber").value(1))
                .andExpect(jsonPath("$.data.itinerary[0].hotelName").value(nullValue()))
                .andExpect(jsonPath("$.data.itinerary[0].items[0].name").value("石林"))
                .andExpect(jsonPath("$.data.reviews").isArray())
                .andExpect(jsonPath("$.data.favorite").value(false))
                .andReturn().getResponse();

        JsonNode data = json.readTree(response.getContentAsString()).get("data");
        assertTrue(data.get("route").has("bookingNotice"), "详情中的 route 应为契约 Route");
        assertFalse(data.get("itinerary").get(0).has("createdAt"),
                "ItineraryDay 不应包含实体字段 createdAt");

        // 未安排酒店的行程在独立列表接口同样不能报错。
        mvc.perform(get("/api/admin/routes/" + id + "/itinerary-days").header("Authorization", staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].hotelName").value(nullValue()))
                .andExpect(jsonPath("$.data[0].hotelId").value(nullValue()));
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    @Test
    void requiresStaffOrAdminRole() throws Exception {
        mvc.perform(get("/api/admin/routes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/routes").header("Authorization", userToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/routes").header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(routeBody("无权限线路", 3)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/routes").header("Authorization", staffToken))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    private String routeBody(String name, int durationDays) {
        return """
                {"name": "%s", "departureCity": "上海", "destination": "云南", "durationDays": %d,
                 "description": "测试线路简介", "coverUrl": "https://example.test/cover.png",
                 "included": "交通住宿", "excluded": "个人消费", "bookingNotice": "携带身份证"}
                """.formatted(name, durationDays);
    }

    private String createRoute(String name, int durationDays) throws Exception {
        String response = mvc.perform(post("/api/admin/routes").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(routeBody(name, durationDays)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("data").get("id").asString();
    }

    private String createDay(String routeId, int dayNumber, String title) throws Exception {
        String body = "{\"dayNumber\":" + dayNumber + ",\"title\":\"" + title + "\"}";
        String response = mvc.perform(post("/api/admin/routes/" + routeId + "/itinerary-days")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("data").get("id").asString();
    }

    /** 通过接口为某天添加一个行程项目，返回项目 id。 */
    private String addItem(String dayId, String name) throws Exception {
        String body = "{\"sortNo\":1,\"itemType\":\"ATTRACTION\",\"name\":\"" + name + "\"}";
        String response = mvc.perform(post("/api/admin/itinerary-days/" + dayId + "/items")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("data").get("id").asString();
    }

    private Hotel hotel() {
        Hotel hotel = new Hotel();
        hotel.name = "契约测试酒店-" + suffix();
        hotel.address = "云南省昆明市";
        hotel.status = 1;
        hotel.longitude = new BigDecimal("102.8328910");
        hotel.latitude = new BigDecimal("24.8800950");
        hotels.insert(hotel);
        return hotel;
    }

    private Attraction attraction() {
        Attraction attraction = new Attraction();
        attraction.name = "契约测试景点-" + suffix();
        attraction.city = "大理";
        attraction.status = 1;
        attraction.longitude = new BigDecimal("100.1645720");
        attraction.latitude = new BigDecimal("25.6064850");
        attractions.insert(attraction);
        return attraction;
    }

    /** 为线路插入一个没有分配导游的团期，用于覆盖 guideId 为 null 的联查路径。 */
    private void insertDepartureWithoutGuide(String routeId) {
        Departure departure = new Departure();
        departure.routeId = Long.valueOf(routeId);
        departure.startDate = java.time.LocalDate.now().plusDays(30);
        departure.endDate = java.time.LocalDate.now().plusDays(33);
        departure.adultPrice = new BigDecimal("2999.00");
        departure.childPrice = new BigDecimal("1999.00");
        departure.maxPeople = 20;
        departure.reservedPeople = 0;
        departure.confirmedPeople = 0;
        departure.status = "OPEN";
        departure.guideId = null;
        departure.version = 0;
        departures.insert(departure);
    }

    private SysUser account() {
        SysUser user = new SysUser();
        user.username = "route_test_" + suffix();
        user.nickname = "Route contract test";
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        user.createdAt = LocalDateTime.now();
        users.insert(user);
        return user;
    }

    private String bearer(SysUser user, String role) {
        return "Bearer " + tokens.createToken(user.id, user.username, Set.of(role));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
