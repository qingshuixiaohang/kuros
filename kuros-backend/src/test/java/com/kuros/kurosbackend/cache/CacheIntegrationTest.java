package com.kuros.kurosbackend.cache;

import com.kuros.kurosbackend.post.api.CreatePostRequest;
import com.kuros.kurosbackend.post.api.PostDetailResponse;
import com.kuros.kurosbackend.user.domain.CommunityUser;
import com.kuros.kurosbackend.user.domain.UserStatus;
import com.kuros.kurosbackend.user.repository.CommunityUserRepository;
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

import java.time.LocalDateTime;
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

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private CommunityPostService postService;

    @Autowired
    private PostPublishingService publishingService;

    @Autowired
    private CommunityUserRepository userRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String testUserId;

    @BeforeEach
    void setUp() {
        // 清空 Redis（SaToken 会话数据）
        Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection().serverCommands().flushDb();
        // 清空缓存
        Objects.requireNonNull(cacheManager.getCache("postDetail")).clear();
        Objects.requireNonNull(cacheManager.getCache("publicProfile")).clear();

        // 查找或创建测试用户
        testUserId = userRepository.findAll().stream().findFirst()
                .map(CommunityUser::getId)
                .orElseGet(() -> {
                    LocalDateTime now = LocalDateTime.now();
                    return userRepository.save(new CommunityUser(
                            "cache-test-user", "13900000099", "缓存测试员", UserStatus.NORMAL, now
                    )).getId();
                });
    }

    @Test
    void 帖子详情首次查询后应被缓存() {
        // 先发布一个帖子
        CreatePostRequest request = new CreatePostRequest(
                "GUIDE", "攻略", "缓存测试帖", "测试摘要", "测试正文内容", List.of(), List.of()
        );
        PostDetailResponse published = publishingService.publish(request, testUserId);
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
        PostDetailResponse published = publishingService.publish(original, testUserId);
        String postId = published.id();

        // 查询一次让缓存生效
        PostDetailResponse firstRead = postService.findPublishedById(postId);
        assertEquals("原始标题", firstRead.title());

        // 更新帖子（@CacheEvict beforeInvocation=true 先驱逐缓存，
        // 然后 update() 内部调用 findPublishedById() 重新填充缓存）
        CreatePostRequest updated = new CreatePostRequest(
                "GUIDE", "攻略", "更新后标题", "更新后摘要", "更新后正文", List.of(), List.of()
        );
        publishingService.update(postId, testUserId, updated);

        // 再次查询应返回新数据（缓存已更新）
        PostDetailResponse afterUpdate = postService.findPublishedById(postId);
        assertEquals("更新后标题", afterUpdate.title());
    }
}
