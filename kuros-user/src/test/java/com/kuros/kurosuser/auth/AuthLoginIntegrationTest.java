package com.kuros.kurosuser.auth;

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
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录链路集成测试（split-06 验收编码化）：验证码 → 登录 → 会话写入共享 Redis → /me，
 * 外加登出与未认证两处边界。
 *
 * 为什么用真实 MySQL 而不是 test profile 的 H2：
 * 1. 认证链路的关键 SQL 依赖 MySQL 语义——assignRole 的 INSERT ... SELECT 从 sys_role
 *    按 role_code 取 id、Flyway 真实方言执行、CHECK 约束与 TIMESTAMP 默认值；
 *    H2 的 MySQL 兼容模式与真实 MySQL 在这些边缘上并不等价
 * 2. 容器与类一一对应、surefire reuseForks=false 独立 JVM，和其余测试类互不干扰
 *
 * 为什么认证路径的写请求刻意不带 CSRF Cookie/Header：
 * 还原前端 api.ts 的真实行为（对 /api/v1/auth/** 不发 X-XSRF-TOKEN），
 * 若拦截器排除规则失效，POST 会得到 403 而非 200，此处即红。
 *
 * 手机号夹具与 backend 旧测试保持一致（1380000000x），双方测试数据天然对齐。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AuthLoginIntegrationTest {

    private static final String MYSQL_ROOT_PASSWORD = "kuros-test-root";

    // 为什么 wait 用端口监听而不是日志：MySQL entrypoint 初始化阶段会先起一个
    // skip-networking 的临时服务器（compose healthcheck 的同类教训），
    // TCP 监听成立意味着最终服务器就绪，是权威信号
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
        // 127.0.0.1 而非 localhost：Windows 上 localhost 可能先解析到 IPv6 [::1]
        //（NacosContainers 同款教训，JDBC 连接同样适用）
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void flushRedis() {
        // Redis 在测试类内共享：每例清空，保证验证码/会话/权限缓存的断言互不污染
        Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection().serverCommands().flushDb();
    }

    @Test
    void 新手机号首次验证码登录会建号并恢复会话() throws Exception {
        mockMvc.perform(post("/api/v1/auth/code")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000005\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devCode").value("123456"));

        var login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000005\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("KUROS_SESSION"))
                .andExpect(jsonPath("$.data.nickname").value("漂泊者0005"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andReturn();

        Cookie sessionCookie = login.getResponse().getCookie("KUROS_SESSION");
        mockMvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("漂泊者0005"));
    }

    @Test
    void 已有种子用户登录不会重复建号() throws Exception {
        mockMvc.perform(post("/api/v1/auth/code")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000001\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000001\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("潮声档案员"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE phone = ?", Integer.class, "13800000001");
        assertEquals(1, rows, "种子用户登录不应再插入新行");
    }

    @Test
    void 无效验证码不能建立登录会话() throws Exception {
        mockMvc.perform(post("/api/v1/auth/code")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000006\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000006\",\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));
    }

    @Test
    void 退出登录后旧会话立即失效() throws Exception {
        mockMvc.perform(post("/api/v1/auth/code")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000007\"}"))
                .andExpect(status().isOk());

        var login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000007\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie sessionCookie = login.getResponse().getCookie("KUROS_SESSION");

        mockMvc.perform(post("/api/v1/auth/logout").cookie(sessionCookie))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 没有会话访问当前用户返回未授权() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 登录会话与角色权限缓存写入共享Redis() throws Exception {
        mockMvc.perform(post("/api/v1/auth/code")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000008\"}"))
                .andExpect(status().isOk());

        var login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000008\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie sessionCookie = login.getResponse().getCookie("KUROS_SESSION");
        String token = sessionCookie.getValue();
        String body = login.getResponse().getContentAsString();
        String userId = JsonPath.read(body, "$.data.id");

        // ① SaToken 会话必须落在共享 Redis（拆服务不拆会话的地基）：
        // key 由 token-name 拼成（{token-name}:login:token:{token}），
        // 这里用后缀匹配而非硬编码前缀，对 sa-token.token-name 配置保持宽容
        Set<String> tokenKeys = redisTemplate.keys("*:login:token:" + token);
        assertNotNull(tokenKeys, "Redis KEYS 不应返回 null");
        assertFalse(tokenKeys.isEmpty(), "登录成功后 Token 会话必须写入共享 Redis，实际 key 数：" + tokenKeys.size());

        // ② 角色缓存（登录事务提交后由 afterCommit 写入）：backend 侧管理员鉴权直接命中它
        Set<String> roles = redisTemplate.opsForSet().members("auth:roles:" + userId);
        assertNotNull(roles);
        assertTrue(roles.contains("USER"), "新用户应写入 USER 角色缓存，实际：" + roles);

        // ③ 权限缓存：USER 角色在 V2 种子里携带基础社区权限
        Set<String> permissions = redisTemplate.opsForSet().members("auth:permissions:" + userId);
        assertNotNull(permissions);
        assertTrue(permissions.contains("post:create"), "USER 角色应携带基础社区权限，实际：" + permissions);

        // ④ 会话可用性收口：用同一 Cookie 访问 /me 成功
        mockMvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(userId));
    }
}
