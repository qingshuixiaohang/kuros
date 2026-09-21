package com.kuros.kurosgateway.discovery;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
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
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nacos 服务发现集成测试（切片 #9 / gateway-01、gateway-02）。
 *
 * 主接缝：真实 Spring 上下文（RANDOM_PORT 触发 WebServerInitializedEvent，
 * 这是 NacosAutoServiceRegistration 的注册时机）+ Testcontainers 起的真实 Nacos v3 服务端。
 *
 * 断言的是外部可见状态：
 * 1. 网关自身注册为 kuros-gateway 的 healthy 实例（通过 client OpenAPI 查询）；
 * 2. 桩后端实例手动注册进 Nacos 后，lb://kuros-backend 路由经服务发现转发成功。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class GatewayNacosDiscoveryIntegrationTest {

    // RANDOM_PORT 下由 Boot 写入的属性，注入 Environment 读取可规避
    // @LocalServerPort 在 Boot 4 模块化后的包名变动
    @Autowired
    private Environment environment;

    // 容器构造细节（鉴权三件套/固定端口/版本对齐）见 NacosContainers
    @Container
    static GenericContainer<?> nacos = NacosContainers.newNacos();

    @DynamicPropertySource
    static void nacosProperties(DynamicPropertyRegistry registry) {
        // 用 127.0.0.1 而非 localhost 的原因见 NacosContainers（IPv6 解析问题）
        registry.add("spring.cloud.nacos.server-addr", () -> NacosContainers.SERVER_ADDR);
        // test profile 默认禁用 Nacos，这里仅对集成测试显式开启 discovery
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "true");
    }

    @Test
    void 网关启动后自动注册为kuros_gateway实例且健康状态为true() throws Exception {
        // client OpenAPI 无需鉴权即可查询（实测 v3 行为），按服务名过滤，
        // data 数组非空且 healthy=true 即证明注册链路端到端打通
        String url = "http://" + NacosContainers.SERVER_ADDR
                + "/nacos/v3/client/ns/instance/list?serviceName=kuros-gateway"
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
        throw new AssertionError("等待 20s 仍未在 Nacos 中发现 kuros-gateway 的健康实例，最后一次应答: " + body);
    }

    @Test
    void lb路由经服务发现转发到注册的桩后端() throws Exception {
        StubBackend stub = new StubBackend();
        stub.start();

        // 用 nacos-client 把桩实例注册为 kuros-backend（模拟 backend 的注册行为），
        // 网关的 lb://kuros-backend 路由应通过 NacosDiscoveryClient 发现它
        Properties properties = new Properties();
        properties.setProperty("serverAddr", NacosContainers.SERVER_ADDR);
        NamingService naming = NacosFactory.createNamingService(properties);
        naming.registerInstance("kuros-backend", "127.0.0.1", stub.port());

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            int gatewayPort = Integer.parseInt(environment.getProperty("local.server.port"));
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + gatewayPort + "/api/v1/lb-probe?from=lb-test"))
                    .GET().build();

            HttpResponse<String> response = null;
            for (int i = 0; i < 40; i++) {
                try {
                    response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) break;
                } catch (java.io.IOException e) {
                    // lb 尚未发现实例时网关返回 503/连接拒绝（ConnectException
                    // 是 IOException 子类，单 catch 已覆盖），重试即可
                }
                Thread.sleep(500);
            }
            assertEquals(200, response != null ? response.statusCode() : -1,
                    "lb 路由应转发到注册的桩实例");
            assertEquals(StubBackend.STUB_JSON, response.body(), "桩响应应原样返回");
            assertEquals("/api/v1/lb-probe", stub.lastPath(), "桩收到的路径应原样透传");
            assertEquals("from=lb-test", stub.lastQuery(), "查询串应原样透传");
        } finally {
            naming.deregisterInstance("kuros-backend", "127.0.0.1", stub.port());
            stub.stop();
        }
    }

}
