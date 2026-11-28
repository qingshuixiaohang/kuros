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
 * 点赞计数、收藏计数、互动状态和用户近期点赞窗口都使用「版本键」围栏：
 * 读路径先取版本、再读数据库、最后由 Lua 在版本未变时写入。失效会先递增版本再删除缓存，
 * 因此「提交前读到旧值、提交后才回填」的请求会被拦下并重新读取，不会把旧快照装回缓存。
 */
@Component
public class InteractionRedisStore {

    /**
     * 版本围栏回填：KEYS[1]=版本key，KEYS[2]=值key；
     * ARGV[1]=读取时的版本，ARGV[2]=DB 值，ARGV[3]=ttl。
     * 版本已变返回 nil（调用方重试），否则写入缺失值并返回当前值。
     */
    private static final DefaultRedisScript<String> BACKFILL_IF_VERSION_UNCHANGED = new DefaultRedisScript<>(
            "if (redis.call('get', KEYS[1]) or '0') ~= ARGV[1] then return nil end; "
                    + "if redis.call('exists', KEYS[2]) == 1 then return redis.call('get', KEYS[2]) end; "
                    + "redis.call('set', KEYS[2], ARGV[2], 'EX', ARGV[3]); return redis.call('get', KEYS[2])",
            String.class);

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;
    @Value("${app.interaction.redis.likes-max-size:1000}")
    private int likesMaxSize = 1000;

    /**
     * 失效点赞：KEYS[1]=计数版本、KEYS[2]=窗口版本、KEYS[3]=状态版本，
     * KEYS[4]=计数、KEYS[5]=近期窗口、KEYS[6]=窗口 ready、KEYS[7]=状态；ARGV[1]=状态版本 ttl。
     */
    private static final DefaultRedisScript<Long> INVALIDATE_LIKES = new DefaultRedisScript<>(
            "redis.call('incr', KEYS[1]); redis.call('incr', KEYS[2]); redis.call('incr', KEYS[3]); "
                    + "redis.call('expire', KEYS[3], ARGV[1]); "
                    + "redis.call('del', KEYS[4], KEYS[5], KEYS[6], KEYS[7]); return 1", Long.class);
    private static final DefaultRedisScript<Long> BACKFILL_LIKES = new DefaultRedisScript<>(
            "if (redis.call('get', KEYS[1]) or '0') ~= ARGV[1] then return 0 end; "
                    + "if redis.call('exists', KEYS[3]) == 1 then return 1 end; "
                    + "redis.call('del', KEYS[2]); for i=3,#ARGV,2 do redis.call('zadd', KEYS[2], ARGV[i], ARGV[i+1]) end; "
                    + "redis.call('expire', KEYS[2], ARGV[2]); redis.call('set', KEYS[3], '1', 'EX', ARGV[2]); return 1", Long.class);
    /**
     * 失效收藏/其他状态：KEYS[1]=状态版本、KEYS[2]=计数版本、KEYS[3]=状态、KEYS[4]=计数；
     * ARGV[1]=状态版本 ttl。
     */
    private static final DefaultRedisScript<Long> INVALIDATE_KIND = new DefaultRedisScript<>(
            "redis.call('incr', KEYS[1]); redis.call('expire', KEYS[1], ARGV[1]); "
                    + "redis.call('incr', KEYS[2]); redis.call('del', KEYS[3], KEYS[4]); return 1", Long.class);

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

    /** 读计数：Redis 命中直接返回；未命中时带版本围栏的回填，版本已变则重读数据库。 */
    public long readCount(InteractionKind kind, String postId, LongSupplier dbBaseline) {
        String key = countKey(kind, postId);
        String versionKey = key + ":version";
        while (true) {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) return Long.parseLong(cached);
            String version = redisTemplate.opsForValue().get(versionKey);
            String value = redisTemplate.execute(BACKFILL_IF_VERSION_UNCHANGED, List.of(versionKey, key),
                    version == null ? "0" : version, Long.toString(dbBaseline.getAsLong()),
                    Long.toString(ttlSeconds));
            if (value != null) return Long.parseLong(value);
        }
    }

    /** 读关系状态：Redis 命中直接返回；miss 则带版本围栏回填，失效后不改写旧快照。 */
    public boolean readState(InteractionKind kind, String postId, String userId, BooleanSupplier dbExists) {
        String key = stateKey(kind, postId, userId);
        String versionKey = key + ":version";
        while (true) {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) return "1".equals(cached);
            String version = redisTemplate.opsForValue().get(versionKey);
            String value = redisTemplate.execute(BACKFILL_IF_VERSION_UNCHANGED, List.of(versionKey, key),
                    version == null ? "0" : version, dbExists.getAsBoolean() ? "1" : "0", Long.toString(ttlSeconds));
            if (value != null) return "1".equals(value);
        }
    }

    public void invalidate(InteractionKind kind, String postId, String userId) {
        String state = stateKey(kind, postId, userId);
        if (kind == InteractionKind.LIKE) {
            String count = countKey(kind, postId);
            String likes = likesKey(userId);
            redisTemplate.execute(INVALIDATE_LIKES, List.of(
                            count + ":version", likes + ":version", state + ":version",
                            count, likes, likes + ":ready", state),
                    Long.toString(ttlSeconds));
            return;
        }
        String count = countKey(kind, postId);
        redisTemplate.execute(INVALIDATE_KIND, List.of(state + ":version", count + ":version", state, count),
                Long.toString(ttlSeconds));
    }

    public void invalidateCounts(String postId) {
        String likeKey = countKey(InteractionKind.LIKE, postId);
        String favoriteKey = countKey(InteractionKind.FAVORITE, postId);
        redisTemplate.execute(new DefaultRedisScript<>(
                        "redis.call('incr', KEYS[1]); redis.call('incr', KEYS[2]); redis.call('del', KEYS[3], KEYS[4]); return 1", Long.class),
                List.of(likeKey + ":version", favoriteKey + ":version", likeKey, favoriteKey));
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
     * 同样使用版本围栏：读取真相后若版本已变（期间发生过提交失效），Lua 返回 -1，
     * 调用方重读真相，避免把失效前读到的旧真相装回缓存。
     *
     * @param truthSupplier 真相来源，通常是关系表的 COUNT(*)——幂等可重算，
     *                       比增量维护的冗余列更可信
     */
    public ReconcileResult reconcileCount(InteractionKind kind, String postId, LongSupplier truthSupplier) {
        String key = countKey(kind, postId);
        String versionKey = key + ":version";
        // 本地构造脚本对象：static 实例复用会导致 execute 返回 null（B2 踩过的坑）
        var script = new DefaultRedisScript<Long>(
                "if (redis.call('get', KEYS[1]) or '0') ~= ARGV[1] then return -1 end; "
                        + "local old = redis.call('get', KEYS[2]); redis.call('incr', KEYS[1]); "
                        + "redis.call('set', KEYS[2], ARGV[2], 'EX', ARGV[3]); if old == ARGV[2] then return 1 else return 0 end", Long.class);
        while (true) {
            String version = redisTemplate.opsForValue().get(versionKey);
            long truth = truthSupplier.getAsLong();
            Long result = redisTemplate.execute(script, List.of(versionKey, key), version == null ? "0" : version,
                    Long.toString(truth), Long.toString(ttlSeconds));
            // Lua 返回 0=修正了偏差、1=原本一致、-1=并发失效后真相已过期
            if (Long.valueOf(0).equals(result) || Long.valueOf(1).equals(result)) {
                return new ReconcileResult(truth, Long.valueOf(0).equals(result));
            }
        }
    }

    private String stateKey(InteractionKind kind, String postId, String userId) {
        return "interaction:" + kind.keyPrefix() + ":" + postId + ":" + userId;
    }

    /** 计数 key 沿用 #11 契约：interaction:count:{like|favorite}:{postId}，值为整数字符串。 */
    private String countKey(InteractionKind kind, String postId) {
        return "interaction:count:" + kind.keyPrefix() + ":" + postId;
    }
}
