package com.kuros.kurosbackend.interaction;

import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.interaction.domain.PostFavoriteId;
import com.kuros.kurosbackend.interaction.domain.PostLikeId;
import com.kuros.kurosbackend.interaction.event.InteractionEvent;
import com.kuros.kurosbackend.interaction.event.InteractionType;
import com.kuros.kurosbackend.interaction.repository.PostFavoriteRepository;
import com.kuros.kurosbackend.interaction.repository.PostLikeRepository;
import com.kuros.kurosbackend.interaction.service.InteractionProjectionService;
import com.kuros.kurosbackend.interaction.service.PostInteractionService;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.domain.PostType;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批量互动状态查询（消除首页 Feed 的 N+1，前端审计 H-2 / H-7）。
 *
 * 要锁定的契约：一次请求拿到「当前用户对一批帖子的 liked/favorited」，
 * 且查询次数是常数（与帖子数无关）。
 *
 * 背景：首页 20 条帖子原先每条发一次 GET /posts/{id}/interactions，
 * 一屏 21 个请求。而后端列表接口早已返回 likeCount/favoriteCount，
 * 所以卡片上的计数根本不需要额外请求——N+1 只发生在"当前用户是否
 * 已赞/已藏"这个 per-user 状态上。
 *
 * 因此批量接口的职责被刻意收窄：**只查关系状态，不返回计数**。
 * 计数由列表响应提供，两边重复返回只会诱发"哪个才是真相"的问题。
 *
 * 未登录时不查 DB、直接返回全 false：游客没有关系状态可言。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class InteractionBatchStateIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("interaction-batch"));
    }

    private static final String AUTHOR_ID = "10000000-0000-0000-0000-000000000001";

    @Autowired
    private PostInteractionService interactionService;

    @Autowired
    private InteractionProjectionService projectionService;

    @Autowired
    private PostLikeRepository likeRepository;

    @Autowired
    private PostFavoriteRepository favoriteRepository;

    @Autowired
    private CommunityPostRepository postRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private MockMvc mockMvc;

    private List<String> postIds;

    /** 构造登录会话 Cookie（与 KurosBackendApplicationTests 同一手法：直接建登录会话，绕过登录链路）。 */
    private Cookie sessionCookie(String userId) {
        return new Cookie("KUROS_SESSION", StpUtil.createLoginSession(userId));
    }

    @BeforeEach
    void setUp() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection().serverCommands().flushDb();

        postIds = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            CommunityPost post = CommunityPost.publish(
                    AUTHOR_ID, PostType.GUIDE, "配队攻略", "批量测试帖 " + i, "摘要", "正文", LocalDateTime.now());
            postRepository.save(post);
            postIds.add(post.getId());
        }
    }

    /**
     * 核心契约：一次批量查询返回每帖的 liked/favorited。
     *
     * 构造 3 种状态混合：第 1 帖已赞、第 2 帖已藏、第 3 帖两者都有、
     * 第 4/5 帖无关系——确保不是"全 true"或"全 false"的假通过。
     */
    @Test
    void 批量查询返回每帖的赞藏状态() {
        String userId = UUID.randomUUID().toString();
        projectionService.apply(InteractionEvent.of(postIds.get(0), userId, InteractionType.LIKE));
        projectionService.apply(InteractionEvent.of(postIds.get(1), userId, InteractionType.FAVORITE));
        projectionService.apply(InteractionEvent.of(postIds.get(2), userId, InteractionType.LIKE));
        projectionService.apply(InteractionEvent.of(postIds.get(2), userId, InteractionType.FAVORITE));

        var states = interactionService.findStatesFor(postIds, userId);

        assertThat(states)
                .as("5 帖全部有结果，一个都不能少")
                .hasSize(5);
        assertThat(states.get(postIds.get(0)))
                .as("第 1 帖：已赞未藏")
                .isEqualTo(new boolean[]{true, false});
        assertThat(states.get(postIds.get(1)))
                .as("第 2 帖：未赞已藏")
                .isEqualTo(new boolean[]{false, true});
        assertThat(states.get(postIds.get(2)))
                .as("第 3 帖：赞藏都有")
                .isEqualTo(new boolean[]{true, true});
        assertThat(states.get(postIds.get(3)))
                .as("第 4 帖：无任何关系")
                .isEqualTo(new boolean[]{false, false});
        assertThat(states.get(postIds.get(4)))
                .as("第 5 帖：无任何关系")
                .isEqualTo(new boolean[]{false, false});
    }

    /**
     * 未登录时不查 DB，直接全 false——游客没有关系状态。
     *
     * 这条同时防"null userId 触发 NPE / 空 IN 查询"这类边界事故。
     */
    @Test
    void 未登录时全部返回未赞未藏() {
        var states = interactionService.findStatesFor(postIds, null);

        assertThat(states).hasSize(5);
        assertThat(states.values())
                .as("游客对所有帖子都是未赞未藏")
                .containsOnly(new boolean[]{false, false});
    }

    /**
     * 空列表不查 DB、不报错（前端偶发空 feed 时的边界）。
     */
    @Test
    void 空帖子列表返回空结果() {
        var states = interactionService.findStatesFor(List.of(), UUID.randomUUID().toString());

        assertThat(states).isEmpty();
    }

    /**
     * 用户间隔离：A 用户赞过的帖子，B 用户查到的是未赞。
     *
     * 这条是批量查询最容易写错的地方——按 postId IN 查关系表时
     * 漏掉 userId 条件就会把别人的点赞算到当前用户头上。
     */
    @Test
    void 不同用户的互动状态相互隔离() {
        String userA = UUID.randomUUID().toString();
        String userB = UUID.randomUUID().toString();
        projectionService.apply(InteractionEvent.of(postIds.get(0), userA, InteractionType.LIKE));

        var statesForA = interactionService.findStatesFor(postIds, userA);
        var statesForB = interactionService.findStatesFor(postIds, userB);

        assertThat(statesForA.get(postIds.get(0))).isEqualTo(new boolean[]{true, false});
        assertThat(statesForB.get(postIds.get(0)))
                .as("B 用户不应看到 A 用户的点赞")
                .isEqualTo(new boolean[]{false, false});
    }

    /**
     * 查询次数是常数：5 帖与 20 帖的 DB 查询数应当相同。
     *
     * 这才是消除 N+1 的硬指标——不是"能返回正确结果"，而是"不随
     * 帖子数线性增长"。用 Hibernate Statistics 实测。
     */
    @Test
    void 查询次数不随帖子数线性增长() {
        String userId = UUID.randomUUID().toString();
        // 再造 15 帖，把规模拉到 20
        for (int i = 0; i < 15; i++) {
            CommunityPost post = CommunityPost.publish(
                    AUTHOR_ID, PostType.GUIDE, "配队攻略", "扩容帖 " + i, "摘要", "正文", LocalDateTime.now());
            postRepository.save(post);
            postIds.add(post.getId());
        }

        var sessionFactory = entityManager.getEntityManagerFactory()
                .unwrap(org.hibernate.SessionFactory.class);
        var statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        interactionService.findStatesFor(postIds, userId);

        long statements = statistics.getPrepareStatementCount();
        assertThat(statements)
                .as("20 帖的批量查询应为常数条 SQL（各一次 IN 查询），实际 %d 条", statements)
                .isLessThanOrEqualTo(6);

        statistics.setStatisticsEnabled(false);
    }

    // ==================== HTTP 层：端点契约 ====================

    /**
     * 匿名可达：游客请求返回 200 且全 0，不跳登录。
     *
     * 这条锁定的是 SaTokenConfigure 白名单——若那条 GET 豁免被删，
     * 游客首屏就会吃 401，整个首页对未登录用户不可用。
     */
    @Test
    void 批量状态端点对游客匿名可达且返回全零() throws Exception {
        mockMvc.perform(get("/api/v1/interactions/batch-states")
                        .param("ids", String.join(",", postIds)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data['" + postIds.get(0) + "'][0]").value(0))
                .andExpect(jsonPath("$.data['" + postIds.get(0) + "'][1]").value(0));
    }

    /**
     * 已登录用户拿到真实状态：赞过的帖子返回 [1,0]。
     */
    @Test
    void 批量状态端点对已登录用户返回真实状态() throws Exception {
        String userId = UUID.randomUUID().toString();
        projectionService.apply(InteractionEvent.of(postIds.get(0), userId, InteractionType.LIKE));

        mockMvc.perform(get("/api/v1/interactions/batch-states")
                        .param("ids", String.join(",", postIds))
                        .cookie(sessionCookie(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['" + postIds.get(0) + "'][0]").value(1))
                .andExpect(jsonPath("$.data['" + postIds.get(0) + "'][1]").value(0))
                .andExpect(jsonPath("$.data['" + postIds.get(1) + "'][0]").value(0));
    }

    /**
     * 超过上限被拒：这个端点服务"一页帖子"，不是任意批量查询入口。
     */
    @Test
    void 超过数量上限的批量查询被拒() throws Exception {
        String tooMany = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> "post-" + i)
                .collect(java.util.stream.Collectors.joining(","));

        mockMvc.perform(get("/api/v1/interactions/batch-states").param("ids", tooMany))
                .andExpect(status().isBadRequest());
    }
}
