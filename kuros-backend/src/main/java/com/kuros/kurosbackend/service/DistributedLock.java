package com.kuros.kurosbackend.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
}
