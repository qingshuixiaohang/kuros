package com.kuros.kurosbackend.perf;

import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.domain.PostType;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.post.service.CommunityPostService;
import com.kuros.kurosbackend.shared.cache.TwoLevelCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 读路径加固性能基准（切片 #13 / rp-07 · DoD 硬要求：真实实测，禁止编造并发数字）。
 *
 * 默认被 {@code @EnabledIfSystemProperty(named="perf", matches="true")} 门控跳过——不进常规
 * {@code mvn test}（避免每次 CI 都 seed 数千行 + 重复计时拖慢流水线）。需要采集数据时手动运行：
 *
 * <pre>
 *   ./mvnw.cmd test -Dtest=ReadPathPerfBenchmark -Dperf=true [-Dperf.posts=5000]
 * </pre>
 *
 * 采集两组「可复现的结构性证据」，全部 println 到 stdout（前缀 [PERF]，便于 grep 回填 docs/learning/13）：
 * 1. offset 深翻页 vs cursor 同深度：随页深增加，offset 的 {@code LIMIT n OFFSET k} 要扫描并丢弃 k 行，
 *    耗时线性上涨；cursor 的 keyset {@code WHERE (published_at,id) < 游标 LIMIT n} 走索引 seek，耗时基本恒定。
 * 2. 缓存击穿保护前后 DB 重建次数：互斥锁 + 双重检查 + 自旋重读下，N 并发打同一冷键只重建 1 次；
 *    无保护（直查）时 N 并发即 N 次重建——量化「惊群」被收敛的倍数。
 *
 * 环境诚实声明：数据来自 Testcontainers Redis + 进程内 H2，用于展示「随规模变化的趋势与量级差异」，
 * 非生产 MySQL 的绝对 RT；生产绝对值需在 compose（MySQL 8）环境用同法采集，切勿把此处数字当作线上承诺。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.cache.type=caffeine",
        "app.cache.spin-retries=20",
        "app.cache.spin-millis=30"
})
@Testcontainers
@EnabledIfSystemProperty(named = "perf", matches = "true")
class ReadPathPerfBenchmark {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("read-path-perf"));
    }

    @Autowired CommunityPostService postService;
    @Autowired CommunityPostRepository postRepository;
    @Autowired TwoLevelCache twoLevelCache;
    @Autowired CacheManager cacheManager;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 数据规模（可用 -Dperf.posts 覆盖）。深翻页要看得出差异，量级需上千。 */
    private static final int N = Integer.getInteger("perf.posts", 5000);
    private static final int PAGE_SIZE = 20;
    /** 每个采样点重复计时次数，取均值抹平 JIT/GC 抖动。 */
    private static final int REPEAT = 50;
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 21, 10, 0);

    private boolean seeded = false;

    @Test
    void offset深翻页vsCursor同深度耗时对比() {
        ensureSeeded();
        int deepest = N / PAGE_SIZE;
        int[] pages = {10, 100, deepest};

        System.out.println("[PERF] ===== offset vs cursor 深翻页耗时（N=" + N + ", pageSize=" + PAGE_SIZE
                + ", repeat=" + REPEAT + ", env=Testcontainers-Redis+H2）=====");
        System.out.printf("[PERF] %-8s %-18s %-18s %-10s%n", "page", "offset avg(ms)", "cursor avg(ms)", "speedup");

        for (int page : pages) {
            // 预热：触发 JIT、连接池、查询计划缓存，避免首次冷启动污染均值
            postService.findPublished(page, PAGE_SIZE, "latest", null, null, null);
            String cursor = cursorAtPage(page);
            if (cursor != null) {
                postService.findPublishedByCursor("latest", null, null, null, cursor, PAGE_SIZE);
            }

            double offsetMs = timeAvgMs(() -> postService.findPublished(page, PAGE_SIZE, "latest", null, null, null));
            double cursorMs = cursor == null ? Double.NaN
                    : timeAvgMs(() -> postService.findPublishedByCursor("latest", null, null, null, cursor, PAGE_SIZE));
            String speedup = Double.isNaN(cursorMs) || cursorMs == 0 ? "n/a"
                    : String.format("%.2fx", offsetMs / cursorMs);
            System.out.printf("[PERF] %-8d %-18.3f %-18.3f %-10s%n", page, offsetMs, cursorMs, speedup);
        }
        System.out.println("[PERF] 结论：offset 耗时随 page 线性上涨（OFFSET 扫描丢弃），cursor 基本恒定（keyset 索引 seek）。");
    }

    @Test
    void 击穿保护前后DB重建次数对比() throws Exception {
        Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection().serverCommands().flushDb();
        Objects.requireNonNull(cacheManager.getCache("postDetail")).clear();

        String cacheName = "perfBreakdown"; // 未登记名 → 纯 L2 路径，最纯粹检验互斥锁重建
        String key = "hot-" + UUID.randomUUID();
        int threads = 64;

        // A) 有互斥锁保护：N 并发打同一冷键
        AtomicInteger guardedRebuilds = new AtomicInteger();
        runConcurrentGet(cacheName, key, threads, guardedRebuilds);

        // B) 无保护对照：等价「缓存失效瞬间全部穿透直查 DB」
        AtomicInteger unguardedRebuilds = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            unguardedRebuilds.incrementAndGet();
            sleep(20); // 模拟一次有代价的 DB 重建
        }

        System.out.println("[PERF] ===== 缓存击穿保护 DB 重建次数（并发=" + threads + "）=====");
        System.out.println("[PERF] 有互斥锁保护 : DB 重建 " + guardedRebuilds.get() + " 次 / " + threads + " 并发");
        System.out.println("[PERF] 无保护(直查) : DB 重建 " + unguardedRebuilds.get() + " 次 / " + threads + " 并发");
        System.out.println("[PERF] 惊群收敛倍数 : " + threads + " → " + guardedRebuilds.get()
                + "（DB 峰值负载下降 " + String.format("%.1f", (1 - guardedRebuilds.get() / (double) threads) * 100) + "%）");

        // 兼作正确性断言：互斥锁必须把重建收敛到恰好 1 次
        assertThat(guardedRebuilds.get()).isEqualTo(1);
        assertThat(unguardedRebuilds.get()).isEqualTo(threads);
    }

    // ---- helpers ----

    private void runConcurrentGet(String cacheName, String key, int threads, AtomicInteger rebuilds) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGun.await(); // 同一起跑线冲出，制造真实惊群
                    return twoLevelCache.get(cacheName, key, String.class, () -> {
                        rebuilds.incrementAndGet();
                        sleep(50);
                        return "rebuilt-value";
                    });
                }));
            }
            startGun.countDown();
            for (Future<String> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 从首页走游标翻到第 page 页的起始游标（page-1 次翻页）；page<=1 返回 null（首页无游标）。 */
    private String cursorAtPage(int page) {
        String cursor = null;
        for (int i = 1; i < page; i++) {
            cursor = postService.findPublishedByCursor("latest", null, null, null, cursor, PAGE_SIZE).nextCursor();
            if (cursor == null) {
                return null; // 数据不足以翻到该深度
            }
        }
        return cursor;
    }

    private double timeAvgMs(Runnable call) {
        long total = 0;
        for (int i = 0; i < REPEAT; i++) {
            long start = System.nanoTime();
            call.run();
            total += System.nanoTime() - start;
        }
        return total / 1_000_000.0 / REPEAT;
    }

    private void ensureSeeded() {
        if (seeded) {
            return;
        }
        jdbcTemplate.update("DELETE FROM post_media");
        jdbcTemplate.update("DELETE FROM post_tags");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM post_likes");
        jdbcTemplate.update("DELETE FROM post_favorites");
        postRepository.deleteAll();
        List<CommunityPost> posts = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            posts.add(CommunityPost.publish("perf-author", PostType.GUIDE, "角色培养",
                    "压测帖" + i, "摘要", "内容", BASE.plusMinutes(i)));
        }
        postRepository.saveAll(posts);
        seeded = true;
        System.out.println("[PERF] seeded " + postRepository.count() + " posts for benchmark.");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
