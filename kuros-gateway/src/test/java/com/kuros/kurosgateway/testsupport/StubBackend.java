package com.kuros.kurosgateway.testsupport;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 进程内桩后端：模拟 kuros-backend 接收网关转发的请求（Q1 决策的"桩服务器"缝）。
 *
 * 选择 JDK 自带 HttpServer 而非 WireMock/MockWebServer：零额外依赖、启动毫秒级，
 * 且只需记录"收到了什么"并原样回显，断言的是转发链路本身而非桩的行为。
 */
public final class StubBackend {

    public static final String STUB_JSON = "{\"source\":\"stub\",\"ok\":true}";
    public static final String STUB_HEADER = "X-Kuros-Stub";

    private HttpServer server;
    private volatile String lastMethod;
    private volatile String lastPath;
    private volatile String lastQuery;
    private volatile String lastCookie;
    private volatile String lastContentType;
    private volatile String lastBody;

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            lastMethod = exchange.getRequestMethod();
            lastPath = exchange.getRequestURI().getPath();
            lastQuery = exchange.getRequestURI().getRawQuery();
            lastCookie = exchange.getRequestHeaders().getFirst("Cookie");
            lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            lastBody = new String(requestBody, StandardCharsets.UTF_8);

            // POST 回显请求体（模拟上传/提交类接口），GET 返回固定 JSON
            boolean isPost = "POST".equalsIgnoreCase(lastMethod);
            byte[] responseBody = isPost ? requestBody : STUB_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", isPost ? "text/plain" : "application/json");
            exchange.getResponseHeaders().set(STUB_HEADER, "1");
            exchange.sendResponseHeaders(isPost ? 201 : 200, responseBody.length == 0 ? -1 : responseBody.length);
            if (responseBody.length > 0) {
                exchange.getResponseBody().write(responseBody);
            }
            exchange.close();
        });
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String lastMethod() {
        return lastMethod;
    }

    public String lastPath() {
        return lastPath;
    }

    public String lastQuery() {
        return lastQuery;
    }

    public String lastCookie() {
        return lastCookie;
    }

    public String lastContentType() {
        return lastContentType;
    }

    public String lastBody() {
        return lastBody;
    }

}
