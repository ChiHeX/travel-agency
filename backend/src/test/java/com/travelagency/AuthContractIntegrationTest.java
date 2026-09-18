package com.travelagency;

import com.travelagency.domain.mapper.SysUserMapper;
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

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses the configured MySQL database; every test rolls back its own fixture data. */
@SpringBootTest
// JwtTokenProvider 会在容器启动时校验签名密钥（缺失即启动失败），
// 这里为测试上下文注入固定密钥，避免集成测试依赖开发者本机的 JWT_SECRET 环境变量。
@TestPropertySource(properties = "app.jwt.secret=integration-test-secret-with-at-least-32-bytes-entropy")
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class AuthContractIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JsonMapper json;
    @Autowired SysUserMapper users;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String username() {
        return "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String registration(String name) {
        return "{\"username\":\"" + name + "\",\"password\":\"Regression123!\",\"nickname\":\"Regression test\"}";
    }

    private JsonNode register(String name) throws Exception {
        var response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registration(name))).andReturn().getResponse();
        assertTrue(response.getStatus() < 300, response.getContentAsString());
        return json.readTree(response.getContentAsString()).get("data");
    }

    @Test
    void registrationHasCreatedStatusLocationAndContractTypes() throws Exception {
        var response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registration(username())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/account/profile"))
                .andReturn().getResponse();
        JsonNode session = json.readTree(response.getContentAsString()).get("data");
        assertTrue(session.get("accessToken").isString());
        assertEquals("Bearer", session.get("tokenType").asString());
        assertTrue(session.get("expiresIn").isIntegralNumber());
        assertTrue(session.get("expiresIn").asLong() > 0);
        assertTrue(session.get("user").get("id").isString());
        assertEquals("ACTIVE", session.get("user").get("status").asString());
        OffsetDateTime.parse(session.get("user").get("createdAt").asString());
    }

    @Test
    void duplicateUsernameReturnsConflict() throws Exception {
        String name = username();
        register(name);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registration(name)))
                .andExpect(status().isConflict());
    }

    @Test
    void profileNullableFieldsCanBeClearedInDatabase() throws Exception {
        JsonNode session = register(username());
        String bearer = "Bearer " + session.get("accessToken").asString();
        mvc.perform(put("/api/account/profile").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"Regression test","realName":"Test","phone":"13800138000",
                                 "email":"test@example.com","avatarUrl":"https://example.com/avatar.png"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(put("/api/account/profile").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"Regression test","realName":null,"phone":null,"email":null,"avatarUrl":null}
                                """))
                .andExpect(status().isOk());
        var saved = users.selectById(session.get("user").get("id").asString());
        assertAll(() -> assertNull(saved.realName), () -> assertNull(saved.phone),
                () -> assertNull(saved.email), () -> assertNull(saved.avatar));
    }

    @Test
    void disabledAccountCannotUsePreviouslyIssuedToken() throws Exception {
        JsonNode session = register(username());
        var user = users.selectById(session.get("user").get("id").asString());
        user.status = 0;
        users.updateById(user);
        mvc.perform(get("/api/orders").header("Authorization", "Bearer " + session.get("accessToken").asString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void preflightAllowsIdempotencyHeaderOnlyForTrustedOrigins() throws Exception {
        for (String origin : new String[]{"http://localhost:5173", "http://127.0.0.1:5173"}) {
            mvc.perform(options("/api/orders").header("Origin", origin)
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin))
                    .andExpect(header().string("Access-Control-Allow-Headers",
                            org.hamcrest.Matchers.containsString("idempotency-key")));
        }
        mvc.perform(options("/api/orders").header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
