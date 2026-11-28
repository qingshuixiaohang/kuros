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

import com.kuros.kurosbackend.interaction.service.InteractionCrashPoint;

import java.nio.file.Files;
import java.nio.file.Path;
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
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(HostConfig.newHostConfig()
                    .withPortBindings(new PortBinding(Ports.Binding.bindPort(16379), new ExposedPort(6379)))));

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
    @Autowired
    private org.apache.rocketmq.client.producer.DefaultMQProducer interactionProducer;
    @Autowired
    private org.apache.rocketmq.client.consumer.DefaultMQPushConsumer interactionConsumer;
    @Autowired
    private com.kuros.kurosbackend.interaction.event.InteractionStreamConfig interactionStreamConfig;
    @org.springframework.beans.factory.annotation.Value("${spring.cloud.stream.rocketmq.binder.name-server}")
    private String rocketmqNameserver;
    @org.springframework.beans.factory.annotation.Value("${spring.datasource.url}")
    private String dbUrl;
    @org.springframework.beans.factory.annotation.Value("${spring.datasource.username}")
    private String dbUsername;
    @org.springframework.beans.factory.annotation.Value("${spring.datasource.password}")
    private String dbPassword;
    @org.springframework.beans.factory.annotation.Value("${spring.data.redis.host}")
    private String redisHost;
    @org.springframework.beans.factory.annotation.Value("${spring.data.redis.port}")
    private String redisPort;
    @MockitoSpyBean
    private com.kuros.kurosbackend.interaction.redis.InteractionRedisStore redisStore;

    @Test
    @org.junit.jupiter.api.Order(10)
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
    @org.junit.jupiter.api.Order(11)
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
    @org.junit.jupiter.api.Order(12)
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
    @org.junit.jupiter.api.Order(13)
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
    @org.junit.jupiter.api.Order(14)
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
    @org.junit.jupiter.api.Order(15)
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
    @org.junit.jupiter.api.Order(16)
    void 同一帖点赞取消再点赞最终只增加一次() {
        long before = likeCountInDb();
        String userId = UUID.randomUUID().toString();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        String last = UUID.randomUUID().toString();
        interactionService.submit(POST_ID, userId, first, InteractionType.LIKE);
        interactionService.submit(POST_ID, userId, second, InteractionType.UNLIKE);
        interactionService.submit(POST_ID, userId, last, InteractionType.LIKE);
        // 三步都 APPLIED 证明 A→B→C 都未被丢弃；终态为点赞且计数只 +1，证明取消没被跳过、
        // 最后一次点赞没有重复累加。乱序若把取消排到最后，终态会变成未点赞，同样会被终态断言拦下。
        awaitUntil(() -> projectionService.findOperation(first, userId).status().equals("APPLIED")
                && projectionService.findOperation(second, userId).status().equals("APPLIED")
                && projectionService.findOperation(last, userId).status().equals("APPLIED")
                && likeRepository.existsById(new PostLikeId(userId, POST_ID))
                && likeCountInDb() == before + 1, Duration.ofSeconds(60));
    }

    @Test
    @org.junit.jupiter.api.Order(20)
    void 无路由的Topic被明确拒绝而不是结果未知() {
        // Broker 正常运行且 autoCreateTopicEnable=false；namesrv 对不存在的 Topic 返回无路由，
        // 客户端抛 NOT_FOUND_TOPIC_EXCEPTION，发布器必须归类为 REJECTED（服务层据此外传 NOT_ACCEPTED）。
        var missingTopicPublisher = new com.kuros.kurosbackend.interaction.event.RocketMqInteractionEventPublisher(
                interactionProducer, "interaction-topic-without-route");
        var result = missingTopicPublisher.publish(
                InteractionEvent.of(POST_ID, UUID.randomUUID().toString(), InteractionType.LIKE));
        assertThat(result).isEqualTo(com.kuros.kurosbackend.interaction.event.InteractionEventPublisher.Result.REJECTED);
    }

    /**
     * 已受理但尚未消费的消息被 Broker 侧清理后，绑定表没有可重投的顺序，操作保持 UNKNOWN。
     *
     * 用「删除并重建 Topic」让 B 的 ConsumeQueue 条目真实消失，代替等待保留期计时器（测试时长内不可控）；
     * A 的失败记录保存在 MySQL，可从 interaction_failures 直接恢复；C 走真实 Broker 证明消费链路仍然可用。
     */
    @Test
    @org.junit.jupiter.api.Order(90)
    void 已受理消息被Broker清理后不会从绑定表恢复() throws Exception {
        long before = likeCountInDb();
        String user = UUID.randomUUID().toString();
        String blockedLike = UUID.randomUUID().toString();
        String lostUnlike = UUID.randomUUID().toString();
        org.mockito.Mockito.doThrow(new IllegalStateException("retention-probe"))
                .when(projectionService).apply(org.mockito.ArgumentMatchers.argThat(
                        event -> event != null && blockedLike.equals(event.eventId())));
        try {
            interactionService.submit(POST_ID, user, blockedLike, InteractionType.LIKE);
            interactionService.submit(POST_ID, user, lostUnlike, InteractionType.UNLIKE);
            awaitUntil(() -> projectionService.isFailurePaused(blockedLike), Duration.ofSeconds(60));
            assertThat(projectionService.findOperation(lostUnlike, user).status()).isEqualTo("UNKNOWN");

            var delete = broker.execInContainer("sh", "mqadmin", "deleteTopic",
                    "-n", "namesrv:9876", "-c", "DefaultCluster", "-t", "post-interaction-topic");
            assertThat(delete.getExitCode()).as("deleteTopic: %s", delete.getStderr()).isZero();
            var recreate = broker.execInContainer("sh", "mqadmin", "updateTopic",
                    "-n", "namesrv:9876", "-b", "localhost:10911",
                    "-t", "post-interaction-topic", "-r", "4", "-w", "4");
            assertThat(recreate.getExitCode()).as("updateTopic: %s", recreate.getStderr()).isZero();

            // 消费者按新 Topic 元数据重建；旧实例仍在重试被暂停的队列。
            // 位点重置到「此刻」：重建后的队列为空，新消费实例从 0 开始拉取，C 才可见。
            var reset = broker.execInContainer("sh", "mqadmin", "resetOffsetByTime",
                    "-n", "namesrv:9876", "-b", "localhost:10911",
                    "-g", "interaction-consumer-group", "-t", "post-interaction-topic", "-s", "now");
            assertThat(reset.getExitCode()).as("resetOffsetByTime: %s", reset.getStderr()).isZero();

            interactionConsumer.shutdown();
            var restarted = interactionStreamConfig.newInteractionConsumer(projectionService, rocketmqNameserver);
            try {
                // A 从 MySQL 失败记录直接恢复，不依赖 Broker 保留的原消息。
                org.mockito.Mockito.doCallRealMethod().when(projectionService).apply(org.mockito.ArgumentMatchers.argThat(
                        event -> event != null && blockedLike.equals(event.eventId())));
                projectionService.resumeFailure(blockedLike);
                assertThat(projectionService.findOperation(blockedLike, user).status()).isEqualTo("APPLIED");
                assertThat(likeRepository.existsById(new PostLikeId(user, POST_ID))).isTrue();
                assertThat(likeCountInDb()).isEqualTo(before + 1);

                // C 经真实 Broker 到新消费者：证明链路恢复，而 B 的原消息已不可拉取。
                String liveUser = UUID.randomUUID().toString();
                String live = UUID.randomUUID().toString();
                awaitUntil(() -> {
                    try {
                        interactionService.submit(POST_ID, liveUser, live, InteractionType.LIKE);
                        return true;
                    } catch (com.kuros.kurosbackend.shared.exception.ServiceUnavailableException retryable) {
                        return false;
                    }
                }, Duration.ofSeconds(120));
                awaitUntil(() -> projectionService.findOperation(live, liveUser).status().equals("APPLIED"),
                        Duration.ofSeconds(120));

                assertThat(projectionService.findOperation(lostUnlike, user).status()).isEqualTo("UNKNOWN");
                assertThat(likeRepository.existsById(new PostLikeId(user, POST_ID)))
                        .as("若 B 的取消被应用，A 的点赞行应已被删除")
                        .isTrue();
                assertThat(likeCountInDb()).isEqualTo(before + 2);
            } finally {
                restarted.shutdown();
            }
        } finally {
            org.mockito.Mockito.doCallRealMethod().when(projectionService).apply(org.mockito.ArgumentMatchers.argThat(
                    event -> event != null && blockedLike.equals(event.eventId())));
        }
    }

    /**
     * 真实消费者进程在三个提交/确认边界被强制终止（{@code Runtime.halt}，不执行关闭钩子）。
     *
     * 消费者运行在独立 JVM（{@code com.kuros.crashharness.InteractionCrashHarness}），父 JVM 的消费者在本用例期间关闭。
     * 每个边界崩溃后，启动一个正常消费子进程让 Broker 重投未确认消息，再断言数据库、计数与缓存：
     * 1. 提交前：事务回滚，操作不存在，重投后只应用一次；
     * 2. 提交后、失效前：操作已提交且保留待失效标记，旧缓存仍在，重投清除标记且不重复计数；
     * 3. 失效后、确认前：缓存已失效但消息未确认，重投幂等且不重复计数。
     */
    @Test
    @org.junit.jupiter.api.Order(91)
    void 消费者进程在三个边界崩溃后均可恢复() throws Exception {
        try {
            interactionConsumer.shutdown();
        } catch (RuntimeException ignored) {
            // 前面的用例可能已关闭该实例
        }
        // 父上下文的定时缓存恢复会与「提交后崩溃」的场景抢跑；本用例内停用，结束时恢复。
        org.mockito.Mockito.doNothing().when(projectionService).recoverPending();
        try {
            runCrashScenarios();
        } finally {
            org.mockito.Mockito.doCallRealMethod().when(projectionService).recoverPending();
        }
    }

    private void runCrashScenarios() throws Exception {
        Path dir = Path.of("target", "crash-harness");
        Files.createDirectories(dir);

        // 场景 1：提交前崩溃
        long before = likeCountInDb();
        String user1 = UUID.randomUUID().toString();
        String op1 = UUID.randomUUID().toString();
        InteractionEvent event1 = eventOf(op1, user1, InteractionType.LIKE);
        submitAndCrash(InteractionCrashPoint.BEFORE_COMMIT, user1, op1, InteractionType.LIKE, dir, "s1");
        assertThat(projectionService.isApplied(event1)).isFalse();
        assertThat(likeRepository.existsById(new PostLikeId(user1, POST_ID))).isFalse();
        assertThat(likeCountInDb()).isEqualTo(before);
        Process recovery1 = startRecovery(dir, "s1-recovery");
        try {
            awaitUntil(() -> projectionService.isApplied(event1)
                    && likeRepository.existsById(new PostLikeId(user1, POST_ID))
                    && likeCountInDb() == before + 1, Duration.ofSeconds(120));
        } finally {
            killQuietly(recovery1);
        }
        assertThat(interactionService.findPost(POST_ID, user1).liked()).isTrue();

        // 场景 2：提交后、缓存失效前崩溃
        long before2 = likeCountInDb();
        String user2 = UUID.randomUUID().toString();
        String op2 = UUID.randomUUID().toString();
        InteractionEvent event2 = eventOf(op2, user2, InteractionType.LIKE);
        String stateKey2 = "interaction:like:" + POST_ID + ":" + user2;
        redisTemplate.opsForValue().set(stateKey2, "0");
        submitAndCrash(InteractionCrashPoint.AFTER_COMMIT, user2, op2, InteractionType.LIKE, dir, "s2");
        assertThat(projectionService.isApplied(event2)).isTrue();
        assertThat(cachePending(op2)).isTrue();
        assertThat(likeRepository.existsById(new PostLikeId(user2, POST_ID))).isTrue();
        assertThat(likeCountInDb()).isEqualTo(before2 + 1);
        assertThat(redisTemplate.opsForValue().get(stateKey2)).as("失效尚未执行，旧缓存应仍在").isEqualTo("0");
        Process recovery2 = startRecovery(dir, "s2-recovery");
        try {
            awaitUntil(() -> !cachePending(op2)
                    && projectionService.isApplied(event2)
                    && likeCountInDb() == before2 + 1, Duration.ofSeconds(120));
        } finally {
            killQuietly(recovery2);
        }
        assertThat(redisTemplate.hasKey(stateKey2)).as("重投触发的失效应删除旧缓存").isFalse();
        assertThat(interactionService.findPost(POST_ID, user2).liked()).isTrue();

        // 场景 3：失效后、确认前崩溃
        long before3 = likeCountInDb();
        String user3 = UUID.randomUUID().toString();
        String op3 = UUID.randomUUID().toString();
        InteractionEvent event3 = eventOf(op3, user3, InteractionType.LIKE);
        String stateKey3 = "interaction:like:" + POST_ID + ":" + user3;
        redisTemplate.opsForValue().set(stateKey3, "0");
        submitAndCrash(InteractionCrashPoint.AFTER_INVALIDATE, user3, op3, InteractionType.LIKE, dir, "s3");
        assertThat(projectionService.isApplied(event3)).isTrue();
        assertThat(likeRepository.existsById(new PostLikeId(user3, POST_ID))).isTrue();
        assertThat(likeCountInDb()).isEqualTo(before3 + 1);
        assertThat(redisTemplate.hasKey(stateKey3)).as("失效已执行").isFalse();
        assertThat(cachePending(op3)).isTrue();
        Process recovery3 = startRecovery(dir, "s3-recovery");
        try {
            awaitUntil(() -> !cachePending(op3)
                    && projectionService.isApplied(event3)
                    && likeCountInDb() == before3 + 1, Duration.ofSeconds(120));
        } finally {
            killQuietly(recovery3);
        }
        assertThat(interactionService.findPost(POST_ID, user3).liked()).isTrue();

        // 恢复父进程消费链路，供后续用例使用；测试 JVM 退出时停止，避免非守护线程拖住 fork。
        var replacement = interactionStreamConfig.newInteractionConsumer(projectionService, rocketmqNameserver);
        Runtime.getRuntime().addShutdownHook(new Thread(replacement::shutdown, "crash-consumer-shutdown"));
    }

    private InteractionEvent eventOf(String operationId, String userId, InteractionType type) {
        return new InteractionEvent(operationId, POST_ID, userId, type, System.currentTimeMillis());
    }

    private boolean cachePending(String operationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select cache_pending from interaction_operations where operation_id = ?", Boolean.class, operationId));
    }

    private void submitAndCrash(String stage, String user, String operation, InteractionType type,
                                Path dir, String tag) throws Exception {
        Path marker = dir.resolve(tag + ".crashed");
        Path ready = dir.resolve(tag + ".ready");
        Path log = dir.resolve(tag + ".log");
        Files.deleteIfExists(marker);
        Files.deleteIfExists(ready);
        Process process = startHarness(stage, operation, marker, ready, log);
        try {
            awaitHarnessReady(ready, log);
            interactionService.submit(POST_ID, user, operation, type);
            awaitHarnessReady(marker, log);
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("崩溃子进程未退出，日志 %s", log).isTrue();
            assertThat(process.exitValue()).isEqualTo(86);
        } finally {
            killQuietly(process);
        }
    }

    private Process startRecovery(Path dir, String tag) throws Exception {
        Path ready = dir.resolve(tag + ".ready");
        Path log = dir.resolve(tag + ".log");
        Files.deleteIfExists(ready);
        Process process = startHarness("", "", dir.resolve(tag + ".marker"), ready, log);
        awaitHarnessReady(ready, log);
        return process;
    }

    private Process startHarness(String stage, String operation, Path marker, Path ready, Path log) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // Windows 命令行有 32K 上限，完整测试 classpath 远超；用 @argfile 传递，命令本身只有 java + 文件路径。
        List<String> arguments = new java.util.ArrayList<>();
        arguments.add("-cp");
        arguments.add("\"" + System.getProperty("java.class.path").replace('\\', '/') + "\"");
        arguments.add("-Dcrash.stage=" + stage);
        arguments.add("-Dcrash.operation=" + operation);
        arguments.add("-Dcrash.marker=" + marker.toAbsolutePath());
        arguments.add("-Dcrash.ready=" + ready.toAbsolutePath());
        arguments.add("-Ddb.url=" + dbUrl);
        arguments.add("-Ddb.username=" + dbUsername);
        arguments.add("-Ddb.password=" + dbPassword);
        arguments.add("-Dredis.host=" + redisHost);
        arguments.add("-Dredis.port=" + redisPort);
        arguments.add("-Drocketmq.nameserver=" + rocketmqNameserver);
        arguments.add(com.kuros.crashharness.InteractionCrashHarness.class.getName());
        Path argsFile = log.resolveSibling(log.getFileName() + ".args");
        Files.writeString(argsFile, String.join(System.lineSeparator(), arguments));
        return new ProcessBuilder(java, "@" + argsFile.toAbsolutePath())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
    }

    private static void awaitHarnessReady(Path file, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(150).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(file)) {
                return;
            }
            Thread.sleep(250);
        }
        String tail = Files.exists(log) ? Files.readString(log) : "";
        throw new AssertionError("等待 " + file + " 超时；子进程日志尾部:\n"
                + tail.substring(Math.max(0, tail.length() - 4000)));
    }

    private static void killQuietly(Process process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor(30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
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
    @org.junit.jupiter.api.Order(21)
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
    @org.junit.jupiter.api.Order(22)
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
    @org.junit.jupiter.api.Order(30)
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
    @org.junit.jupiter.api.Order(31)
    void redis停止跨恢复周期后重启并恢复缓存() throws Exception {
        String user = UUID.randomUUID().toString();
        var event = new InteractionEvent(UUID.randomUUID().toString(), POST_ID, user, InteractionType.LIKE, System.currentTimeMillis());
        long before = likeCountInDb();
        interactionService.findPost(POST_ID, user);
        int port = redis.getMappedPort(6379);
        redis.getDockerClient().stopContainerCmd(redis.getContainerId()).exec();
        org.mockito.Mockito.clearInvocations(projectionService);
        try {
            assertThat(publisher.publish(event)).isEqualTo(com.kuros.kurosbackend.interaction.event.InteractionEventPublisher.Result.SENT);
            awaitUntil(() -> projectionService.isApplied(event), Duration.ofSeconds(60));
            org.mockito.Mockito.verify(projectionService, org.mockito.Mockito.timeout(75000).atLeast(2)).recoverPending();
            assertThat(projectionService.findOperation(event.eventId(), user).status()).isEqualTo("APPLIED");
            assertThat(likeRepository.existsById(new PostLikeId(user, POST_ID))).isTrue();
            assertThat(likeCountInDb()).isEqualTo(before + 1);
            assertThat(jdbc.queryForObject("select cache_pending from interaction_operations where operation_id = ?", Boolean.class, event.eventId())).isTrue();
        } finally {
            redis.getDockerClient().startContainerCmd(redis.getContainerId()).exec();
        }
        assertThat(redis.getMappedPort(6379)).isEqualTo(port);
        awaitUntil(() -> !jdbc.queryForObject("select cache_pending from interaction_operations where operation_id = ?", Boolean.class, event.eventId()), Duration.ofSeconds(75));
        assertThat(interactionService.findPost(POST_ID, user).liked()).isTrue();
    }

    @Test
    @org.junit.jupiter.api.Order(32)
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
    @org.junit.jupiter.api.Order(33)
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
