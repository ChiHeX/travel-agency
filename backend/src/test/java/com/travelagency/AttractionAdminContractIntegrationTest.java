package com.travelagency;

import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.domain.entity.Attraction;
import com.travelagency.domain.entity.PlaceGuide;
import com.travelagency.domain.entity.PlaceGuideItem;
import com.travelagency.domain.entity.RouteItineraryDay;
import com.travelagency.domain.entity.RouteItineraryItem;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.AttractionMapper;
import com.travelagency.domain.mapper.PlaceGuideItemMapper;
import com.travelagency.domain.mapper.PlaceGuideMapper;
import com.travelagency.domain.mapper.RouteItineraryDayMapper;
import com.travelagency.domain.mapper.RouteItineraryItemMapper;
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
 * 景点管理（契约 {@code Admin Resources} 的 {@code /admin/attractions}）的数据库集成测试：
 * 走真实 HTTP 处理链、JWT、MyBatis-Plus 与 MySQL，覆盖只有连库才能验证的部分
 * —— 201 + {@code Location}、响应字段形状、修改后的可见性、204 删除与 409 引用冲突。
 *
 * <p><b>需要数据库</b>（未配置时整个类被跳过）：</p>
 * <pre>
 * $env:TRAVEL_MYSQL_TEST = "true"
 * mvn -ntp test -Dtest=AttractionAdminContractIntegrationTest
 * </pre>
 *
 * <p>与 {@code AttractionAdminWebContractTest} 的分工：后者不需要数据库，覆盖 401/403、
 * 契约外字段与字段校验；本类只覆盖"真的写进库、真的读出来"的那部分行为。</p>
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
class AttractionAdminContractIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired AttractionMapper attractions;
    @Autowired TravelRouteMapper routes;
    @Autowired RouteItineraryDayMapper days;
    @Autowired RouteItineraryItemMapper items;
    @Autowired PlaceGuideMapper guides;
    @Autowired PlaceGuideItemMapper guideItems;
    @Autowired SysUserMapper users;
    @Autowired JwtTokenProvider tokens;
    @Autowired JsonMapper json;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /**
     * 创建 → 列表可见 → 修改 → 停用后从公开接口消失 → 未被引用时可删除。
     *
     * <p>逐条钉住的是旧实现的违约点：创建回 200 且无 {@code Location}、响应里
     * {@code status} 是整数而坐标是两位小数字符串、修改不存在也回 200、
     * 删除回 200 信封而不是 204。</p>
     */
    @Test
    @DisplayName("景点管理全链路：201+Location → 列表 → 修改 → 停用 → 204 删除")
    void adminAttractionLifecycleFollowsTheContract() throws Exception {
        String token = adminToken();
        String name = "契约景点-" + shortId();

        // 创建：201 + Location + AttractionEnvelope
        MockHttpServletResponse created = mvc()
                .perform(post("/api/admin/attractions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"city\":\"大理\",\"address\":\"云南省大理市\","
                                + "\"longitude\":100.165,\"latitude\":25.694,\"intro\":\"演示简介\","
                                + "\"dataSource\":\"团队测试数据\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.name").value(name))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn().getResponse();
        String id = data(created).path("id").asString();
        assertTrue(created.getHeader("Location").endsWith("/api/admin/attractions/" + id),
                "Location 必须指向新建资源：" + created.getHeader("Location"));
        assertTrue(Long.parseLong(id) > 0, "响应 id 必须是契约 Id（非空数字字符串）");

        // 响应字段形状：契约 Attraction 必填项齐全，status 是枚举、坐标是 JSON number
        JsonNode body = data(created);
        for (String required : new String[]{"id", "name", "city", "status", "createdAt", "updatedAt"}) {
            assertNotNull(body.get(required), "响应缺少契约必填字段：" + required);
        }
        assertTrue(body.get("longitude").isNumber(), "坐标必须是 JSON number，不能是 BigDecimal 字符串");
        assertEquals(100.165, body.get("longitude").asDouble(), 0.0000001);
        assertEquals(25.694, body.get("latitude").asDouble(), 0.0000001);

        // 列表：keyword 命中，且形状与创建响应同口径
        JsonNode found = null;
        for (JsonNode item : okData(get("/api/admin/attractions").header("Authorization", token)
                .param("keyword", name)).path("items")) {
            if (id.equals(item.path("id").asString())) {
                found = item;
            }
        }
        assertNotNull(found, "keyword 应能检索到刚创建的景点");
        assertEquals("ACTIVE", found.path("status").asString());

        // 修改：200 + 修改后的景点，且 status 未提交时保持 ACTIVE
        JsonNode updated = okData(put("/api/admin/attractions/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "-改名\",\"city\":\"丽江\",\"address\":null,"
                        + "\"longitude\":100.233,\"latitude\":26.872,\"intro\":null,"
                        + "\"dataSource\":\"团队测试数据\"}"));
        assertEquals(name + "-改名", updated.path("name").asString());
        assertEquals("丽江", updated.path("city").asString());
        assertTrue(updated.get("address").isNull(), "PUT 需要能清空可选字段");
        assertEquals("ACTIVE", updated.path("status").asString(), "未提交 status 时状态不应发生变化");

        // 停用：公开详情 / 公开列表都不再暴露该景点
        okData(put("/api/admin/attractions/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "-改名\",\"city\":\"丽江\",\"dataSource\":\"团队测试数据\","
                        + "\"status\":\"DISABLED\"}"));
        mvc().perform(get("/api/attractions/{id}", id))
                .andExpect(status().isNotFound());
        assertEquals(0, attractions.selectById(Long.parseLong(id)).status,
                "停用必须真的落库成 0（否则公开接口的 404 只是响应层的假象）");

        // 停用之后再提交一次不带 status 的资料编辑：状态必须原样留在 DISABLED。
        // 本用例<b>证明不了</b>并发下的丢失更新（那需要在服务的读取与写回之间插入另一次提交，
        // 见 AttractionServiceTest#concurrentDisableSurvivesAnEditThatDoesNotSubmitStatus）；
        // 它挡的是另一类回归：把"未提交 status"当成"按 ACTIVE 建档"。
        JsonNode editedWhileDisabled = okData(put("/api/admin/attractions/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "-再次编辑\",\"city\":\"丽江\","
                        + "\"dataSource\":\"团队测试数据\"}"));
        assertEquals("DISABLED", editedWhileDisabled.path("status").asString(),
                "不带 status 的资料编辑不得改变已停用景点的状态");
        assertEquals(0, attractions.selectById(Long.parseLong(id)).status,
                "未提交 status 时必须连 status 列都不写，库内仍应是 0");
        mvc().perform(get("/api/attractions/{id}", id))
                .andExpect(status().isNotFound());

        // 删除：未被任何行程 / 指南 / 攻略引用 → 204 且无响应体
        MockHttpServletResponse deleted = mvc()
                .perform(delete("/api/admin/attractions/" + id).header("Authorization", token))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();
        assertEquals(0, deleted.getContentAsByteArray().length, "204 不能带响应体");
        assertNull(attractions.selectById(Long.parseLong(id)), "删除后库内不应再有该行");

        // 删除不存在的景点：404（旧实现回 200，调用方会以为删掉了）
        mvc().perform(delete("/api/admin/attractions/999999999").header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /**
     * 被线路行程引用的景点不能删除：契约 {@code DELETE /admin/attractions/{attractionId}}
     * 声明的是"删除未被行程引用的景点资料"，冲突必须是 409。
     *
     * <p>旧实现直接 {@code deleteById}，只会撞上外键并返回 500。</p>
     */
    @Test
    @DisplayName("删除被行程引用的景点：409，且景点仍在库内")
    void deleteIsRejectedWhileItineraryStillReferencesTheAttraction() throws Exception {
        String token = adminToken();
        Attraction place = place("引用中景点-" + shortId());
        routeReferencing(place);

        mvc().perform(delete("/api/admin/attractions/" + place.id).header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTRACTION_STATE_CONFLICT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("线路行程")));
        assertNotNull(attractions.selectById(place.id), "409 时不得删除任何数据");
    }

    /**
     * 删除被地点指南引用的景点同样不能删除（三张引用表都要挡住）。
     *
     * <p>指南记录必须带 {@code author_id}：{@code place_guide.author_id} 是 NOT NULL 且外键指向
     * {@code sys_user}，缺少作者会直接让夹具插入失败。</p>
     */
    @Test
    @DisplayName("删除被地点指南引用的景点：409")
    void deleteIsRejectedWhilePlaceGuideStillReferencesTheAttraction() throws Exception {
        SysUser admin = adminAccount();
        String token = token(admin);
        Attraction first = place("指南景点A-" + shortId());
        Attraction second = place("指南景点B-" + shortId());
        PlaceGuide guide = new PlaceGuide();
        guide.title = "契约指南-" + shortId();
        guide.city = "大理";
        guide.destination = "大理";
        guide.status = "DRAFT";
        guide.authorId = admin.id;
        guides.insert(guide);
        for (int index = 0; index < 2; index++) {
            PlaceGuideItem item = new PlaceGuideItem();
            item.guideId = guide.id;
            item.attractionId = index == 0 ? first.id : second.id;
            item.sortOrder = index + 1;
            guideItems.insert(item);
        }

        mvc().perform(delete("/api/admin/attractions/" + first.id).header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("地点指南")));
        assertNotNull(attractions.selectById(first.id));
    }

    /** 修改不存在的景点必须 404，而不是回 200 + 请求体。 */
    @Test
    @DisplayName("修改不存在的景点：404")
    void updateMissingAttractionReturnsNotFound() throws Exception {
        mvc().perform(put("/api/admin/attractions/999999999").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"不存在\",\"city\":\"大理\",\"dataSource\":\"团队测试数据\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /** 列表 keyword 的契约长度上限在真实服务上同样生效（100 码点通过、101 被拒）。 */
    @Test
    @DisplayName("列表 keyword：100 码点通过、101 码点 422")
    void listKeywordHonoursTheCodePointLimit() throws Exception {
        String token = adminToken();
        mvc().perform(get("/api/admin/attractions").header("Authorization", token)
                        .param("page", "1").param("size", "5").param("keyword", "😀".repeat(100)))
                .andExpect(status().isOk());
        mvc().perform(get("/api/admin/attractions").header("Authorization", token)
                        .param("page", "1").param("size", "5").param("keyword", "😀".repeat(101)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ===================== 夹具与断言工具 =====================

    /** 一个可用的管理员账号：JwtAuthenticationFilter 会回查账号状态，所以必须真实落库。 */
    private SysUser adminAccount() {
        SysUser user = new SysUser();
        user.username = "attr_" + shortId();
        user.nickname = "景点契约";
        user.realName = "景点契约";
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

    private Attraction place(String name) {
        Attraction place = new Attraction();
        place.name = name;
        place.city = "大理";
        place.address = "云南省大理市";
        place.longitude = new BigDecimal("100.1650000");
        place.latitude = new BigDecimal("25.6940000");
        place.intro = "演示简介";
        place.dataSource = "团队测试数据";
        place.status = 1;
        attractions.insert(place);
        return place;
    }

    /** 建一条已上架线路，并把给定景点挂到它的第一天行程里。 */
    private void routeReferencing(Attraction place) {
        TravelRoute route = new TravelRoute();
        route.name = "景点引用线路-" + shortId();
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
        day.title = "游览";
        days.insert(day);
        RouteItineraryItem item = new RouteItineraryItem();
        item.dayId = day.id;
        item.sortNo = 1;
        item.itemType = "ATTRACTION";
        item.name = place.name;
        item.attractionId = place.id;
        items.insert(item);
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
