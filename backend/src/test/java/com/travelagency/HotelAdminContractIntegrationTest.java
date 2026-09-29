package com.travelagency;

import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.domain.entity.Hotel;
import com.travelagency.domain.entity.RouteItineraryDay;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelRoute;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 酒店资料管理（契约 {@code Admin Resources} 的 {@code /admin/hotels}）的数据库集成测试：
 * 走真实 HTTP 处理链、JWT、MyBatis-Plus 与 MySQL，覆盖只有连库才能验证的部分
 * —— 201 + {@code Location}、响应字段形状、修改后的可见性、204 删除与 409 引用冲突。
 *
 * <p><b>需要数据库</b>（未配置时整个类被跳过）：</p>
 * <pre>
 * $env:TRAVEL_MYSQL_TEST = "true"
 * mvn -ntp test -Dtest=HotelAdminContractIntegrationTest
 * </pre>
 *
 * <p>与 {@code HotelAdminWebContractTest} 的分工：后者不需要数据库，覆盖 401/403、
 * 契约外字段与字段校验；本类只覆盖"真的写进库、真的读出来"的那部分行为。</p>
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
class HotelAdminContractIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired HotelMapper hotels;
    @Autowired TravelRouteMapper routes;
    @Autowired RouteItineraryDayMapper days;
    @Autowired SysUserMapper users;
    @Autowired JwtTokenProvider tokens;
    @Autowired JsonMapper json;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /**
     * 创建 → 列表可见 → 修改 → 停用 → 未被引用时可删除。
     *
     * <p>逐条钉住的是旧实现的违约点：创建回 200 且无 {@code Location}、响应里
     * {@code status} 是整数而坐标是两位小数字符串、修改不存在也回 200、
     * 删除回 200 信封而不是 204。</p>
     */
    @Test
    @DisplayName("酒店管理全链路：201+Location → 列表 → 修改 → 停用 → 204 删除")
    void adminHotelLifecycleFollowsTheContract() throws Exception {
        String token = adminToken();
        String name = "契约酒店-" + shortId();

        // 创建：201 + Location + HotelEnvelope
        MockHttpServletResponse created = mvc()
                .perform(post("/api/admin/hotels").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"address\":\"云南省昆明市测试路 1 号\","
                                + "\"contactPhone\":\"087112345678\",\"longitude\":102.832,\"latitude\":24.88,"
                                + "\"intro\":\"演示简介\",\"dataSource\":\"团队测试数据\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.name").value(name))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn().getResponse();
        String id = data(created).path("id").asString();
        assertTrue(created.getHeader("Location").endsWith("/api/admin/hotels/" + id),
                "Location 必须指向新建资源：" + created.getHeader("Location"));
        assertTrue(Long.parseLong(id) > 0, "响应 id 必须是契约 Id（非空数字字符串）");

        // 响应字段形状：契约 Hotel 必填项齐全，status 是枚举、坐标是 JSON number
        JsonNode body = data(created);
        for (String required : new String[]{"id", "name", "status", "createdAt", "updatedAt"}) {
            assertNotNull(body.get(required), "响应缺少契约必填字段：" + required);
        }
        assertTrue(body.get("longitude").isNumber(), "坐标必须是 JSON number，不能是 BigDecimal 字符串");
        assertEquals(102.832, body.get("longitude").asDouble(), 0.0000001);
        assertEquals(24.88, body.get("latitude").asDouble(), 0.0000001);
        // 契约 Hotel 没有 city：多出来的字段会让严格模式的前端契约校验失败
        assertFalse(body.has("city"), "契约 Hotel 不含 city，不得由实体带出契约外字段");

        // 列表：keyword 命中，且形状与创建响应同口径
        JsonNode found = null;
        for (JsonNode item : okData(get("/api/admin/hotels").header("Authorization", token)
                .param("keyword", name)).path("items")) {
            if (id.equals(item.path("id").asString())) {
                found = item;
            }
        }
        assertNotNull(found, "keyword 应能检索到刚创建的酒店");
        assertEquals("ACTIVE", found.path("status").asString());
        assertEquals("087112345678", found.path("contactPhone").asString());

        // 修改：200 + 修改后的酒店，且 status 未提交时保持 ACTIVE
        JsonNode updated = okData(put("/api/admin/hotels/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "-改名\",\"address\":null,\"contactPhone\":null,"
                        + "\"longitude\":102.733,\"latitude\":25.044,\"intro\":null,"
                        + "\"dataSource\":\"团队测试数据\"}"));
        assertEquals(name + "-改名", updated.path("name").asString());
        assertTrue(updated.get("address").isNull(), "PUT 需要能清空可选字段");
        assertTrue(updated.get("contactPhone").isNull(), "PUT 需要能清空可选字段");
        assertEquals("ACTIVE", updated.path("status").asString(), "未提交 status 时状态不应发生变化");

        // 停用：状态必须真的落库成 0
        okData(put("/api/admin/hotels/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "-改名\",\"dataSource\":\"团队测试数据\","
                        + "\"status\":\"DISABLED\"}"));
        assertEquals(0, hotels.selectById(Long.parseLong(id)).status,
                "停用必须真的落库成 0");

        // 停用之后再提交一次不带 status 的资料编辑：状态必须原样留在 DISABLED。
        // 本用例证明不了并发下的丢失更新（那需要在本事务的读取与写回之间插入另一次提交，
        // 见 HotelServiceTest#concurrentDisableSurvivesAnEditThatDoesNotSubmitStatus）；
        // 它挡的是另一类回归：把"未提交 status"当成"按 ACTIVE 建档"。
        JsonNode editedWhileDisabled = okData(put("/api/admin/hotels/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "-再次编辑\",\"dataSource\":\"团队测试数据\"}"));
        assertEquals("DISABLED", editedWhileDisabled.path("status").asString(),
                "不带 status 的资料编辑不得改变已停用酒店的状态");
        assertEquals(0, hotels.selectById(Long.parseLong(id)).status,
                "未提交 status 时必须连 status 列都不写，库内仍应是 0");

        // 删除：未被任何行程引用 → 204 且无响应体
        MockHttpServletResponse deleted = mvc()
                .perform(delete("/api/admin/hotels/" + id).header("Authorization", token))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();
        assertEquals(0, deleted.getContentAsByteArray().length, "204 不能带响应体");
        assertNull(hotels.selectById(Long.parseLong(id)), "删除后库内不应再有该行");

        // 删除不存在的酒店：404（旧实现回 200，调用方会以为删掉了）
        mvc().perform(delete("/api/admin/hotels/999999999").header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // 修改不存在的酒店：404（旧实现是无条件 updateById，影响 0 行也回 200 + 请求体）
        mvc().perform(put("/api/admin/hotels/999999999").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"不存在\",\"dataSource\":\"团队测试数据\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /**
     * 被线路每日行程引用的酒店不能删除：契约 {@code DELETE /admin/hotels/{hotelId}}
     * 声明的是"删除未被行程引用的酒店资料"，冲突必须是 409。
     *
     * <p>旧实现直接 {@code deleteById}，只会撞上 {@code fk_day_hotel} 外键并返回 500。</p>
     */
    @Test
    @DisplayName("删除被行程引用的酒店：409，且酒店仍在库内")
    void deleteIsRejectedWhileItineraryDayStillReferencesTheHotel() throws Exception {
        String token = adminToken();
        Hotel hotel = hotel("引用中酒店-" + shortId());
        dayReferencing(hotel);

        mvc().perform(delete("/api/admin/hotels/" + hotel.id).header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOTEL_STATE_CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("线路行程")));
        assertNotNull(hotels.selectById(hotel.id), "409 时不得删除任何数据");
    }

    /**
     * 停用不是删除：被行程引用的酒店即使停用也仍然留着，行程里的酒店名不会凭空消失。
     *
     * <p>这条挡的是"把停用实现成删除"或"停用后行程联查取不到酒店名"这类回归 ——
     * 用户端线路详情的"住宿"一行直接来自 {@code route_itinerary_day.hotel_id} 的联查结果。</p>
     */
    @Test
    @DisplayName("停用被行程引用的酒店：资料仍在，行程联查仍有酒店名")
    void disablingAReferencedHotelKeepsTheItineraryIntact() throws Exception {
        String token = adminToken();
        Hotel hotel = hotel("停用酒店-" + shortId());
        RouteItineraryDay day = dayReferencing(hotel);

        okData(put("/api/admin/hotels/" + hotel.id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + hotel.name + "\",\"dataSource\":\"团队测试数据\","
                        + "\"status\":\"DISABLED\"}"));

        assertNotNull(hotels.selectById(hotel.id), "停用不得删除资料");
        assertEquals(0, hotels.selectById(hotel.id).status);
        RouteItineraryDay reloaded = days.selectById(day.id);
        assertEquals(hotel.id, reloaded.hotelId, "停用不得改动行程上的酒店关联");
    }

    /** 列表 keyword 的契约长度上限在真实服务上同样生效（100 码点通过、101 被拒）。 */
    @Test
    @DisplayName("列表 keyword：100 码点通过、101 码点 422")
    void listKeywordHonoursTheCodePointLimit() throws Exception {
        String token = adminToken();
        mvc().perform(get("/api/admin/hotels").header("Authorization", token)
                        .param("page", "1").param("size", "5").param("keyword", "😀".repeat(100)))
                .andExpect(status().isOk());
        mvc().perform(get("/api/admin/hotels").header("Authorization", token)
                        .param("page", "1").param("size", "5").param("keyword", "😀".repeat(101)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /** 分页参数按契约钳制：size 超过上限 100 时按 100 返回，而不是一次拉全表。 */
    @Test
    @DisplayName("列表分页：size 超上限被钳到 100，page 从 1 开始")
    void listClampsPagingParameters() throws Exception {
        String token = adminToken();
        JsonNode page = okData(get("/api/admin/hotels").header("Authorization", token)
                .param("page", "0").param("size", "1000"));
        assertEquals(1, page.path("page").asInt(), "page 小于 1 时按第 1 页处理");
        assertEquals(100, page.path("size").asInt(), "size 必须钳到契约上限 100");
        assertTrue(page.get("items").isArray(), "items 必须是数组，空数据时为 []");
    }

    // ===================== 夹具与断言工具 =====================

    /** 一个可用的管理员账号：JwtAuthenticationFilter 会回查账号状态，所以必须真实落库。 */
    private SysUser adminAccount() {
        SysUser user = new SysUser();
        user.username = "hotel_" + shortId();
        user.nickname = "酒店契约";
        user.realName = "酒店契约";
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        users.insert(user);
        return user;
    }

    private String token(SysUser user) {
        return "Bearer " + tokens.createToken(user.id, user.username, Set.of("ADMIN"));
    }

    /** 建账号并直接拿到令牌，供不需要复用账号的用例使用。 */
    private String adminToken() {
        return token(adminAccount());
    }

    private Hotel hotel(String name) {
        Hotel hotel = new Hotel();
        hotel.name = name;
        hotel.address = "云南省昆明市测试路 1 号";
        hotel.contactPhone = "087112345678";
        hotel.longitude = new BigDecimal("102.8320000");
        hotel.latitude = new BigDecimal("24.8800000");
        hotel.intro = "演示简介";
        hotel.dataSource = "团队测试数据";
        hotel.status = 1;
        hotels.insert(hotel);
        return hotel;
    }

    /** 建一条线路并把给定酒店排进它第一天的行程，返回该行程天。 */
    private RouteItineraryDay dayReferencing(Hotel hotel) {
        TravelRoute route = new TravelRoute();
        route.name = "酒店引用线路-" + shortId();
        route.departureCity = "昆明";
        route.destination = "大理";
        route.durationDays = 1;
        route.status = "DRAFT";
        route.ratingAvg = BigDecimal.ZERO;
        route.ratingCount = 0;
        route.validBookingCount = 0;
        route.deleted = 0;
        routes.insert(route);
        RouteItineraryDay day = new RouteItineraryDay();
        day.routeId = route.id;
        day.dayNumber = 1;
        day.title = "抵达并入住";
        day.hotelId = hotel.id;
        days.insert(day);
        return day;
    }

    /** 断言 200 并返回 data 节点。 */
    private JsonNode okData(MockHttpServletRequestBuilder request) throws Exception {
        return data(mvc().perform(request).andExpect(status().isOk()).andReturn().getResponse());
    }

    /** MockHttpServletResponse 默认按 ISO-8859-1 解码，中文会乱码，必须按 UTF-8 读字节。 */
    private JsonNode data(MockHttpServletResponse response) {
        return json.readTree(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8)).get("data");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
