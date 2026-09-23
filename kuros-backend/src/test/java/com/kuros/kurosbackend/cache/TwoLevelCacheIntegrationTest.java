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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两级缓存读路径集成测试（切片 #13 / rp-01）。
 *
 * 为什么单独建类而不复用主套件？
 * 主套件用 spring.cache.type=none 禁用缓存（每用例直查 DB），而两级缓存行为必须在
 * spring.cache.type=caffeine（enabled）下才能验证——这里用 @TestPropertySource 覆盖，
 * 只影响本类，不污染其他测试（prior art：CacheIntegrationTest）。
 *
 * 验证三件外部可观测行为（不绑定内部实现）：
 * 1. L2 miss → DB 重建 → 回填 L2 + L1；
 * 2. L1 miss（L2 仍在）→ 命中 L2 → 回填 L1；
 * 3. 计数解耦：内容被缓存后，改动 Redis 实时计数，详情返回的计数随之变化（缓存未污染计数）。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.cache.type=caffeine")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TwoLevelCacheIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    // 本测试只验证缓存行为，作者展示无关紧要（Feign 不可达时由 toAuthor 降级为占位作者）
    private static final String TEST_USER_ID = "10000000-0000-0000-0000-000000000001";

    /** L2 key 前缀，与 TwoLevelCache.l2Key 契约一致（cache:{cacheName}:{key}）。 */
    private static final String L2_POST_DETAIL_PREFIX = "cache:postDetail:";

    /** #11 互动实时计数 key，与 InteractionRedisStore.countKey 契约一致（interaction:count:{kind}:{postId}）。 */
    private static final String LIKE_COUNT_KEY_PREFIX = "interaction:count:like:";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("two-level-cache"));
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
        Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection().serverCommands().flushDb();
        Objects.requireNonNull(cacheManager.getCache("postDetail")).clear();
    }

    @Test
    void L2未命中时查DB并回填L2与L1() {
        String postId = publish("回填测试帖");
        // 清干净两层，确保从「全 miss」状态开始
        evictL1(postId);
        redisTemplate.delete(L2_POST_DETAIL_PREFIX + postId);

        PostDetailResponse detail = postService.findPublishedById(postId);

        assertThat(detail.title()).isEqualTo("回填测试帖");
        // L2 已回填，且存的是「内容字段」JSON——不含 likeCount/favoriteCount（计数解耦的存储级证据）
        String l2Json = redisTemplate.opsForValue().get(L2_POST_DETAIL_PREFIX + postId);
        assertThat(l2Json).isNotNull().contains("回填测试帖");
        assertThat(l2Json).doesNotContain("likeCount").doesNotContain("favoriteCount");
        // L1 已回填
        assertThat(l1(postId)).isNotNull();
    }

    @Test
    void L1未命中但L2命中时回填L1() {
        String postId = publish("L2命中测试帖");
        // 第一次读：populate L1 + L2
        postService.findPublishedById(postId);
        assertThat(redisTemplate.hasKey(L2_POST_DETAIL_PREFIX + postId)).isTrue();

        // 只清 L1，保留 L2 → 模拟「另一节点/重启后 L1 冷、L2 仍热」
        evictL1(postId);
        assertThat(l1(postId)).isNull();

        PostDetailResponse detail = postService.findPublishedById(postId);

        // 命中 L2 返回正确内容，并回填 L1
        assertThat(detail.title()).isEqualTo("L2命中测试帖");
        assertThat(l1(postId)).isNotNull();
    }

    @Test
    void 详情计数取自实时Redis源而非缓存内容() {
        String postId = publish("计数解耦测试帖");

        // 第一次读：内容进缓存，计数为 DB 基线 0
        PostDetailResponse first = postService.findPublishedById(postId);
        assertThat(first.likeCount()).isZero();

        // 模拟一次点赞落到 #11 实时计数源（直接改 Redis 计数键，等价写路径 flip 的效果）
        redisTemplate.opsForValue().increment(LIKE_COUNT_KEY_PREFIX + postId, 42);

        // 再读：内容仍命中缓存（未变），但计数从实时源叠加 → 必须是 42，而非缓存里的 0
        PostDetailResponse second = postService.findPublishedById(postId);
        assertThat(second.title()).isEqualTo("计数解耦测试帖");
        assertThat(second.likeCount()).isEqualTo(42L);
    }

    private String publish(String title) {
        CreatePostRequest request = new CreatePostRequest(
                "GUIDE", "攻略", title, "测试摘要", "测试正文内容", List.of(), List.of()
        );
        return publishingService.publish(request, TEST_USER_ID).id();
    }

    private void evictL1(String postId) {
        Objects.requireNonNull(cacheManager.getCache("postDetail")).evict(postId);
    }

    private Object l1(String postId) {
        Cache cache = Objects.requireNonNull(cacheManager.getCache("postDetail"));
        Cache.ValueWrapper wrapper = cache.get(postId);
        return wrapper == null ? null : wrapper.get();
    }
}
