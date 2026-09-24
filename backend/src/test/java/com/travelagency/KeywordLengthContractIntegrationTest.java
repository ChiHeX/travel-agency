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
 * 列表端点查询参数 {@code keyword} 的契约长度上限（{@code maxLength: 100}）回归测试。
 *
 * <p><b>背景</b>：契约给一批列表端点的 {@code keyword} 声明了 {@code maxLength: 100}，
 * 但实现完全不校验 —— 上限只存在于 {@code docs/openapi.yaml} 里。这些关键字最终会被拼成
 * {@code LIKE %…%} 交给 MySQL，其中 {@code /routes}、{@code /attractions}、{@code /articles}
 * 还是<b>无需登录</b>的公开接口。</p>
 *
 * <p><b>为什么必须走真库 + 真 HTTP 层</b>：本类钉住两件事。其一是"边界内不能被误伤"——
 * 恰好 100 字符必须照旧返回 200，这只有在参数校验通过、查询真的落到数据库、结果真的
 * 按分页信封序列化之后才成立；纯 Mockito 单测直接断言 service 返回值，绕过了
 * 参数校验与序列化链。其二是"超长必须能被定位"——{@code errors[0].field} 要能指回
 * {@code keyword} 参数，这依赖类上的 {@code @Validated} 让方法参数约束生效
 * （去掉它仍会 422，但 {@code field} 会退化成空串，客户端无从知道是哪个参数错了）。</p>
 *
 * <p><b>按 Unicode 码点计数</b>：100 个汉字或 emoji 都合法，不能按 UTF-8 字节数或
 * Java UTF-16 码元数误判。覆盖两类字符在 100 / 101 码点两侧的边界。</p>
 *
 * <p><b>顺序约定</b>：每个方法里先跑预期 200 的调用，再跑预期 422 的调用。
 * 断言失败时报错信息里带上端点路径，避免"4 个端点里的哪一个挂了"需要靠猜。</p>
 *
 * <p>需要数据库：{@code $env:TRAVEL_MYSQL_TEST = "true"}。整个类在事务内执行，结束时统一回滚。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long",
        "app.integrations.alipay.callback-secret=keyword-length-callback-secret-32-bytes"
})
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class KeywordLengthContractIntegrationTest {

    /** 契约声明了 {@code keyword.maxLength: 100} 且无需登录的三个公开列表端点。 */
    private static final List<String> PUBLIC_ENDPOINTS =
            List.of("/api/routes", "/api/attractions", "/api/articles");

    /** 后台线路列表，同样声明了 {@code keyword.maxLength: 100}，但要求 ADMIN / STAFF。 */
    private static final String ADMIN_ROUTES = "/api/admin/routes";

    /** 与实现约定的约束消息：既是给用户看的提示，也是本类用来确认"拒绝理由正确"的锚点。 */
    private static final String EXPECTED_MESSAGE = "keyword 长度不能超过 100 个字符";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ------------------------------------------------------------------
    // 边界内：100 字符（含汉字）与不传参数都必须照旧可用
    // ------------------------------------------------------------------

    @Test
    @DisplayName("keyword 恰好 100 码点（含汉字和 emoji）与不传 keyword 都返回 200")
    void keywordAtTheLimitIsAccepted() throws Exception {
        for (String path : PUBLIC_ENDPOINTS) {
            expectAccepted(paged(get(path), null), path + "（不传 keyword）");
            expectAccepted(paged(get(path), "k".repeat(100)), path + "（100 个 ASCII）");
            // 契约的 maxLength 是字符数，不是字节数：100 个汉字是 300 字节，仍然合法。
            expectAccepted(paged(get(path), "关".repeat(100)), path + "（100 个汉字）");
            expectAccepted(paged(get(path), "😀".repeat(100)), path + "（100 个 emoji）");
        }

        expectAccepted(adminRoutes(null), ADMIN_ROUTES + "（不传 keyword）");
        expectAccepted(adminRoutes("k".repeat(100)), ADMIN_ROUTES + "（100 个 ASCII）");
        expectAccepted(adminRoutes("关".repeat(100)), ADMIN_ROUTES + "（100 个汉字）");
        expectAccepted(adminRoutes("😀".repeat(100)), ADMIN_ROUTES + "（100 个 emoji）");
    }

    // ------------------------------------------------------------------
    // 边界外：101 字符必须 422，且 errors[] 要能指回 keyword
    // ------------------------------------------------------------------

    @Test
    @DisplayName("keyword 超过 100 字符 → 422 VALIDATION_ERROR，errors[0].field 指回 keyword、error 消息为约定文案")
    void keywordOverTheLimitIsRejectedWithLocatableErrors() throws Exception {
        for (String path : PUBLIC_ENDPOINTS) {
            expectRejected(paged(get(path), "k".repeat(101)), path + "（101 个 ASCII）");
            expectRejected(paged(get(path), "关".repeat(101)), path + "（101 个汉字）");
            expectRejected(paged(get(path), "😀".repeat(101)), path + "（101 个 emoji）");
        }

        expectRejected(adminRoutes("k".repeat(101)), ADMIN_ROUTES + "（101 个 ASCII）");
        expectRejected(adminRoutes("关".repeat(101)), ADMIN_ROUTES + "（101 个汉字）");
        expectRejected(adminRoutes("😀".repeat(101)), ADMIN_ROUTES + "（101 个 emoji）");
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    /** 统一带上分页参数，避免"没传 page/size"这类噪声混进断言。 */
    private static MockHttpServletRequestBuilder paged(MockHttpServletRequestBuilder request, String keyword) {
        request.param("page", "1").param("size", "5");
        return keyword == null ? request : request.param("keyword", keyword);
    }

    /** 后台端点的请求必须带 STAFF 身份，否则会在参数校验之前先返回 401/403。 */
    private static MockHttpServletRequestBuilder adminRoutes(String keyword) {
        return paged(get(ADMIN_ROUTES).with(user("keyword-length-staff").roles("STAFF")), keyword);
    }

    private void expectAccepted(MockHttpServletRequestBuilder request, String label) throws Exception {
        int status = mvc.perform(request).andReturn().getResponse().getStatus();
        assertEquals(200, status, label + "：keyword 未超长时必须返回 200（0 字或 ≤100 个字符）");
    }

    private void expectRejected(MockHttpServletRequestBuilder request, String label) throws Exception {
        mvc.perform(request)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].field", endsWith("keyword")))
                .andExpect(jsonPath("$.errors[0].message").value(EXPECTED_MESSAGE));
    }
}
