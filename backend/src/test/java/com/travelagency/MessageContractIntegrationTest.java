package com.travelagency;

import com.travelagency.common.security.JwtTokenProvider;
import com.travelagency.domain.entity.Message;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.mapper.MessageMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class MessageContractIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired SysUserMapper users;
    @Autowired MessageMapper messages;
    @Autowired JwtTokenProvider tokens;
    private MockMvc mvc;

    @BeforeEach
    void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    private SysUser user() {
        SysUser user = new SysUser();
        user.username = "msg_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        user.nickname = "通知测试";
        user.passwordHash = "unused-test-hash";
        user.status = 1;
        user.deleted = 0;
        users.insert(user);
        return user;
    }

    private String bearer(SysUser user) {
        return "Bearer " + tokens.createToken(user.id, user.username, Set.of("USER"));
    }

    @Test
    void newNotificationTypesSupportUnreadAndReadOperationsWithoutCrossUserAccess() throws Exception {
        SysUser owner = user();
        String token = bearer(owner);
        Message first = null;
        for (String type : List.of("ORDER_AUDIT_ANOMALY", "DEPARTURE_REMINDER", "DEPARTURE_STATUS")) {
            Message message = new Message();
            message.userId = owner.id;
            message.type = type;
            message.title = "测试通知 " + type;
            message.content = "仅用于自动化验收的通知正文";
            message.readFlag = 0;
            messages.insert(message);
            if (first == null) first = message;
        }
        mvc.perform(get("/api/messages/unread-count").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.count").value(3));
        mvc.perform(get("/api/messages").param("unreadOnly", "true").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items[0].id").isString())
                .andExpect(jsonPath("$.data.items[0].read").value(false));
        mvc.perform(patch("/api/messages/" + first.id + "/read").header("Authorization", bearer(user())))
                .andExpect(status().isNotFound());
        for (int repeat = 0; repeat < 2; repeat++) {
            mvc.perform(patch("/api/messages/" + first.id + "/read").header("Authorization", token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.read").value(true));
        }
        mvc.perform(get("/api/messages/unread-count").header("Authorization", token))
                .andExpect(jsonPath("$.data.count").value(2));
        mvc.perform(post("/api/messages/read-all").header("Authorization", token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/messages/unread-count").header("Authorization", token))
                .andExpect(jsonPath("$.data.count").value(0));
        mvc.perform(get("/api/messages").param("unreadOnly", "true").header("Authorization", token))
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/messages")).andExpect(status().isUnauthorized());
    }
}
