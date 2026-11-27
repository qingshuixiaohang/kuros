package com.kuros.kurosbackend.interaction;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.kuros.kurosbackend.interaction.domain.PostLikeId;
import com.kuros.kurosbackend.interaction.repository.PostLikeRepository;
import com.kuros.kurosbackend.interaction.service.PostInteractionService;
import com.kuros.kurosbackend.interaction.service.InteractionProjectionService;
import com.kuros.kurosbackend.interaction.event.InteractionEvent;
import com.kuros.kurosbackend.interaction.event.InteractionType;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 互动异步落库 RocketMQ 端到端集成测试（切片 #11）：真实 namesrv + broker 容器，验证
 * 点赞、取消和多用户并发经真实 RocketMQ、Redis、MySQL 收敛。
 *
 * ⚠️ 默认跳过（gated）：本类是唯一需要真实 RocketMQ broker 的重量级用例，用 @EnabledIfSystemProperty
 * 门控——默认 {@code mvn test} 不跑（保持主套件确定性与速度），需显式 {@code -Dkuros.it.rocketmq=true}
 * 且有 Docker 环境才执行。交互逻辑（异步写路径 / 幂等 / 降级 / 顺序终态）已由无 broker 的
 * {@code InteractionAsyncIntegrationTest} 确定性覆盖；本类专验「Spring Cloud Stream + RocketMQ binder」
 * 的真实传输链路（绑定名、orderly、序列化、消费触发），这部分无法脱离 broker 验证。
 *
 * brokerIP1=localhost + 固定端口绑定的原因见 src/test/resources/rocketmq/broker-test.conf 注释。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@Tag("rocketmq-e2e")
@EnabledIfSystemProperty(named = "kuros.it.rocketmq", matches = "true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InteractionRocketMQIntegrationTest {

    private static final String POST_ID = "10000000-0000-0000-0000-000000000001";
    private static final String ROCKETMQ_IMAGE = "apache/rocketmq:5.3.1";
    private static final int NAMESRV_PORT = 9876;
    private static final int BROKER_PORT = 10911;
    private static final int BROKER_FAST_PORT = 10909;

    static final Network network = Network.newNetwork();

    @Container
    static GenericContainer<?> mysql = new GenericContainer<>("mysql:8.0")
            .withEnv("MYSQL_ROOT_PASSWORD", "test-only-password")
            .withEnv("MYSQL_DATABASE", "kuros_test")
            .withExposedPorts(3306)
            .waitingFor(Wait.forLogMessage(".*ready for connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static GenericContainer<?> namesrv = new GenericContainer<>(ROCKETMQ_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("namesrv")
            .withExposedPorts(NAMESRV_PORT)
            .withCommand("sh", "mqnamesrv")
            .waitingFor(Wait.forLogMessage(".*The Name Server boot success.*\\n", 1)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    @Container
    static GenericContainer<?> broker = new GenericContainer<>(ROCKETMQ_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("broker")
            .dependsOn(namesrv)
            .withExposedPorts(BROKER_PORT, BROKER_FAST_PORT)
            .withEnv("NAMESRV_ADDR", "namesrv:" + NAMESRV_PORT)
            .withEnv("JAVA_OPT_EXT", "-Xms512m -Xmx512m -Xmn256m")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("rocketmq/broker-test.conf"),
                    "/home/rocketmq/broker.conf")
            // 固定端口：客户端经 namesrv 拿到 brokerIP1=localhost:10911 后须能在宿主直连该端口
            .withCreateContainerCmdModifier(InteractionRocketMQIntegrationTest::bindBrokerFixedPorts)
            .withCommand("sh", "mqbroker", "-c", "/home/rocketmq/broker.conf")
            .waitingFor(Wait.forLogMessage(".*The broker.*boot success.*\\n", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        var topic = broker.execInContainer("sh", "mqadmin", "updateTopic", "-n", "namesrv:9876",
                "-b", "localhost:10911", "-t", "post-interaction-topic", "-r", "4", "-w", "4");
        if (topic.getExitCode() != 0) {
            throw new IllegalStateException("Topic creation failed: " + topic.getStderr());
        }
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "2s");
        registry.add("spring.datasource.url", () -> "jdbc:mysql://" + mysql.getHost() + ":"
                + mysql.getMappedPort(3306) + "/kuros_test?characterEncoding=UTF-8&serverTimezone=UTC");
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "test-only-password");
        // 打开异步链路（覆盖 test profile 的 async.enabled=false / 空 function.definition）：
        // @DynamicPropertySource 优先级高于 application-test.properties，故此处生效
        registry.add("app.interaction.async.enabled", () -> "true");
        registry.add("spring.cloud.function.definition", () -> "");
        registry.add("spring.cloud.stream.rocketmq.binder.name-server",
                () -> namesrv.getHost() + ":" + namesrv.getMappedPort(NAMESRV_PORT));
    }

    @Autowired
    private PostInteractionService interactionService;
    @Autowired
    private PostLikeRepository likeRepository;
    @MockitoSpyBean
    private CommunityPostRepository postRepository;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @MockitoSpyBean
    private InteractionProjectionService projectionService;
    @MockitoSpyBean
    private com.kuros.kurosbackend.interaction.event.InteractionEventPublisher publisher;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoSpyBean
    private com.kuros.kurosbackend.interaction.redis.InteractionRedisStore redisStore;

    @Test
    void 业务回调失败后消息会重新消费() {
        String userId = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        org.mockito.Mockito.doThrow(new IllegalStateException("temporary failure")).doCallRealMethod()
                .when(projectionService).apply(org.mockito.ArgumentMatchers.argThat(
                        event -> event != null && operationId.equals(event.eventId())));
        interactionService.submit(POST_ID, userId, operationId, InteractionType.LIKE);
        awaitUntil(() -> projectionService.findOperation(operationId, userId).status().equals("APPLIED"), Duration.ofSeconds(60));
        org.mockito.Mockito.verify(projectionService, org.mockito.Mockito.atLeast(2)).apply(
                org.mockito.ArgumentMatchers.argThat(event -> event != null && operationId.equals(event.eventId())));
    }

    @Test
    void 重试耗尽后暂停队列并由管理员恢复() {
        String userId = UUID.randomUUID().toString();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        org.mockito.Mockito.doThrow(new IllegalStateException("failure"))
                .when(projectionService).apply(org.mockito.ArgumentMatchers.argThat(
                        event -> event != null && first.equals(event.eventId())));
        interactionService.submit(POST_ID, userId, first, InteractionType.LIKE);
        interactionService.submit(POST_ID, userId, second, InteractionType.UNLIKE);
        awaitUntil(() -> projectionService.isFailurePaused(first), Duration.ofSeconds(60));
        assertThat(projectionService.findOperation(second, userId).status()).isEqualTo("UNKNOWN");
        org.mockito.Mockito.doCallRealMethod().when(projectionService).apply(org.mockito.ArgumentMatchers.argThat(
                event -> event != null && first.equals(event.eventId())));
        projectionService.resumeFailure(first);
        awaitUntil(() -> projectionService.findOperation(second, userId).status().equals("APPLIED"), Duration.ofSeconds(60));
        assertThat(likeRepository.existsById(new PostLikeId(userId, POST_ID))).isFalse();
    }

    @Test
    void 点赞经RocketMQ顺序消费后DB最终一致() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        long before = likeCountInDb();
        String userId = UUID.randomUUID().toString();

        interactionService.like(POST_ID, userId);

        // 轮询等待真实 broker 传输 + 顺序消费 → DB 最终一致
        awaitUntil(() -> likeRepository.existsById(new PostLikeId(userId, POST_ID))
                && likeCountInDb() == before + 1
                && interactionService.findPost(POST_ID, userId).liked(), Duration.ofSeconds(60));
    }

    @Test
    void 点赞完成后取消终态回到基线() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        long before = likeCountInDb();
        String userId = UUID.randomUUID().toString();

        interactionService.like(POST_ID, userId);
        awaitUntil(() -> likeRepository.existsById(new PostLikeId(userId, POST_ID))
                && likeCountInDb() == before + 1, Duration.ofSeconds(60));
        interactionService.unlike(POST_ID, userId);

        // 已观察到点赞完成后再取消，避免在消费前误判初始状态为最终状态。
        awaitUntil(() -> !likeRepository.existsById(new PostLikeId(userId, POST_ID))
                && likeCountInDb() == before, Duration.ofSeconds(60));
    }

    @Test
    void 连续提交点赞和取消按顺序完成() {
        long before = likeCountInDb();
        String userId = UUID.randomUUID().toString();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        interactionService.submit(POST_ID, userId, first, InteractionType.LIKE);
        interactionService.submit(POST_ID, userId, second, InteractionType.UNLIKE);
        awaitUntil(() -> projectionService.findOperation(second, userId).status().equals("APPLIED")
                && !likeRepository.existsById(new PostLikeId(userId, POST_ID))
                && likeCountInDb() == before, Duration.ofSeconds(60));
    }

    @Test
    void 多用户并发点赞同一帖后计数与新增关系一致() throws Exception {
        long before = likeCountInDb();
        List<String> users = java.util.stream.IntStream.range(0, 8)
                .mapToObj(ignored -> UUID.randomUUID().toString()).toList();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(users.size())) {
            var requests = users.stream().map(user -> executor.submit(() -> {
                start.await();
                interactionService.like(POST_ID, user);
                return null;
            })).toList();
            start.countDown();
            for (var request : requests) {
                request.get(30, TimeUnit.SECONDS);
            }
        }
        awaitUntil(() -> users.stream().allMatch(user ->
                        likeRepository.existsById(new PostLikeId(user, POST_ID)))
                && likeCountInDb() == before + users.size(), Duration.ofSeconds(60));
    }

    @Test
    void 同一帖点赞取消再点赞最终只增加一次() {
        long before = likeCountInDb();
        String userId = UUID.randomUUID().toString();
        String last = UUID.randomUUID().toString();
        interactionService.submit(POST_ID, userId, UUID.randomUUID().toString(), InteractionType.LIKE);
        interactionService.submit(POST_ID, userId, UUID.randomUUID().toString(), InteractionType.UNLIKE);
        interactionService.submit(POST_ID, userId, last, InteractionType.LIKE);
        awaitUntil(() -> projectionService.findOperation(last, userId).status().equals("APPLIED")
                && likeRepository.existsById(new PostLikeId(userId, POST_ID))
                && likeCountInDb() == before + 1, Duration.ofSeconds(60));
    }

    @Test
    @org.junit.jupiter.api.Order(Integer.MAX_VALUE)
    void broker停机后不会误报受理() {
        String user = UUID.randomUUID().toString();
        String operation = UUID.randomUUID().toString();
        long before = likeCountInDb();
        interactionService.findPost(POST_ID, user);
        String stateKey = "interaction:like:" + POST_ID + ":" + user;
        String countKey = "interaction:count:like:" + POST_ID;
        String stateBefore = redisTemplate.opsForValue().get(stateKey);
        String countBefore = redisTemplate.opsForValue().get(countKey);
        broker.getDockerClient().stopContainerCmd(broker.getContainerId()).exec();
        try {
            var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> interactionService.submit(POST_ID, user, operation, InteractionType.LIKE),
                    com.kuros.kurosbackend.shared.exception.ServiceUnavailableException.class);
            assertThat(failure).isNotNull();
            assertThat(failure.getCode()).isIn("INTERACTION_NOT_ACCEPTED", "INTERACTION_DELIVERY_UNKNOWN");
            System.out.println("Broker stopped: " + failure.getCode());
            if (failure.getCode().equals("INTERACTION_NOT_ACCEPTED")) {
                assertThat(likeRepository.existsById(new PostLikeId(user, POST_ID))).isFalse();
                assertThat(likeCountInDb()).isEqualTo(before);
                assertThat(redisTemplate.opsForValue().get(stateKey)).isEqualTo(stateBefore);
                assertThat(redisTemplate.opsForValue().get(countKey)).isEqualTo(countBefore);
            }
        } finally {
            broker.getDockerClient().startContainerCmd(broker.getContainerId()).exec();
        }
    }

    @Test
    void broker确认后丢失受理结果并以同一编号重试() {
        String user = UUID.randomUUID().toString();
        String operation = UUID.randomUUID().toString();
        long before = likeCountInDb();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            var sent = invocation.callRealMethod();
            if (attempts.getAndIncrement() == 0) {
                assertThat(sent).isEqualTo(com.kuros.kurosbackend.interaction.event.InteractionEventPublisher.Result.SENT);
                return com.kuros.kurosbackend.interaction.event.InteractionEventPublisher.Result.UNKNOWN;
            }
            return sent;
        }).when(publisher).publish(org.mockito.ArgumentMatchers.argThat(event -> event != null && operation.equals(event.eventId())));
        try {
            var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> interactionService.submit(POST_ID, user, operation, InteractionType.LIKE),
                    com.kuros.kurosbackend.shared.exception.ServiceUnavailableException.class);
            assertThat(failure).isNotNull();
            assertThat(failure.getCode()).isEqualTo("INTERACTION_DELIVERY_UNKNOWN");
            interactionService.submit(POST_ID, user, operation, InteractionType.LIKE);
            awaitUntil(() -> projectionService.findOperation(operation, user).status().equals("APPLIED"), Duration.ofSeconds(60));
            assertThat(likeRepository.existsById(new PostLikeId(user, POST_ID))).isTrue();
            assertThat(likeCountInDb()).isEqualTo(before + 1);
            assertThat(attempts.get()).isEqualTo(2);
        } finally {
            org.mockito.Mockito.reset(publisher);
        }
    }

    @Test
    void 真实消息重复投递和旧消息不会覆盖取消() {
        long before = likeCountInDb();
        String user = UUID.randomUUID().toString();
        var like = new InteractionEvent(UUID.randomUUID().toString(), POST_ID, user, InteractionType.LIKE, System.currentTimeMillis());
        var unlike = new InteractionEvent(UUID.randomUUID().toString(), POST_ID, user, InteractionType.UNLIKE, System.currentTimeMillis());
        var completed = new CountDownLatch(2);
        org.mockito.Mockito.doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            completed.countDown();
            return result;
        }).when(projectionService).apply(like);
        assertThat(publisher.publish(like)).isEqualTo(com.kuros.kurosbackend.interaction.event.InteractionEventPublisher.Result.SENT);
        awaitUntil(() -> projectionService.isApplied(like), Duration.ofSeconds(60));
        assertThat(publisher.publish(unlike)).isEqualTo(com.kuros.kurosbackend.interaction.event.InteractionEventPublisher.Result.SENT);
        awaitUntil(() -> projectionService.isApplied(unlike), Duration.ofSeconds(60));
        assertThat(publisher.publish(like)).isEqualTo(com.kuros.kurosbackend.interaction.event.InteractionEventPublisher.Result.SENT);
        try {
            assertThat(completed.await(60, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
        assertThat(likeRepository.existsById(new PostLikeId(user, POST_ID))).isFalse();
        assertThat(likeCountInDb()).isEqualTo(before);
    }

    @Test
    void redis暂停跨提交后恢复待失效缓存() {
        String user = UUID.randomUUID().toString();
        var event = new InteractionEvent(UUID.randomUUID().toString(), POST_ID, user, InteractionType.LIKE, System.currentTimeMillis());
        interactionService.findPost(POST_ID, user);
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        try {
            assertThat(publisher.publish(event)).isEqualTo(com.kuros.kurosbackend.interaction.event.InteractionEventPublisher.Result.SENT);
            awaitUntil(() -> projectionService.isApplied(event), Duration.ofSeconds(60));
            assertThat(likeRepository.existsById(new PostLikeId(user, POST_ID))).isTrue();
            assertThat(jdbc.queryForObject("select cache_pending from interaction_operations where operation_id = ?", Boolean.class, event.eventId())).isTrue();
        } finally {
            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        }
        awaitUntil(() -> {
            try { projectionService.recoverPending(); return !jdbc.queryForObject("select cache_pending from interaction_operations where operation_id = ?", Boolean.class, event.eventId()); }
            catch (org.springframework.dao.DataAccessException ex) { return false; }
        }, Duration.ofSeconds(60));
        assertThat(interactionService.findPost(POST_ID, user).liked()).isTrue();
    }

    @Test
    void 提交后失效前抛错仍保留待恢复标记() {
        String user = UUID.randomUUID().toString();
        var event = new InteractionEvent(UUID.randomUUID().toString(), POST_ID, user, InteractionType.LIKE, System.currentTimeMillis());
        org.mockito.Mockito.doThrow(new IllegalStateException("injected after commit"))
                .when(redisStore).invalidate(InteractionType.LIKE.kind(), POST_ID, user);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> projectionService.apply(event)).isInstanceOf(IllegalStateException.class);
            assertThat(projectionService.isApplied(event)).isTrue();
            assertThat(jdbc.queryForObject("select cache_pending from interaction_operations where operation_id = ?", Boolean.class, event.eventId())).isTrue();
        } finally {
            org.mockito.Mockito.doCallRealMethod().when(redisStore).invalidate(InteractionType.LIKE.kind(), POST_ID, user);
        }
        projectionService.apply(event);
        assertThat(jdbc.queryForObject("select cache_pending from interaction_operations where operation_id = ?", Boolean.class, event.eventId())).isFalse();
    }

    @Test
    void 提交前失败事务完整回滚后可重投() {
        String user = UUID.randomUUID().toString();
        var event = new InteractionEvent(UUID.randomUUID().toString(), POST_ID, user, InteractionType.LIKE, System.currentTimeMillis());
        long before = likeCountInDb();
        org.mockito.Mockito.doThrow(new IllegalStateException("injected before commit"))
                .when(postRepository).incrementLikeCount(POST_ID);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> projectionService.apply(event)).isInstanceOf(IllegalStateException.class);
            assertThat(projectionService.isApplied(event)).isFalse();
            assertThat(likeRepository.existsById(new PostLikeId(user, POST_ID))).isFalse();
            assertThat(likeCountInDb()).isEqualTo(before);
        } finally {
            org.mockito.Mockito.reset(postRepository);
        }
        projectionService.apply(event);
        assertThat(projectionService.isApplied(event)).isTrue();
        assertThat(likeCountInDb()).isEqualTo(before + 1);
    }

    private long likeCountInDb() {
        return postRepository.findById(POST_ID).map(CommunityPost::getLikeCount).orElseThrow();
    }

    /** 轮询直至条件满足或超时——异步最终一致断言范式（无 Awaitility 依赖，保持测试栈精简）。 */
    private void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("轮询被中断", ex);
            }
        }
        throw new AssertionError("DB 在 " + timeout + " 内未收敛到期望终态");
    }

    /** 把 broker 的 10911/10909 以固定端口绑定到宿主，使 brokerIP1=localhost 对宿主 JVM 可达。 */
    private static void bindBrokerFixedPorts(CreateContainerCmd cmd) {
        HostConfig hostConfig = cmd.getHostConfig() != null ? cmd.getHostConfig() : HostConfig.newHostConfig();
        hostConfig.withPortBindings(
                new PortBinding(Ports.Binding.bindPort(BROKER_PORT), new ExposedPort(BROKER_PORT)),
                new PortBinding(Ports.Binding.bindPort(BROKER_FAST_PORT), new ExposedPort(BROKER_FAST_PORT)));
        cmd.withHostConfig(hostConfig);
    }
}
