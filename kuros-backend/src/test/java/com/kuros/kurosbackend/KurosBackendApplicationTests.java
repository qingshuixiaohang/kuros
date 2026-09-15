package com.kuros.kurosbackend;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

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

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void 评论列表支持最新排序分页并保留删除占位() throws Exception {
        mockMvc.perform(get("/api/v1/posts/10000000-0000-0000-0000-000000000001/comments")
                        .param("sort", "latest")
                        .param("page", "1")
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].content").value("该评论已删除"))
                .andExpect(jsonPath("$.data[0].deleted").value(true))
                .andExpect(jsonPath("$.data[1].id").value("30000000-0000-0000-0000-000000000003"))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.pageSize").value(2))
                .andExpect(jsonPath("$.meta.totalItems").value(4));
    }

    @Test
    void 评论列表支持按热度排序() throws Exception {
        mockMvc.perform(get("/api/v1/posts/10000000-0000-0000-0000-000000000001/comments")
                        .param("sort", "hot")
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("30000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.data[1].id").value("30000000-0000-0000-0000-000000000002"));
    }

    @Test
    void 游客发表评论返回未授权() throws Exception {
        mockMvc.perform(post("/api/v1/posts/10000000-0000-0000-0000-000000000001/comments")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"游客评论\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DirtiesContext
    void 浏览器先获取Csrf令牌后可以发表评论() throws Exception {
        Cookie sessionCookie = login("13800000018");
        var csrfResponse = mockMvc.perform(get("/api/v1/auth/csrf").cookie(sessionCookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andReturn();
        Cookie csrfCookie = csrfResponse.getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/v1/posts/10000000-0000-0000-0000-000000000001/comments")
                        .cookie(sessionCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"浏览器令牌链路正常。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("浏览器令牌链路正常。"));
    }

    @Test
    @DirtiesContext
    void 登录用户可以发表评论但不能创建二级回复() throws Exception {
        Cookie sessionCookie = login("13800000008");
        String commentsPath = "/api/v1/posts/10000000-0000-0000-0000-000000000001/comments";

        mockMvc.perform(post(commentsPath)
                        .cookie(sessionCookie)
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"实战里这套循环很好上手。\",\"parentId\":\"30000000-0000-0000-0000-000000000001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentId").value("30000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.data.author.nickname").value("漂泊者0008"));

        mockMvc.perform(post(commentsPath)
                        .cookie(sessionCookie)
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"不允许继续嵌套。\",\"parentId\":\"30000000-0000-0000-0000-000000000002\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMENT_NESTING_NOT_ALLOWED"));
    }

    @Test
    @DirtiesContext
    void 只能删除自己的评论并保留删除占位() throws Exception {
        Cookie ownerCookie = login("13800000009");
        String commentsPath = "/api/v1/posts/10000000-0000-0000-0000-000000000001/comments";
        var created = mockMvc.perform(post(commentsPath)
                        .cookie(ownerCookie)
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"准备按这个顺序练一轮。\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String responseBody = created.getResponse().getContentAsString();
        int idStart = responseBody.indexOf("\"id\":\"") + 6;
        String commentId = responseBody.substring(idStart, responseBody.indexOf('"', idStart));

        Cookie otherCookie = login("13800000010");
        mockMvc.perform(delete(commentsPath + "/" + commentId)
                        .cookie(otherCookie)
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(delete(commentsPath + "/" + commentId)
                        .cookie(ownerCookie)
                        .with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(commentsPath).param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + commentId + "')].content").value("该评论已删除"))
                .andExpect(jsonPath("$.data[?(@.id == '" + commentId + "')].deleted").value(true));
    }

    private Cookie login(String phone) throws Exception {
        mockMvc.perform(post("/api/v1/auth/code")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk());
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("KUROS_SESSION");
    }

}
