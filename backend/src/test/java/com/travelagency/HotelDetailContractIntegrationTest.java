package com.travelagency;

import com.travelagency.common.security.JwtTokenProvider;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.domain.entity.Hotel;
import com.travelagency.domain.entity.HotelImage;
import com.travelagency.domain.entity.RouteItineraryDay;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.HotelImageMapper;
import com.travelagency.domain.mapper.HotelMapper;
import com.travelagency.domain.mapper.RouteItineraryDayMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
class HotelDetailContractIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired HotelMapper hotels;
    @Autowired HotelImageMapper hotelImages;
    @Autowired RouteItineraryDayMapper days;
    @Autowired TravelRouteMapper routes;
    @Autowired SysUserMapper users;
    @Autowired JwtTokenProvider tokens;
    @Autowired JsonMapper json;

    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        }
        return mvc;
    }

    @Test
    @DisplayName("公开详情：已发布线路安排过的启用酒店返回公开字段，不含后台管理信息")
    void publicHotelDetailExposesOnlyPublicFields() throws Exception {
        Hotel hotel = hotel("公开详情酒店", 1);
        hotel.city = "杭州";
        hotel.coverUrl = "https://example.com/cover.jpg";
        hotel.starRating = 4;
        hotel.facilities = "[\"WIFI\",\"PARKING\"]";
        hotel.checkInTime = "14:00";
        hotel.checkOutTime = "12:00";
        hotels.updateById(hotel);
        image(hotel.id, "https://example.com/3.jpg", 3);
        image(hotel.id, "https://example.com/1.jpg", 1);
        image(hotel.id, "https://example.com/2.jpg", 2);
        TravelRoute route = route("公开详情线路", "PUBLISHED", hotel.id, "HOTEL");

        JsonNode data = okData(get("/api/routes/{routeId}/hotels/{hotelId}", route.id, hotel.id));

        assertEquals(hotel.id.toString(), data.path("id").asText(), "契约 Id 是字符串");
        assertEquals("公开详情酒店", data.path("name").asText());
        assertEquals("杭州", data.path("city").asText());
        assertEquals(4, data.path("starRating").asInt());
        assertEquals("14:00", data.path("checkInTime").asText());
        assertEquals("12:00", data.path("checkOutTime").asText());
        assertEquals("WIFI", data.path("facilities").get(0).asText());
        assertEquals(3, data.path("images").size());
        assertTrue(data.get("longitude").isNumber(), "坐标必须是 JSON number");
        assertEquals("团队测试数据", data.path("dataSource").asText());

        for (String leaked : List.of("version", "status", "contactPhone", "createdAt", "updatedAt")) {
            assertFalse(data.has(leaked), "公开详情不得返回后台字段：" + leaked);
        }
    }

    @Test
    @DisplayName("公开详情：未发布线路统一 404 RESOURCE_NOT_FOUND")
    void publicHotelDetailHidesUnpublishedRoutes() throws Exception {
        Hotel hotel = hotel("未发布线路酒店", 1);
        for (String status : List.of("DRAFT", "OFFLINE")) {
            TravelRoute route = route("未发布线路-" + status, status, hotel.id, "HOTEL");
            assertPublicNotFound(route.id, hotel.id);
        }

        TravelRoute deleted = route("已删除线路", "PUBLISHED", hotel.id, "HOTEL");
        deleted.deleted = 1;
        routes.updateById(deleted);
        assertPublicNotFound(deleted.id, hotel.id);
    }

    @Test
    @DisplayName("公开详情：酒店未被这条线路引用时 404")
    void publicHotelDetailHidesHotelsNotArrangedInTheRoute() throws Exception {
        Hotel arranged = hotel("本线路酒店", 1);
        Hotel unrelated = hotel("无关酒店", 1);
        TravelRoute route = route("无关酒店线路", "PUBLISHED", arranged.id, "HOTEL");

        assertPublicNotFound(route.id, unrelated.id);
        okData(get("/api/routes/{routeId}/hotels/{hotelId}", route.id, arranged.id));
    }

    @Test
    @DisplayName("公开详情：停用酒店 404，行程仍保留酒店名称但不给摘要")
    void publicHotelDetailHidesDisabledHotels() throws Exception {
        Hotel disabled = hotel("已停用酒店", 0);
        TravelRoute route = route("停用酒店线路", "PUBLISHED", disabled.id, "HOTEL");

        assertPublicNotFound(route.id, disabled.id);

        JsonNode itinerary = okData(get("/api/routes/{routeId}", route.id)).path("itinerary").get(0);
        assertEquals("已停用酒店", itinerary.path("hotelName").asText(),
                "行程里安排的酒店名称是历史事实，不因停用而改写");
        assertTrue(itinerary.get("hotel").isNull(),
                "停用酒店不返回公开摘要，用户端才不会给出打不开的详情入口");
    }

    @Test
    @DisplayName("住宿校验：类型与酒店关联不一致时 422，合法的 STANDARD / NONE 能落库")
    void adminRejectsInconsistentAccommodationArrangement() throws Exception {
        String token = adminToken();
        Hotel hotel = hotel("住宿校验酒店", 1);
        TravelRoute route = route("住宿校验线路", "DRAFT", null, null);
        Long dayId = dayIdOf(route.id, 1);

        postDay(token, route.id, 2, "{\"dayNumber\":2,\"title\":\"第二天\",\"accommodationType\":\"HOTEL\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("hotelId"));
        postDay(token, route.id, 3, "{\"dayNumber\":3,\"title\":\"第三天\",\"accommodationType\":\"STANDARD\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("accommodationStandard"));
        postDay(token, route.id, 4, "{\"dayNumber\":4,\"title\":\"第四天\",\"accommodationType\":\"NONE\","
                + "\"hotelId\":\"" + hotel.id + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("hotelId"));
        postDay(token, route.id, 5, "{\"dayNumber\":5,\"title\":\"第五天\",\"accommodationType\":\"STANDARD\","
                + "\"accommodationStandard\":\"市区舒适型酒店\",\"hotelId\":\"" + hotel.id + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("hotelId"));
        postDay(token, route.id, 6, "{\"dayNumber\":6,\"title\":\"第六天\",\"accommodationType\":\"CAMPING\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        putDay(token, dayId, "{\"dayNumber\":1,\"title\":\"第一天\",\"accommodationType\":\"STANDARD\","
                + "\"accommodationStandard\":\"市区舒适型酒店\",\"roomType\":\"双床房\","
                + "\"breakfastIncluded\":true,\"accommodationNote\":\"具体酒店以出团通知为准。\"}")
                .andExpect(status().isOk());
        JsonNode standard = dayOf(okData(get("/api/admin/routes/{routeId}/itinerary-days", route.id).header("Authorization", token)), 1);
        assertEquals("STANDARD", standard.path("accommodationType").asText());
        assertEquals("市区舒适型酒店", standard.path("accommodationStandard").asText());
        assertEquals("双床房", standard.path("roomType").asText());
        assertTrue(standard.path("breakfastIncluded").asBoolean());
        assertTrue(standard.get("hotelId").isNull(), "STANDARD 的 hotelId 必须为空");
        assertTrue(standard.get("hotel").isNull());

        Long noneDayId = addDay(token, route.id, 7, "第七天");
        putDay(token, noneDayId, "{\"dayNumber\":7,\"title\":\"第七天\",\"accommodationType\":\"NONE\","
                + "\"accommodationNote\":\"当天返程，不含住宿。\"}")
                .andExpect(status().isOk());
        Long pendingDayId = addDay(token, route.id, 8, "第八天");
        putDay(token, pendingDayId, "{\"dayNumber\":8,\"title\":\"第八天\"}")
                .andExpect(status().isOk());
        JsonNode dayViews = okData(get("/api/admin/routes/{routeId}/itinerary-days", route.id).header("Authorization", token));
        assertEquals("NONE", dayOf(dayViews, 7).path("accommodationType").asText());
        assertEquals("PENDING", dayOf(dayViews, 8).path("accommodationType").asText(),
                "缺少酒店关联必须落到 PENDING，不能解释成不含住宿");
    }

    @Test
    @DisplayName("住宿校验：HOTEL 关联酒店并保留三态早餐说明")
    void adminPersistsHotelArrangementWithThreeStateBreakfast() throws Exception {
        String token = adminToken();
        Hotel hotel = hotel("三态早餐酒店", 1);
        TravelRoute route = route("三态早餐线路", "DRAFT", null, null);

        Long dayId = dayIdOf(route.id, 1);
        putDay(token, dayId, "{\"dayNumber\":1,\"title\":\"第一天\",\"accommodationType\":\"HOTEL\","
                + "\"hotelId\":\"" + hotel.id + "\",\"roomType\":\"大床房\",\"breakfastIncluded\":false}")
                .andExpect(status().isOk());

        JsonNode day = dayOf(okData(get("/api/admin/routes/{routeId}/itinerary-days", route.id).header("Authorization", token)), 1);
        assertEquals("HOTEL", day.path("accommodationType").asText());
        assertEquals(hotel.id.toString(), day.path("hotelId").asText());
        assertEquals("三态早餐酒店", day.path("hotelName").asText());
        assertEquals("大床房", day.path("roomType").asText());
        assertFalse(day.path("breakfastIncluded").asBoolean(),
                "false 必须与「未说明」区分：这里断言的正是「显式不含早餐」");
        assertNotNull(day.get("breakfastIncluded"), "breakfastIncluded 必须存在（三态字段）");
        assertEquals(hotel.id.toString(), day.path("hotel").path("id").asText(), "HOTEL 应带出酒店摘要");
        assertEquals("大理", day.path("hotel").path("city").asText());

        putDay(token, dayId, "{\"dayNumber\":1,\"title\":\"第一天\",\"accommodationType\":\"HOTEL\","
                + "\"hotelId\":\"" + hotel.id + "\"}")
                .andExpect(status().isOk());
        JsonNode cleared = dayOf(okData(get("/api/admin/routes/{routeId}/itinerary-days", route.id).header("Authorization", token)), 1);
        assertTrue(cleared.get("breakfastIncluded").isNull(),
                "未提交即尚未说明；PUT 是整体替换，必须能把它清成 null");
    }

    @Test
    @DisplayName("图片排序：按 sortOrder 升序返回，与提交顺序无关")
    void hotelImagesAreOrderedBySortOrder() throws Exception {
        String token = adminToken();

        MockHttpServletResponse created = mvc().perform(post("/api/admin/hotels")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"图片排序酒店-" + shortId() + "\",\"city\":\"杭州\","
                                + "\"dataSource\":\"团队测试数据\",\"images\":["
                                + "{\"url\":\"https://example.com/3.jpg\",\"alt\":\"外景\",\"sortOrder\":3},"
                                + "{\"url\":\"https://example.com/1.jpg\",\"sortOrder\":1},"
                                + "{\"url\":\"https://example.com/2.jpg\",\"alt\":\"客房\",\"sortOrder\":2}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        JsonNode createdHotel = data(created);
        String hotelId = createdHotel.path("id").asText();
        assertEquals(List.of("https://example.com/1.jpg", "https://example.com/2.jpg", "https://example.com/3.jpg"),
                urls(createdHotel.path("images")), "创建响应就应按 sortOrder 升序");

        JsonNode fetched = okData(get("/api/admin/hotels/{hotelId}", hotelId).header("Authorization", token));
        assertEquals(List.of("https://example.com/1.jpg", "https://example.com/2.jpg", "https://example.com/3.jpg"),
                urls(fetched.path("images")), "重新读取必须给出同一顺序");

        TravelRoute route = route("图片排序线路", "PUBLISHED", Long.valueOf(hotelId), "HOTEL");
        JsonNode publicDetail = okData(get("/api/routes/{routeId}/hotels/{hotelId}",
                route.id, Long.valueOf(hotelId)));
        assertEquals(List.of("https://example.com/1.jpg", "https://example.com/2.jpg", "https://example.com/3.jpg"),
                urls(publicDetail.path("images")), "公开详情与后台上传顺序口径一致");
    }

    @Test
    @DisplayName("版本冲突：过期 version 返回 409，且资料与图片都不被替换")
    void staleHotelVersionIsRejectedWithoutTouchingImages() throws Exception {
        String token = adminToken();
        Hotel hotel = hotel("版本冲突酒店", 1);
        image(hotel.id, "https://example.com/keep.jpg", 1);

        String payload = "{\"name\":\"版本冲突酒店-改名\",\"city\":\"大理\",\"dataSource\":\"团队测试数据\","
                + "\"images\":[{\"url\":\"https://example.com/replaced.jpg\",\"sortOrder\":1}],"
                + "\"version\":1}";
        mvc().perform(put("/api/admin/hotels/{hotelId}", hotel.id)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOTEL_VERSION_CONFLICT"));

        JsonNode stored = okData(get("/api/admin/hotels/{hotelId}", hotel.id).header("Authorization", token));
        assertEquals(0, stored.path("version").asInt(), "冲突的修改不得推进版本号");
        assertEquals("版本冲突酒店", stored.path("name").asText(), "冲突的修改不得写入资料");
        assertEquals(List.of("https://example.com/keep.jpg"), urls(stored.path("images")),
                "冲突的修改不得替换图片");

        JsonNode updated = okData(put("/api/admin/hotels/{hotelId}", hotel.id)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.replace("\"version\":1", "\"version\":0")));
        assertEquals(1, updated.path("version").asInt());
        assertEquals(List.of("https://example.com/replaced.jpg"), urls(updated.path("images")));
    }

    private static List<String> urls(JsonNode images) {
        List<String> result = new java.util.ArrayList<>();
        for (JsonNode image : images) {
            result.add(image.path("url").asText());
        }
        return result;
    }

    private static JsonNode dayOf(JsonNode dayList, int dayNumber) {
        for (JsonNode day : dayList) {
            if (day.path("dayNumber").asInt() == dayNumber) {
                return day;
            }
        }
        throw new AssertionError("行程里没有第 " + dayNumber + " 天");
    }

    private void assertPublicNotFound(Long routeId, Long hotelId) throws Exception {
        mvc().perform(get("/api/routes/{routeId}/hotels/{hotelId}", routeId, hotelId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private org.springframework.test.web.servlet.ResultActions postDay(
            String token, Long routeId, int dayNumber, String body) throws Exception {
        return mvc().perform(post("/api/admin/routes/{routeId}/itinerary-days", routeId)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions putDay(
            String token, Long dayId, String body) throws Exception {
        return mvc().perform(put("/api/admin/itinerary-days/{dayId}", dayId)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Long addDay(String token, Long routeId, int dayNumber, String title) throws Exception {
        postDay(token, routeId, dayNumber,
                "{\"dayNumber\":" + dayNumber + ",\"title\":\"" + title + "\"}")
                .andExpect(status().isCreated());
        return dayIdOf(routeId, dayNumber);
    }

    private Long dayIdOf(Long routeId, int dayNumber) {
        return days.selectList(new QueryWrapper<RouteItineraryDay>()
                        .eq("route_id", routeId).eq("day_number", dayNumber))
                .get(0).id;
    }

    private Hotel hotel(String name, int status) {
        Hotel hotel = new Hotel();
        hotel.name = name;
        hotel.city = "大理";
        hotel.address = "云南省昆明市测试路 1 号";
        hotel.contactPhone = "087112345678";
        hotel.longitude = new BigDecimal("102.8320000");
        hotel.latitude = new BigDecimal("24.8800000");
        hotel.intro = "演示简介";
        hotel.dataSource = "团队测试数据";
        hotel.status = status;
        hotel.version = 0;
        hotels.insert(hotel);
        return hotel;
    }

    private void image(Long hotelId, String url, int sortOrder) {
        HotelImage image = new HotelImage();
        image.hotelId = hotelId;
        image.url = url;
        image.sortOrder = sortOrder;
        hotelImages.insert(image);
    }

    private TravelRoute route(String name, String status, Long hotelId, String accommodationType) {
        TravelRoute route = new TravelRoute();
        route.name = name + "-" + shortId();
        route.departureCity = "昆明";
        route.destination = "大理";
        route.durationDays = 1;
        route.status = status;
        route.ratingAvg = BigDecimal.ZERO;
        route.ratingCount = 0;
        route.validBookingCount = 0;
        route.deleted = 0;
        routes.insert(route);
        RouteItineraryDay day = new RouteItineraryDay();
        day.routeId = route.id;
        day.dayNumber = 1;
        day.title = "抵达并入住";
        day.hotelId = hotelId;
        if (accommodationType != null) {
            day.accommodationType = accommodationType;
        }
        days.insert(day);
        return route;
    }

    private SysUser adminAccount() {
        SysUser user = new SysUser();
        user.username = "hotel_detail_" + shortId();
        user.nickname = "酒店住宿契约";
        user.realName = "酒店住宿契约";
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        users.insert(user);
        return user;
    }

    private String adminToken() {
        SysUser user = adminAccount();
        return "Bearer " + tokens.createToken(user.id, user.username, Set.of("ADMIN"));
    }

    private JsonNode okData(MockHttpServletRequestBuilder request) throws Exception {
        return data(mvc().perform(request).andExpect(status().isOk()).andReturn().getResponse());
    }

    private JsonNode data(MockHttpServletResponse response) {
        return json.readTree(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8)).get("data");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
