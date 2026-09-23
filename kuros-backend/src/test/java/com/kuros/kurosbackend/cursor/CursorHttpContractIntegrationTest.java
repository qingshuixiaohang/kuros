package com.kuros.kurosbackend.cursor;

import cn.dev33.satoken.stp.StpUtil;
import com.jayway.jsonpath.JsonPath;
import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.feed.redis.FeedTimelineStore;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.domain.PostType;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 游标分页 HTTP 契约集成测试（切片 #13 / rp-07）。
 *
 * 服务级正确性已由 FeedCursorIntegrationTest / PostListCursorIntegrationTest 覆盖；
 * 本类只补「HTTP 边界」这一层——证明 cursor/limit 查询参数正确穿过 Controller，
 * 并产出前端约定的 JSON 信封：
 *
 * 游标模式（传 limit）：{ "data": { "items": [...], "nextCursor": "...", "hasMore": bool } }
 *   —— meta 为 null，被 ApiResponse 的 @JsonInclude(NON_NULL) 抹掉，前端据此走「加载更多」而非页码。
 * offset 模式（不传 limit）：{ "data": [...], "meta": { page, pageSize, totalItems, totalPages } }
 *   —— 两套契约在同一端点并存，旧页码 UI 调用方零破坏（spec D9）。
 * 非法游标：全局异常处理映射为 400 + { "code": "INVALID_CURSOR" }。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CursorHttpContractIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("cursor-http"));
    }

    @Autowired MockMvc mockMvc;
    @Autowired CommunityPostRepository postRepository;
    @Autowired FeedTimelineStore timelineStore;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final String AUTHOR = "cursor-http-author";
    private static final String USER = "cursor-http-user";
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 21, 10, 0);

    @BeforeEach
    void setUp() {
        timelineStore.clear(USER);
        jdbcTemplate.update("DELETE FROM post_media");
        jdbcTemplate.update("DELETE FROM post_tags");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM post_likes");
        jdbcTemplate.update("DELETE FROM post_favorites");
        postRepository.deleteAll();
        // 5 个不同发布时间的已发布帖子，供 latest 游标/offset 两种模式消费
        for (int i = 0; i < 5; i++) {
            seedPost("HTTP契约" + i, BASE.plusMinutes(i));
        }
    }

    @Test
    void 帖子列表游标模式返回CursorPageResult信封且无meta() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/v1/posts")
                        .param("sort", "latest")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").isNotEmpty())
                // 游标模式不含总数：meta 被 NON_NULL 抹掉，前端据此判定为无限滚动契约
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andReturn();

        String nextCursor = JsonPath.read(first.getResponse().getContentAsString(), "$.data.nextCursor");

        // 透传 nextCursor 取下一页：不重复上一页、仍有下一页
        mockMvc.perform(get("/api/v1/posts")
                        .param("sort", "latest")
                        .param("limit", "2")
                        .param("cursor", nextCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.meta").doesNotExist());
    }

    @Test
    void 帖子列表offset模式仍返回items加meta向后兼容() throws Exception {
        // 不传 limit → 走旧 offset 契约：data 为数组、meta 携带总数（页码 UI 依赖）
        mockMvc.perform(get("/api/v1/posts")
                        .param("sort", "latest")
                        .param("page", "1")
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.pageSize").value(2))
                .andExpect(jsonPath("$.meta.totalItems").value(5));
    }

    @Test
    void 非法游标映射为400与INVALID_CURSOR() throws Exception {
        mockMvc.perform(get("/api/v1/posts")
                        .param("sort", "latest")
                        .param("limit", "2")
                        .param("cursor", "!!!not-base64!!!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test
    void 关注流游标模式返回CursorPageResult信封() throws Exception {
        // 把 5 个帖子推入 USER 的 timeline，并直建会话（脱离 HTTP 登录，等价真实登录态）
        List<String> postIds = postRepository.findAll().stream().map(CommunityPost::getId).toList();
        timelineStore.pushToTimelines(postIds.get(0), BASE.plusMinutes(0), List.of(USER));
        for (int i = 1; i < postIds.size(); i++) {
            timelineStore.pushToTimelines(postIds.get(i), BASE.plusMinutes(i), List.of(USER));
        }
        Cookie session = new Cookie("KUROS_SESSION", StpUtil.createLoginSession(USER));

        MvcResult first = mockMvc.perform(get("/api/v1/feed/following")
                        .cookie(session)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").isNotEmpty())
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andReturn();

        String nextCursor = JsonPath.read(first.getResponse().getContentAsString(), "$.data.nextCursor");
        mockMvc.perform(get("/api/v1/feed/following")
                        .cookie(session)
                        .param("limit", "2")
                        .param("cursor", nextCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.meta").doesNotExist());
    }

    @Test
    void 关注流未登录被拦截() throws Exception {
        // 不带会话访问受保护端点：SaToken 拦截器应拒绝（游标模式不改变鉴权语义）
        mockMvc.perform(get("/api/v1/feed/following").param("limit", "2"))
                .andExpect(status().is(401));
    }

    private void seedPost(String title, LocalDateTime publishedAt) {
        CommunityPost post = CommunityPost.publish(
                AUTHOR, PostType.GUIDE, "角色培养", title, "摘要", "内容", publishedAt);
        postRepository.save(post);
    }
}
