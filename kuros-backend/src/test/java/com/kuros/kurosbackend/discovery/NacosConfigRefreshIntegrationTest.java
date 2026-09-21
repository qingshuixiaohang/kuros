package com.kuros.kurosbackend.discovery;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.kuros.kurosbackend.config.SpringDocStatusBridge;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nacos 配置中心集成测试（切片 #8 / nacos-02）。
 *
 * 验证两条外部可见行为：
 * 1. 分层覆盖：Nacos 预置的 dataId 内容在启动时覆盖本地 application.properties 默认值
 * 2. 动态刷新：运行期发布新配置 → 不重启应用，限流阈值与桥接状态变化
 *
 * 测试 profile 默认禁用 Nacos 与 Sentinel，这里用 @SpringBootTest 内联属性
 * 显式开启 —— 内联属性会在 config data 解析期生效，@DynamicPropertySource 不行
 * （它注册的属性源晚于 spring.config.import 的解析时机）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.server-addr=" + NacosContainers.SERVER_ADDR,
        "spring.cloud.nacos.config.enabled=true",
        "app.sentinel.enabled=true"
})
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NacosConfigRefreshIntegrationTest {

    /** 默认 dataId 约定：${spring.application.name}.${file-extension} */
    private static final String DATA_ID = "kuros-backend.properties";
    private static final String GROUP = "DEFAULT_GROUP";

    // 不能用 @Container：播种配置必须发生在 Spring 上下文启动之前
    // （config data import 在上下文启动时一次性拉取），
    // 手动在 @BeforeAll 启动才能保证时序
    static GenericContainer<?> nacos = NacosContainers.newNacos();
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static ConfigService configService;

    @BeforeAll
    static void startNacosAndSeedConfig() throws Exception {
        nacos.start();
        redis.start();
        configService = NacosFactory.createConfigService(NacosContainers.SERVER_ADDR);
        // 播种初始覆盖值：阈值 555（本地默认 100）、springdoc 关闭（本地默认 true）
        configService.publishConfig(DATA_ID, GROUP,
                "app.sentinel.posts-list-qps=555\nspringdoc.api-docs.enabled=false\n");
    }

    @AfterAll
    static void stopContainers() {
        nacos.stop();
        redis.stop();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    SpringDocStatusBridge.ApiDocsStatus apiDocsStatus;

    private static FlowRule postListRule() {
        return FlowRuleManager.getRules().stream()
                .filter(rule -> "api-posts-list".equals(rule.getResource()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("api-posts-list 规则未注册"));
    }

    @Test
    @Order(1)
    void Nacos预置配置在启动时覆盖本地默认值() {
        // 覆盖生效的证明：阈值是 Nacos 里的 555 而非本地默认 100
        assertEquals(555, postListRule().getCount(),
                "启动时应读到 Nacos 覆盖值，实际: " + postListRule().getCount());
        assertFalse(apiDocsStatus.isApiDocsEnabled(), "springdoc 开关应被 Nacos 覆盖为 false");
    }

    @Test
    @Order(2)
    void 发布新配置后不重启应用即生效() throws Exception {
        configService.publishConfig(DATA_ID, GROUP,
                "app.sentinel.posts-list-qps=999\nspringdoc.api-docs.enabled=true\n");

        // eager 路径：长轮询推送通常秒级到达，事件监听器重读 Environment 重新 loadRules；
        // 给 30s 容忍窗口，超时后取现值断言（失败信息里带实际值方便排查）
        FlowRule rule = postListRule();
        for (int i = 0; i < 60 && rule.getCount() != 999; i++) {
            Thread.sleep(500);
            rule = postListRule();
        }
        assertEquals(999, rule.getCount(),
                "运行期改配置后限流阈值应变为 999，实际: " + rule.getCount());

        // lazy 路径：@RefreshScope 桥接 bean 下次访问时重建为最新值
        assertTrue(apiDocsStatus.isApiDocsEnabled(), "桥接 bean 应在刷新后观察到新开关状态");
    }

}
