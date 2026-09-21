package com.kuros.kurosbackend.interaction.redis;

import com.kuros.kurosbackend.interaction.event.InteractionKind;
import com.kuros.kurosbackend.interaction.event.InteractionType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 互动实时读源（切片 #11）：Redis 前置计数 + 关系状态，写路径即时返回、读路径 Redis 优先。
 *
 * 数据模型（两类 key，均 String）：
 * - 状态：interaction:{like|favorite}:{postId}:{userId} = "1"(已赞/藏) | "0"(未)
 * - 计数：interaction:count:{like|favorite}:{postId} = 整数字符串
 *
 * 为什么状态用「单 key/用户」而不是「一个 Set 存全部点赞用户」？
 * Set 方案在热帖上会退化成大 key（百万成员），且回填（把 DB 关系灌进 Set）非幂等、有竞态；
 * 单 key 天然按用户分片、回填只是「不存在则用 DB 值 set 一次」，幂等且无大 key 风险。
 *
 * 计数回填的竞态如何用 Lua 根除？
 * 多个请求同时 miss 同一计数 key 时，若「GET-miss → 查 DB → SET 基线 → INCR」分步执行，
 * 后来的 SET 会覆盖先到者的 INCR 结果（丢失更新）。COUNT_BACKFILL_DELTA 把
 * 「exists 判断 + set 基线 + expire + incrby」放进单个 Lua（Redis 单线程原子执行）：
 * 基线只在 key 不存在时设一次，之后每次只叠加 delta，绝不覆盖。
 */
@Component
public class InteractionRedisStore {

    /** 计数回填+增量：KEYS[1]=计数key，ARGV[1]=DB基线，ARGV[2]=delta，ARGV[3]=ttl；返回增量后的新值。 */
    private static final DefaultRedisScript<Long> COUNT_BACKFILL_DELTA = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 0 then "
                    + "redis.call('set', KEYS[1], ARGV[1]); "
                    + "redis.call('expire', KEYS[1], ARGV[3]); "
                    + "end "
                    + "return redis.call('incrby', KEYS[1], ARGV[2])",
            Long.class);

    /** 状态回填：KEYS[1]=状态key，ARGV[1]=DB状态("1"/"0")，ARGV[2]=ttl；返回当前值。 */
    private static final DefaultRedisScript<String> STATE_BACKFILL = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 0 then "
                    + "redis.call('set', KEYS[1], ARGV[1]); "
                    + "redis.call('expire', KEYS[1], ARGV[2]); "
                    + "end "
                    + "return redis.call('get', KEYS[1])",
            String.class);

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;

    public InteractionRedisStore(StringRedisTemplate redisTemplate,
                                 @Value("${app.interaction.redis.ttl-seconds:86400}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds;
    }

    /** 读计数：Redis 命中直接返回；miss 则用 DB 基线回填（delta=0）后返回。 */
    public long readCount(InteractionKind kind, String postId, LongSupplier dbBaseline) {
        String key = countKey(kind, postId);
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return Long.parseLong(cached);
        }
        Long value = redisTemplate.execute(COUNT_BACKFILL_DELTA, List.of(key),
                Long.toString(dbBaseline.getAsLong()), "0", Long.toString(ttlSeconds));
        return value != null ? value : dbBaseline.getAsLong();
    }

    /** 读关系状态：Redis 命中直接返回；miss 则用 DB 存在性回填后返回。 */
    public boolean readState(InteractionKind kind, String postId, String userId, BooleanSupplier dbExists) {
        String key = stateKey(kind, postId, userId);
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return "1".equals(cached);
        }
        String value = redisTemplate.execute(STATE_BACKFILL, List.of(key),
                dbExists.getAsBoolean() ? "1" : "0", Long.toString(ttlSeconds));
        return "1".equals(value);
    }

    /**
     * 写路径翻转（必须在 per-user 分布式锁内调用）：
     * 读当前态（miss 回源 DB）→ 仅当目标态≠当前态才 delta=±1、置新状态、更新计数 → 返回实时计数。
     *
     * 重复点击（当前态==目标态）不动计数，保证幂等——这也是「先判翻转再增减」的核心。
     * 计数用 Lua 回填+增量：即使计数 key 恰好过期/首次访问，也能原子地在 DB 基线上叠加本次 delta。
     */
    public long flip(InteractionType type, String postId, String userId,
                     BooleanSupplier dbExists, LongSupplier dbBaseline) {
        InteractionKind kind = type.kind();
        boolean current = readState(kind, postId, userId, dbExists);
        boolean target = type.targetState();
        if (current == target) {
            return readCount(kind, postId, dbBaseline);
        }
        long delta = target ? 1L : -1L;
        // 用 Duration 重载而非已废弃的 set(K,V,long,TimeUnit)——Spring Data Redis 4 起统一以 Duration 表达 TTL
        redisTemplate.opsForValue().set(stateKey(kind, postId, userId), target ? "1" : "0", Duration.ofSeconds(ttlSeconds));
        Long value = redisTemplate.execute(COUNT_BACKFILL_DELTA, List.of(countKey(kind, postId)),
                Long.toString(dbBaseline.getAsLong()), Long.toString(delta), Long.toString(ttlSeconds));
        return value != null ? value : dbBaseline.getAsLong() + delta;
    }

    private String stateKey(InteractionKind kind, String postId, String userId) {
        return "interaction:" + kind.keyPrefix() + ":" + postId + ":" + userId;
    }

    private String countKey(InteractionKind kind, String postId) {
        return "interaction:count:" + kind.keyPrefix() + ":" + postId;
    }
}
