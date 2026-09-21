package com.kuros.kurosbackend;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * kuros-user 内部 API 的 JDK HttpServer 桩（split-08 Feign 桩测试）。
 *
 * 为什么用 JDK 内置 HttpServer 而不是 WireMock/MockServer：
 * 内部契约面只有 3 个 GET（batch/following/followers），返回体是固定 JSON——
 * 用零依赖的 com.sun.net.httpserver 起一个真端口即可让 Feign 走完整 HTTP 编解码链路，
 * 既验证了"url 直连"配置生效，又不给 backend 测试引入新的重型测试依赖。
 *
 * 通过 {@code app.feign.kuros-user.url} 注入本桩地址，Feign 绕过 Nacos/LoadBalancer 直连，
 * 桩测试因此秒级启动（真实 lb 链路由 NacosFeignIntegrationTest 用 Testcontainers 覆盖）。
 *
 * 数据与 kuros-user 的 V2__user_seed.sql 逐字对齐：两侧种子漂移会让组合视图对不上
 * （种子文件的注释也显式声明了这条约束）。未命中的 id 按真实语义"静默跳过"，
 * 用来验证内容域对未知作者回退占位昵称"用户"。
 */
public final class UserDirectoryStub implements AutoCloseable {

    /** 4 个种子用户：id → {nickname, avatarUrl(null), bio}，与 kuros-user V2__user_seed.sql 一致。 */
    private static final Map<String, String[]> SEED_USERS = seedUsers();

    private static Map<String, String[]> seedUsers() {
        Map<String, String[]> users = new LinkedHashMap<>();
        users.put("10000000-0000-0000-0000-000000000001",
                new String[]{"潮声档案员", null, "记录版本变化，也记录每一次实战尝试。"});
        users.put("10000000-0000-0000-0000-000000000002",
                new String[]{"无音区夜行者", null, "把复杂机制拆成可以直接练习的步骤。"});
        users.put("10000000-0000-0000-0000-000000000003",
                new String[]{"今汐的留声机", null, "整理角色与声骸之间的配合思路。"});
        users.put("10000000-0000-0000-0000-000000000004",
                new String[]{"漂泊者手册", null, "给刚刚踏上旅途的漂泊者一些方向。"});
        return users;
    }

    private final HttpServer server;
    // 显式持有 executor 引用：HttpServer.stop() 按 JDK 契约不会关闭调用方自带的 executor，
    // 必须在 close() 里 shutdownNow，否则残留线程会阻止 Surefire fork 的 JVM 退出（构建末尾挂起）
    private final ExecutorService executor;
    /** 健康开关：置 false 时所有接口返回 500，用来模拟 kuros-user 整体不可用（触发降级）。 */
    private final AtomicBoolean healthy = new AtomicBoolean(true);

    public UserDirectoryStub() {
        try {
            // 端口 0 = 由 OS 分配空闲端口，避免测试并行时固定端口冲突
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("无法启动 kuros-user 桩服务器", e);
        }
        // 守护线程：即便某个测试类漏调 close()，也不会因非守护线程残留而挂住 JVM 退出（双保险）
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "user-directory-stub");
            thread.setDaemon(true);
            return thread;
        });
        // /batch 比 /users/ 更长，HttpServer 按最长前缀匹配，二者互不干扰
        server.createContext("/internal/v1/users/batch", this::handleBatch);
        server.createContext("/internal/v1/users/", this::handleFollow);
        server.setExecutor(executor);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    public void setHealthy(boolean value) {
        healthy.set(value);
    }

    @Override
    public void close() {
        server.stop(0);
        // stop() 不关自带 executor，必须显式 shutdownNow 释放线程
        executor.shutdownNow();
    }

    /** GET /internal/v1/users/batch?ids=a&ids=b：按输入顺序返回命中项，未知 id 跳过。 */
    private void handleBatch(HttpExchange exchange) throws IOException {
        if (rejectIfUnhealthy(exchange)) {
            return;
        }
        List<String> ids = parseIds(exchange.getRequestURI().getRawQuery());
        StringBuilder data = new StringBuilder("[");
        boolean first = true;
        for (String id : ids) {
            String[] user = SEED_USERS.get(id);
            if (user == null) {
                continue; // 未知 id 静默跳过（与 InternalUserService.findBriefs 同语义）
            }
            if (!first) {
                data.append(',');
            }
            data.append(briefJson(id, user));
            first = false;
        }
        data.append(']');
        writeJson(exchange, 200, "{\"data\":" + data + "}");
    }

    /**
     * GET /internal/v1/users/{id}/following|followers：返回固定的关注/粉丝样本。
     * following 恒含 ...0002、followers 恒含 ...0003——让 findOwn 的分页断言确定化，
     * 且这两个 id 都是种子用户，本地能查到其帖子统计，正好验证"跨服务用户 + 本地内容"的组合。
     */
    private void handleFollow(HttpExchange exchange) throws IOException {
        if (rejectIfUnhealthy(exchange)) {
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String id = path.endsWith("/followers")
                ? "10000000-0000-0000-0000-000000000003"
                : "10000000-0000-0000-0000-000000000002";
        String[] user = SEED_USERS.get(id);
        String meta = "{\"page\":1,\"pageSize\":20,\"totalItems\":1,\"totalPages\":1}";
        writeJson(exchange, 200, "{\"data\":[" + briefJson(id, user) + "],\"meta\":" + meta + "}");
    }

    private boolean rejectIfUnhealthy(HttpExchange exchange) throws IOException {
        if (healthy.get()) {
            return false;
        }
        // 500 让 Feign 默认 ErrorDecoder 抛 FeignException → 门面据此走降级/503
        writeJson(exchange, 500, "{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"stub down\"}");
        return true;
    }

    private List<String> parseIds(String rawQuery) {
        List<String> ids = new ArrayList<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return ids;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (!"ids".equals(key)) {
                continue;
            }
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            // Feign 把 List 编码为重复参数 ids=a&ids=b；同时兼容逗号写法 ids=a,b
            for (String one : value.split(",")) {
                if (!one.isBlank()) {
                    ids.add(one.trim());
                }
            }
        }
        return ids;
    }

    private String briefJson(String id, String[] user) {
        return "{\"id\":\"" + escape(id) + "\","
                + "\"nickname\":\"" + escape(user[0]) + "\","
                + "\"avatarUrl\":" + (user[1] == null ? "null" : "\"" + escape(user[1]) + "\"") + ","
                + "\"bio\":" + (user[2] == null ? "null" : "\"" + escape(user[2]) + "\"") + "}";
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
