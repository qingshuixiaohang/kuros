package com.kuros.kurosbackend.cache;

import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.post.service.CommunityPostService;
import com.kuros.kurosbackend.shared.cache.TwoLevelCache;
import com.kuros.kurosbackend.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 缓存击穿/穿透防护集成测试（切片 #13 / rp-02）。
 *
 * 验证 TwoLevelCache 在 L2 miss 时的两条高并发纪律（外部可观测行为，不绑定内部实现）：
 * 1. 防击穿：N 个并发线程打同一未缓存键，DB 重建只发生一次（互斥锁 + 自旋收敛惊群）；
 * 2. 防穿透：查不存在的键，第一次落 DB 并写 __NULL__ 空值哨兵，第二次命中哨兵不再落 DB；
 * 3. 服务级串联：查不存在的帖子两次均抛 ResourceNotFoundException，且哨兵确实写入了 L2。
 *
 * 为什么调 spin-retries/spin-millis？
 * 并发测试要让「未抢到锁的线程」在自旋窗口内一定等到持锁者重建完（否则降级直查 DB，重建计数会 >1）。
 * 这里把窗口放宽到 10×30ms=300ms，重建 loader 只睡 100ms，确保自旋期间必然读到回填结果、断言稳定。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.cache.type=caffeine",
        "app.cache.spin-retries=10",
        "app.cache.spin-millis=30"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CacheBreakdownIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /** 空值哨兵字面量，须与 TwoLevelCache.NULL_SENTINEL 契约一致。 */
    private static final String NULL_SENTINEL = "__NULL__";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("cache-breakdown"));
    }

    @Autowired
    private TwoLevelCache twoLevelCache;
    @Autowired
    private CommunityPostService postService;
    @Autowired
    private CacheManager cacheManager;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection().serverCommands().flushDb();
        Objects.requireNonNull(cacheManager.getCache("postDetail")).clear();
    }

    @Test
    void 并发击穿同一未缓存键时DB只重建一次() throws Exception {
        // 未登记的缓存名 → L1 为 null → 纯 L2 路径，最纯粹地检验互斥锁重建
        String cacheName = "breakdownMutex";
        String key = "hot-post";
        AtomicInteger rebuilds = new AtomicInteger();
        int threads = 16;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<String>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGun.await(); // 所有线程在同一起跑线冲出，制造真实惊群
                    return twoLevelCache.get(cacheName, key, String.class, () -> {
                        rebuilds.incrementAndGet();
                        sleep(100); // 模拟一次有代价的 DB 重建
                        return "rebuilt-value";
                    });
                }));
            }
            startGun.countDown();

            Set<String> results = new HashSet<>();
            for (Future<String> f : futures) {
                results.add(f.get(5, TimeUnit.SECONDS));
            }
            // 只有一个线程穿透 DB 重建，其余全部读到同一份重建结果
            assertThat(rebuilds.get()).isEqualTo(1);
            assertThat(results).containsExactly("rebuilt-value");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 空值哨兵拦截不存在键使第二次不落DB() {
        String cacheName = "breakdownSentinel";
        String key = "missing-post";
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> {
            loads.incrementAndGet();
            return null; // DB 查无结果
        };

        String first = twoLevelCache.get(cacheName, key, String.class, loader);
        assertThat(first).isNull();
        assertThat(loads.get()).isEqualTo(1);
        // 哨兵已写入 L2（防穿透的存储级证据）
        assertThat(redisTemplate.opsForValue().get("cache:" + cacheName + ":" + key)).isEqualTo(NULL_SENTINEL);

        String second = twoLevelCache.get(cacheName, key, String.class, loader);
        assertThat(second).isNull();
        // 命中哨兵：loader 未被再次调用，DB 未被再次穿透
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void 查询不存在帖子两次均抛NotFound并写入哨兵() {
        String missingId = "00000000-0000-0000-0000-000000000099";

        assertThatThrownBy(() -> postService.findPublishedById(missingId))
                .isInstanceOf(ResourceNotFoundException.class);
        // 第一次查无 → 详情缓存写入空值哨兵
        assertThat(redisTemplate.opsForValue().get("cache:postDetail:" + missingId)).isEqualTo(NULL_SENTINEL);

        // 第二次命中哨兵：仍抛 404，但不再穿透 DB
        assertThatThrownBy(() -> postService.findPublishedById(missingId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
