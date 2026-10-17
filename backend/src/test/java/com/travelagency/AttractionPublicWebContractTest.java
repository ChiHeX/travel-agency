package com.travelagency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 景点公开浏览端点（契约 {@code Content} 的 {@code /attractions}）的 Web 层契约冒烟测试：
 * <b>不需要数据库</b>，随普通 {@code mvn test} 一起执行。
 *
 * <p>{@code AttractionAdminWebContractTest} 只覆盖 {@code /admin/attractions}，公开的
 * {@code GET /attractions} 与 {@code GET /attractions/{attractionId}} 此前没有任何
 * 不需要数据库的用例；而它们是无登录即可访问、且直接暴露在外的两个端点。</p>
 *
 * <p>覆盖的都是"在访问数据库之前就该有结论"的行为：</p>
 * <ul>
 *   <li>无需登录：不带任何身份时，超长参数应拿到 <b>422</b>、非数字路径参数应拿到 <b>400</b>；
 *       若这两个端点被改成需要认证，这里会先拿到 401，断言随之失败；</li>
 *   <li>契约给查询参数声明的上限必须真的生效：{@code keyword} 100 码点、{@code city} 64 码点，
 *       超长返回 422 且 {@code errors[]} 能指回具体参数
 *       （上限只写在 {@code docs/openapi.yaml} 里是拦不住请求的）；</li>
 *   <li>公开路径是只读的：{@code POST} / {@code PUT} / {@code DELETE} 没有处理器（405），
 *       而不是在公开路径上提供写入能力 —— 写入口只存在于 {@code /admin/attractions}。</li>
 * </ul>
 *
 * <p>"边界内不能被误伤"（恰好 100 / 64 码点仍返回 200）必须在查询真的落到数据库、
 * 结果真的按分页信封序列化之后才成立，因此由 {@code TRAVEL_MYSQL_TEST=true} 下的
 * {@code AttractionTripIntegrationTest} 覆盖。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
class AttractionPublicWebContractTest {

    private static final String EXPECTED_KEYWORD_MESSAGE = "keyword 长度不能超过 100 个字符";
    private static final String EXPECTED_CITY_MESSAGE = "city 长度不能超过 64 个字符";

    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        }
        return mvc;
    }

    /** 统一带上分页参数，避免"没传 page/size"这类噪声混进断言。 */
    private static MockHttpServletRequestBuilder paged(MockHttpServletRequestBuilder request) {
        return request.param("page", "1").param("size", "5");
    }

    /**
     * 公开端点不得要求登录。
     *
     * <p>用"参数校验失败"作为探针：它发生在认证之后、数据库之前，因此拿到 422/400
     * 就同时证明了"无需登录"与"路径已注册"；两种失败都会让本用例失败。</p>
     */
    @Test
    @DisplayName("公开景点端点无需登录：校验失败返回 422/400 而不是 401")
    void publicEndpointsAreReachableWithoutLogin() throws Exception {
        mvc().perform(paged(get("/api/attractions")).param("keyword", "k".repeat(101)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 非数字路径参数在进入业务逻辑前被判为参数类型错误（400）；未注册的路径会得到 404。
        mvc().perform(get("/api/attractions/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    /**
     * 契约 {@code GET /attractions} 的 {@code keyword}：{@code maxLength: 100}。
     *
     * <p>该参数会被拼成 {@code LIKE %…%}，而本端点无需登录，上限必须由服务端把关。
     * 公开列表此前没有不依赖数据库的用例，这条断言与
     * {@code KeywordLengthContractIntegrationTest} 的带库版本互为补充。</p>
     */
    @Test
    @DisplayName("公开列表 keyword 超过 100 码点：422，errors[0].field 指回 keyword")
    void listRejectsKeywordOverTheContractLimit() throws Exception {
        mvc().perform(paged(get("/api/attractions")).param("keyword", "😀".repeat(101)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("keyword")))
                .andExpect(jsonPath("$.errors[0].message").value(EXPECTED_KEYWORD_MESSAGE));
    }

    /**
     * 契约 {@code GET /attractions} 的 {@code city}：{@code maxLength: 64}。
     *
     * <p>它是无需登录的公开参数；只把上限写在 {@code docs/openapi.yaml} 里时，
     * 客户端可以送任意长度的字符串进来（实现此前就是这样），超长在库外返回 422。</p>
     */
    @Test
    @DisplayName("公开列表 city 超过 64 码点：422，errors[0].field 指回 city")
    void listRejectsCityOverTheContractLimit() throws Exception {
        mvc().perform(paged(get("/api/attractions")).param("city", "城".repeat(65)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value(endsWith("city")))
                .andExpect(jsonPath("$.errors[0].message").value(EXPECTED_CITY_MESSAGE));
    }

    /**
     * 公开景点路径只读：写操作必须只存在于 {@code /admin/attractions}。
     *
     * <p>用 STAFF 身份发送，是为了跨过 {@code SecurityConfig} 对非 GET 请求的认证要求，
     * 从而真的走到 DispatcherServlet：若公开路径上存在写端点，这里会返回 2xx 而不是 405。</p>
     */
    @Test
    @DisplayName("公开景点路径不接受写入：POST/PUT/DELETE 均为 405")
    void publicPathsDoNotAcceptWrites() throws Exception {
        mvc().perform(post("/api/attractions").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());

        mvc().perform(put("/api/attractions/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());

        mvc().perform(delete("/api/attractions/1").with(user("staff").roles("STAFF")))
                .andExpect(status().isMethodNotAllowed());
    }
}
