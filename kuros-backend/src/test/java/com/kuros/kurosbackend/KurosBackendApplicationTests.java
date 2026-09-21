package com.kuros.kurosbackend;

import cn.dev33.satoken.stp.StpUtil;
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
import com.kuros.kurosbackend.media.storage.MediaAssetService;
// Testcontainers 2.x 中 GenericContainer 仍在 org.testcontainers.containers 包（已用 jar tf 核实 2.0.5 实际结构，
// 官方迁移说明只适用于部分模块专属容器类）
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

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
 * split-06 起认证端点（/api/v1/auth/**）已迁至 kuros-user：
 * - 本测试不再走 HTTP 登录，改用"直建会话"helper（StpUtil.createLoginSession 写共享 Redis）
 * - 登录链路的完整验收（验证码→登录→会话写共享 Redis→/me）在 kuros-user 的
 *   AuthLoginIntegrationTest 里用 Testcontainers MySQL + Redis 覆盖
 * - CSRF 令牌 fixture 从 GET /api/v1/auth/csrf 改为公开帖子列表 GET（backend 侧该端点已 404）
 *
 * split-07 起用户域数据迁出本库（V10 DROP users 等表），本测试相应调整：
 * - login helper 不再建用户行：会话主键改为手机号派生的确定性 UUID
 * - 作者相关断言从真实昵称改为占位作者"未知漂泊者"（authorId 保留，见 CommunityPostService.toAuthor）
 * - 用户资料/个人中心端点整端降级为 503（split-08 Feign 回填后恢复）
 * - 关注端点迁至 kuros-user：本测试改为断言"直连 404"
 *
 * 与之前版本的核心区别：
 * - 使用 Testcontainers 启动真实 Redis 容器（替代原来的纯 H2 内存测试）
 * - CSRF 不再用 Spring Security 的 csrf() post-processor，改为手动设置双重提交 Cookie + Header
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
        // 唯一 H2 库名：库生命周期与本类 context 对齐（详见 TestDatabases 注释）
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("main"));
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
                .andExpect(jsonPath("$.data.author.nickname").value("未知漂泊者"));
    }

    @Test
    void 公开查询不会暴露已删除帖子() throws Exception {
        mockMvc.perform(get("/api/v1/posts/10000000-0000-0000-0000-000000000099"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
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
        // 令牌来源从 GET /api/v1/auth/csrf 改为公开帖子列表 GET：
        // split-06 起认证端点已迁至 kuros-user，backend 侧该路径无 handler（404）；
        // 浏览器真实链路里任意 GET 都会被 CsrfInterceptor 种下 XSRF-TOKEN Cookie，行为等价
        var csrfResponse = mockMvc.perform(get("/api/v1/posts").cookie(sessionCookie))
                .andExpect(status().isOk())
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
    void 认证端点已迁出本服务直连返回404() throws Exception {
        // split-06 验收编码化：/api/v1/auth/** 迁至 kuros-user 后，backend 直连必须 404。
        // 拦截器链刻意放行该前缀（SaToken notMatch + CSRF exclude），请求落到"无 handler"
        // 才得到 404；若放行条目被当作死配置删除，这里会先吃到 401/403 而红
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000001\",\"code\":\"123456\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 窗口期用户资料端整端降级为503而帖子列表仍可用() throws Exception {
        String userId = "10000000-0000-0000-0000-000000000001";

        // split-07：用户域数据迁出本库，资料聚合整端降级为 503——
        // 语义是"服务端能力暂不可用"（split-08 经 Feign 回填后恢复），而非 404"用户不存在"
        mockMvc.perform(get("/api/v1/users/" + userId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

        // 按作者查帖子不依赖用户表：帖子仍在本库，窗口期保持可用；
        // 作者为占位（authorId 保留——前端关注按钮据此调用 kuros-user 的关注 API）
        mockMvc.perform(get("/api/v1/users/" + userId + "/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].title").value("长离焚火队：从零到毕业的配队思路"))
                .andExpect(jsonPath("$.data[0].author.nickname").value("未知漂泊者"))
                .andExpect(jsonPath("$.data[0].author.id").value(userId))
                .andExpect(jsonPath("$.meta.totalItems").value(1));
    }

    @Test
    void 个人中心窗口期整端降级为503() throws Exception {
        Cookie sessionCookie = login("13800000001");

        // 个人中心聚合依赖用户资料与关注关系，两者均已迁出本库（V10），窗口期整端降级；
        // 能拿到 503（而非 401）说明会话鉴权通过、请求已到达控制器——降级不影响登录本身
        mockMvc.perform(get("/api/v1/users/me/profile").cookie(sessionCookie))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
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
    void 关注端点已迁出本服务直连返回404() throws Exception {
        // split-07 验收编码化：关注端点迁至 kuros-user 后，backend 直连必须 404。
        // 与 auth 前缀不同，该路径未被拦截器放行——所以必须携带会话（否则先吃 401），
        // 走完鉴权（POST/DELETE 再经 CSRF）后落到"无 handler"才是 404；
        // 行为验收（幂等/并发防重复）由 kuros-user 的 UserFollowIntegrationTest 覆盖。
        Cookie sessionCookie = login("13800000008");
        String followPath = "/api/v1/users/10000000-0000-0000-0000-000000000002/follow";

        mockMvc.perform(get(followPath).cookie(sessionCookie))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(followPath).cookie(sessionCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(followPath).cookie(sessionCookie).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isNotFound());
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
                .andExpect(jsonPath("$.data.author.nickname").value("未知漂泊者"));

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
                .andExpect(jsonPath("$.data.author.nickname").value("未知漂泊者"))
                .andExpect(jsonPath("$.data.author.id").value(testUserId("13800000008")))
                .andExpect(jsonPath("$.data.likeCount").value(0))
                .andExpect(jsonPath("$.data.favoriteCount").value(0))
                .andReturn();

        String responseBody = created.getResponse().getContentAsString();
        int idStart = responseBody.indexOf("\"id\":\"") + 6;
        String postId = responseBody.substring(idStart, responseBody.indexOf('"', idStart));
        mockMvc.perform(get("/api/v1/posts/" + postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("长离实战循环记录"))
                .andExpect(jsonPath("$.data.author.nickname").value("未知漂泊者"))
                .andExpect(jsonPath("$.data.author.id").value(testUserId("13800000008")));
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

        Cookie adminCookie = loginAsAdmin("13800000001");
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

    /**
     * 测试用"直建会话"helper（split-06 起认证端点已迁至 kuros-user，本测试不再走 HTTP 登录）。
     *
     * 为什么可以脱离请求上下文直接建会话：
     * StpUtil.createLoginSession 只依赖 config/DAO/EventCenter（1.39.0 源码实证），
     * 不触碰 SaHolder 的请求上下文（那是 login() 写 Cookie 才需要的步骤）。
     * Token 由它写入与生产同一份 Redis，语义等价于真实登录产生的会话，
     * 后续请求携带同名 KUROS_SESSION Cookie 即可通过 SaToken 的 checkLogin。
     *
     * split-07：用户表已迁出本库，不再建用户行——会话主键由手机号派生确定性 UUID
     * （同手机号恒得同 id，多次 login 幂等；测试可用 testUserId() 复算后断言其本人数据）。
     * 不分配 RBAC 角色：普通用户端点只做 checkLogin。
     */
    private Cookie login(String phone) {
        return sessionCookie(testUserId(phone));
    }
    
    /**
     * 管理员会话 helper：split-07 起角色表（sys_role/sys_user_role）已迁出本库，
     * StpInterfaceImpl 改为 Redis-only——角色必须像真实登录那样出现在共享 Redis，
     * 否则 checkRole("ADMIN") 读到空列表导致 403。
     * 真实链路里这步由 kuros-user 的 AuthService.login 完成（key 契约 auth:roles:{userId}），
     * 这里手工写入等价于"登录时已同步"的状态。
     */
    private Cookie loginAsAdmin(String phone) {
        String userId = testUserId(phone);
        redisTemplate.opsForSet().add("auth:roles:" + userId, "ADMIN");
        return sessionCookie(userId);
    }
    
    /**
     * 手机号 → 确定性会话主键（UUID v3）：与真实"一号一账号"等价，且测试可复算；
     * kuros-test: 前缀避免与任何真实 UUID 撞号。
     */
    private String testUserId(String phone) {
        return UUID.nameUUIDFromBytes(("kuros-test:" + phone).getBytes(StandardCharsets.UTF_8)).toString();
    }
    
    private Cookie sessionCookie(String userId) {
        return new Cookie("KUROS_SESSION", StpUtil.createLoginSession(userId));
    }

}
