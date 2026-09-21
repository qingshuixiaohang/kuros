package com.kuros.kurosbackend.interaction;

import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.interaction.api.PostInteractionResponse;
import com.kuros.kurosbackend.interaction.domain.PostLikeId;
import com.kuros.kurosbackend.interaction.event.InteractionEvent;
import com.kuros.kurosbackend.interaction.event.InteractionEventPublisher;
import com.kuros.kurosbackend.interaction.event.InteractionType;
import com.kuros.kurosbackend.interaction.repository.PostLikeRepository;
import com.kuros.kurosbackend.interaction.service.InteractionProjectionService;
import com.kuros.kurosbackend.interaction.service.PostInteractionService;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 互动异步写路径集成测试（切片 #11）：不依赖 RocketMQ broker，用「记录型发布器」在最高接缝（Service）
 * 验证异步化后的核心行为契约。真实 broker 的端到端链路见 {@code InteractionRocketMQIntegrationTest}（gated，交 CI）。
 *
 * 为什么这一类能在无 broker 下验证异步语义？
 * test profile 关异步（app.interaction.async.enabled=false）→ RocketMQ 发布器与 Stream 消费绑定都不装配，
 * binder 全程不连 name-server；再用 @Primary 的 RecordingPublisher 顶替 Noop，用开关模拟两种投递结果：
 * - deliver=true：等价「MQ 投递成功」→ 写路径只应改 Redis + 记事件，绝不同步写 DB（异步化的本质）；
 * - deliver=false：等价「未启用 / 投递失败」→ 写路径必须锁内同步降级落库（互动不丢）。
 * 落库正确性 / 消费幂等 / 顺序终态则直接驱动 InteractionProjectionService.apply——它就是消费端与降级路径
 * 共用的唯一落库入口，因此绕过 MQ 传输也能验证「消费端契约」。
 *
 * 断言一律用「相对增量」而非绝对值，配合每用例唯一 userId + @BeforeEach 清 Redis，
 * 因此无需 @DirtiesContext 重载上下文（既快又隔离）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(InteractionAsyncIntegrationTest.RecordingPublisherConfig.class)
class InteractionAsyncIntegrationTest {

    private static final String POST_ID = "10000000-0000-0000-0000-000000000001";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("interaction-async"));
    }

    @Autowired
    private PostInteractionService interactionService;
    @Autowired
    private InteractionProjectionService projectionService;
    @Autowired
    private PostLikeRepository likeRepository;
    @Autowired
    private CommunityPostRepository postRepository;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RecordingPublisher publisher;

    @BeforeEach
    void reset() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection().serverCommands().flushDb();
        publisher.reset();
    }

    @Test
    void 投递成功时写路径只改Redis并发事件而不同步落库() {
        long before = likeCountInDb();
        String userId = UUID.randomUUID().toString();
        publisher.deliver.set(true);

        PostInteractionResponse resp = interactionService.like(POST_ID, userId);

        // 即时反馈：响应取自 Redis 实时读源，已 +1、liked=true
        assertThat(resp.liked()).isTrue();
        assertThat(resp.likeCount()).isEqualTo(before + 1);
        // 关键断言：写路径未同步写 DB——请求线程脱离了 DB 写（这正是热点行竞争被根除的前提）
        assertThat(likeRepository.existsById(new PostLikeId(userId, POST_ID))).isFalse();
        assertThat(likeCountInDb()).isEqualTo(before);
        // 事件契约：分区键 postId、userId、类型正确（顺序消息按 postId 分区）
        assertThat(publisher.published).hasSize(1);
        InteractionEvent event = publisher.published.get(0);
        assertThat(event.postId()).isEqualTo(POST_ID);
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.type()).isEqualTo(InteractionType.LIKE);

        // 模拟消费端顺序消费该事件 → DB 最终一致
        projectionService.apply(event);
        assertThat(likeRepository.existsById(new PostLikeId(userId, POST_ID))).isTrue();
        assertThat(likeCountInDb()).isEqualTo(before + 1);
    }

    @Test
    void 投递失败时锁内同步降级落库保证互动不丢() {
        long before = likeCountInDb();
        String userId = UUID.randomUUID().toString();
        publisher.deliver.set(false); // 模拟 MQ 不可用 / 未启用

        PostInteractionResponse resp = interactionService.like(POST_ID, userId);

        assertThat(resp.liked()).isTrue();
        assertThat(resp.likeCount()).isEqualTo(before + 1);
        // 降级：锁内已同步落库（commit 先于 unlock），DB 立即可见，互动绝不丢
        assertThat(likeRepository.existsById(new PostLikeId(userId, POST_ID))).isTrue();
        assertThat(likeCountInDb()).isEqualTo(before + 1);
    }

    @Test
    void 消费端对同一事件重复投递保持幂等() {
        long before = likeCountInDb();
        String userId = UUID.randomUUID().toString();
        InteractionEvent event = InteractionEvent.of(POST_ID, userId, InteractionType.LIKE);

        projectionService.apply(event);
        projectionService.apply(event); // 至少一次投递 → 可能重复消费

        // 关系仅一行、计数只 +1（existsById 前置判断挡住二次 increment）
        assertThat(likeRepository.existsById(new PostLikeId(userId, POST_ID))).isTrue();
        assertThat(likeCountInDb()).isEqualTo(before + 1);
    }

    @Test
    void 同一帖的点赞与取消按序消费后终态回到基线() {
        long before = likeCountInDb();
        String userId = UUID.randomUUID().toString();

        // 顺序消费：同 postId 落同一队列、单线程串行，LIKE 必先于 UNLIKE 生效
        projectionService.apply(InteractionEvent.of(POST_ID, userId, InteractionType.LIKE));
        projectionService.apply(InteractionEvent.of(POST_ID, userId, InteractionType.UNLIKE));

        assertThat(likeRepository.existsById(new PostLikeId(userId, POST_ID))).isFalse();
        assertThat(likeCountInDb()).isEqualTo(before);
    }

    @Test
    void 写路径重复点赞在Redis与DB两侧都幂等不重复计数() {
        long before = likeCountInDb();
        String userId = UUID.randomUUID().toString();
        publisher.deliver.set(false); // 走降级同步，便于同时校验 Redis 与 DB 收敛一致

        PostInteractionResponse first = interactionService.like(POST_ID, userId);
        PostInteractionResponse second = interactionService.like(POST_ID, userId);

        assertThat(first.likeCount()).isEqualTo(before + 1);
        assertThat(second.likeCount()).isEqualTo(before + 1); // 状态未翻转 → 不再 +1
        assertThat(likeCountInDb()).isEqualTo(before + 1);     // DB 也仅一行、只 +1
    }

    private long likeCountInDb() {
        return postRepository.findById(POST_ID).map(CommunityPost::getLikeCount).orElseThrow();
    }

    /** 记录型发布器：@Primary 顶替 Noop，开关控制投递结果并留存事件供断言 / 回放。 */
    static class RecordingPublisher implements InteractionEventPublisher {
        final AtomicBoolean deliver = new AtomicBoolean(false);
        final List<InteractionEvent> published = new CopyOnWriteArrayList<>();

        @Override
        public boolean publish(InteractionEvent event) {
            published.add(event);
            return deliver.get();
        }

        void reset() {
            deliver.set(false);
            published.clear();
        }
    }

    @TestConfiguration
    static class RecordingPublisherConfig {
        @Bean
        @Primary
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }
    }
}
