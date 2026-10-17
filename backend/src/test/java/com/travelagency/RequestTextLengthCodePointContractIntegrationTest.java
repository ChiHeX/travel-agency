package com.travelagency;

import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.domain.entity.Attraction;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.mapper.AttractionMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 请求体文本长度口径（Unicode 码点）的契约回归。
 *
 * <p>背景：#46（后台导游管理）的收尾记录把「其余模块仍用 {@code @Size}」列为范围外、
 * 需要另开变更处理。{@code @Size} 数的是 <b>UTF-16 码元</b>，而契约的 {@code maxLength}
 * 是 JSON Schema 口径、数的是 <b>字符（码点）</b>；库内列宽也是码点口径
 * （utf8mb4 下 {@code VARCHAR(N)} 就是 N 个字符）。一个 emoji 占 2 个码元却只是 1 个码点，
 * 于是「契约允许、库内存得下」的内容被判成超长回 422，调用方无从规避。</p>
 *
 * <p>本类对每个受影响模块各取一个代表字段，钉住两侧边界：</p>
 * <ul>
 *   <li>恰好等于契约上限的 emoji 内容 → 接受，且原样落库/原样返回；</li>
 *   <li>上限 +1 个码点 → 422 {@code VALIDATION_ERROR}，且 {@code errors[]} 指回该字段。</li>
 * </ul>
 *
 * <p>之所以必须走真实 HTTP + 真库：这两侧边界只在「码点 / 码元」有差别时才可观测，
 * 而恰好到上限的内容还要能真的写进列宽按码点定义的列里 —— 纯 Mockito 单测看不到响应码，
 * 也看不到写入是否被截断。</p>
 *
 * <p><b>需要数据库</b>（未配置时整个类被跳过）：</p>
 * <pre>
 * $env:TRAVEL_MYSQL_TEST = "true"
 * mvn -ntp test -Dtest=RequestTextLengthCodePointContractIntegrationTest
 * </pre>
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
class RequestTextLengthCodePointContractIntegrationTest {

    private static final String EMOJI = "😀";

    @Autowired WebApplicationContext context;
    @Autowired AttractionMapper attractions;
    @Autowired SysUserMapper users;
    @Autowired JwtTokenProvider tokens;
    @Autowired JsonMapper json;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ===================== 景点：AttractionUpsertRequest =====================

    /**
     * 景点名称上限 128：128 个 emoji 是 128 个码点、256 个码元。
     * 用 {@code @Size} 时它会被判成超长，而契约与 {@code VARCHAR(128)} 都允许。
     * PUT 复用同一 DTO，这里一并钉住。
     */
    @Test
    @DisplayName("景点：名称按码点计数，新建与修改都接受上限个 emoji，越界一个码点回 422")
    void attractionCountsNameInCodePoints() throws Exception {
        String token = adminToken();
        String name = EMOJI.repeat(128);
        assertTrue(name.length() > 128, "前提：该名称的 UTF-16 码元数确实超过契约上限");
        assertEquals(128, name.codePointCount(0, name.length()), "前提：码点数恰好等于契约上限");

        JsonNode created = data(mvc()
                .perform(post("/api/admin/attractions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"city\":\"大理\","
                                + "\"dataSource\":\"团队测试数据\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
        assertEquals(name, created.path("name").asString(), "上限个码点必须原样保存，不能被截断");

        String id = created.path("id").asString();
        JsonNode updated = data(mvc()
                .perform(put("/api/admin/attractions/" + id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"city\":\"大理\","
                                + "\"dataSource\":\"团队测试数据\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse());
        assertEquals(name, updated.path("name").asString(), "修改走同一 DTO，口径必须一致");

        // 上限 +1 个码点：必须仍是 422，说明上限没有被放宽
        mvc().perform(post("/api/admin/attractions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + EMOJI.repeat(129) + "\",\"city\":\"大理\","
                                + "\"dataSource\":\"团队测试数据\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    // ===================== 线路：RouteUpsertRequest =====================

    @Test
    @DisplayName("线路：名称上限 200 按码点计数，200 个 emoji 可建，201 个回 422")
    void routeCountsNameInCodePoints() throws Exception {
        String token = adminToken();
        String name = EMOJI.repeat(200);
        assertTrue(name.length() > 200, "前提：码元数确实超过契约上限");

        JsonNode created = data(mvc()
                .perform(post("/api/admin/routes").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(name, EMOJI.repeat(500))))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
        assertEquals(name, created.path("name").asString());
        // 封面地址同样按码点：500 个 emoji 是 500 码点 / 1000 码元
        assertEquals(EMOJI.repeat(500), created.path("coverUrl").asString());

        mvc().perform(post("/api/admin/routes").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(EMOJI.repeat(201), "https://example.com/a.png")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        mvc().perform(post("/api/admin/routes").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody("正常线路名", EMOJI.repeat(501))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("coverUrl"));
    }

    // ===================== 行程：ItineraryDayRequest / ItineraryItemRequest =====================

    @Test
    @DisplayName("行程：交通说明 255、项目名称 200 均按码点计数，越界一个码点回 422")
    void itineraryCountsTextInCodePoints() throws Exception {
        String token = adminToken();
        String routeId = data(mvc()
                .perform(post("/api/admin/routes").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody("行程码点线路-" + shortId(), null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse()).path("id").asString();

        String transportation = EMOJI.repeat(255);
        JsonNode day = data(mvc()
                .perform(post("/api/admin/routes/" + routeId + "/itinerary-days").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayNumber\":1,\"title\":\"抵达\",\"transportation\":\""
                                + transportation + "\",\"meals\":\"" + EMOJI.repeat(255) + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
        assertEquals(transportation, day.path("transportation").asString());
        assertEquals(EMOJI.repeat(255), day.path("meals").asString());

        String dayId = day.path("id").asString();
        String itemName = EMOJI.repeat(200);
        JsonNode item = data(mvc()
                .perform(post("/api/admin/itinerary-days/" + dayId + "/items").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortNo\":1,\"itemType\":\"OTHER\",\"name\":\"" + itemName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
        assertEquals(itemName, item.path("name").asString());

        mvc().perform(post("/api/admin/routes/" + routeId + "/itinerary-days").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayNumber\":2,\"title\":\"抵达\",\"transportation\":\""
                                + EMOJI.repeat(256) + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("transportation"));

        mvc().perform(post("/api/admin/itinerary-days/" + dayId + "/items").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortNo\":2,\"itemType\":\"OTHER\",\"name\":\"" + EMOJI.repeat(201) + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    // ===================== 攻略：ArticleRequest =====================

    @Test
    @DisplayName("攻略：标题 200 按码点计数，200 个 emoji 可建，201 个回 422")
    void articleCountsTitleInCodePoints() throws Exception {
        String token = adminToken();
        String title = EMOJI.repeat(200);

        JsonNode created = data(mvc()
                .perform(post("/api/admin/articles").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"content\":\"" + EMOJI.repeat(1000)
                                + "\",\"city\":\"大理\",\"destination\":\"云南\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
        assertEquals(title, created.path("title").asString());
        // 正文上限 100000：这里用 1000 个 emoji（2000 个码元）验证码元口径不再误判
        assertEquals(EMOJI.repeat(1000), created.path("content").asString());

        mvc().perform(post("/api/admin/articles").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + EMOJI.repeat(201) + "\",\"content\":\"正文\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("title"));
    }

    // ===================== 地点指南：PlaceGuideRequest =====================

    @Test
    @DisplayName("地点指南：标题 200 按码点计数，places 的元素个数仍按 @Size 校验")
    void placeGuideCountsTitleInCodePoints() throws Exception {
        String token = adminToken();
        Attraction first = attractionFixture();
        Attraction second = attractionFixture();
        String title = EMOJI.repeat(200);

        JsonNode created = data(mvc()
                .perform(post("/api/admin/place-guides").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"city\":\"大理\",\"places\":["
                                + "{\"attractionId\":" + first.id + "},{\"attractionId\":" + second.id + "}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
        assertEquals(title, created.path("title").asString());

        mvc().perform(post("/api/admin/place-guides").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + EMOJI.repeat(201) + "\",\"city\":\"大理\",\"places\":["
                                + "{\"attractionId\":" + first.id + "},{\"attractionId\":" + second.id + "}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("title"));

        // places 上的 @Size 数的是列表元素个数，与文本长度无关：1 个地点仍按 422 拒绝
        mvc().perform(post("/api/admin/place-guides").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"指南\",\"city\":\"大理\",\"places\":["
                                + "{\"attractionId\":" + first.id + "}]}"))
                .andExpect(status().isUnprocessableContent());
    }

    // ===================== 咨询：ConsultationRequest =====================

    @Test
    @DisplayName("在线咨询：标题 100 与内容 2000 按码点计数，越界一个码点回 422")
    void consultationCountsTextInCodePoints() throws Exception {
        String token = userToken();
        String title = EMOJI.repeat(100);
        String content = EMOJI.repeat(2000);

        JsonNode created = data(mvc()
                .perform(post("/api/consultations").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"content\":\"" + content + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
        assertEquals(title, created.path("title").asString());
        assertEquals(content, created.path("content").asString());

        mvc().perform(post("/api/consultations").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + EMOJI.repeat(101) + "\",\"content\":\"正文\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("title"));

        mvc().perform(post("/api/consultations").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"标题\",\"content\":\"" + EMOJI.repeat(2001) + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("content"));
    }

    // ===================== 常用出行人：TravelerRequest / TravelerUpdateRequest =====================

    @Test
    @DisplayName("常用出行人：姓名 64 按码点计数，新增接受、修改越界回 422")
    void travelerCountsNameInCodePoints() throws Exception {
        String token = userToken();
        String name = EMOJI.repeat(64);

        JsonNode created = data(mvc()
                .perform(post("/api/travelers").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(travelerBody(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
        assertEquals(name, created.path("name").asString());

        String id = created.path("id").asString();
        mvc().perform(put("/api/travelers/" + id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(travelerBody(EMOJI.repeat(65))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    // ===================== 员工：StaffAccountRequest / StaffUpdateRequest =====================

    /**
     * 员工姓名上限 64，而 {@code sys_user.nickname} 只有 {@code VARCHAR(32)}。
     * #46 已把昵称改为落账号名，这里顺带确认 64 个 emoji 的姓名能建档成功
     * （既不被码元口径误拒，也不会在写 nickname 时撞列宽）。
     */
    @Test
    @DisplayName("员工：姓名 64 按码点计数，建档成功；修改越界回 422")
    void staffCountsRealNameInCodePoints() throws Exception {
        String token = adminToken();
        String realName = EMOJI.repeat(64);
        String employeeNo = "EMP" + shortId();

        JsonNode created = data(mvc()
                .perform(post("/api/admin/staff").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"staff_" + shortId() + "\",\"password\":\"DemoPass123!\","
                                + "\"realName\":\"" + realName + "\",\"employeeNo\":\"" + employeeNo + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
        assertEquals(realName, created.path("realName").asString());
        // 昵称列是 VARCHAR(32)，落的是账号名而不是姓名（否则 33-64 个字符的姓名会撞列宽）
        SysUser account = users.selectById(Long.parseLong(created.path("userId").asString()));
        assertEquals(account.username, account.nickname, "昵称落账号名，完整姓名落 real_name");
        assertEquals(realName, account.realName);

        String staffId = created.path("id").asString();
        mvc().perform(put("/api/admin/staff/" + staffId).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"" + EMOJI.repeat(65) + "\","
                                + "\"employeeNo\":\"" + employeeNo + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("realName"));
    }

    /**
     * 改动前建的员工账号昵称就是姓名（{@code nickname == realName}），这类旧账号改名时
     * 仍会走「昵称跟着姓名走」的分支 —— 而姓名可到 64 个字符、昵称列只有 32，
     * 不做列宽判断就会让一次合法的改名变成 500。
     */
    @Test
    @DisplayName("员工：旧账号改成长姓名不再撞昵称列宽，完整姓名仍落 real_name")
    void staffRenameOnLegacyAccountDoesNotOverflowNicknameColumn() throws Exception {
        String token = adminToken();
        String employeeNo = "EMP" + shortId();
        JsonNode created = data(mvc()
                .perform(post("/api/admin/staff").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"staff_" + shortId() + "\",\"password\":\"DemoPass123!\","
                                + "\"realName\":\"王顾问\",\"employeeNo\":\"" + employeeNo + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
        long staffId = Long.parseLong(created.path("id").asString());
        long userId = Long.parseLong(created.path("userId").asString());

        // 还原成改动前的形状：昵称被初始化成姓名
        SysUser legacy = users.selectById(userId);
        legacy.nickname = legacy.realName;
        users.updateById(legacy);

        // 1) 旧账号改成短姓名：昵称仍按原行为跟着姓名走
        mvc().perform(put("/api/admin/staff/" + staffId).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"王顾问（改）\",\"employeeNo\":\"" + employeeNo + "\"}"))
                .andExpect(status().isOk());
        assertEquals("王顾问（改）", users.selectById(userId).nickname);

        // 2) 旧账号再改成长姓名（40 个码点）：不得因为昵称列宽报 500
        String longName = EMOJI.repeat(40);
        JsonNode updated = data(mvc()
                .perform(put("/api/admin/staff/" + staffId).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"" + longName + "\",\"employeeNo\":\"" + employeeNo + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse());
        assertEquals(longName, updated.path("realName").asString(), "完整姓名必须原样落 real_name");

        SysUser after = users.selectById(userId);
        assertEquals(longName, after.realName);
        assertEquals("王顾问（改）", after.nickname, "姓名超出昵称列宽时不再同步昵称，而不是报 500");
        assertTrue(after.nickname.codePointCount(0, after.nickname.length()) <= 32);
    }

    // ===================== 注册与个人资料：RegisterRequest / ProfileRequest =====================

    @Test
    @DisplayName("注册与资料：昵称 32 按码点计数，32 个 emoji 可注册，33 个回 422")
    void registerAndProfileCountNicknameInCodePoints() throws Exception {
        String nickname = EMOJI.repeat(32);
        String username = "u_" + shortId();

        JsonNode registered = data(mvc()
                .perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"DemoPass123!\","
                                + "\"nickname\":\"" + nickname + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse());
        assertEquals(nickname, registered.path("user").path("nickname").asString());

        mvc().perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u_" + shortId() + "\",\"password\":\"DemoPass123!\","
                                + "\"nickname\":\"" + EMOJI.repeat(33) + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("nickname"));

        // 资料修改走 ProfileRequest，昵称上限同为 32 个码点
        String token = data(mvc()
                .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"DemoPass123!\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse()).path("accessToken").asString();

        mvc().perform(put("/api/account/profile").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + nickname + "\"}"))
                .andExpect(status().isOk());

        mvc().perform(put("/api/account/profile").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + EMOJI.repeat(33) + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("nickname"));
    }

    // ===================== 下单：CreateOrderRequest 的出行人快照 =====================

    /**
     * 下单请求里的出行人姓名上限 64。这里用「不存在的团期」把业务失败与校验失败区分开：
     * 长度合法时进入业务逻辑（团期不存在 → 409），长度越界时连业务逻辑都进不去（422）。
     */
    @Test
    @DisplayName("下单：出行人姓名 64 按码点计数，合法长度进入业务校验，越界回 422")
    void orderCountsTravelerNameInCodePoints() throws Exception {
        String token = userToken();
        String key = UUID.randomUUID().toString().replace("-", "");

        // 64 个 emoji = 64 码点 / 128 码元：校验通过，随后因团期不存在被业务拒绝（409）
        mvc().perform(post("/api/orders").header("Authorization", token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(EMOJI.repeat(64), 99999999)))
                .andExpect(status().isConflict());

        mvc().perform(post("/api/orders").header("Authorization", token)
                        .header("Idempotency-Key", key + "x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(EMOJI.repeat(65), 99999999)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("travelers[0].name"));
    }

    // ===================== 请求体构造与夹具 =====================

    private String routeBody(String name, String coverUrl) {
        return "{\"name\":\"" + name + "\",\"departureCity\":\"上海\",\"destination\":\"云南\","
                + "\"durationDays\":6" + (coverUrl == null ? "" : ",\"coverUrl\":\"" + coverUrl + "\"") + "}";
    }

    private String travelerBody(String name) {
        return "{\"name\":\"" + name + "\",\"gender\":\"MALE\",\"birthDate\":\"1990-01-01\","
                + "\"idType\":\"CHINESE_ID_CARD\",\"idNo\":\"320101199001011234\","
                + "\"emergencyName\":\"李四\",\"emergencyPhone\":\"13900139000\"}";
    }

    private String orderBody(String travelerName, long departureId) {
        return "{\"departureId\":" + departureId + ",\"adultCount\":1,\"childCount\":0,"
                + "\"contactName\":\"张三\",\"contactPhone\":\"13800138000\",\"travelers\":[{"
                + "\"name\":\"" + travelerName + "\",\"gender\":\"MALE\",\"birthDate\":\"1990-01-01\","
                + "\"idType\":\"CHINESE_ID_CARD\",\"idNo\":\"320101199001011234\","
                + "\"emergencyName\":\"李四\",\"emergencyPhone\":\"13900139000\","
                + "\"travelerType\":\"ADULT\"}]}";
    }

    /** 地点指南要求「带坐标的已启用景点」，因此夹具必须给出经纬度。 */
    private Attraction attractionFixture() {
        Attraction attraction = new Attraction();
        attraction.name = "码点景点-" + shortId();
        attraction.city = "大理";
        attraction.longitude = new BigDecimal("100.1650000");
        attraction.latitude = new BigDecimal("25.6940000");
        attraction.dataSource = "团队测试数据";
        attraction.status = 1;
        attractions.insert(attraction);
        return attraction;
    }

    /** JwtAuthenticationFilter 会回查账号状态，所以令牌对应的账号必须真实落库。 */
    private SysUser account(String prefix) {
        SysUser user = new SysUser();
        user.username = prefix + "_" + shortId();
        user.nickname = user.username;
        user.realName = user.username;
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        users.insert(user);
        return user;
    }

    private String adminToken() {
        SysUser user = account("len_admin");
        return "Bearer " + tokens.createToken(user.id, user.username, Set.of("ADMIN"));
    }

    private String userToken() {
        SysUser user = account("len_user");
        return "Bearer " + tokens.createToken(user.id, user.username, Set.of("USER"));
    }

    /** MockHttpServletResponse 默认按 ISO-8859-1 解码，中文与 emoji 会乱码，必须按 UTF-8 读字节。 */
    private JsonNode data(MockHttpServletResponse response) {
        return json.readTree(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8)).get("data");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
