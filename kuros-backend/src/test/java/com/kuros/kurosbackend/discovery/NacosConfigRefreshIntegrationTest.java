package com.kuros.kurosbackend.discovery;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.shared.config.SpringDocStatusBridge;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
        // config data 解析阶段（spring.config.import）就要读到测试容器地址，
        // 但 @SpringBootTest 内联 properties 在同 JVM 先跑过其他 Spring 测试后实测失效
        // （客户端回退 application.properties 的 ${NACOS_SERVER_ADDR:localhost:8848}，
        // 而 compose 恰好跑在 8848——拉取结果为空后静默用本地默认值）。
        // 系统属性为 JVM 级来源，在所有 environment 处理阶段均可见，覆盖默认值。
        System.setProperty("spring.cloud.nacos.server-addr", NacosContainers.SERVER_ADDR);
        System.setProperty("spring.cloud.nacos.config.server-addr", NacosContainers.SERVER_ADDR);
        nacos.start();
        redis.start();
        configService = NacosFactory.createConfigService(NacosContainers.SERVER_ADDR);
        // 播种初始覆盖值：阈值 555（本地默认 100）、springdoc 关闭（本地默认 true）。
        // 容器 HTTP 就绪 ≠ gRPC 就绪，而 Spring 的 config import 是一次性拉取
        // （optional 失败即静默回退本地默认值）——必须先证明配置通道可用再放行 context，
        // 否则全量跑高负载下会偶发拉取扑空。
        seedConfigWhenChannelReady();
    }

    /** publish + 读回校验，直到 gRPC 配置通道真正可用（最长 60s）。 */
    private static void seedConfigWhenChannelReady() throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                configService.publishConfig(DATA_ID, GROUP,
                        "app.sentinel.posts-list-qps=555\nspringdoc.api-docs.enabled=false\n");
                String readBack = configService.getConfig(DATA_ID, GROUP, 5000);
                if (readBack != null && readBack.contains("555")) {
                    return;
                }
                lastFailure = new IllegalStateException("配置读回为空或不含 555: " + readBack);
            } catch (Exception exception) {
                lastFailure = exception;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Nacos 配置通道 60s 内未就绪，种子配置写入失败", lastFailure);
    }

    @AfterAll
    static void stopContainers() {
        nacos.stop();
        redis.stop();
        // 清理系统属性，避免污染同 JVM 后续测试类的 Nacos 地址解析
        System.clearProperty("spring.cloud.nacos.server-addr");
        System.clearProperty("spring.cloud.nacos.config.server-addr");
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // 唯一 H2 库名：库生命周期与本类 context 对齐（详见 TestDatabases 注释）
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("nacos-config"));
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
