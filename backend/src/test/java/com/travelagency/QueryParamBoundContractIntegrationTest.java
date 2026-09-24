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
 * 契约给数值型查询参数声明了边界（{@code durationDays} minimum 1、{@code departureMonth} 1..12），
 * 此前实现只接不校验：{@code durationDays=0} 被原样拿去 eq 匹配，{@code departureMonth} 越界则
 * 被静默忽略后返回未筛选结果 —— 调用方拿不到任何"参数非法"的信号。
 *
 * <p>边界校验放在 controller 参数上（类已有 {@code @Validated}），越界由 {@code GlobalExceptionHandler}
 * 转成 422 {@code VALIDATION_ERROR} + 可定位到参数的 {@code errors[]}。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long",
        "app.integrations.alipay.callback-secret=query-param-bound-callback-secret"
})
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class QueryParamBoundContractIntegrationTest {

    /** 参数名 / 契约内的合法取值 / 契约外的取值。 */
    private record Bound(String name, List<String> inside, List<String> outside) {
    }

    private static final List<Bound> BOUNDS = List.of(
            new Bound("durationDays", List.of("1", "2", "365"), List.of("0", "-3")),
            new Bound("departureMonth", List.of("1", "6", "12"), List.of("0", "13", "-1")));

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("不传或取契约内数值 → 200")
    void insideBoundsIsAccepted() throws Exception {
        mvc.perform(routes(null, null)).andExpect(status().isOk());
        for (Bound b : BOUNDS) {
            for (String value : b.inside()) {
                mvc.perform(routes(b.name(), value)).andExpect(status().isOk());
            }
        }
    }

    @Test
    @DisplayName("越界 → 422 VALIDATION_ERROR，errors[0].field 指回该参数")
    void outsideBoundsIsRejected() throws Exception {
        for (Bound b : BOUNDS) {
            for (String value : b.outside()) {
                mvc.perform(routes(b.name(), value))
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                        .andExpect(jsonPath("$.errors[0].field", endsWith(b.name())))
                        .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
            }
        }
    }

    @Test
    @DisplayName("GET /admin/guides 的 keyword 已进契约：到 100 收下，101 回 422")
    void guideKeywordLengthIsEnforced() throws Exception {
        for (String value : List.of("", "k".repeat(100), "关".repeat(100), "😀".repeat(100))) {
            mvc.perform(guides(value)).andExpect(status().isOk());
        }
        for (String value : List.of("k".repeat(101), "😀".repeat(101))) {
            mvc.perform(guides(value))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors[0].field", endsWith("keyword")));
        }
    }

    private MockHttpServletRequestBuilder routes(String name, String value) {
        MockHttpServletRequestBuilder request = get("/api/routes").param("page", "1").param("size", "5");
        return value == null ? request : request.param(name, value);
    }

    private MockHttpServletRequestBuilder guides(String keyword) {
        return get("/api/admin/guides").param("page", "1").param("size", "5").param("keyword", keyword)
                .with(user("query-param-bound-admin").roles("ADMIN"));
    }
}
