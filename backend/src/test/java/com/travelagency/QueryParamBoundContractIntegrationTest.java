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
import static org.junit.jupiter.api.Assertions.assertEquals;
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
            new Bound("departureMonth", List.of("1", "6", "12"), List.of("0", "13", "-1")),
            // 价格：契约 Money 是"非负、固定两位小数"的字符串；实现接受不带小数的写法
            // （现有搜索页与收藏链接会传 minPrice=100），但负数与超过两位小数的值必须拒绝。
            new Bound("minPrice", List.of("0", "0.00", "100", "2999.00", "999.5"), List.of("-1", "-0.01", "1.234")),
            new Bound("maxPrice", List.of("0", "0.00", "100", "2999.00", "999.5"), List.of("-1", "-0.01", "1.234")));

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
    @DisplayName("非数值价格是参数类型错误 → 400（与其它 BigDecimal 参数一致，不是 422）")
    void nonNumericPriceIsBadRequest() throws Exception {
        for (String name : List.of("minPrice", "maxPrice")) {
            for (String value : List.of("abc", "1.2.3", "1,000")) {
                int status = mvc.perform(routes(name, value)).andReturn().getResponse().getStatus();
                assertEquals(400, status, name + "=" + value + " 应因参数类型错误返回 400");
            }
        }
    }

    /**
     * 记录一个容易踩的既有行为：Java 的 {@code BigDecimal} 经由 {@code Character.digit} 解析数字，
     * 因此**全角数字**（Unicode Nd）也是合法数值，{@code minPrice=１２３} 会被当成 123 收下。
     *
     * <p>契约的 {@code Money} 模式只覆盖 ASCII 数字，所以这里属于上一段注释里"宽松一档"的又一处；
     * 若要把入参收紧到与模式完全一致（同时拒绝全角数字与不带小数的写法），需要改这条断言，
     * 并同步规范前端与 URL 入参。</p>
     */
    @Test
    @DisplayName("全角数字被当作同一数值接受（BigDecimal 解析口径，属契约模式的宽松超集）")
    void fullWidthDigitsAreParsedAsNumbers() throws Exception {
        for (String name : List.of("minPrice", "maxPrice")) {
            assertEquals(200, mvc.perform(routes(name, "１２３")).andReturn().getResponse().getStatus(),
                    name + " 的全角数字当前会被 BigDecimal 解析为 123");
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
