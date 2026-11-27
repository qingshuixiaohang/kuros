package com.kuros.kurosbackend.interaction;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.kuros.kurosbackend.interaction.domain.PostLikeId;
import com.kuros.kurosbackend.interaction.repository.PostLikeRepository;
import com.kuros.kurosbackend.interaction.service.PostInteractionService;
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
        registry.add("spring.datasource.url", () -> "jdbc:mysql://" + mysql.getHost() + ":"
                + mysql.getMappedPort(3306) + "/kuros_test?characterEncoding=UTF-8&serverTimezone=UTC");
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "test-only-password");
        // 打开异步链路（覆盖 test profile 的 async.enabled=false / 空 function.definition）：
        // @DynamicPropertySource 优先级高于 application-test.properties，故此处生效
        registry.add("app.interaction.async.enabled", () -> "true");
        registry.add("spring.cloud.function.definition", () -> "interactionConsumer");
        registry.add("spring.cloud.stream.rocketmq.binder.name-server",
                () -> namesrv.getHost() + ":" + namesrv.getMappedPort(NAMESRV_PORT));
    }

    @Autowired
    private PostInteractionService interactionService;
    @Autowired
    private PostLikeRepository likeRepository;
    @Autowired
    private CommunityPostRepository postRepository;
    @Autowired
    private StringRedisTemplate redisTemplate;

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
