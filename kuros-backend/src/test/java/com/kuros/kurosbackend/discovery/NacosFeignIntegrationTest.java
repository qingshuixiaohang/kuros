package com.kuros.kurosbackend.discovery;

import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.UserDirectoryStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nacos 真实链路 Feign 集成测试（split-08，工单 #4）。
 *
 * 与 KurosBackendApplicationTests 里的桩测试互补：
 * - 桩测试用 {@code app.feign.kuros-user.url} 直连 JDK HttpServer，绕过服务发现，秒级验证编解码/降级
 * - 本测试把同一个桩注册进真实 Nacos（服务名 kuros-user），url 留空 → Feign 走
 *   {@code lb://kuros-user} 经 Spring Cloud LoadBalancer + NacosDiscoveryClient 解析实例，
 *   端到端验证"生产形态"的服务发现链路（这是 url 直连覆盖不到的那一段）
 *
 * 为什么用 NamingService 客户端注册而非 HTTP OpenAPI：ephemeral 实例需要持续心跳，
 * NamingService.registerInstance 自带心跳线程，与框架自身注册 backend 用的是同一套客户端，最贴近真实。
 *
 * register-enabled=false：本测试只需 Feign 作为"消费方"发现 kuros-user，
 * 无需 backend 把自己注册进去（MOCK web 环境本就不触发自动注册，这里再显式关闭以杜绝干扰）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NacosFeignIntegrationTest {

    private static final String SEED_USER_ID = "10000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Container
    static GenericContainer<?> nacos = NacosContainers.newNacos();

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    // 复用桩测试同一个 JDK HttpServer 桩：这里把它注册进 Nacos 当作真实的 kuros-user 实例
    static final UserDirectoryStub stub = new UserDirectoryStub();

    private static NamingService namingService;

    @DynamicPropertySource
    static void nacosProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.cloud.nacos.server-addr", () -> NacosContainers.SERVER_ADDR);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "true");
        registry.add("spring.cloud.nacos.discovery.register-enabled", () -> "false");
        // 关键：url 留空 → Feign 走 lb://kuros-user（经 Nacos 发现），而非直连桩
        registry.add("app.feign.kuros-user.url", () -> "");
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("nacos-feign"));
    }

    @BeforeAll
    static void registerStubAsKurosUser() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("serverAddr", NacosContainers.SERVER_ADDR);
        namingService = NamingFactory.createNamingService(properties);
        // 把桩注册为 kuros-user 的 ephemeral 实例（127.0.0.1:随机端口），NamingService 自动维持心跳
        namingService.registerInstance("kuros-user", "127.0.0.1", stub.port());
    }

    @AfterAll
    static void shutdown() throws Exception {
        if (namingService != null) {
            namingService.shutDown();
        }
        stub.close();
    }

    @Test
    void Feign经Nacos发现kurosUser并回填真实昵称() throws Exception {
        // 服务发现的实例列表在客户端/LoadBalancer 侧有缓存与推送延迟，注册后首次解析可能尚未就绪；
        // 用重试轮询等待链路打通（未就绪时资料页因 requireUser 失败返回 503，就绪后转 200）
        AssertionError last = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                mockMvc.perform(get("/api/v1/users/" + SEED_USER_ID))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.nickname").value("潮声档案员"))
                        .andExpect(jsonPath("$.data.postCount").value(1));
                return;
            } catch (AssertionError | RuntimeException e) {
                last = e instanceof AssertionError ae ? ae : new AssertionError(e.getMessage(), e);
                Thread.sleep(500);
            }
        }
        throw new AssertionError("等待 20s 仍未通过 Nacos 发现 kuros-user 回填昵称（lb 链路未打通）", last);
    }
}
