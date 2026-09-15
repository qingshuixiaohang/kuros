package com.kuros.kurosbackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KurosBackendApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 游客可以按分类分页浏览已发布帖子() throws Exception {
        mockMvc.perform(get("/api/v1/posts")
                        .param("category", "配队攻略")
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].title").value("长离焚火队：从零到毕业的配队思路"))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.pageSize").value(10));
    }

    @Test
    void 游客可以查看已发布帖子详情() throws Exception {
        mockMvc.perform(get("/api/v1/posts/10000000-0000-0000-0000-000000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("长离焚火队：从零到毕业的配队思路"))
                .andExpect(jsonPath("$.data.content").isNotEmpty())
                .andExpect(jsonPath("$.data.author.nickname").value("潮声档案员"));
    }

    @Test
    void 公开查询不会暴露已删除帖子() throws Exception {
        mockMvc.perform(get("/api/v1/posts/10000000-0000-0000-0000-000000000099"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
    }

    @Test
    void 首次验证码登录会创建用户并恢复会话() throws Exception {
        mockMvc.perform(post("/api/v1/auth/code")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000005\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devCode").value("123456"));

        var login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000005\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("KUROS_SESSION"))
                .andExpect(jsonPath("$.data.nickname").value("漂泊者0005"))
                .andReturn();

        var sessionCookie = login.getResponse().getCookie("KUROS_SESSION");
        mockMvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("漂泊者0005"));
    }

    @Test
    void 已有用户验证码登录不会重复创建用户() throws Exception {
        mockMvc.perform(post("/api/v1/auth/code")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000001\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000001\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("潮声档案员"));
    }

    @Test
    void 无效验证码不能建立登录会话() throws Exception {
        mockMvc.perform(post("/api/v1/auth/code")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000006\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000006\",\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));
    }

    @Test
    void 退出登录后会话立即失效() throws Exception {
        mockMvc.perform(post("/api/v1/auth/code")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000007\"}"))
                .andExpect(status().isOk());

        var login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000007\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn();
        var sessionCookie = login.getResponse().getCookie("KUROS_SESSION");

        mockMvc.perform(post("/api/v1/auth/logout").cookie(sessionCookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("KUROS_SESSION", 0));

        mockMvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 没有会话访问当前用户返回未授权() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

}
