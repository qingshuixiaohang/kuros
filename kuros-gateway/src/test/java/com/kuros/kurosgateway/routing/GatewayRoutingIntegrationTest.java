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
 * 纯路由转发集成测试（切片 #9 / gateway-02；split-06 扩为双桩）。
 *
 * 主接缝：真实网关上下文 + 进程内桩后端，断言请求经网关后"原样透传"——
 * 路径、查询串、Cookie、请求体、Content-Type 原样到达桩；
 * 桩的状态码、body、Content-Type 原样返回给客户端。
 *
 * split-06 起网关有两条路由：/api/v1/auth/** → kuros-user（优先），
 * /** → kuros-backend；split-07 新增第三条：/api/v1/users/{id}/follow → kuros-user。
 * 两个桩同时在场，用响应头 X-Kuros-Stub 的桩名
 * 判定"本次请求实际命中哪个服务"，锁定优先级不回归。
 *
 * 不起 Nacos 容器（Q1 决策：桩服务器缝，秒级反馈）：路由 uri 覆盖为桩地址，
 * 绕开 lb:// 与服务发现。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayRoutingIntegrationTest {

    // static 块启动：保证 @DynamicPropertySource 的 lazy supplier 求值时端口已确定
    static final StubBackend stub = new StubBackend();
    static final StubBackend userStub = new StubBackend("user");

    static {
        stub.start();
        userStub.start();
    }

    @AfterAll
    static void stopStub() {
        stub.stop();
        userStub.stop();
    }

    @DynamicPropertySource
    static void routingProperties(DynamicPropertyRegistry registry) {
        // 覆盖 GatewayRoutesConfig 中的 lb:// 目标：纯转发测试不依赖服务发现
        registry.add("app.routes.backend-uri", () -> "http://127.0.0.1:" + stub.port());
        registry.add("app.routes.user-uri", () -> "http://127.0.0.1:" + userStub.port());
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
        // 响应头桩名 = 命中方证据（split-06 双桩）：非认证路径仍走 backend 通配路由
        assertEquals("backend", response.headers().firstValue(StubBackend.STUB_HEADER).orElse(""),
                "非认证路径应命中 backend 桩");
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
        assertEquals("backend", response.headers().firstValue(StubBackend.STUB_HEADER).orElse(""),
                "非认证路径应命中 backend 桩");
        assertEquals(payload, response.body(), "回显 body 应原样返回");
        assertEquals("POST", stub.lastMethod());
        assertEquals("text/plain", stub.lastContentType(), "请求 Content-Type 应透传");
        assertEquals(payload, stub.lastBody(), "请求体应原样转发");
    }

    @Test
    void 认证路径优先路由到用户服务而非后端通配路由() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(gatewayBase() + "/api/v1/auth/me"))
                .header("Cookie", "KUROS_SESSION=user-side-session")
                .GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        // 双桩同场：响应头的桩名是"请求实际命中哪条路由"的顺序无关证据——
        // 若 kuros-user-auth 路由缺失或晚于 /** 匹配，这里会拿到 "backend"
        assertEquals("user", response.headers().firstValue(StubBackend.STUB_HEADER).orElse(""),
                "认证路径必须命中用户服务路由（优先于 /** 通配）");
        assertEquals("/api/v1/auth/me", userStub.lastPath(), "认证路径应原样透传到用户服务");
        assertEquals("KUROS_SESSION=user-side-session", userStub.lastCookie(), "会话 Cookie 应透传");
    }

    @Test
    void 关注路径优先路由到用户服务而非后端通配路由() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        String followPath = "/api/v1/users/10000000-0000-0000-0000-000000000002/follow";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(gatewayBase() + followPath))
                .header("Cookie", "KUROS_SESSION=user-side-session")
                .GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        // split-07：关注端点自 backend 迁至 kuros-user——若 kuros-user-follow 路由缺失，
        // 请求会落到 /** 通配的 backend 桩（那边窗口期已是 404 无 handler），
        // 这里拿到 "user" 即为命中新路由的证据
        assertEquals("user", response.headers().firstValue(StubBackend.STUB_HEADER).orElse(""),
                "关注路径必须命中用户服务路由（优先于 /** 通配）");
        assertEquals(followPath, userStub.lastPath(), "关注路径应原样透传到用户服务");
        assertEquals("KUROS_SESSION=user-side-session", userStub.lastCookie(), "会话 Cookie 应透传");
    }

}
