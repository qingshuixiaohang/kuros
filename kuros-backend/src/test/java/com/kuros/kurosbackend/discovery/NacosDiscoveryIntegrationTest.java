package com.kuros.kurosbackend.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nacos 服务发现集成测试（切片 #8 / nacos-01）。
 *
 * 主接缝：真实 Spring 上下文（RANDOM_PORT 触发 WebServerInitializedEvent，
 * 这是 NacosAutoServiceRegistration 的注册时机；MOCK 环境不会启动真实
 * Tomcat，注册永远不会发生）+ Testcontainers 起的真实 Nacos v3 服务端。
 *
 * 断言的是外部可见状态：通过 Nacos 的 client OpenAPI 查询实例列表，
 * 而不是注入 Spring 内部的注册器对象。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class NacosDiscoveryIntegrationTest {

    // 容器构造细节（鉴权三件套/固定端口/版本对齐）见 NacosContainers
    @Container
    static GenericContainer<?> nacos = NacosContainers.newNacos();

    // 全上下文测试还需要 Redis（SaToken 依赖），与 KurosBackendApplicationTests 同款
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void nacosProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // test profile 默认禁用 Nacos，这里仅对集成测试显式开启 discovery；
        // 用 127.0.0.1 而非 localhost 的原因见 NacosContainers（IPv6 解析问题）
        registry.add("spring.cloud.nacos.server-addr", () -> NacosContainers.SERVER_ADDR);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "true");
    }

    @Test
    void 应用启动后自动注册为Nacos实例且健康状态为true() throws Exception {
        // client OpenAPI 无需鉴权即可查询（实测 v3 行为），按服务名过滤，
        // data 数组非空且 healthy=true 即证明注册链路端到端打通
        String url = "http://" + NacosContainers.SERVER_ADDR
                + "/nacos/v3/client/ns/instance/list?serviceName=kuros-backend"
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
        throw new AssertionError("等待 20s 仍未在 Nacos 中发现 kuros-backend 的健康实例，最后一次应答: " + body);
    }

}
