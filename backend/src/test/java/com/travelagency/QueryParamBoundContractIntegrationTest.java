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
 * 契约给数值型查询参数声明了边界（{@code durationDays} minimum 1、{@code departureMonth} 1..12、
 * 价格引用 {@code PriceFilter}），此前实现只接不校验：{@code durationDays=0} 被原样拿去 eq 匹配，
 * {@code departureMonth} 越界则被静默忽略后返回未筛选结果 —— 调用方拿不到任何"参数非法"的信号。
 *
 * <p>本测试的 {@code inside}/{@code outside} 就是契约原文的可执行版本：判据一律取自
 * {@code docs/openapi.yaml}，不引用实现里的常量。</p>
 *
 * <p>校验放在 controller 参数上（类已有 {@code @Validated}），不合法由 {@code GlobalExceptionHandler}
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

    /** 价格按契约 {@code PriceFilter}：ASCII 数字、最多两位小数、整数部分不限位数、可省略小数部分、
     * 允许前导零、空串视为未提供。 */
    private static final List<String> PRICE_INSIDE =
            List.of("0", "0.00", "100", "999.5", "2999.00", "0100", "1000000000.00", "");

    private static final List<String> PRICE_OUTSIDE =
            List.of("-1", "-0.01", "1.234", "１２３", "1e3", "+100", "abc", "1.2.3", "1,000", ".5", "100.");

    private static final List<Bound> BOUNDS = List.of(
            new Bound("durationDays", List.of("1", "2", "365"), List.of("0", "-3")),
            new Bound("departureMonth", List.of("1", "6", "12"), List.of("0", "13", "-1")),
            new Bound("minPrice", PRICE_INSIDE, PRICE_OUTSIDE),
            new Bound("maxPrice", PRICE_INSIDE, PRICE_OUTSIDE));

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("不传、空串或取契约内取值 → 200")
    void insideBoundsIsAccepted() throws Exception {
        mvc.perform(routes(null, null)).andExpect(status().isOk());
        for (Bound b : BOUNDS) {
            for (String value : b.inside()) {
                mvc.perform(routes(b.name(), value)).andExpect(status().isOk());
            }
        }
    }

    @Test
    @DisplayName("契约外取值 → 422 VALIDATION_ERROR，errors[0].field 指回该参数")
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

    /**
     * 上一版实现（{@code BigDecimal} + {@code @DecimalMin} + {@code @Digits(integer = 9, fraction = 2)}）
     * 与契约的两类偏差，逐条钉住：全角数字 / 科学计数法 / 带符号会被 {@code BigDecimal} 静默解析成数值
     * 收下，而契约的 10 位整数价格会被 {@code @Digits} 误拒。两类偏差方向相反，只补一侧都会漏。
     */
    @Test
    @DisplayName("价格：上一版误收的写法现在拒绝，上一版误拒的 10 位整数价格现在收下")
    void priceFilterClosesBothDirectionsOfThePreviousMismatch() throws Exception {
        for (String name : List.of("minPrice", "maxPrice")) {
            for (String value : List.of("１２３", "1e3", "+100")) {
                mvc.perform(routes(name, value))
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                        .andExpect(jsonPath("$.errors[0].field", endsWith(name)));
            }
            for (String value : List.of("1000000000.00", "9999999999.99")) {
                mvc.perform(routes(name, value)).andExpect(status().isOk());
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
