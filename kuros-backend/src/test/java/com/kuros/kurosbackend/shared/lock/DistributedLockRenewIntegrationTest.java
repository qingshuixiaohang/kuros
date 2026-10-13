package com.kuros.kurosbackend.shared.lock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分布式锁续期（watchdog）测试（审计 M2 的回归）。
 *
 * 要锁定的行为：持锁时间超过初始 TTL 时，锁不得因过期而失效。
 * 这正是裸 tryLock 的缺陷——临界区含 MQ 投递/DB 事务，慢时数秒，
 * 而调用方原来硬编码 TTL=3 秒。
 *
 * 踩坑记录（本测试抓到的真实 bug）：renew 最初用 static 的
 * DefaultRedisScript 常量，实测在本项目配置下（StringRedisTemplate +
 * 默认序列化器）复用同一脚本实例的第二次起 execute 返回 null，续期静默
 * 失败。定位手法是加一个"每次新建脚本对象"的诊断用例作对照——它通过而
 * 封装失败，差异才浮出来。修复为每次调用本地构造脚本对象。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class DistributedLockRenewIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private DistributedLock distributedLock;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void 持锁超过初始TTL时看门狗续期使锁不失效() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection().serverCommands().flushDb();

        String key = "lock:renew-test";
        // TTL 1 秒：看门狗每 ~333ms 续一次，业务睡 2.5 秒必然跨过初始 TTL
        String observedValue = distributedLock.withLock(key, 1, () -> {
            try {
                TimeUnit.MILLISECONDS.sleep(2500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 业务结束时锁仍由自己持有：能读到自己的锁值即证明没过期
            return redisTemplate.opsForValue().get(key);
        });

        assertThat(observedValue)
                .as("持锁 2.5s（初始 TTL 仅 1s）后锁必须仍由自己持有，说明看门狗续期生效")
                .isNotNull();

        // withLock 结束即解锁
        assertThat(redisTemplate.hasKey(key)).isFalse();
    }

    @Test
    void renew对非持有者返回false且不重置别人的锁() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection().serverCommands().flushDb();

        String key = "lock:renew-ownership";
        String mine = distributedLock.tryLock(key, 10);
        assertThat(mine).isNotNull();

        // 先验证"持有者能续期"这个基本前提——若非持有者用例先跑过且失败，
        // 基本前提不成立会让后面的断言失去意义
        boolean renewed = distributedLock.renew(key, mine, 10);
        assertThat(renewed)
                .as("持有者续期必须成功（Lua 脚本与参数传递的基本前提），锁值=%s", mine)
                .isTrue();

        // 用另一个"持有者身份"尝试续期：必须失败
        assertThat(distributedLock.renew(key, "someone-elses-token", 10)).isFalse();

        // 锁仍在原持有者手里，TTL 也没被改写（仍接近 10s 而非被重置）
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo(mine);
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isBetween(5L, 10L);

        distributedLock.unlock(key, mine);
    }

    @Test
    void 未抢到锁时withLock不执行业务且返回null() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection().serverCommands().flushDb();

        String key = "lock:renew-contended";
        String holder = distributedLock.tryLock(key, 10);
        assertThat(holder).isNotNull();

        AtomicInteger executions = new AtomicInteger();
        Object result = distributedLock.withLock(key, 10, () -> {
            executions.incrementAndGet();
            return "should-not-run";
        });

        assertThat(result).isNull();
        assertThat(executions.get()).isZero();

        distributedLock.unlock(key, holder);
    }
}
