package com.travelagency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约给列表端点声明了字符串长度上限（departureCity 64 / destination 128 / city 64 / module 64），
 * 此前实现只接参数不校验，超长值会被原样拼进 LIKE / eq。
 *
 * <p>与 keyword 同一条链路：{@code @CodePointLength} 按 Unicode 码点计数，类上的 {@code @Validated}
 * 让它生效，超长由 {@code GlobalExceptionHandler} 转成 422 + 可定位到参数的 {@code errors[]}。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long",
        "app.integrations.alipay.callback-secret=query-param-length-callback-secret"
})
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class QueryParamLengthContractIntegrationTest {

    /** 路径 / 参数名 / 契约上限；后台端点的请求要带 ADMIN 身份，否则先命中 403。 */
    private record Param(String path, String name, int limit, boolean admin) {
    }

    private static final List<Param> PARAMS = List.of(
            new Param("/api/routes", "departureCity", 64, false),
            new Param("/api/routes", "destination", 128, false),
            new Param("/api/attractions", "city", 64, false),
            new Param("/api/articles", "destination", 128, false),
            new Param("/api/admin/logs", "module", 64, true));

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("参数恰好到上限（ASCII / 汉字 / emoji），以及不传、空串，都返回 200")
    void atTheLimitIsAccepted() throws Exception {
        for (Param p : PARAMS) {
            expect(p, null, 200);
            expect(p, "", 200);
            expect(p, "k".repeat(p.limit()), 200);
            expect(p, "关".repeat(p.limit()), 200);
            expect(p, "😀".repeat(p.limit()), 200);
        }
    }

    @Test
    @DisplayName("超过上限 → 422 VALIDATION_ERROR，errors[0].field 指回该参数")
    void overTheLimitIsRejected() throws Exception {
        for (Param p : PARAMS) {
            expect(p, "k".repeat(p.limit() + 1), 422);
            expect(p, "😀".repeat(p.limit() + 1), 422);
        }
    }

    private void expect(Param p, String value, int expected) throws Exception {
        MockHttpServletRequestBuilder request = get(p.path()).param("page", "1").param("size", "5");
        if (p.admin()) {
            request = request.with(user("query-param-length-admin").roles("ADMIN"));
        }
        if (value != null) {
            request = request.param(p.name(), value);
        }
        if (expected == 200) {
            mvc.perform(request).andExpect(status().isOk());
            return;
        }
        mvc.perform(request)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field", endsWith(p.name())))
                .andExpect(jsonPath("$.errors[0].message")
                        .value(p.name() + " 长度不能超过 " + p.limit() + " 个字符"));
    }
}
