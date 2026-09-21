package com.kuros.kurosuser.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户服务骨架集成测试（切片 #10 / split-05）：本服务"能启动、能注册、能迁移"的验收编码化。
 *
 * 主接缝：真实 Spring 上下文（RANDOM_PORT 触发 WebServerInitializedEvent，即 Nacos
 * 自动注册的时机）+ Testcontainers 起的真实 Nacos v3 与 Redis 容器。
 *
 * Redis 容器的必要性说明：user 是第一个带 SaToken/Redis 的独立服务，
 * /actuator/health 聚合了 RedisHealthIndicator——没有真实 Redis 时 health 直接 DOWN，
 * 而 health=UP 正是 compose depends_on 与部署验收门控依赖的信号。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class UserNacosDiscoveryIntegrationTest {

    // RANDOM_PORT 下由 Boot 写入的属性，注入 Environment 读取可规避
    // @LocalServerPort 在 Boot 4 模块化后的包名变动（与 gateway 测试同款写法）
    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 容器构造细节（鉴权三件套/固定端口/版本对齐）见 NacosContainers
    @Container
    static GenericContainer<?> nacos = NacosContainers.newNacos();

    // 与 backend 测试同款：redis:7-alpine，随机端口映射即可（无 gRPC 偏移量问题）
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        // 用 127.0.0.1 而非 localhost 的原因见 NacosContainers（IPv6 解析问题）
        registry.add("spring.cloud.nacos.server-addr", () -> NacosContainers.SERVER_ADDR);
        // test profile 默认禁用 Nacos，这里仅对集成测试显式开启 discovery
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "true");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Test
    void 服务启动后自动注册为kuros_user实例且健康状态为true() throws Exception {
        // client OpenAPI 无需鉴权即可查询（实测 v3 行为），按服务名过滤，
        // data 数组非空且 healthy=true 即证明注册链路端到端打通
        String url = "http://" + NacosContainers.SERVER_ADDR
                + "/nacos/v3/client/ns/instance/list?serviceName=kuros-user"
                + "&groupName=DEFAULT_GROUP&namespaceId=public";
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();

        String body = "";
        for (int i = 0; i < 40; i++) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            body = response.body();
            if (body.contains("\"healthy\":true")) {
                assertTrue(body.contains("\"ip\""), "实例应包含注册 IP: " + body);
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("等待 20s 仍未在 Nacos 中发现 kuros-user 的健康实例，最后一次应答: " + body);
    }

    @Test
    void health端点返回200且状态为UP() throws Exception {
        // 探的正是 compose healthcheck 与 CI 部署验收用的同一入口
        HttpResponse<String> response = get("/actuator/health");
        assertEquals(200, response.statusCode(), "health 应可访问: " + response.body());
        assertTrue(response.body().contains("\"status\":\"UP\""), "health 应为 UP: " + response.body());
    }

    @Test
    void prometheus端点返回200() throws Exception {
        // 缺 micrometer-registry-prometheus 时该端点 404（切片 #7 教训），
        // 断言 200 + 指标正文出现即证明采集入口可用
        HttpResponse<String> response = get("/actuator/prometheus");
        assertEquals(200, response.statusCode(), "prometheus 应可访问: " + response.body());
        assertTrue(response.body().contains("# HELP"), "应返回 Prometheus 指标正文");
    }

    @Test
    void flyway迁移后用户种子与backend同源() {
        // V1/V2 在真实 Flyway 流程下执行后的外部可见状态：4 条种子，
        // user1 的 ID/手机号/角色与 backend V2/V6 逐字一致（split-06 迁移的前提）
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        assertEquals(4, count, "V2 种子应插入 4 个用户");
        String phone = jdbcTemplate.queryForObject(
                "SELECT phone FROM users WHERE id = ? AND role = 'ADMIN'",
                String.class, "10000000-0000-0000-0000-000000000001");
        assertEquals("13800000001", phone, "user1 应为 ADMIN 且手机号与 backend 相同");
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        int port = Integer.parseInt(environment.getProperty("local.server.port"));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

}
