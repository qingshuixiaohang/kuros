package com.kuros.kurosbackend.interaction.redis;

import com.kuros.kurosbackend.interaction.event.InteractionKind;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.Set;
import java.util.ArrayList;
import com.kuros.kurosbackend.interaction.domain.PostLike;
import java.time.ZoneOffset;

/**
 * 互动旁路缓存，未命中时从数据库回填。
 *
 * 点赞计数和用户近期点赞窗口使用独立命名空间；收藏继续使用原有缓存。
 * 版本键保留在 Redis 中，用于防止并发失效后的旧数据库快照回填。
 */
@Component
public class InteractionRedisStore {

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
    @Value("${app.interaction.redis.likes-max-size:1000}")
    private int likesMaxSize = 1000;

    private static final DefaultRedisScript<Long> INVALIDATE_LIKES = new DefaultRedisScript<>(
            "redis.call('incr', KEYS[1]); redis.call('incr', KEYS[2]); "
                    + "redis.call('del', KEYS[3], KEYS[4], KEYS[5]); return 1", Long.class);
    private static final DefaultRedisScript<Long> BACKFILL_LIKES = new DefaultRedisScript<>(
            "if (redis.call('get', KEYS[1]) or '0') ~= ARGV[1] then return 0 end; "
                    + "if redis.call('exists', KEYS[3]) == 1 then return 1 end; "
                    + "redis.call('del', KEYS[2]); for i=3,#ARGV,2 do redis.call('zadd', KEYS[2], ARGV[i], ARGV[i+1]) end; "
                    + "redis.call('expire', KEYS[2], ARGV[2]); redis.call('set', KEYS[3], '1', 'EX', ARGV[2]); return 1", Long.class);
    private static final DefaultRedisScript<String> BACKFILL_COUNT = new DefaultRedisScript<>(
            "if (redis.call('get', KEYS[1]) or '0') ~= ARGV[1] then return nil end; "
                    + "if redis.call('exists', KEYS[2]) == 0 then redis.call('set', KEYS[2], ARGV[2], 'EX', ARGV[3]) end; return redis.call('get', KEYS[2])", String.class);

    public int likesMaxSize() { return likesMaxSize; }

    public Set<String> recentLikes(String userId, Supplier<List<PostLike>> load) {
        String key = likesKey(userId);
        String marker = key + ":ready";
        String versionKey = key + ":version";
        while (true) {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(marker))) {
                Set<String> cached = redisTemplate.opsForZSet().reverseRange(key, 0, -1);
                return cached == null ? Set.of() : cached;
            }
            String version = redisTemplate.opsForValue().get(versionKey);
            List<PostLike> rows = load.get();
            List<String> args = new ArrayList<>();
            args.add(version == null ? "0" : version);
            args.add(Long.toString(ttlSeconds));
            for (PostLike row : rows) {
                var timestamp = row.getCreatedAt().toInstant(ZoneOffset.UTC);
                args.add(Long.toString(timestamp.getEpochSecond() * 1_000_000 + timestamp.getNano() / 1_000));
                args.add(row.getPostId());
            }
            Long installed = redisTemplate.execute(BACKFILL_LIKES, List.of(versionKey, key, marker), args.toArray());
            if (Long.valueOf(1).equals(installed)) continue;
        }
    }

    private String likesKey(String userId) { return "user:likes:patten:" + userId + ":post"; }

    public InteractionRedisStore(StringRedisTemplate redisTemplate,
                                 @Value("${app.interaction.redis.ttl-seconds:86400}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds;
    }

    /** 读计数：Redis 命中直接返回，未命中时使用 DB 基线回填。 */
    public long readCount(InteractionKind kind, String postId, LongSupplier dbBaseline) {
        String key = countKey(kind, postId);
        if (kind == InteractionKind.LIKE) {
            String versionKey = key + ":version";
            while (true) {
                String cached = redisTemplate.opsForValue().get(key);
                if (cached != null) return Long.parseLong(cached.split(",", 2)[0]);
                String version = redisTemplate.opsForValue().get(versionKey);
                long baseline = dbBaseline.getAsLong();
                String value = redisTemplate.execute(BACKFILL_COUNT, List.of(versionKey, key),
                        version == null ? "0" : version, baseline + ",0", Long.toString(ttlSeconds));
                if (value != null) return Long.parseLong(value.split(",", 2)[0]);
            }
        }
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return Long.parseLong(cached);
        }
        long baseline = dbBaseline.getAsLong();
        String value = redisTemplate.execute(STATE_BACKFILL, List.of(key),
                Long.toString(baseline), Long.toString(ttlSeconds));
        return value != null ? Long.parseLong(value) : baseline;
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

    public void invalidate(InteractionKind kind, String postId, String userId) {
        if (kind == InteractionKind.LIKE) {
            String count = countKey(kind, postId);
            String likes = likesKey(userId);
            redisTemplate.execute(INVALIDATE_LIKES, List.of(count + ":version", likes + ":version", count, likes, likes + ":ready"));
            return;
        }
        redisTemplate.delete(List.of(stateKey(kind, postId, userId), countKey(kind, postId)));
    }

    public void invalidateCounts(String postId) {
        String key = countKey(InteractionKind.LIKE, postId);
        redisTemplate.execute(new DefaultRedisScript<>("redis.call('incr', KEYS[1]); redis.call('del', KEYS[2], KEYS[3]); return 1", Long.class),
                List.of(key + ":version", key, countKey(InteractionKind.FAVORITE, postId)));
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
        if (kind == InteractionKind.LIKE) {
            String versionKey = key + ":version";
            var script = new DefaultRedisScript<Long>(
                    "if (redis.call('get', KEYS[1]) or '0') ~= ARGV[1] then return -1 end; "
                            + "local old = redis.call('get', KEYS[2]); redis.call('incr', KEYS[1]); "
                            + "redis.call('set', KEYS[2], ARGV[2], 'EX', ARGV[3]); if old == ARGV[2] then return 1 else return 0 end", Long.class);
            while (true) {
                String version = redisTemplate.opsForValue().get(versionKey);
                long truth = truthSupplier.getAsLong();
                Long result = redisTemplate.execute(script, List.of(versionKey, key), version == null ? "0" : version,
                        truth + ",0", Long.toString(ttlSeconds));
                if (Long.valueOf(0).equals(result) || Long.valueOf(1).equals(result)) return new ReconcileResult(truth, result == 0);
            }
        }
        long truth = truthSupplier.getAsLong();
        // 本地构造脚本对象：static 实例复用会导致 execute 返回 null（B2 踩过的坑）
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(RECONCILE_SCRIPT, Long.class);
        Long changed = redisTemplate.execute(script, List.of(key),
                kind == InteractionKind.LIKE ? truth + ",0" : Long.toString(truth), Long.toString(ttlSeconds));
        // Lua 返回 0=修正了偏差、1=原本一致；execute 返回 null 时保守地当作"未修正"
        boolean corrected = Long.valueOf(0L).equals(changed);
        return new ReconcileResult(truth, corrected);
    }

    private String stateKey(InteractionKind kind, String postId, String userId) {
        return "interaction:" + kind.keyPrefix() + ":" + postId + ":" + userId;
    }

    private String countKey(InteractionKind kind, String postId) {
        if (kind == InteractionKind.LIKE) return "count:patten:post:" + postId;
        return "interaction:count:" + kind.keyPrefix() + ":" + postId;
    }
}
