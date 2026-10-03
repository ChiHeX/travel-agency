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
