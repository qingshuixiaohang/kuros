package com.kuros.kurosbackend.cache;

import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.post.api.CreatePostRequest;
import com.kuros.kurosbackend.post.api.PostDetailResponse;
import com.kuros.kurosbackend.post.service.CommunityPostService;
import com.kuros.kurosbackend.post.service.PostPublishingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Caffeine 缓存行为集成测试。
 *
 * 为什么要单独建一个测试类而不是复用 KurosBackendApplicationTests？
 * 因为主测试套件用 spring.cache.type=none 禁用缓存（保护现有 38 个测试），
 * 这里需要用 spring.cache.type=caffeine 来验证缓存行为。
 *
 * @TestPropertySource 覆盖 test profile 的 cache.type 设置，
 * 这样只影响这一个测试类，不会污染其他测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.cache.type=caffeine")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CacheIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    // split-07：用户表已迁出本库，发帖作者直接用固定 UUID——
    // 本测试只验证缓存行为，作者展示无关紧要（占位作者由 toAuthor 统一处理）
    private static final String TEST_USER_ID = "10000000-0000-0000-0000-000000000001";

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // 唯一 H2 库名：库生命周期与本类 context 对齐（详见 TestDatabases 注释）
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("cache"));
    }

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private CommunityPostService postService;

    @Autowired
    private PostPublishingService publishingService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        // 清空 Redis（SaToken 会话数据）
        Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection().serverCommands().flushDb();
        // 清空缓存（split-07：publicProfile 缓存已随用户域迁出移除）
        Objects.requireNonNull(cacheManager.getCache("postDetail")).clear();
    }

    @Test
    void 帖子详情首次查询后应被缓存() {
        // 先发布一个帖子
        CreatePostRequest request = new CreatePostRequest(
                "GUIDE", "攻略", "缓存测试帖", "测试摘要", "测试正文内容", List.of(), List.of()
        );
        PostDetailResponse published = publishingService.publish(request, TEST_USER_ID);
        String postId = published.id();

        // 清空缓存，确保从干净状态开始
        Cache postDetailCache = Objects.requireNonNull(cacheManager.getCache("postDetail"));
        postDetailCache.clear();

        // 第一次查询：应该穿透到 DB 并写入缓存
        PostDetailResponse first = postService.findPublishedById(postId);
        assertNotNull(first);

        // 验证缓存中已有该 key
        Cache.ValueWrapper cached = postDetailCache.get(postId);
        assertNotNull(cached, "第一次查询后，帖子详情应被缓存");

        // 第二次查询：应命中缓存（这里通过验证缓存存在来间接证明）
        PostDetailResponse second = postService.findPublishedById(postId);
        assertEquals(first.id(), second.id());
        assertEquals(first.title(), second.title());
    }

    @Test
    void 更新帖子后缓存应返回新数据() {
        // 发布帖子
        CreatePostRequest original = new CreatePostRequest(
                "GUIDE", "攻略", "原始标题", "原始摘要", "原始正文", List.of(), List.of()
        );
        PostDetailResponse published = publishingService.publish(original, TEST_USER_ID);
        String postId = published.id();

        // 查询一次让缓存生效
        PostDetailResponse firstRead = postService.findPublishedById(postId);
        assertEquals("原始标题", firstRead.title());

        // 更新帖子（@CacheEvict beforeInvocation=true 先驱逐缓存，
        // 然后 update() 内部调用 findPublishedById() 重新填充缓存）
        CreatePostRequest updated = new CreatePostRequest(
                "GUIDE", "攻略", "更新后标题", "更新后摘要", "更新后正文", List.of(), List.of()
        );
        publishingService.update(postId, TEST_USER_ID, updated);

        // 再次查询应返回新数据（缓存已更新）
        PostDetailResponse afterUpdate = postService.findPublishedById(postId);
        assertEquals("更新后标题", afterUpdate.title());
    }
}
