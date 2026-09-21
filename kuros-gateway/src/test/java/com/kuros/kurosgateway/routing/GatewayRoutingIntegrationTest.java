package com.kuros.kurosgateway.routing;

import com.kuros.kurosgateway.testsupport.StubBackend;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 纯路由转发集成测试（切片 #9 / gateway-02）。
 *
 * 主接缝：真实网关上下文 + 进程内桩后端，断言请求经网关后"原样透传"——
 * 路径、查询串、Cookie、请求体、Content-Type 原样到达桩；
 * 桩的状态码、body、Content-Type 原样返回给客户端。
 *
 * 不起 Nacos 容器（Q1 决策：桩服务器缝，秒级反馈）：路由 uri 覆盖为桩地址，
 * 绕开 lb:// 与服务发现。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayRoutingIntegrationTest {

    // static 块启动：保证 @DynamicPropertySource 的 lazy supplier 求值时端口已确定
    static final StubBackend stub = new StubBackend();

    static {
        stub.start();
    }

    @AfterAll
    static void stopStub() {
        stub.stop();
    }

    @DynamicPropertySource
    static void routingProperties(DynamicPropertyRegistry registry) {
        // 覆盖 GatewayRoutesConfig 中的 lb://kuros-backend：纯转发测试不依赖服务发现
        registry.add("app.routes.backend-uri", () -> "http://127.0.0.1:" + stub.port());
    }

    @Autowired
    private Environment environment;

    private String gatewayBase() {
        return "http://127.0.0.1:" + environment.getProperty("local.server.port");
    }

    @Test
    void get请求经网关原样转发到后端且响应原样返回() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(gatewayBase() + "/api/v1/posts/123?pageSize=10&sort=hot"))
                .header("Cookie", "KUROS_SESSION=abc123")
                .GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(StubBackend.STUB_JSON, response.body(), "桩响应 body 应原样返回");
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""),
                "桩响应 Content-Type 应原样返回");
        assertEquals("/api/v1/posts/123", stub.lastPath(), "路径应原样透传（纯换门，无改写）");
        assertEquals("pageSize=10&sort=hot", stub.lastQuery(), "查询串应原样透传");
        assertEquals("KUROS_SESSION=abc123", stub.lastCookie(), "SaToken 会话 Cookie 应透传");
    }

    @Test
    void post请求体经网关原样转发且状态码透传() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        String payload = "stub-payload-123";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(gatewayBase() + "/api/v1/files/images"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(201, response.statusCode(), "桩的状态码应原样透传");
        assertEquals(payload, response.body(), "回显 body 应原样返回");
        assertEquals("POST", stub.lastMethod());
        assertEquals("text/plain", stub.lastContentType(), "请求 Content-Type 应透传");
        assertEquals(payload, stub.lastBody(), "请求体应原样转发");
    }

}
