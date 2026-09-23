package com.kuros.kurosbackend.cache;

import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.post.api.CreatePostRequest;
import com.kuros.kurosbackend.post.service.CommunityPostService;
import com.kuros.kurosbackend.post.service.PostPublishingService;
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
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨节点 L1 失效（pub/sub）集成测试（切片 #13 / rp-03）。
 *
 * 验证两件外部可观测行为：
 * 1. 写路径驱逐（TwoLevelCache.evict）删除共享 L2，并向 cache:evict:{cacheName} 频道广播失效键——
 *    用一个「探针订阅者」捕获广播，证明消息确实发出（而非仅本节点静默清 L1）；
 * 2. 订阅节点收到失效消息后清除「本地」L1——直接向频道发消息模拟「另一个节点」的广播，
 *    断言本节点由 CachePubSubConfig 装配的生产监听器把对应 L1 键清掉（跨节点一致性的收端）。
 *
 * 为什么探针订阅者要「预热」？RedisMessageListenerContainer 的 SUBSCRIBE 是异步建立的，
 * 若容器刚 start 就发消息可能丢失。先发一个 warmup 键并等到探针收到，确认订阅生效后再开始断言。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.cache.type=caffeine")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CacheEvictionPubSubIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final String TEST_USER_ID = "10000000-0000-0000-0000-000000000001";
    private static final String L2_POST_DETAIL_PREFIX = "cache:postDetail:";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("cache-pubsub"));
    }

    @Autowired
    private TwoLevelCache twoLevelCache;
    @Autowired
    private CommunityPostService postService;
    @Autowired
    private PostPublishingService publishingService;
    @Autowired
    private CacheManager cacheManager;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RedisConnectionFactory connectionFactory;

    /** 探针订阅者：捕获 cache:evict:* 上的广播消息体（失效键），用于断言「消息确实发出」。 */
    private RedisMessageListenerContainer spyContainer;
    private final BlockingQueue<String> spyReceived = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() throws Exception {
        Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection().serverCommands().flushDb();
        Objects.requireNonNull(cacheManager.getCache("postDetail")).clear();
        spyReceived.clear();

        spyContainer = new RedisMessageListenerContainer();
        spyContainer.setConnectionFactory(connectionFactory);
        spyContainer.addMessageListener(
                (message, pattern) -> spyReceived.add(new String(message.getBody(), StandardCharsets.UTF_8)),
                new PatternTopic(TwoLevelCache.EVICT_CHANNEL_PREFIX + "*"));
        spyContainer.afterPropertiesSet();
        spyContainer.start();

        // 预热：等到探针确实收到一条消息，证明 SUBSCRIBE 已生效，避免随后的广播因订阅未就绪而丢失
        redisTemplate.convertAndSend(TwoLevelCache.evictChannel(CacheNames.POST_DETAIL), "__warmup__");
        assertThat(awaitSpyMessage("__warmup__", 5000)).isTrue();
        spyReceived.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (spyContainer != null) {
            spyContainer.stop();
            spyContainer.destroy();
        }
    }

    @Test
    void 写路径驱逐删除L2并广播失效消息() throws Exception {
        String postId = publish("广播失效测试帖");
        postService.findPublishedById(postId); // 回填 L1 + L2
        assertThat(redisTemplate.hasKey(L2_POST_DETAIL_PREFIX + postId)).isTrue();

        twoLevelCache.evict(CacheNames.POST_DETAIL, postId);

        // 共享 L2 被删除
        assertThat(redisTemplate.hasKey(L2_POST_DETAIL_PREFIX + postId)).isFalse();
        // 失效广播已发出并被（探针）订阅者收到
        assertThat(awaitSpyMessage(postId, 5000)).isTrue();
    }

    @Test
    void 订阅节点收到失效消息后清除本地L1() {
        String key = "remote-evicted-post";
        Cache l1 = Objects.requireNonNull(cacheManager.getCache("postDetail"));
        // 模拟本节点 L1 仍揣着旧内容（另一节点尚未广播时的状态）
        l1.put(key, "stale-content");
        assertThat(l1.get(key)).isNotNull();

        // 模拟「另一个节点」广播失效：直接发频道消息，不经本节点 evict（隔离出订阅端行为）
        redisTemplate.convertAndSend(TwoLevelCache.evictChannel(CacheNames.POST_DETAIL), key);

        // 生产监听器（CachePubSubConfig 装配）收到后应清掉本节点 L1 对应键
        awaitUntil(() -> l1.get(key) == null, 5000);
        assertThat(l1.get(key)).isNull();
    }

    private String publish(String title) {
        CreatePostRequest request = new CreatePostRequest(
                "GUIDE", "攻略", title, "测试摘要", "测试正文内容", List.of(), List.of()
        );
        return publishingService.publish(request, TEST_USER_ID).id();
    }

    private boolean awaitSpyMessage(String expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            String message = spyReceived.poll(100, TimeUnit.MILLISECONDS);
            if (expected.equals(message)) {
                return true;
            }
        }
        return false;
    }

    private void awaitUntil(BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
