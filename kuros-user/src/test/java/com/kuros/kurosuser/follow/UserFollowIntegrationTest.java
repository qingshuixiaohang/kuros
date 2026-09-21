package com.kuros.kurosuser.follow;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasSize;

/**
 * 关注链路 + 内部 API 集成测试（split-07 验收编码化）。
 *
 * 覆盖两条缝：
 * 1. 公开关注链路（/api/v1/users/{id}/follow，经网关路由到本服务）：
 *    幂等关注/取关、自关注拒绝、目标不存在 404、并发防重复；
 * 2. 内部 API（/internal/v1/users/**，不经网关、供 split-08 的 backend Feign 消费）：
 *    批量用户查询保序跳过缺失、关注状态/计数、关注与粉丝列表分页。
 *
 * 为什么用真实 MySQL 而不是 test profile 的 H2：
 * 1. 并发防重复的验证根基是"复合主键 + 分布式锁"在真实引擎上的行为——
 *    H2 的重复键异常类型与事务隔离语义和 MySQL 并不等价，锁竞争路径会失真；
 * 2. Flyway 真实方言执行、CHECK 约束（自关注）在真实引擎上被验证
 *    （与 AuthLoginIntegrationTest 的同类结论一致）。
 *
 * 为什么内部 API 与关注链路合并为一个测试类：
 * 每个测试类都要独起一套 MySQL + Redis 容器（surefire reuseForks=false 独立 JVM），
 * 合并共享容器可把 CI 上的容器冷启动成本减半；两者同属关注域的服务缝，内聚成立。
 *
 * CSRF 处理与 backend 测试同一约定：CsrfInterceptor 只校验 cookie == header
 * （不校验服务端存储），所以固定值即可；auth 路径的写请求刻意不带 CSRF
 * （与前端 api.ts 行为一致，拦截器排除规则失效时此处会红而非静默通过）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class UserFollowIntegrationTest {

    private static final String MYSQL_ROOT_PASSWORD = "kuros-test-root";

    // wait 用端口监听而非日志：MySQL entrypoint 初始化阶段会先起 skip-networking 的
    // 临时服务器（compose healthcheck 的同类教训），TCP 监听成立意味着最终服务器就绪
    @Container
    static GenericContainer<?> mysql = new GenericContainer<>("mysql:8.0")
            .withEnv("MYSQL_DATABASE", "kuros_user")
            .withEnv("MYSQL_ROOT_PASSWORD", MYSQL_ROOT_PASSWORD)
            .withExposedPorts(3306)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        // 127.0.0.1 而非 localhost：Windows 上 localhost 可能先解析到 IPv6 [::1]（NacosContainers 同款教训）
        registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:" + mysql.getMappedPort(3306)
                + "/kuros_user?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8");
        // test profile 默认是 H2 驱动，这里连驱动一起覆盖（@DynamicPropertySource 优先级最高）
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> MYSQL_ROOT_PASSWORD);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    // 种子用户（V2__user_seed.sql）：作为稳定的"被关注方"，不依赖测试内的建号
    private static final String SEED_USER_1 = "10000000-0000-0000-0000-000000000001"; // 潮声档案员
    private static final String SEED_USER_2 = "10000000-0000-0000-0000-000000000002"; // 无音区夜行者
    private static final String MISSING_USER = "00000000-0000-0000-0000-0000000000ff";

    // 固定 CSRF fixture：拦截器只比较 cookie == header，不校验服务端存储
    private static final String CSRF_TOKEN = "test-csrf-token";
    private static final Cookie CSRF_COOKIE = new Cookie("XSRF-TOKEN", CSRF_TOKEN);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        // Redis 在测试类内共享：清验证码/会话缓存，保证登录与断言互不污染
        Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection().serverCommands().flushDb();
        // 关注关系跨测试残留会污染计数断言（种子用户被多个测试当目标），每例清空
        jdbcTemplate.execute("DELETE FROM user_follows");
    }

    // ---- 公开关注链路 ----

    @Test
    void 未登录用户不能关注() throws Exception {
        // 不带任何 Cookie：SaToken 必须先把游客拦成 401（不能落到业务层）
        mockMvc.perform(post("/api/v1/users/" + SEED_USER_2 + "/follow"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 关注与取消关注全链路幂等() throws Exception {
        TestSession session = login("13800000021");
        String path = "/api/v1/users/" + SEED_USER_2 + "/follow";

        mockMvc.perform(get(path).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetUserId").value(SEED_USER_2))
                .andExpect(jsonPath("$.data.followed").value(false))
                .andExpect(jsonPath("$.data.followerCount").value(0));

        mockMvc.perform(post(path).cookie(session.cookie()).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followed").value(true))
                .andExpect(jsonPath("$.data.followerCount").value(1));

        // 重复关注：幂等（走锁内 existsById 分支，不再插行）
        mockMvc.perform(post(path).cookie(session.cookie()).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followed").value(true))
                .andExpect(jsonPath("$.data.followerCount").value(1));

        mockMvc.perform(delete(path).cookie(session.cookie()).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followed").value(false))
                .andExpect(jsonPath("$.data.followerCount").value(0));

        // 重复取关：幂等（走锁内 existsById=false 分支，不再删行）
        mockMvc.perform(delete(path).cookie(session.cookie()).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followed").value(false))
                .andExpect(jsonPath("$.data.followerCount").value(0));
    }

    @Test
    void 不能关注自己() throws Exception {
        TestSession session = login("13800000022");

        mockMvc.perform(post("/api/v1/users/" + session.userId() + "/follow")
                        .cookie(session.cookie()).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FOLLOW_SELF_NOT_ALLOWED"));
    }

    @Test
    void 目标用户不存在时返回404() throws Exception {
        TestSession session = login("13800000023");

        mockMvc.perform(get("/api/v1/users/" + MISSING_USER + "/follow").cookie(session.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/users/" + MISSING_USER + "/follow")
                        .cookie(session.cookie()).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void 并发重复关注只写入一条关系() throws Exception {
        TestSession session = login("13800000024");
        String path = "/api/v1/users/" + SEED_USER_2 + "/follow";
        int attempts = 16;

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                statuses.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    // 全部线程带同一会话同时发 POST：锁竞争下失败的线程走幂等兜底
                    // （tryLock 失败 → 直接返回当前状态），不应抛错
                    return mockMvc.perform(post(path)
                                    .cookie(session.cookie()).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未全部就绪");
            start.countDown();
            for (Future<Integer> status : statuses) {
                assertEquals(200, status.get(30, TimeUnit.SECONDS), "并发关注请求都应返回 200");
            }
        } finally {
            pool.shutdownNow();
        }

        // 最终态唯一性：分布式锁 + 复合主键双保险，只允许留下一条关系行
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_follows WHERE follower_id = ? AND followed_id = ?",
                Integer.class, session.userId(), SEED_USER_2);
        assertEquals(1, rows, "并发重复关注最终只能留下一条关系行");

        mockMvc.perform(get(path).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followed").value(true))
                .andExpect(jsonPath("$.data.followerCount").value(1));
    }

    // ---- 内部 API（不经网关；生产需 mTLS 或内部 token，见 InternalUserController） ----

    @Test
    void 内部批量查询保留请求顺序并跳过未知ID() throws Exception {
        // 刻意不带任何 Cookie：内部 API 在 SaToken 白名单内（/internal/**），
        // 若放行规则缺失，这里会先被 checkLogin 拦成 401 而红
        mockMvc.perform(get("/internal/v1/users/batch")
                        .param("ids", SEED_USER_1 + ",missing-id," + SEED_USER_2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(SEED_USER_1))
                .andExpect(jsonPath("$.data[0].nickname").value("潮声档案员"))
                .andExpect(jsonPath("$.data[1].id").value(SEED_USER_2))
                .andExpect(jsonPath("$.data[1].nickname").value("无音区夜行者"));
    }

    @Test
    void 内部关注状态查询返回计数与跟随标记() throws Exception {
        TestSession session = login("13800000025");
        mockMvc.perform(post("/api/v1/users/" + SEED_USER_2 + "/follow")
                        .cookie(session.cookie()).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isOk());

        mockMvc.perform(get("/internal/v1/users/" + SEED_USER_2 + "/follow-stats")
                        .param("viewerId", session.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetUserId").value(SEED_USER_2))
                .andExpect(jsonPath("$.data.followerCount").value(1))
                .andExpect(jsonPath("$.data.followed").value(true));

        // 不传 viewerId：匿名视角（followed=false，计数不受影响）
        mockMvc.perform(get("/internal/v1/users/" + SEED_USER_2 + "/follow-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followerCount").value(1))
                .andExpect(jsonPath("$.data.followed").value(false));

        mockMvc.perform(get("/internal/v1/users/" + MISSING_USER + "/follow-stats"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void 内部关注列表与粉丝列表分页返回用户摘要() throws Exception {
        TestSession session = login("13800000026");
        mockMvc.perform(post("/api/v1/users/" + SEED_USER_2 + "/follow")
                        .cookie(session.cookie()).cookie(CSRF_COOKIE).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isOk());

        // following：会话用户的关注列表 → 含被关注的种子用户
        mockMvc.perform(get("/internal/v1/users/" + session.userId() + "/following")
                        .param("page", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(SEED_USER_2))
                .andExpect(jsonPath("$.data[0].nickname").value("无音区夜行者"))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.totalItems").value(1));

        // followers：种子用户的粉丝列表 → 含会话用户（登录建号昵称 = 漂泊者 + 后四位）
        mockMvc.perform(get("/internal/v1/users/" + SEED_USER_2 + "/followers")
                        .param("page", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(session.userId()))
                .andExpect(jsonPath("$.data[0].nickname").value("漂泊者0026"))
                .andExpect(jsonPath("$.meta.totalItems").value(1));
    }

    // ---- 测试支撑 ----

    private record TestSession(Cookie cookie, String userId) {}

    /**
     * 测试用"真实 HTTP 登录"helper：验证码 → 登录 → 会话 Cookie + userId。
     * 与 AuthLoginIntegrationTest 同一约定（123456 固定验证码只在 test profile 生效），
     * 不直建会话——关注端点依赖 SaToken 的完整读取链路（Cookie → Redis 会话）。
     */
    private TestSession login(String phone) throws Exception {
        mockMvc.perform(post("/api/v1/auth/code")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk());

        var login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookie = login.getResponse().getCookie("KUROS_SESSION");
        String userId = JsonPath.read(login.getResponse().getContentAsString(), "$.data.id");
        return new TestSession(cookie, userId);
    }
}
