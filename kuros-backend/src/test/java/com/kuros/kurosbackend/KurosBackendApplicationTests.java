package com.kuros.kurosbackend;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
    void 健康检查只返回服务状态而不暴露敏感详情() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

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
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
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
    void 公开用户资料只展示公开信息和已发布帖子() throws Exception {
        String userId = "10000000-0000-0000-0000-000000000001";
        mockMvc.perform(get("/api/v1/users/" + userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(userId))
                .andExpect(jsonPath("$.data.nickname").value("潮声档案员"))
                .andExpect(jsonPath("$.data.phone").doesNotExist())
                .andExpect(jsonPath("$.data.postCount").value(1));

        mockMvc.perform(get("/api/v1/users/" + userId + "/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].title").value("长离焚火队：从零到毕业的配队思路"))
                .andExpect(jsonPath("$.meta.totalItems").value(1));
    }

    @Test
    @DirtiesContext
    void 当前用户个人中心包含自己的帖子和评论但不暴露手机号() throws Exception {
        Cookie sessionCookie = login("13800000001");

        mockMvc.perform(get("/api/v1/users/me/profile").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.nickname").value("潮声档案员"))
                .andExpect(jsonPath("$.data.profile.phone").doesNotExist())
                .andExpect(jsonPath("$.data.stats.postCount").value(1))
                .andExpect(jsonPath("$.data.posts.items", hasSize(1)))
                .andExpect(jsonPath("$.data.comments.items", hasSize(1)))
                .andExpect(jsonPath("$.data.comments.items[0].content").value("谢谢反馈！低配队伍可以先保证循环完整，再慢慢补面板，不用一开始就追求毕业词条。"));
    }

    @Test
    @DirtiesContext
    void 个人中心返回收藏帖子和关注用户() throws Exception {
        Cookie sessionCookie = login("13800000008");

        mockMvc.perform(post("/api/v1/posts/10000000-0000-0000-0000-000000000001/interactions/favorite").cookie(sessionCookie).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/users/10000000-0000-0000-0000-000000000002/follow").cookie(sessionCookie).with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me/profile").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorites.items", hasSize(1)))
                .andExpect(jsonPath("$.data.favorites.items[0].id").value("10000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.data.following.items", hasSize(1)))
                .andExpect(jsonPath("$.data.following.items[0].nickname").value("无音区夜行者"))
                .andExpect(jsonPath("$.data.fans.items", hasSize(0)));

        Cookie followedUserSession = login("13800000002");
        mockMvc.perform(get("/api/v1/users/me/profile").cookie(followedUserSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fans.items", hasSize(1)))
                .andExpect(jsonPath("$.data.fans.items[0].id").exists());
    }

    @Test
    @DirtiesContext
    void 登录用户点赞和取消点赞帖子且重复操作幂等() throws Exception {
        Cookie sessionCookie = login("13800000008");
        String interactionPath = "/api/v1/posts/10000000-0000-0000-0000-000000000001/interactions";

        mockMvc.perform(get(interactionPath).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(3700));

        mockMvc.perform(post(interactionPath + "/like").cookie(sessionCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(3701));

        mockMvc.perform(post(interactionPath + "/like").cookie(sessionCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(3701));

        mockMvc.perform(delete(interactionPath + "/like").cookie(sessionCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(3700));
    }

    @Test
    @DirtiesContext
    void 登录用户收藏和取消收藏帖子且重复操作幂等() throws Exception {
        Cookie sessionCookie = login("13800000008");
        String interactionPath = "/api/v1/posts/10000000-0000-0000-0000-000000000001/interactions";

        mockMvc.perform(post(interactionPath + "/favorite").cookie(sessionCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorited").value(true))
                .andExpect(jsonPath("$.data.favoriteCount").value(1201));

        mockMvc.perform(post(interactionPath + "/favorite").cookie(sessionCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorited").value(true))
                .andExpect(jsonPath("$.data.favoriteCount").value(1201));

        mockMvc.perform(delete(interactionPath + "/favorite").cookie(sessionCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorited").value(false))
                .andExpect(jsonPath("$.data.favoriteCount").value(1200));
    }

    @Test
    @DirtiesContext
    void 登录用户可以关注和取消关注其他用户且重复操作幂等() throws Exception {
        Cookie sessionCookie = login("13800000008");
        String followPath = "/api/v1/users/10000000-0000-0000-0000-000000000002/follow";

        mockMvc.perform(get(followPath).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followed").value(false))
                .andExpect(jsonPath("$.data.followerCount").value(0));

        mockMvc.perform(post(followPath).cookie(sessionCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followed").value(true))
                .andExpect(jsonPath("$.data.followerCount").value(1));

        mockMvc.perform(post(followPath).cookie(sessionCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followed").value(true))
                .andExpect(jsonPath("$.data.followerCount").value(1));

        mockMvc.perform(delete(followPath).cookie(sessionCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followed").value(false))
                .andExpect(jsonPath("$.data.followerCount").value(0));
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

    @Test
    void 游客不能发布帖子() throws Exception {
        mockMvc.perform(post("/api/v1/posts")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"type\":\"GUIDE\",\"category\":\"配队攻略\",\"title\":\"游客帖子\",\"content\":\"不应该发布\",\"tags\":[\"测试\"]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DirtiesContext
    void 登录用户发布帖子后可以在公开列表查看且作者绑定当前用户() throws Exception {
        Cookie sessionCookie = login("13800000008");

        var created = mockMvc.perform(post("/api/v1/posts")
                        .cookie(sessionCookie)
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"type\":\"GUIDE\",\"category\":\"配队攻略\",\"title\":\"长离实战循环记录\",\"content\":\"循环内容\",\"tags\":[\"长离\",\"实战\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("GUIDE"))
                .andExpect(jsonPath("$.data.author.nickname").value("漂泊者0008"))
                .andExpect(jsonPath("$.data.likeCount").value(0))
                .andExpect(jsonPath("$.data.favoriteCount").value(0))
                .andReturn();

        String responseBody = created.getResponse().getContentAsString();
        int idStart = responseBody.indexOf("\"id\":\"") + 6;
        String postId = responseBody.substring(idStart, responseBody.indexOf('"', idStart));
        mockMvc.perform(get("/api/v1/posts/" + postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("长离实战循环记录"))
                .andExpect(jsonPath("$.data.author.nickname").value("漂泊者0008"));
    }

    @Test
    void 发布帖子会校验必填字段和内容类型() throws Exception {
        Cookie sessionCookie = login("13800000008");

        mockMvc.perform(post("/api/v1/posts")
                        .cookie(sessionCookie)
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"type\":\"GUIDE\",\"category\":\"配队攻略\",\"title\":\" \",\"content\":\"正文\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POST_TITLE_REQUIRED"));

        mockMvc.perform(post("/api/v1/posts")
                        .cookie(sessionCookie)
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"type\":\"UNKNOWN\",\"category\":\"配队攻略\",\"title\":\"测试帖子\",\"content\":\"正文\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POST_TYPE_INVALID"));
    }

    @Test
    @DirtiesContext
    void 作者可以编辑自己的帖子但其他用户不能操作并且可以软删除() throws Exception {
        Cookie ownerCookie = login("13800000008");
        var created = mockMvc.perform(post("/api/v1/posts")
                        .cookie(ownerCookie)
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"type\":\"GUIDE\",\"category\":\"配队攻略\",\"title\":\"待维护的帖子\",\"content\":\"原始正文\",\"tags\":[\"长离\"]}"))
                .andExpect(status().isCreated())
                .andReturn();
        String responseBody = created.getResponse().getContentAsString();
        int idStart = responseBody.indexOf("\"id\":\"") + 6;
        String postId = responseBody.substring(idStart, responseBody.indexOf('"', idStart));
        String path = "/api/v1/posts/" + postId;

        Cookie otherCookie = login("13800000010");
        mockMvc.perform(put(path)
                        .cookie(otherCookie)
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"type\":\"GUIDE\",\"category\":\"配队攻略\",\"title\":\"不应被修改\",\"content\":\"正文\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put(path)
                        .cookie(ownerCookie)
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"type\":\"GENERAL\",\"category\":\"心得\",\"title\":\"已更新的帖子\",\"content\":\"更新后的正文\",\"tags\":[\"实战\",\"轮切\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("已更新的帖子"))
                .andExpect(jsonPath("$.data.content").value("更新后的正文"))
                .andExpect(jsonPath("$.data.tags", hasSize(2)));

        mockMvc.perform(delete(path).cookie(ownerCookie).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(path).cookie(ownerCookie).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(path)).andExpect(status().isNotFound());
    }

    @Test
    void 游客不能上传图片且登录用户上传合法图片() throws Exception {
        MockMultipartFile image = new MockMultipartFile("file", "tide.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/files/images").file(image))
                .andExpect(status().isUnauthorized());

        Cookie sessionCookie = login("13800000008");
        mockMvc.perform(multipart("/api/v1/files/images")
                        .file(image)
                        .cookie(sessionCookie)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.containsString("/media/")))
                .andExpect(jsonPath("$.data.contentType").value("image/png"));
    }

    @Test
    void 上传图片会拒绝非图片类型和超大文件() throws Exception {
        Cookie sessionCookie = login("13800000008");
        MockMultipartFile text = new MockMultipartFile("file", "notes.txt", "text/plain", "not an image".getBytes());
        MockMultipartFile oversized = new MockMultipartFile("file", "large.png", "image/png", new byte[5 * 1024 * 1024 + 1]);

        mockMvc.perform(multipart("/api/v1/files/images").file(text).cookie(sessionCookie).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_TYPE_INVALID"));
        mockMvc.perform(multipart("/api/v1/files/images").file(oversized).cookie(sessionCookie).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));
    }

    @Test
    @DirtiesContext
    void 用户可以举报内容且同一目标不能重复提交待处理举报() throws Exception {
        mockMvc.perform(post("/api/v1/reports/POST/10000000-0000-0000-0000-000000000002")
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"SPAM\"}"))
                .andExpect(status().isUnauthorized());

        Cookie userCookie = login("13800000008");
        mockMvc.perform(post("/api/v1/reports/POST/10000000-0000-0000-0000-000000000002")
                        .cookie(userCookie)
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"SPAM\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        mockMvc.perform(post("/api/v1/reports/POST/10000000-0000-0000-0000-000000000002")
                        .cookie(userCookie)
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"ABUSE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_DUPLICATE"));
    }

    @Test
    @DirtiesContext
    void 管理员可以处理举报并处置帖子() throws Exception {
        Cookie userCookie = login("13800000008");
        var created = mockMvc.perform(post("/api/v1/reports/POST/10000000-0000-0000-0000-000000000002")
                        .cookie(userCookie)
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"MISINFORMATION\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String reportBody = created.getResponse().getContentAsString();
        int idStart = reportBody.indexOf("\"id\":\"") + 6;
        String reportId = reportBody.substring(idStart, reportBody.indexOf('"', idStart));

        Cookie normalCookie = login("13800000009");
        mockMvc.perform(get("/api/v1/admin/reports").cookie(normalCookie))
                .andExpect(status().isForbidden());

        Cookie adminCookie = login("13800000001");
        mockMvc.perform(get("/api/v1/admin/reports").cookie(adminCookie)
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(reportId));

        mockMvc.perform(post("/api/v1/admin/reports/" + reportId + "/handle")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\",\"note\":\"确认内容不实\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        mockMvc.perform(get("/api/v1/posts/10000000-0000-0000-0000-000000000002"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/admin/reports/" + reportId + "/handle")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_ALREADY_HANDLED"));
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
