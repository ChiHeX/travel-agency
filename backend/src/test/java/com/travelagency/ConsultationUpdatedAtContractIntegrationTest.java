package com.travelagency;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.domain.entity.Consultation;
import com.travelagency.domain.entity.ConsultationReply;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.mapper.ConsultationMapper;
import com.travelagency.domain.mapper.ConsultationReplyMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 关闭咨询的接口响应必须带回数据库维护的最新 {@code updatedAt}。
 *
 * <p><b>为什么要单独一个类</b>：{@code BaseEntity} 的两列时间戳已经交回数据库维护，
 * 但这只保证「库里的值不被旧值压掉」，管不到「响应里装的是什么」。{@code ConsultationController.close}
 * 的旧实现把更新前读到的实体直接交给 {@code ConsultationView.from(...)}：库里的
 * {@code updated_at} 已经前进，响应却仍带着旧值，而 {@code updatedAt} 是契约 Consultation
 * 的必返字段。这类错值靠 BusinessException / 状态断言都发现不了，只能比对「响应 vs 库」。</p>
 *
 * <p>断言刻意**跨秒**：{@code DATETIME} 没有小数位，插入与关闭若落在同一秒，
 * 即使该列从未前进，两次读到的值也相等 —— 不跨秒的断言会假绿。</p>
 *
 * <p>需要数据库：{@code $env:TRAVEL_MYSQL_TEST = "true"}。
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class ConsultationUpdatedAtContractIntegrationTest {

    /** 跨秒边界所需的等待：1.1s 足以保证关闭动作落在不同的秒。 */
    private static final long CROSS_SECOND_MILLIS = 1_100L;

    @Autowired WebApplicationContext context;
    @Autowired SysUserMapper users;
    @Autowired ConsultationMapper consultations;
    @Autowired ConsultationReplyMapper replies;
    @Autowired JwtTokenProvider tokens;
    @Autowired JsonMapper json;

    private Long userId;
    private Long consultationId;
    private String token;

    @BeforeEach
    void setUp() {
        SysUser user = new SysUser();
        user.username = "cu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        user.nickname = user.username;
        user.realName = user.username;
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        users.insert(user);
        userId = user.id;
        token = "Bearer " + tokens.createToken(user.id, user.username, Set.of("USER"));

        // 状态取 REPLIED：close 只有在「不是 CLOSED」时才真的写库，
        // 用 CLOSED 或 OPEN 都会让这次调用变成空操作，测不到更新后的时间戳。
        Consultation consultation = new Consultation();
        consultation.userId = userId;
        consultation.title = "关闭咨询的更新时间回归";
        consultation.content = "内容";
        consultation.status = "REPLIED";
        consultations.insert(consultation);
        consultationId = consultation.id;
    }

    @AfterEach
    void tearDown() {
        if (consultationId != null) {
            replies.delete(new QueryWrapper<ConsultationReply>().eq("consultation_id", consultationId));
            consultations.deleteById(consultationId);
        }
        if (userId != null) {
            users.deleteById(userId);
        }
    }

    @Test
    @DisplayName("关闭咨询后 updated_at 由数据库推进，且接口响应带回库里的最新值")
    void closeReturnsFreshUpdatedAt() throws Exception {
        LocalDateTime before = readUpdatedAt();
        assertNotNull(before, "插入后 updated_at 必须由 DEFAULT CURRENT_TIMESTAMP 填上");

        Thread.sleep(CROSS_SECOND_MILLIS);
        String body = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build()
                .perform(post("/api/consultations/" + consultationId + "/close")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        LocalDateTime after = readUpdatedAt();
        assertTrue(after.isAfter(before),
                "关闭咨询是一次业务更新，updated_at 必须前进：before=" + before + " after=" + after);

        JsonNode data = json.readTree(body).path("data");
        assertEquals("CLOSED", data.path("status").asString(), "响应里的状态必须是关闭后的值");
        // 序列化带上了时区偏移（+08:00），库里的列是 LocalDateTime，因此按偏移量解析后再取本地时间，
        // 两侧比较的仍是同一个时刻，不依赖 JVM 默认时区。
        assertEquals(after, OffsetDateTime.parse(data.path("updatedAt").asString()).toLocalDateTime(),
                "关闭接口的响应必须带回库里的最新 updatedAt，不能是更新前的旧值");
    }

    /** 每次调用都是一次新的查询，拿到的才是库里的当前值。 */
    private LocalDateTime readUpdatedAt() {
        return consultations.selectById(consultationId).updatedAt;
    }
}
