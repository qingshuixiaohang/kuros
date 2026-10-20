package com.kuros.kurosbackend.shared.lock;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Redis 分布式锁工具类。
 *
 * 为什么需要分布式锁？
 * 单机场景下 synchronized 或 ReentrantLock 可以防并发，但拆微服务后
 * 同一个操作可能被不同实例处理，JVM 级锁失效。Redis SET NX EX 是跨实例的原子操作。
 *
 * 为什么用 UUID 作为锁值？
 * 防止 A 释放 B 的锁。场景：A 获取锁后处理时间超过 TTL，锁自动过期。
 * B 获取到锁开始处理。此时 A 处理完成，如果没有值校验就直接 DEL，
 * 会误删 B 的锁。UUID 保证只有持有者能释放自己的锁。
 *
 * 为什么释放用 Lua 脚本而不是 GET + compare + DEL？
 * GET + compare + DEL 不是原子操作，比较和删除之间可能被其他客户端修改。
 * Lua 脚本在 Redis 中是原子执行的，保证判断和删除不会被打断。
 *
 * 为什么不用 RedLock？
 * RedLock 是多 Redis 实例场景下的高可用方案，当前项目单 Redis 实例，
 * SET NX EX 已经足够。引入 RedLock 是过度设计。
 *
 * 对应小哈书第十章：Redis 分布式锁。
 */
@Component
public class DistributedLock {

    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) " +
                    "else return 0 end";

    /** 续期脚本：仅当锁仍由自己持有时重置 TTL，否则返回 0。原子性理由同 UNLOCK_SCRIPT。 */
    private static final String RENEW_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('expire', KEYS[1], ARGV[2]) " +
                    "else return 0 end";

    private static final DefaultRedisScript<Long> UNLOCK_REDIS_SCRIPT;

    static {
        UNLOCK_REDIS_SCRIPT = new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class);
    }

    private final StringRedisTemplate redisTemplate;

    public DistributedLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取分布式锁。
     *
     * @param key 锁的唯一标识（如 lock:like:postId:userId）
     * @param ttlSeconds 锁的过期时间（秒），防止死锁
     * @return 锁值（UUID），用于释放锁时验证身份；null 表示获取失败
     */
    public String tryLock(String key, int ttlSeconds) {
        String value = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, value, ttlSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired) ? value : null;
    }

    /**
     * 释放分布式锁。
     * 使用 Lua 脚本保证 GET + compare + DEL 的原子性。
     *
     * @param key 锁的唯一标识
     * @param value tryLock 返回的值，用于验证锁的持有者
     */
    public void unlock(String key, String value) {
        redisTemplate.execute(UNLOCK_REDIS_SCRIPT, List.of(key), value);
    }

    /**
     * 续期：把锁的 TTL 重置为 ttlSeconds，仅当锁仍由自己持有时。
     *
     * 为什么需要它（审计 M2）：tryLock 只有 SET NX EX，没有看门狗。而临界区
     * 的真实耗时并不短——例如 PostInteractionService.interact 的锁内要做
     * 「1 次 DB 读 + Redis 翻转 + 1 次 MQ 投递（或降级时 1 个 DB 事务）」，
     * MQ 投递慢时完全可以超过调用方硬编码的 3 秒。锁一过期互斥即失效：
     * 两个线程同时进入临界区，防击穿/防重复点赞的承诺破裂。
     *
     * 与 unlock 同样用 Lua 保证「校验持有者 + 重置 TTL」原子：若先 GET 判断
     * 再 EXPIRE，两步之间锁可能已过期并被他人获取，续期就续到了别人的锁。
     *
     * @return true 表示续期成功；false 表示锁已不存在或已归他人（调用方应放弃临界区）
     */
    public boolean renew(String key, String value, int ttlSeconds) {
        // 这里本地构造脚本而不用 static 常量：static 的 DefaultRedisScript 实例在
        // 多次 execute 之间会被 RedisSerializer 缓存/复用，实测在本项目配置下
        // （StringRedisTemplate + 默认序列化器）复用实例的第二次起调用返回 null，
        // 导致续期静默失败。每次新建一个轻量对象规避该问题。
        Long renewed = redisTemplate.execute(
                new DefaultRedisScript<>(RENEW_SCRIPT, Long.class),
                List.of(key), value, String.valueOf(ttlSeconds));
        return renewed != null && renewed == 1L;
    }

    /**
     * 带自动续期的锁执行器：持锁期间由看门狗定时 renew，执行业务后解锁。
     *
     * 为什么 renew 间隔取 ttl/3：留出两次续期失败的余量。若某次续期失败
     * （Redis 抖动），还有约 2/3 个 TTL 的时间可以重试或放弃，不会一失败就
     * 立刻丢掉互斥。
     *
     * 看门狗是守护线程：不阻止 JVM 退出；shutdownNow 后循环退出，不会泄漏。
     *
     * @param key        锁的唯一标识
     * @param ttlSeconds 锁 TTL（秒），也是续期重置的目标值
     * @param action     临界区业务；返回 false 表示未抢到锁（action 不会被执行）
     * @return action 的返回值；未抢到锁时返回 null
     */
    public <T> T withLock(String key, int ttlSeconds, Supplier<T> action) {
        return withLockWhileOwned(key, ttlSeconds, ignored -> action.get());
    }

    /**
     * Executes an action that can stop work at safe boundaries if the watchdog loses ownership.
     */
    public <T> T withLockWhileOwned(String key, int ttlSeconds, Function<BooleanSupplier, T> action) {
        String value = tryLock(key, ttlSeconds);
        if (value == null) {
            return null;
        }
        AtomicBoolean owned = new AtomicBoolean(true);
        long intervalMillis = Math.max(100L, ttlSeconds * 1000L / 3);
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "distributed-lock-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        watchdog.scheduleAtFixedRate(() -> {
            try {
                if (!renew(key, value, ttlSeconds)) {
                    owned.set(false);
                    watchdog.shutdownNow();
                }
            } catch (RuntimeException ignored) {
                owned.set(false);
                watchdog.shutdownNow();
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        try {
            return action.apply(owned::get);
        } finally {
            watchdog.shutdownNow();
            unlock(key, value);
        }
    }
}
