package com.kuros.kurosuser.auth;

import com.kuros.kurosuser.api.PhoneCodeRequest;
import com.kuros.kurosuser.api.PhoneLoginRequest;
import com.kuros.kurosuser.repository.SysRoleRepository;
import com.kuros.kurosuser.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * 孤儿会话回归测试（安全加固 A3）。
 *
 * 漏洞：{@code AuthService.login()} 是 @Transactional，方法体内先
 * {@code StpUtil.login(user.getId())} 把会话写进共享 Redis，之后才
 * {@code syncRoleField(user)} 落库。若后者失败导致事务回滚，Redis 里
 * 已残留一个指向**从未提交用户**的有效会话——用户拿着 Cookie 能过
 * backend 的 checkLogin（跨服务会话可见性放大爆炸半径）。
 *
 * 已有的 afterCommit 只保护了 syncPermissionsToRedis，漏了最关键的那次写。
 *
 * 复现手法：spy SysRoleRepository 让 findRoleCodesByUserId 抛异常。
 * syncRoleField 第一行就是调它，故异常必然发生在 StpUtil.login 之后、
 * 事务提交之前——正是漏洞窗口。对该方法置桩不影响同类的其他用例
 * （它们用的手机号走 else 分支，见下方 isRollbackCase 判断）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AuthOrphanSessionIntegrationTest {

    private static final String MYSQL_ROOT_PASSWORD = "kuros-test-root";

    /** 触发回滚的专用手机号：spy 只对这个号码抛异常，其他用例不受影响。 */
    private static final String ROLLBACK_PHONE = "13800000099";

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
        registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:" + mysql.getMappedPort(3306)
                + "/kuros_user?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8");
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> MYSQL_ROOT_PASSWORD);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // 测试链路需要万能码才能走到登录（A1 修复后默认值已为空）
        registry.add("app.auth.dev-code", () -> "123456");
        registry.add("app.auth.dev-code-exposed", () -> "true");
    }

    @Autowired
    private AuthService authService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    private SysRoleRepository roleRepository;

    @Test
    void 事务回滚时不得在Redis残留会话() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection().serverCommands().flushDb();

        authService.issueCode(new PhoneCodeRequest(ROLLBACK_PHONE));

        // syncRoleField 第一行就是 findRoleCodesByUserId，置桩后异常必然落在
        // StpUtil.login() 之后、事务提交之前——正是漏洞窗口
        doThrow(new RuntimeException("模拟 syncRoleField 失败"))
                .when(roleRepository).findRoleCodesByUserId(anyString());

        assertThatThrownBy(() -> authService.login(
                new PhoneLoginRequest(ROLLBACK_PHONE, "123456")))
                .as("syncRoleField 失败必须让整个登录事务回滚")
                .isInstanceOf(RuntimeException.class)
                .hasMessage("模拟 syncRoleField 失败");

        // 核心断言：Redis 中不得残留任何 SaToken 会话
        Set<String> sessionKeys = redisTemplate.keys("*:login:token:*");
        assertThat(sessionKeys)
                .as("事务回滚后 Redis 不得残留指向未提交用户的会话，实际 key：%s", sessionKeys)
                .isNullOrEmpty();
    }
}
