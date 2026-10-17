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
import java.util.List;
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
                        .content("{\"name\":\"" + name + "\",\"city\":\"昆明\","
                                + "\"address\":\"云南省昆明市测试路 1 号\","
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
        for (String required : new String[]{"id", "name", "city", "status", "version", "images",
                "facilities", "createdAt", "updatedAt"}) {
            assertNotNull(body.get(required), "响应缺少契约必填字段：" + required);
        }
        assertEquals(0, body.path("version").asInt(), "新建的乐观锁版本号从 0 起算");
        assertTrue(body.get("longitude").isNumber(), "坐标必须是 JSON number，不能是 BigDecimal 字符串");
        assertEquals(102.832, body.get("longitude").asDouble(), 0.0000001);
        assertEquals(24.88, body.get("latitude").asDouble(), 0.0000001);
        assertEquals("昆明", body.path("city").asString(), "city 必须原样回显提交的城市");

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
        assertEquals("昆明", found.path("city").asString(), "列表与创建响应必须给出同一个城市");
        assertEquals(0, found.path("version").asInt(), "列表与创建响应必须给出同一个版本号");

        // 修改：200 + 修改后的酒店，且 status 未提交时保持 ACTIVE
        JsonNode updated = okData(put("/api/admin/hotels/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "-改名\",\"city\":\"昆明\",\"address\":null,"
                        + "\"contactPhone\":null,\"longitude\":102.733,\"latitude\":25.044,\"intro\":null,"
                        + "\"dataSource\":\"团队测试数据\",\"version\":0}"));
        assertEquals(name + "-改名", updated.path("name").asString());
        assertTrue(updated.get("address").isNull(), "PUT 需要能清空可选字段");
        assertTrue(updated.get("contactPhone").isNull(), "PUT 需要能清空可选字段");
        assertEquals("ACTIVE", updated.path("status").asString(), "未提交 status 时状态不应发生变化");
        assertEquals(1, updated.path("version").asInt(), "修改成功后版本号必须推进到 1");

        // 停用：状态必须真的落库成 0
        JsonNode disabled = okData(put("/api/admin/hotels/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "-改名\",\"city\":\"昆明\",\"dataSource\":\"团队测试数据\","
                        + "\"status\":\"DISABLED\",\"version\":1}"));
        assertEquals(0, hotels.selectById(Long.parseLong(id)).status,
                "停用必须真的落库成 0");
        assertEquals(2, disabled.path("version").asInt());

        // 停用之后再提交一次不带 status 的资料编辑：状态必须原样留在 DISABLED。
        // 本用例证明不了并发下的丢失更新（那需要在本事务的读取与写回之间插入另一次提交，
        // 见 HotelServiceTest#concurrentDisableSurvivesAnEditThatDoesNotSubmitStatus）；
        // 它挡的是另一类回归：把"未提交 status"当成"按 ACTIVE 建档"。
        JsonNode editedWhileDisabled = okData(put("/api/admin/hotels/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "-再次编辑\",\"city\":\"昆明\","
                        + "\"dataSource\":\"团队测试数据\",\"version\":2}"));
        assertEquals("DISABLED", editedWhileDisabled.path("status").asString(),
                "不带 status 的资料编辑不得改变已停用酒店的状态");
        assertEquals(0, hotels.selectById(Long.parseLong(id)).status,
                "未提交 status 时必须连 status 列都不写，库内仍应是 0");
        assertEquals(3, editedWhileDisabled.path("version").asInt());

        // 乐观锁：拿旧版本再提交一次必须 409，且不改动任何数据（版本也不前进）。
        mvc().perform(put("/api/admin/hotels/" + id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"陈旧表单提交\",\"city\":\"昆明\",\"dataSource\":\"团队测试数据\","
                                + "\"version\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOTEL_VERSION_CONFLICT"));
        assertEquals(name + "-再次编辑", hotels.selectById(Long.parseLong(id)).name,
                "409 时不得写入任何字段");
        assertEquals(3, hotels.selectById(Long.parseLong(id)).version, "409 时版本不得前进");

        // 详情端点：乐观锁冲突后前端靠它按主键取回服务器最新资料（含最新名称与版本号），
        // 因此这里断言"409 之后立刻读详情能拿到冲突提示里那个版本"。
        JsonNode latest = okData(get("/api/admin/hotels/" + id).header("Authorization", token));
        assertEquals(id, latest.path("id").asString());
        assertEquals(name + "-再次编辑", latest.path("name").asString());
        assertEquals("DISABLED", latest.path("status").asString());
        assertEquals(3, latest.path("version").asInt(), "详情必须给出最新版本号");
        for (String required : new String[]{"id", "name", "city", "status", "version", "createdAt", "updatedAt"}) {
            assertNotNull(latest.get(required), "详情缺少契约必填字段：" + required);
        }

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

        // 详情不存在：404（冲突面板据此提示"资料可能已被删除"）
        mvc().perform(get("/api/admin/hotels/999999999").header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // 修改不存在的酒店：404（旧实现是无条件 updateById，影响 0 行也回 200 + 请求体）
        mvc().perform(put("/api/admin/hotels/999999999").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"不存在\",\"city\":\"昆明\",\"dataSource\":\"团队测试数据\","
                                + "\"version\":0}"))
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
                .content("{\"name\":\"" + hotel.name + "\",\"city\":\"大理\","
                        + "\"dataSource\":\"团队测试数据\",\"status\":\"DISABLED\",\"version\":0}"));

        assertNotNull(hotels.selectById(hotel.id), "停用不得删除资料");
        assertEquals(0, hotels.selectById(hotel.id).status);
        RouteItineraryDay reloaded = days.selectById(day.id);
        assertEquals(hotel.id, reloaded.hotelId, "停用不得改动行程上的酒店关联");
    }

    /**
     * 冻结契约的 {@code HotelCreateRequest} / {@code HotelUpdateRequest} 给各文本字段写明了 maxLength，
     * 而库内的列宽是 {@code VARCHAR(128/255/20/500)} 与 {@code TEXT}。两边必须一致：
     * 列比契约窄时，正好取到契约上限的合法请求会在写库阶段炸成 5xx ——
     * 那是"契约允许、实现拒绝"，调用方无从规避。这里按<b>上限值本身</b>创建并要求原样读回。
     */
    @Test
    @DisplayName("创建：各字段取契约 maxLength 上限值时被接受，且原样落库")
    void createAcceptsValuesExactlyAtContractLimits() throws Exception {
        String name = "上".repeat(128);
        String address = "址".repeat(255);
        String contactPhone = "0".repeat(20);
        String intro = "介".repeat(10000);
        String dataSource = "源".repeat(500);

        MockHttpServletResponse created = mvc()
                .perform(post("/api/admin/hotels").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"city\":\"昆明\",\"address\":\"" + address + "\","
                                + "\"contactPhone\":\"" + contactPhone + "\",\"intro\":\"" + intro + "\","
                                + "\"dataSource\":\"" + dataSource + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        JsonNode data = data(created);
        assertEquals(128, data.path("name").asString().length(), "name 上限 128 应被接受");
        assertEquals(255, data.path("address").asString().length(), "address 上限 255 应被接受");
        assertEquals(20, data.path("contactPhone").asString().length(), "contactPhone 上限 20 应被接受");
        assertEquals(10000, data.path("intro").asString().length(), "intro 上限 10000 应被接受");
        assertEquals(500, data.path("dataSource").asString().length(), "dataSource 上限 500 应被接受");

        // 同时确认真实落库（响应只是回查结果，若写入被截断这里会不一致）
        Hotel stored = hotels.selectById(Long.parseLong(data.path("id").asString()));
        assertEquals(128, stored.name.length());
        assertEquals(10000, stored.intro.length());
    }

    /**
     * 契约的 {@code maxLength} 按 Unicode <b>码点</b>计数（JSON Schema 口径），不是 UTF-16 码元：
     * 一个 emoji 占 2 个码元却只是 1 个码点。用 {@code @Size} 校验时"码元超限、码点合法"的内容
     * 会被判成超长回 422 —— 契约允许、实现却拒绝，调用方（例如导入工具）无从规避。
     *
     * <p>这里按码元超限的取值要求被<b>接受</b>：这是 {@code @CodePointLength} 与 {@code @Size}
     * 唯一能被观测到的差别，因此本用例是这条口径的回归防线。
     * 库内列宽同样是按字符（码点）定义的（utf8mb4 下 {@code VARCHAR(128)} 就是 128 个码点），
     * 所以 100 个 emoji 能被完整存下、原样读回。</p>
     */
    @Test
    @DisplayName("创建：长度按码点计数，码元超限但码点合法的内容必须被接受")
    void createCountsTextLimitsInCodePointsNotUtf16Units() throws Exception {
        String name = "😀".repeat(100);        // 100 码点 / 200 码元
        String city = "😀".repeat(30);         // 30 码点 / 60 码元
        String address = "😀".repeat(200);     // 200 码点 / 400 码元
        String contactPhone = "😀".repeat(15); // 15 码点 / 30 码元
        String dataSource = "😀".repeat(400);  // 400 码点 / 800 码元
        assertTrue(name.length() > 128, "前提：该名称的 UTF-16 码元数确实超过契约上限");
        assertTrue(name.codePointCount(0, name.length()) <= 128, "前提：码点数在契约上限之内");

        JsonNode created = data(mvc()
                .perform(post("/api/admin/hotels").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"city\":\"" + city + "\",\"address\":\"" + address + "\","
                                + "\"contactPhone\":\"" + contactPhone + "\","
                                + "\"dataSource\":\"" + dataSource + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());

        assertEquals(name, created.path("name").asString(), "名称必须原样返回，不能在写库时被截断");
        assertEquals(city, created.path("city").asString(), "city 与其它文本字段同一口径：按码点计数");
        assertEquals(address, created.path("address").asString());
        assertEquals(contactPhone, created.path("contactPhone").asString());
        assertEquals(dataSource, created.path("dataSource").asString());

        Hotel stored = hotels.selectById(Long.parseLong(created.path("id").asString()));
        assertEquals(100, stored.name.codePointCount(0, stored.name.length()),
                "库内应完整保存 100 个码点");
        assertEquals(30, stored.city.codePointCount(0, stored.city.length()),
                "city 列宽是 64 个字符（码点），30 个 emoji 必须完整保存");
        assertEquals(200, stored.address.codePointCount(0, stored.address.length()));
    }

    @Test
    @DisplayName("创建：只提交契约必填字段时建档成功，可选列为 NULL")
    void createWithOnlyRequiredFieldsLeavesOptionalColumnsNull() throws Exception {
        String name = "最小酒店-" + shortId();
        JsonNode created = data(mvc()
                .perform(post("/api/admin/hotels").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"city\":\"大理\","
                                + "\"dataSource\":\"团队测试数据\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());

        assertEquals(name, created.path("name").asString());
        assertEquals("大理", created.path("city").asString(), "city 是必填项，提交了就应原样回显");
        assertEquals("ACTIVE", created.path("status").asString(), "未提交 status 时按 ACTIVE 建档");
        assertTrue(created.get("address").isNull(), "未提交的可选字段应为 null 而不是空串");
        assertTrue(created.get("contactPhone").isNull());
        assertTrue(created.get("longitude").isNull());
        assertTrue(created.get("latitude").isNull());
        assertTrue(created.get("intro").isNull());
        assertTrue(created.get("starRating").isNull(), "没有可靠依据时星级必须是 null，不能用 0 凑数");
        assertTrue(created.get("coverUrl").isNull());
        assertTrue(created.get("checkInTime").isNull());
        assertTrue(created.get("checkOutTime").isNull());

        Hotel stored = hotels.selectById(Long.parseLong(created.path("id").asString()));
        assertNull(stored.address, "库内应是真的 NULL");
        assertNull(stored.longitude);
    }

    /**
     * 后台列表不过滤 {@code status}：停用的酒店必须仍能被工作人员看到并改回来
     * （契约给 {@code GET /admin/hotels} 的参数只有 page/size/keyword，没有 status）。
     */
    @Test
    @DisplayName("后台列表不过滤状态：停用酒店仍出现在列表里")
    void adminListKeepsDisabledHotelsVisible() throws Exception {
        String token = adminToken();
        String name = "停用酒店-" + shortId();
        data(mvc().perform(post("/api/admin/hotels").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"city\":\"大理\","
                                + "\"dataSource\":\"团队测试数据\",\"status\":\"DISABLED\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());

        JsonNode found = null;
        for (JsonNode item : okData(get("/api/admin/hotels").header("Authorization", token)
                .param("keyword", name)).path("items")) {
            found = item;
        }
        assertNotNull(found, "停用的酒店必须仍出现在后台列表里，否则无法被改回启用");
        assertEquals("DISABLED", found.path("status").asString());
    }

    /** {@code keyword} 按契约匹配酒店名称、地址与简介，且不匹配时回空列表（不返回全量）。 */
    @Test
    @DisplayName("列表 keyword：名称/地址/简介三个字段都能命中，未命中回空列表")
    void listKeywordMatchesNameAddressAndIntro() throws Exception {
        String token = adminToken();
        String mark = shortId();
        data(mvc().perform(post("/api/admin/hotels").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"检索酒店-" + mark + "\",\"city\":\"大理\","
                                + "\"address\":\"地址标记" + mark + "\","
                                + "\"intro\":\"简介标记" + mark + "\",\"dataSource\":\"团队测试数据\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());

        for (String keyword : List.of("检索酒店-" + mark, "地址标记" + mark, "简介标记" + mark)) {
            JsonNode page = okData(get("/api/admin/hotels").header("Authorization", token)
                    .param("keyword", keyword));
            assertEquals(1, page.path("items").size(), "keyword「" + keyword + "」应恰好命中 1 条");
        }

        JsonNode none = okData(get("/api/admin/hotels").header("Authorization", token)
                .param("keyword", "绝不可能存在-" + mark));
        assertEquals(0, none.path("items").size(), "未命中必须回空列表，而不是退化成全量");
        assertEquals(0, none.path("total").asInt());
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

    /**
     * 分页参数按契约取值域（page ≥ 1、1 ≤ size ≤ 100）：越界一律钳到边界，
     * 既不能因为 size 过大一次拉全表，也不能因为 page=0/负数回退成非法 SQL。
     */
    @Test
    @DisplayName("列表分页：page/size 越界被钳到契约边界")
    void listClampsPagingParameters() throws Exception {
        String token = adminToken();
        JsonNode page = okData(get("/api/admin/hotels").header("Authorization", token)
                .param("page", "0").param("size", "1000"));
        assertEquals(1, page.path("page").asInt(), "page 小于 1 时按第 1 页处理");
        assertEquals(100, page.path("size").asInt(), "size 必须钳到契约上限 100");
        assertTrue(page.get("items").isArray(), "items 必须是数组，空数据时为 []");

        JsonNode negative = okData(get("/api/admin/hotels").header("Authorization", token)
                .param("page", "-5").param("size", "0"));
        assertEquals(1, negative.path("page").asInt(), "page 为负时按第 1 页处理");
        assertEquals(1, negative.path("size").asInt(), "size 小于 1 时按 1 处理，不能回全量");
    }

    @Test
    @DisplayName("酒店资料字段的落库读回：图片按 sortOrder 排序、设施保序、星级可空、PUT 可清空")
    void profileFieldsRoundTripThroughTheDatabase() throws Exception {
        String token = adminToken();
        String name = "资料字段回归酒店-" + shortId();
        String submittedImages = "[{\"url\":\"https://cdn.example.com/hotels/c.jpg\",\"alt\":\"第三张\",\"sortOrder\":3},"
                + "{\"url\":\"https://cdn.example.com/hotels/a.jpg\",\"alt\":\"第一张\",\"sortOrder\":1},"
                + "{\"url\":\"https://cdn.example.com/hotels/b.jpg\",\"alt\":\"第二张\",\"sortOrder\":2}]";

        JsonNode created = data(mvc().perform(post("/api/admin/hotels").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"city\":\"大理\","
                                + "\"dataSource\":\"团队测试数据\","
                                + "\"images\":" + submittedImages + ","
                                + "\"facilities\":[\"PARKING\",\"WIFI\"],"
                                + "\"starRating\":4,\"checkInTime\":\"14:00\",\"checkOutTime\":\"12:00\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
        String id = created.path("id").asString();

        assertEquals("大理", created.path("city").asString(), "city 必须原样回显");
        assertEquals(4, created.path("starRating").asInt(), "提交了星级就必须落库读出");
        assertEquals("14:00", created.path("checkInTime").asString());
        assertEquals("12:00", created.path("checkOutTime").asString());

        JsonNode images = created.path("images");
        assertEquals(3, images.size(), "三张图片都要落库");
        assertEquals(List.of("https://cdn.example.com/hotels/a.jpg",
                        "https://cdn.example.com/hotels/b.jpg",
                        "https://cdn.example.com/hotels/c.jpg"),
                List.of(images.get(0).path("url").asString(), images.get(1).path("url").asString(),
                        images.get(2).path("url").asString()),
                "图片必须按 sortOrder 升序返回，而不是按提交或写入顺序");
        assertEquals(1, images.get(0).path("sortOrder").asInt());
        assertEquals(2, images.get(1).path("sortOrder").asInt());
        assertEquals(3, images.get(2).path("sortOrder").asInt());

        assertEquals(List.of("PARKING", "WIFI"),
                List.of(created.path("facilities").get(0).asString(),
                        created.path("facilities").get(1).asString()),
                "设施标签必须按提交顺序读回（库内是 JSON 数组，顺序即契约顺序）");

        JsonNode minimal = data(mvc().perform(post("/api/admin/hotels").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"星级未填-" + shortId() + "\",\"city\":\"大理\","
                                + "\"dataSource\":\"团队测试数据\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
        assertTrue(minimal.get("starRating").isNull(), "未提交星级时必须回 null，不能编造一个星级");
        assertTrue(minimal.path("images").isArray(), "images 必须是数组");
        assertEquals(0, minimal.path("images").size(), "没有图片时按契约回 []");
        assertEquals(0, minimal.path("facilities").size(), "没有设施时按契约回 []");

        JsonNode cleared = okData(put("/api/admin/hotels/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"city\":\"大理\","
                        + "\"dataSource\":\"团队测试数据\",\"version\":0}"));
        assertTrue(cleared.path("images").isArray());
        assertEquals(0, cleared.path("images").size(), "PUT 省略 images 必须清空既有图片，而不是保留旧值");
        assertEquals(0, cleared.path("facilities").size(), "PUT 省略 facilities 必须清空既有设施");
        assertTrue(cleared.get("starRating").isNull(), "PUT 省略 starRating 必须清成 null");
        assertEquals("大理", cleared.path("city").asString());
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
        hotel.city = "大理";
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
        day.accommodationType = "HOTEL";
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
