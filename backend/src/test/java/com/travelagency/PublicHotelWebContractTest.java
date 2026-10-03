package com.travelagency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 线路下的酒店公开详情（契约 {@code GET /routes/{routeId}/hotels/{hotelId}}）的 Web 层契约冒烟测试：
 * <b>不需要数据库</b>，随普通 {@code mvn test} 一起执行。
 *
 * <p>只覆盖"在访问数据库之前就该有结论"的行为：</p>
 * <ul>
 *   <li>无需登录：不带任何身份时，非数字路径参数应拿到 <b>400</b>；若这个端点被改成需要认证，
 *       这里会先拿到 401，断言随之失败 —— 酒店详情是用户端页面按需调用的公开接口；</li>
 *   <li>公开路径是只读的：{@code POST} / {@code PUT} / {@code DELETE} 没有处理器（405），
 *       写入口只存在于 {@code /admin/hotels}。</li>
 * </ul>
 *
 * <p>"三个 404 条件"（线路未发布 / 酒店未被该线路引用 / 酒店已停用）必须真的查到数据库才成立，
 * 由 {@code TRAVEL_MYSQL_TEST=true} 下的 {@code HotelDetailContractIntegrationTest} 覆盖。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
class PublicHotelWebContractTest {

    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        }
        return mvc;
    }

    /**
     * 公开端点不得要求登录。
     *
     * <p>用"路径参数类型错误"作为探针：它发生在认证之后、数据库之前，因此拿到 400
     * 就同时证明了"无需登录"与"路径已注册"；未被放行时会先得到 401。</p>
     */
    @Test
    @DisplayName("酒店公开详情无需登录：路径参数类型错误返回 400 而不是 401")
    void publicHotelDetailIsReachableWithoutLogin() throws Exception {
        mvc().perform(get("/api/routes/1/hotels/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        mvc().perform(get("/api/routes/abc/hotels/1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    /**
     * 公开酒店路径只读：写操作必须只存在于 {@code /admin/hotels}。
     *
     * <p>用 STAFF 身份发送是为了跨过 {@code SecurityConfig} 对非 GET 请求的认证要求，
     * 从而真的走到 DispatcherServlet：若公开路径上存在写端点，这里会返回 2xx 而不是 405。</p>
     */
    @Test
    @DisplayName("公开酒店路径不接受写入：POST/PUT/DELETE 均为 405")
    void publicHotelPathDoesNotAcceptWrites() throws Exception {
        mvc().perform(post("/api/routes/1/hotels/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());

        mvc().perform(put("/api/routes/1/hotels/1").with(user("staff").roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());

        mvc().perform(delete("/api/routes/1/hotels/1").with(user("staff").roles("STAFF")))
                .andExpect(status().isMethodNotAllowed());
    }
}
