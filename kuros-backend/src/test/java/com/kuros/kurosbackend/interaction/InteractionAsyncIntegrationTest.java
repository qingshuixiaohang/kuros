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
import com.kuros.kurosbackend.shared.exception.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.kuros.kurosbackend.interaction.redis.InteractionRedisStore;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 互动异步写路径集成测试（切片 #11）：不依赖 RocketMQ broker，用「记录型发布器」在最高接缝（Service）
 * 验证异步化后的核心行为契约。真实 broker 的端到端链路见 {@code InteractionRocketMQIntegrationTest}（gated，交 CI）。
 *
 * 为什么这一类能在无 broker 下验证异步语义？
 * test profile 关异步（app.interaction.async.enabled=false）→ RocketMQ 发布器与 Stream 消费绑定都不装配，
 * binder 全程不连 name-server。RecordingPublisher 注入不同投递结果，消费者通过投影服务直接触发。
 *
 * 断言一律用「相对增量」而非绝对值，配合每用例唯一 userId + @BeforeEach 清 Redis，
 * 因此无需 @DirtiesContext 重载上下文（既快又隔离）。
 */
@SpringBootTest
@AutoConfigureMockMvc
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
    @Autowired
    private MockMvc mockMvc;
    @MockitoSpyBean
    private InteractionRedisStore redisStore;

    @BeforeEach
    void reset() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection().serverCommands().flushDb();
        publisher.reset();
    }

    @Test
    void 投递成功后仅消费者落库并失效旧缓存() {
        long before = likeCountInDb();
        String userId = UUID.randomUUID().toString();
        publisher.result = InteractionEventPublisher.Result.SENT;
        interactionService.findPost(POST_ID, userId);

        PostInteractionResponse resp = interactionService.like(POST_ID, userId);

        assertThat(resp.liked()).isFalse();
        assertThat(resp.likeCount()).isEqualTo(before);
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
        assertThat(redisTemplate.hasKey("interaction:count:like:" + POST_ID)).isFalse();
        assertThat(redisTemplate.hasKey("interaction:like:" + POST_ID + ":" + userId)).isFalse();
        assertThat(likeRepository.existsById(new PostLikeId(userId, POST_ID))).isTrue();
        assertThat(likeCountInDb()).isEqualTo(before + 1);
        assertThat(interactionService.findPost(POST_ID, userId).liked()).isTrue();
        assertThat(interactionService.findPost(POST_ID, userId).likeCount()).isEqualTo(before + 1);
    }

    @Test
    void 投递拒绝或结果未知时不写业务数据和缓存() {
        long before = likeCountInDb();
        String userId = UUID.randomUUID().toString();
        publisher.result = InteractionEventPublisher.Result.REJECTED;

        assertThatThrownBy(() -> interactionService.like(POST_ID, userId))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo("INTERACTION_NOT_ACCEPTED"));
        publisher.result = InteractionEventPublisher.Result.UNKNOWN;
        assertThatThrownBy(() -> interactionService.like(POST_ID, userId))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo("INTERACTION_DELIVERY_UNKNOWN"));

        assertThat(redisTemplate.hasKey("interaction:count:like:" + POST_ID)).isFalse();
        assertThat(redisTemplate.hasKey("interaction:like:" + POST_ID + ":" + userId)).isFalse();
        assertThat(likeRepository.existsById(new PostLikeId(userId, POST_ID))).isFalse();
        assertThat(likeCountInDb()).isEqualTo(before);
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
    void 取消后重放已完成的点赞不会恢复关系() {
        long before = likeCountInDb();
        String userId = UUID.randomUUID().toString();
        InteractionEvent like = InteractionEvent.of(POST_ID, userId, InteractionType.LIKE);

        projectionService.apply(like);
        projectionService.apply(InteractionEvent.of(POST_ID, userId, InteractionType.UNLIKE));
        projectionService.apply(like);

        assertThat(likeRepository.existsById(new PostLikeId(userId, POST_ID))).isFalse();
        assertThat(likeCountInDb()).isEqualTo(before);
    }

    @Test
    void 操作查询只向本人返回数据库状态() {
        String userId = UUID.randomUUID().toString();
        InteractionEvent event = InteractionEvent.of(POST_ID, userId, InteractionType.LIKE);
        assertThat(projectionService.findOperation(event.eventId(), userId).status()).isEqualTo("UNKNOWN");
        projectionService.apply(event);
        var result = projectionService.findOperation(event.eventId(), userId);
        assertThat(result.status()).isEqualTo("APPLIED");
        assertThat(result.state().liked()).isTrue();
        assertThat(projectionService.findOperation(event.eventId(), UUID.randomUUID().toString()).status())
                .isEqualTo("UNKNOWN");
    }

    @Test
    void 受理请求保留调用方操作编号和目标状态() {
        String userId = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        publisher.result = InteractionEventPublisher.Result.SENT;
        interactionService.submit(POST_ID, userId, operationId, InteractionType.LIKE);
        assertThat(publisher.published).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo(operationId);
            assertThat(event.type()).isEqualTo(InteractionType.LIKE);
        });
        assertThat(likeRepository.existsById(new PostLikeId(userId, POST_ID))).isFalse();
    }

    @Test
    void 缓存失效失败后可从持久记录恢复() {
        String userId = UUID.randomUUID().toString();
        InteractionEvent event = InteractionEvent.of(POST_ID, userId, InteractionType.LIKE);
        interactionService.findPost(POST_ID, userId);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down")).doCallRealMethod()
                .when(redisStore).invalidate(event.type().kind(), POST_ID, userId);
        assertThatThrownBy(() -> projectionService.apply(event)).isInstanceOf(IllegalStateException.class);
        assertThat(projectionService.findOperation(event.eventId(), userId).status()).isEqualTo("APPLIED");
        projectionService.recoverPending();
        assertThat(redisTemplate.hasKey("interaction:like:" + POST_ID + ":" + userId)).isFalse();
        assertThat(projectionService.findOperation(event.eventId(), userId).state().liked()).isTrue();
    }

    @Test
    void 多次缓存失效失败后业务仍已应用并最终恢复() {
        String userId = UUID.randomUUID().toString();
        InteractionEvent event = InteractionEvent.of(POST_ID, userId, InteractionType.LIKE);
        interactionService.findPost(POST_ID, userId);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .doThrow(new IllegalStateException("redis still down"))
                .doThrow(new IllegalStateException("redis still down"))
                .doCallRealMethod().when(redisStore).invalidate(event.type().kind(), POST_ID, userId);
        assertThatThrownBy(() -> projectionService.apply(event)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> projectionService.recoverPending()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> projectionService.recoverPending()).isInstanceOf(IllegalStateException.class);
        assertThat(projectionService.findOperation(event.eventId(), userId).status()).isEqualTo("APPLIED");
        projectionService.recoverPending();
        assertThat(redisTemplate.hasKey("interaction:like:" + POST_ID + ":" + userId)).isFalse();
        assertThat(interactionService.findPost(POST_ID, userId).liked()).isTrue();
    }

    @Test
    void 操作接口受理并限制查询身份() throws Exception {
        String user = UUID.randomUUID().toString();
        String operation = UUID.randomUUID().toString();
        var cookie = new jakarta.servlet.http.Cookie("KUROS_SESSION", cn.dev33.satoken.stp.StpUtil.createLoginSession(user));
        publisher.result = InteractionEventPublisher.Result.SENT;
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/interaction-operations")
                        .cookie(cookie, new jakarta.servlet.http.Cookie("XSRF-TOKEN", "test"))
                        .header("X-XSRF-TOKEN", "test").contentType("application/json")
                        .content("{\"operationId\":\"" + operation + "\",\"postId\":\"" + POST_ID + "\",\"type\":\"LIKE\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
        projectionService.apply(publisher.published.getFirst());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/interaction-operations/" + operation).cookie(cookie))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.status").value("APPLIED"));
        var other = new jakarta.servlet.http.Cookie("KUROS_SESSION", cn.dev33.satoken.stp.StpUtil.createLoginSession(UUID.randomUUID().toString()));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/interaction-operations/" + operation).cookie(other))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.status").value("UNKNOWN"));
    }

    @Test
    void 旧互动地址可携带操作编号并返回受理结果() throws Exception {
        String user = UUID.randomUUID().toString();
        String operation = UUID.randomUUID().toString();
        var cookie = new jakarta.servlet.http.Cookie("KUROS_SESSION", cn.dev33.satoken.stp.StpUtil.createLoginSession(user));
        publisher.result = InteractionEventPublisher.Result.SENT;
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/posts/" + POST_ID + "/interactions/like")
                        .cookie(cookie, new jakarta.servlet.http.Cookie("XSRF-TOKEN", "test"))
                        .header("X-XSRF-TOKEN", "test").header("Idempotency-Key", operation))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.operationId").value(operation));
        assertThat(publisher.published).singleElement().satisfies(event -> assertThat(event.eventId()).isEqualTo(operation));
    }

    @Test
    void 失败任务暂停后可恢复且保留记录() {
        InteractionEvent event = InteractionEvent.of(POST_ID, UUID.randomUUID().toString(), InteractionType.LIKE);
        projectionService.recordFailure(event, "TemporaryFailure");
        assertThat(projectionService.isFailurePaused(event.eventId())).isTrue();
        projectionService.resumeFailure(event.eventId());
        assertThat(projectionService.isFailurePaused(event.eventId())).isFalse();
        assertThat(projectionService.findOperation(event.eventId(), event.userId()).status()).isEqualTo("APPLIED");
        projectionService.apply(event);
        assertThat(projectionService.findOperation(event.eventId(), event.userId()).status()).isEqualTo("APPLIED");
    }

    @Test
    void 并发重投同一操作只生效一次() throws Exception {
        String userId = UUID.randomUUID().toString();
        long before = likeCountInDb();
        InteractionEvent event = InteractionEvent.of(POST_ID, userId, InteractionType.LIKE);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var tasks = java.util.stream.IntStream.range(0, 4).mapToObj(i -> executor.submit(() -> {
                gate.await();
                projectionService.apply(event);
                return null;
            })).toList();
            gate.countDown();
            for (var task : tasks) task.get();
        }
        var state = projectionService.findOperation(event.eventId(), userId).state();
        assertThat(state.liked()).isTrue();
        assertThat(state.likeCount()).isEqualTo(before + 1);
    }

    @Test
    void 已绑定的操作编号不能用于另一目标状态() {
        String userId = UUID.randomUUID().toString();
        InteractionEvent event = InteractionEvent.of(POST_ID, userId, InteractionType.LIKE);
        projectionService.apply(event);
        publisher.result = InteractionEventPublisher.Result.SENT;
        assertThatThrownBy(() -> interactionService.submit(POST_ID, userId, event.eventId(), InteractionType.UNLIKE))
                .isInstanceOf(com.kuros.kurosbackend.shared.exception.AuthRequestException.class);
        assertThat(publisher.published).isEmpty();
    }

    @Test
    void 首次消息尚未消费时操作编号仍不能绑定另一目标状态() {
        String userId = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        publisher.result = InteractionEventPublisher.Result.SENT;
        interactionService.submit(POST_ID, userId, operationId, InteractionType.LIKE);
        assertThatThrownBy(() -> interactionService.submit(POST_ID, userId, operationId, InteractionType.UNLIKE))
                .isInstanceOf(com.kuros.kurosbackend.shared.exception.AuthRequestException.class);
        assertThat(publisher.published).hasSize(1);
    }

    @Test
    void 结果未知后重试明确连接失败仍保持未知() {
        String userId = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        publisher.result = InteractionEventPublisher.Result.UNKNOWN;
        assertThatThrownBy(() -> interactionService.submit(POST_ID, userId, operationId, InteractionType.LIKE))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo("INTERACTION_DELIVERY_UNKNOWN"));
        publisher.result = InteractionEventPublisher.Result.REJECTED;
        assertThatThrownBy(() -> interactionService.submit(POST_ID, userId, operationId, InteractionType.LIKE))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo("INTERACTION_DELIVERY_UNKNOWN"));
    }

    @Test
    void 并发请求不能把同一操作编号绑定到不同用户() throws Exception {
        String operationId = UUID.randomUUID().toString();
        String firstUser = UUID.randomUUID().toString();
        String secondUser = UUID.randomUUID().toString();
        publisher.result = InteractionEventPublisher.Result.SENT;
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var results = java.util.List.of(firstUser, secondUser).stream().map(user -> pool.submit(() -> {
                gate.await();
                try {
                    interactionService.submit(POST_ID, user, operationId, InteractionType.LIKE);
                    return "SENT";
                } catch (com.kuros.kurosbackend.shared.exception.AuthRequestException conflict) {
                    return "CONFLICT";
                }
            })).toList();
            gate.countDown();
            assertThat(java.util.List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder("SENT", "CONFLICT");
        }
        assertThat(publisher.published).hasSize(1);
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
        publisher.result = InteractionEventPublisher.Result.DISABLED;

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
        Result result = Result.DISABLED;
        final List<InteractionEvent> published = new CopyOnWriteArrayList<>();

        @Override
        public Result publish(InteractionEvent event) {
            published.add(event);
            return result;
        }

        void reset() {
            result = Result.DISABLED;
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
