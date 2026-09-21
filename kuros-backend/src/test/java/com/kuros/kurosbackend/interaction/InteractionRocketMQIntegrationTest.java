package com.kuros.kurosbackend.interaction;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.interaction.api.PostInteractionResponse;
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
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 互动异步落库 RocketMQ 端到端集成测试（切片 #11）：真实 namesrv + broker 容器，验证
 * 「点赞 → 发顺序消息 → 消费端幂等落库 → DB 最终一致」全链路，以及同一帖 LIKE→UNLIKE 的顺序终态。
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
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("interaction-rocketmq"));
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

        // 写路径即时返回 Redis 实时值（此刻 DB 尚未落库）
        PostInteractionResponse resp = interactionService.like(POST_ID, userId);
        assertThat(resp.liked()).isTrue();
        assertThat(resp.likeCount()).isEqualTo(before + 1);

        // 轮询等待真实 broker 传输 + 顺序消费 → DB 最终一致
        awaitUntil(() -> likeRepository.existsById(new PostLikeId(userId, POST_ID))
                && likeCountInDb() == before + 1, Duration.ofSeconds(60));
    }

    @Test
    void 同一帖点赞与取消经顺序消费后终态回到基线() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        long before = likeCountInDb();
        String userId = UUID.randomUUID().toString();

        interactionService.like(POST_ID, userId);
        interactionService.unlike(POST_ID, userId);

        // 顺序消费（同 postId 落同队列串行）保证 LIKE 先于 UNLIKE 生效 → 终态无关系行、计数回基线。
        // 若并发乱序（UNLIKE 先于 LIKE 到达）则终态会残留一行 / 计数停在 before+1，此断言即红。
        awaitUntil(() -> !likeRepository.existsById(new PostLikeId(userId, POST_ID))
                && likeCountInDb() == before, Duration.ofSeconds(60));
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
