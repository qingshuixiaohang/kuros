package com.kuros.kurosbackend;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import com.kuros.kurosbackend.storage.MediaAssetService;
// Testcontainers 2.x 中 GenericContainer 仍在 org.testcontainers.containers 包（已用 jar tf 核实 2.0.5 实际结构，
// 官方迁移说明只适用于部分模块专属容器类）
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Objects;

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

/**
 * 后端集成测试（SaToken + Redis 版本）。
 *
 * 与之前版本的核心区别：
 * - 使用 Testcontainers 启动真实 Redis 容器（替代原来的纯 H2 内存测试）
 * - CSRF 不再用 Spring Security 的 csrf() post-processor，改为手动设置双重提交 Cookie + Header
 * - 登录流程不变（POST /auth/code + POST /auth/login），SaToken 自动设置 KUROS_SESSION Cookie
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class KurosBackendApplicationTests {

    // Testcontainers Redis：每个测试类共享一个容器，@BeforeEach 清空数据保证测试隔离
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    // 测试用固定 CSRF Token：CsrfInterceptor 只校验 cookie == header，不校验服务端存储
    private static final String CSRF_TOKEN = "test-csrf-token";
    private static final Cookie CSRF_COOKIE = new Cookie("XSRF-TOKEN", CSRF_TOKEN);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MediaAssetService imageStorageService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void flushRedis() {
        // 每个测试前清空 Redis，保证测试隔离（H2 已经是内存模式，每次上下文重建时自动清空）
        Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection().serverCommands().flushDb();
    }

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
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000005\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devCode").value("123456"));

        var login = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
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
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000001\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000001\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("潮声档案员"));
    }

    @Test
    void 无效验证码不能建立登录会话() throws Exception {
        mockMvc.perform(post("/api/v1/auth/code")
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000006\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000006\",\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));
    }

    @Test
    void 退出登录后会话立即失效() throws Exception {
        mockMvc.perform(post("/api/v1/auth/code")
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000007\"}"))
                .andExpect(status().isOk());

        var login = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000007\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn();
        var sessionCookie = login.getResponse().getCookie("KUROS_SESSION");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(sessionCookie)
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isNoContent());

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

        mockMvc.perform(post("/api/v1/posts/10000000-0000-0000-0000-000000000001/interactions/favorite").cookie(sessionCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/users/10000000-0000-0000-0000-000000000002/follow").cookie(sessionCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
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

        mockMvc.perform(post(interactionPath + "/like").cookie(sessionCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(3701));

        mockMvc.perform(post(interactionPath + "/like").cookie(sessionCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(3701));

        mockMvc.perform(delete(interactionPath + "/like").cookie(sessionCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(3700));
    }

    @Test
    @DirtiesContext
    void 登录用户收藏和取消收藏帖子且重复操作幂等() throws Exception {
        Cookie sessionCookie = login("13800000008");
        String interactionPath = "/api/v1/posts/10000000-0000-0000-0000-000000000001/interactions";

        mockMvc.perform(post(interactionPath + "/favorite").cookie(sessionCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorited").value(true))
                .andExpect(jsonPath("$.data.favoriteCount").value(1201));

        mockMvc.perform(post(interactionPath + "/favorite").cookie(sessionCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorited").value(true))
                .andExpect(jsonPath("$.data.favoriteCount").value(1201));

        mockMvc.perform(delete(interactionPath + "/favorite").cookie(sessionCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
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

        mockMvc.perform(post(followPath).cookie(sessionCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followed").value(true))
                .andExpect(jsonPath("$.data.followerCount").value(1));

        mockMvc.perform(post(followPath).cookie(sessionCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followed").value(true))
                .andExpect(jsonPath("$.data.followerCount").value(1));

        mockMvc.perform(delete(followPath).cookie(sessionCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
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
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"实战里这套循环很好上手。\",\"parentId\":\"30000000-0000-0000-0000-000000000001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentId").value("30000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.data.author.nickname").value("漂泊者0008"));

        mockMvc.perform(post(commentsPath)
                        .cookie(sessionCookie)
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
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
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
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
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(delete(commentsPath + "/" + commentId)
                        .cookie(ownerCookie)
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(commentsPath).param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + commentId + "')].content").value("该评论已删除"))
                .andExpect(jsonPath("$.data[?(@.id == '" + commentId + "')].deleted").value(true));
    }

    @Test
    void 游客不能发布帖子() throws Exception {
        mockMvc.perform(post("/api/v1/posts")
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
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
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
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
    @DirtiesContext
    void 发布帖子可以绑定上传图片并按顺序返回媒体列表() throws Exception {
        Cookie sessionCookie = login("13800000028");
        String firstAssetId = uploadImageAndReadAssetId(sessionCookie, "first.png");
        String secondAssetId = uploadImageAndReadAssetId(sessionCookie, "second.png");

        var created = mockMvc.perform(post("/api/v1/posts")
                        .cookie(sessionCookie)
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"type\":\"GUIDE\",\"category\":\"配队攻略\",\"title\":\"多图实战记录\",\"content\":\"正文\",\"mediaAssetIds\":[\"" + firstAssetId + "\",\"" + secondAssetId + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.media", hasSize(2)))
                .andExpect(jsonPath("$.data.media[0].id").value(firstAssetId))
                .andExpect(jsonPath("$.data.media[0].sortOrder").value(0))
                .andExpect(jsonPath("$.data.media[0].isCover").value(true))
                .andExpect(jsonPath("$.data.media[1].id").value(secondAssetId))
                .andReturn();

        String postId = readJsonString(created.getResponse().getContentAsString(), "id");
        mockMvc.perform(get("/api/v1/posts/" + postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.media", hasSize(2)))
                .andExpect(jsonPath("$.data.coverImageUrl").value(org.hamcrest.Matchers.containsString("/media/")));
        mockMvc.perform(get("/api/v1/posts").param("keyword", "多图实战记录"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].media[0].id").value(firstAssetId));
    }

    @Test
    @DirtiesContext
    void 帖子媒体只能由上传者绑定且更新时可以调整顺序() throws Exception {
        Cookie ownerCookie = login("13800000029");
        String firstAssetId = uploadImageAndReadAssetId(ownerCookie, "owned.png");
        Cookie otherCookie = login("13800000030");

        mockMvc.perform(post("/api/v1/posts")
                        .cookie(otherCookie)
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"type\":\"GUIDE\",\"category\":\"配队攻略\",\"title\":\"越权媒体\",\"content\":\"正文\",\"mediaAssetIds\":[\"" + firstAssetId + "\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        var created = mockMvc.perform(post("/api/v1/posts")
                        .cookie(ownerCookie)
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"type\":\"GUIDE\",\"category\":\"配队攻略\",\"title\":\"可编辑多图\",\"content\":\"正文\",\"mediaAssetIds\":[\"" + firstAssetId + "\"]}"))
                .andExpect(status().isCreated())
                .andReturn();
        String postId = readJsonString(created.getResponse().getContentAsString(), "id");

        String secondAssetId = uploadImageAndReadAssetId(ownerCookie, "new.png");
        mockMvc.perform(put("/api/v1/posts/" + postId)
                        .cookie(ownerCookie)
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"type\":\"GUIDE\",\"category\":\"配队攻略\",\"title\":\"可编辑多图\",\"content\":\"正文\",\"mediaAssetIds\":[\"" + secondAssetId + "\",\"" + firstAssetId + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.media[0].id").value(secondAssetId))
                .andExpect(jsonPath("$.data.media[0].isCover").value(true))
                .andExpect(jsonPath("$.data.media[1].id").value(firstAssetId));
    }

    @Test
    void 发布帖子会校验必填字段和内容类型() throws Exception {
        Cookie sessionCookie = login("13800000008");

        mockMvc.perform(post("/api/v1/posts")
                        .cookie(sessionCookie)
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"type\":\"GUIDE\",\"category\":\"配队攻略\",\"title\":\" \",\"content\":\"正文\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POST_TITLE_REQUIRED"));

        mockMvc.perform(post("/api/v1/posts")
                        .cookie(sessionCookie)
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
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
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
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
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"type\":\"GUIDE\",\"category\":\"配队攻略\",\"title\":\"不应被修改\",\"content\":\"正文\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put(path)
                        .cookie(ownerCookie)
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"type\":\"GENERAL\",\"category\":\"心得\",\"title\":\"已更新的帖子\",\"content\":\"更新后的正文\",\"tags\":[\"实战\",\"轮切\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("已更新的帖子"))
                .andExpect(jsonPath("$.data.content").value("更新后的正文"))
                .andExpect(jsonPath("$.data.tags", hasSize(2)));

        mockMvc.perform(delete(path).cookie(ownerCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(path).cookie(ownerCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(path)).andExpect(status().isNotFound());
    }

    @Test
    void 游客不能上传图片且登录用户上传合法图片() throws Exception {
        MockMultipartFile image = new MockMultipartFile("file", "tide.png", "image/png", minimalPng());

        mockMvc.perform(multipart("/api/v1/files/images").file(image))
                .andExpect(status().isUnauthorized());

        Cookie sessionCookie = login("13800000008");
        mockMvc.perform(multipart("/api/v1/files/images")
                        .file(image)
                        .cookie(sessionCookie)
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.assetId").isNotEmpty())
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.containsString("/media/")))
                .andExpect(jsonPath("$.data.contentType").value("image/png"));
    }

    @Test
    void 上传图片会拒绝仅伪造扩展名和请求类型的文件() throws Exception {
        Cookie sessionCookie = login("13800000008");
        MockMultipartFile fakeImage = new MockMultipartFile("file", "not-really.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/files/images").file(fakeImage).cookie(sessionCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_CONTENT_INVALID"));
    }

    @Test
    void 上传图片会拒绝非图片类型和超大文件() throws Exception {
        Cookie sessionCookie = login("13800000008");
        MockMultipartFile text = new MockMultipartFile("file", "notes.txt", "text/plain", "not an image".getBytes());
        MockMultipartFile oversized = new MockMultipartFile("file", "large.png", "image/png", new byte[10 * 1024 * 1024 + 1]);

        mockMvc.perform(multipart("/api/v1/files/images").file(text).cookie(sessionCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_TYPE_INVALID"));
        mockMvc.perform(multipart("/api/v1/files/images").file(oversized).cookie(sessionCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));
    }

    @Test
    @DirtiesContext
    void 登录用户可以删除自己尚未绑定帖子的临时图片但不能删除他人的图片() throws Exception {
        Cookie ownerCookie = login("13800000031");
        String assetId = uploadImageAndReadAssetId(ownerCookie, "temporary.png");
        Cookie otherCookie = login("13800000032");

        mockMvc.perform(delete("/api/v1/files/images/" + assetId)
                        .cookie(otherCookie)
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/files/images/" + assetId)
                        .cookie(ownerCookie)
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/files/images/" + assetId)
                        .cookie(ownerCookie)
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isNoContent());
    }

    @Test
    @DirtiesContext
    void 临时图片清理服务会按截止时间清理未绑定资源() throws Exception {
        Cookie ownerCookie = login("13800000033");
        uploadImageAndReadAssetId(ownerCookie, "expired.png");

        org.junit.jupiter.api.Assertions.assertEquals(1, imageStorageService.cleanupTemporaryAssets(LocalDateTime.now().plusMinutes(1)));
    }

    private String uploadImageAndReadAssetId(Cookie sessionCookie, String fileName) throws Exception {
        var result = mockMvc.perform(multipart("/api/v1/files/images")
                        .file(new MockMultipartFile("file", fileName, "image/png", minimalPng()))
                        .cookie(sessionCookie)
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isCreated())
                .andReturn();
        return readJsonString(result.getResponse().getContentAsString(), "assetId");
    }

    private byte[] minimalPng() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    }

    private String readJsonString(String body, String field) {
        String marker = "\"" + field + "\":\"";
        int start = body.indexOf(marker) + marker.length();
        return body.substring(start, body.indexOf('"', start));
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
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"SPAM\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        mockMvc.perform(post("/api/v1/reports/POST/10000000-0000-0000-0000-000000000002")
                        .cookie(userCookie)
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
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
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
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
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\",\"note\":\"确认内容不实\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        mockMvc.perform(get("/api/v1/posts/10000000-0000-0000-0000-000000000002"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/admin/reports/" + reportId + "/handle")
                        .cookie(adminCookie)
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_ALREADY_HANDLED"));
    }

    private Cookie login(String phone) throws Exception {
        mockMvc.perform(post("/api/v1/auth/code")
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk());
        return mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("KUROS_SESSION");
    }

}
