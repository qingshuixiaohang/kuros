package com.kuros.kurosbackend.report;

import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.post.api.CreatePostRequest;
import com.kuros.kurosbackend.post.service.CommunityPostService;
import com.kuros.kurosbackend.post.service.PostPublishingService;
import com.kuros.kurosbackend.report.api.CreateReportRequest;
import com.kuros.kurosbackend.report.api.HandleReportRequest;
import com.kuros.kurosbackend.report.service.ContentReportService;
import com.kuros.kurosbackend.shared.cache.CacheNames;
import com.kuros.kurosbackend.shared.cache.TwoLevelCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 举报处置后必须驱逐帖子详情两级缓存（审计 H5 的回归测试）。
 *
 * 场景还原：管理员确认一条针对帖子的举报 → 帖子被置为删除态 → 若不清缓存，L2 里的
 * 旧内容最长存活 60s，期间 GET /api/v1/posts/{id} 仍能完整读到已处置的正文与媒体 URL。
 *
 * 为什么这个 bug 在 KurosBackendApplicationTests 里不会暴露：那个测试类的 profile 是
 * spring.cache.type=none（两级缓存整体关闭，evict 直接 return），且它的举报用例是
 * 「先处置、后首次读取」——缓存本来就是冷的，404 断言恒过。要复现必须让缓存生效，
 * 并严格按「先读（灌缓存）→ 处置 → 再读」的顺序。
 *
 * 与 CacheEvictionPubSubIntegrationTest 同款基架（H2 + Redis 容器 + caffeine 开关），
 * 但这里断言的是「广播确实发生」这一外部可观测行为，而不是 pub/sub 机制本身。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.cache.type=caffeine")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReportDispositionCacheEvictionIntegrationTest {

    private static final String REPORTER_ID = "10000000-0000-0000-0000-000000000002";
    private static final String ADMIN_ID = "10000000-0000-0000-0000-000000000001";
    private static final String L2_POST_DETAIL_PREFIX = "cache:postDetail:";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("report-evict"));
    }

    @Autowired
    private ContentReportService reportService;
    @Autowired
    private PostPublishingService publishingService;
    @Autowired
    private CommunityPostService postService;
    @Autowired
    private TwoLevelCache twoLevelCache;
    @Autowired
    private CacheManager cacheManager;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RedisConnectionFactory connectionFactory;

    /** 探针订阅者：捕获 cache:evict:* 广播，证明驱逐真的对外发出了失效消息。 */
    private RedisMessageListenerContainer spyContainer;
    private final BlockingQueue<String> spyReceived = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection().serverCommands().flushDb();
        Objects.requireNonNull(cacheManager.getCache(CacheNames.POST_DETAIL)).clear();
        spyReceived.clear();

        spyContainer = new RedisMessageListenerContainer();
        spyContainer.setConnectionFactory(connectionFactory);
        spyContainer.addMessageListener(
                (message, pattern) -> spyReceived.add(new String(message.getBody(), StandardCharsets.UTF_8)),
                new PatternTopic(TwoLevelCache.EVICT_CHANNEL_PREFIX + "*"));
        try {
            spyContainer.afterPropertiesSet();
            spyContainer.start();
        } catch (Exception e) {
            throw new IllegalStateException("探针订阅者启动失败", e);
        }

        // 预热：SUBSCRIBE 异步建立，先发一条并等到探针收到，避免后续断言因订阅未就绪而丢失
        redisTemplate.convertAndSend(TwoLevelCache.evictChannel(CacheNames.POST_DETAIL), "__warmup__");
        assertThat(awaitSpyMessage("__warmup__", 5000)).isTrue();
        spyReceived.clear();
    }

    @AfterEach
    void tearDown() {
        if (spyContainer != null) {
            spyContainer.stop();
            try {
                spyContainer.destroy();
            } catch (Exception e) {
                // 探针清理失败不影响测试结论，只避免掩盖真实断言失败
                spyContainer = null;
            }
        }
    }

    @Test
    void 举报成立处置帖子后立即驱逐详情两级缓存() {
        String postId = publish("待处置的违规帖");

        // ① 先读一次，把内容灌进 L1 + L2（复现 bug 的必要前置：缓存必须是热的）
        postService.findPublishedById(postId);
        Cache l1 = Objects.requireNonNull(cacheManager.getCache(CacheNames.POST_DETAIL));
        assertThat(l1.get(postId)).isNotNull();
        assertThat(redisTemplate.hasKey(L2_POST_DETAIL_PREFIX + postId)).isTrue();

        // ② 举报 → 管理员确认
        var report = reportService.create("POST", postId, REPORTER_ID,
                new CreateReportRequest("MISINFORMATION"));
        reportService.handle(report.id(), ADMIN_ID, new HandleReportRequest("CONFIRM", "确认内容不实"));

        // ③ 处置后 L1 与 L2 都必须已失效——否则被处置内容还能再读 60s
        assertThat(l1.get(postId)).isNull();
        assertThat(redisTemplate.hasKey(L2_POST_DETAIL_PREFIX + postId)).isFalse();
        // 且失效广播已发出（其他节点能准实时收到）
        assertThat(awaitSpyMessage(postId, 5000)).isTrue();

        // ④ 帖子确实已不可读
        assertThat(postService.findPublishedById(postId)).isNull();
    }

    @Test
    void 举报被驳回时不应驱逐详情缓存() {
        String postId = publish("被驳回举报的帖子");

        postService.findPublishedById(postId);
        Cache l1 = Objects.requireNonNull(cacheManager.getCache(CacheNames.POST_DETAIL));
        assertThat(l1.get(postId)).isNotNull();

        var report = reportService.create("POST", postId, REPORTER_ID,
                new CreateReportRequest("SPAM"));
        reportService.handle(report.id(), ADMIN_ID, new HandleReportRequest("REJECT", "内容合规"));

        // 驳回不动内容，缓存必须原样保留（避免无谓的跨节点失效风暴）
        assertThat(l1.get(postId)).isNotNull();
        assertThat(redisTemplate.hasKey(L2_POST_DETAIL_PREFIX + postId)).isTrue();
        assertThat(postService.findPublishedById(postId)).isNotNull();
    }

    private String publish(String title) {
        CreatePostRequest request = new CreatePostRequest(
                "GUIDE", "攻略", title, "测试摘要", "测试正文内容", List.of(), List.of()
        );
        return publishingService.publish(request, REPORTER_ID).id();
    }

    private boolean awaitSpyMessage(String expected, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                String message = spyReceived.poll(100, TimeUnit.MILLISECONDS);
                if (expected.equals(message)) {
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
