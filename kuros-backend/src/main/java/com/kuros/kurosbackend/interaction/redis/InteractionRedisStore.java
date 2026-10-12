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

    /**
     * 对账：把计数 key 设为 DB 真相值，返回修正后的值（审计 M5 / B3）。
     *
     * KEYS[1]=计数key，ARGV[1]=真相值，ARGV[2]=ttl。
     * 返回值语义（供调用方判断是否需要记录/告警）：
     *   1 = 原本就一致，未改动
     *   0 = 原本不一致，已修正为真相值
     *
     * 为什么用 Lua 而不是「GET 比一下再 SET」：分步执行时，两次操作之间
     * 可能有正常写路径的 flip 插进来（把计数改成新值），那样对账会用旧真相
     * 覆盖掉一次合法增量——为了修偏差反而制造偏差。Lua 让「比对 + 修正」
     * 原子完成，等价于持锁对账。
     *
     * 注意这里不复用 B2 踩过的坑（static 脚本实例复用导致 execute 返回 null），
     * 每次调用本地构造脚本对象。
     */
    private static final String RECONCILE_SCRIPT =
            "local current = redis.call('get', KEYS[1]); "
                    + "if current == ARGV[1] then "
                    + "  return 1; "
                    + "end; "
                    + "redis.call('set', KEYS[1], ARGV[1]); "
                    + "redis.call('expire', KEYS[1], ARGV[2]); "
                    + "return 0";

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

    /**
     * 对账结果：修正后的计数值，以及是否真的发生了修正（供日志/告警判断）。
     */
    public record ReconcileResult(long count, boolean corrected) {
    }

    /**
     * 对账计数：以 DB 真相为准修正 Redis（审计 M5 / B3）。
     *
     * 什么时候会不一致（所以需要对账）：
     * - 写路径即时翻 Redis，DB 计数列靠 MQ 异步投影更新，中间有窗口；
     * - MQ 投递失败虽有同步降级落库，但降级也失败（DB 抖动）时事件永久
     *   丢失，DB 计数列永久偏低，而 Redis 是对的——此时该修的是 DB 列；
     * - Redis 计数 TTL 到期后按 DB 基线回填，若基线本身是错的，错误会被
     *   固化成真相。
     *
     * 本方法只负责「把 Redis 拉回真相」。DB 冗余列的修正是调用方的责任
     * （见 InteractionCountReconciler.reconcile），因为那是 DB 写、要
     * 走事务，不能跟 Redis 操作混在一个非事务方法里。
     *
     * @param truthSupplier 真相来源，通常是关系表的 COUNT(*)——幂等可重算，
     *                       比增量维护的冗余列更可信
     */
    public ReconcileResult reconcileCount(InteractionKind kind, String postId, LongSupplier truthSupplier) {
        String key = countKey(kind, postId);
        long truth = truthSupplier.getAsLong();
        // 本地构造脚本对象：static 实例复用会导致 execute 返回 null（B2 踩过的坑）
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(RECONCILE_SCRIPT, Long.class);
        Long changed = redisTemplate.execute(script, List.of(key),
                Long.toString(truth), Long.toString(ttlSeconds));
        // Lua 返回 0=修正了偏差、1=原本一致；execute 返回 null 时保守地当作"未修正"
        boolean corrected = Long.valueOf(0L).equals(changed);
        return new ReconcileResult(truth, corrected);
    }

    private String stateKey(InteractionKind kind, String postId, String userId) {
        return "interaction:" + kind.keyPrefix() + ":" + postId + ":" + userId;
    }

    private String countKey(InteractionKind kind, String postId) {
        return "interaction:count:" + kind.keyPrefix() + ":" + postId;
    }
}
