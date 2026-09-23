package com.kuros.kurosbackend.shared.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kuros.kurosbackend.shared.lock.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * 两级缓存读组件（切片 #13 / ADR 0006 D1）：L1 Caffeine（进程内、纳秒、短 TTL）→
 * L2 Redis（跨节点共享、毫秒、长 TTL、JSON 字符串）→ DB（loader 重建），命中即返回并逐层回填。
 *
 * 为什么不用 Spring 的 @Cacheable 注解？
 * 注解式缓存只能表达「单层、命中即返回」，无法表达本组件的复合读流：
 * L1→L2→DB 三级穿透、L2 命中后回填 L1、L2-miss 时的互斥锁重建（rp-02）、空值哨兵防穿透（rp-02）。
 * 这些逻辑必须显式编排，故把缓存从「注解切面」下沉为「可组合的读组件」。
 *
 * 层级职责与 TTL 的取舍：
 * - L1 复用 {@link CacheManager}（CaffeineCacheManager）托管的缓存，TTL 在 CacheConfig 配（10s）。
 *   短 TTL 是「跨节点不一致窗口」的上界——即便 pub/sub 失效消息丢失（rp-03），最长 10s 自然收敛。
 * - L2 用 String 结构存 JSON（而非 JDK 序列化）：可读、跨语言、跨重启共享，且 value 结构演进时
 *   旧条目反序列化失败会被降级为 miss（见 readL2 的容错），不会污染读路径。
 *
 * 两条防高并发的纪律（rp-02）：
 * - 防击穿（breakdown）：热帖 L2 过期瞬间，成百上千并发同时 miss，若都穿透 DB 会瞬间打垮库。
 *   用 {@link DistributedLock} 抢一把「重建锁」，只放一个请求去 DB 重建，其余短暂自旋等它回填 L2。
 * - 防穿透（penetration）：查询根本不存在的 id（如爬虫/攻击）每次都 miss、每次都打 DB。
 *   DB 查无结果时往 L2 写一个短 TTL 的 {@code __NULL__} 空值哨兵，后续命中哨兵直接返回 null、不打 DB。
 *
 * 容错纪律：Redis/锁/序列化故障绝不阻断读——L2 读写、加锁解锁异常一律降级（miss / 直查 DB / 不回填）。
 * 「缓存与锁是加速器与协调器，不是数据源」，任何一环故障都不能让请求失败，最多退回直查 DB。
 *
 * 测试开关：{@code spring.cache.type=none} 时 enabled=false，get 直接透传 loader、evict 直接返回，
 * L1/L2/锁全程不触碰——保护主测试套件「每用例直查 DB、行为可预测」的既有约定。
 */
@Component
public class TwoLevelCache {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelCache.class);

    /** L2 key 前缀：cache:{cacheName}:{key}，与业务 key（如 interaction:*、feed:*）命名空间隔离。 */
    private static final String L2_KEY_PREFIX = "cache:";

    /**
     * 跨节点失效广播频道前缀：cache:evict:{cacheName}（rp-03）。
     * 订阅方按模式 cache:evict:* 监听（见 CachePubSubConfig），收到消息只清「本节点」的 L1。
     * public 供发布方（本类 evict）与订阅方（CacheEvictionListener / 配置）共用同一命名契约。
     */
    public static final String EVICT_CHANNEL_PREFIX = "cache:evict:";

    /** 重建锁 key 前缀：lock:{cacheName}:{key}，复用 #5 DistributedLock 的命名习惯。 */
    private static final String LOCK_PREFIX = "lock:";

    /**
     * 空值哨兵：DB 查无结果时写入 L2 的占位串。
     * 为什么用一个不可能与真实 JSON 相等的字面量？真实内容序列化后一定是以 '{' 开头的 JSON 对象，
     * 而 "__NULL__" 永远不可能反序列化成目标类型，故能安全区分「缓存了一个空结果」与「缓存了真实内容」。
     */
    private static final String NULL_SENTINEL = "__NULL__";

    /**
     * L2 序列化专用 ObjectMapper（Jackson 2）。
     *
     * 为什么自建而不注入 Spring 的 ObjectMapper bean？
     * Spring Boot 4 默认改用 Jackson 3（包名 tools.jackson.*）做 web 序列化，容器里根本没有
     * com.fasterxml.jackson.databind.ObjectMapper 这个 bean（注入会直接启动失败）。本项目的 Jackson 2
     * 是为 sa-token-redis-jackson 显式引入的（它用 Jackson 2 序列化会话到 Redis）。这里同样用 Jackson 2
     * 自建一个 mapper：① 与现有 Redis 序列化技术栈一致；② 自包含、不依赖 web 层的 Jackson 3 配置；
     * ③ 缓存的类型（PostDetailContent / PublicProfileResponse）由本组件全权掌控，无需复用 web mapper 的定制。
     * 注册 JavaTimeModule 支持 LocalDateTime；关闭 WRITE_DATES_AS_TIMESTAMPS 让日期序列化为 ISO-8601 字符串（可读、跨版本稳定）。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final CacheManager l1CacheManager;
    private final StringRedisTemplate redis;
    private final DistributedLock distributedLock;
    private final boolean enabled;
    private final Duration l2Ttl;
    private final Duration sentinelTtl;
    private final int lockTtlSeconds;
    private final int spinRetries;
    private final long spinMillis;

    /**
     * @param cacheManagerProvider L1 缓存管理器的「可选」提供者。
     *   为什么用 ObjectProvider 而不是直接注入 CacheManager？
     *   本项目 {@code @EnableCaching} 只挂在 CacheConfig 上，而 CacheConfig 是 {@code @ConditionalOnExpression(type != none)}。
     *   当 {@code spring.cache.type=none}（主测试套件）时 CacheConfig 不加载 → 无 {@code @EnableCaching} →
     *   Spring Boot 的 CacheAutoConfiguration（{@code @ConditionalOnBean(CacheAspectSupport)}）也不激活 →
     *   容器里根本没有 CacheManager bean。直接注入会启动失败；用 ObjectProvider.getIfAvailable() 允许其为 null。
     *   而 type=none 时 enabled=false，get/evict 都会提前旁路、从不触碰 L1，故 l1CacheManager 为 null 完全安全。
     * @param lockTtlSeconds   重建锁 TTL（秒）：持锁者若崩溃，锁最多这么久自动释放，不会永久死锁。
     * @param spinRetries      未抢到锁时的自旋次数。
     * @param spinMillis       每次自旋的等待毫秒；总等待窗口 = spinRetries × spinMillis，超过则降级直查 DB。
     * @param sentinelTtlSeconds 空值哨兵 TTL（秒）：比正常内容短，让「刚被创建的资源」不会因哨兵长期不可见。
     */
    public TwoLevelCache(ObjectProvider<CacheManager> cacheManagerProvider,
                         StringRedisTemplate redis,
                         DistributedLock distributedLock,
                         @Value("${spring.cache.type:caffeine}") String cacheType,
                         @Value("${app.cache.l2.ttl-seconds:60}") long l2TtlSeconds,
                         @Value("${app.cache.sentinel-ttl-seconds:30}") long sentinelTtlSeconds,
                         @Value("${app.cache.lock-ttl-seconds:3}") int lockTtlSeconds,
                         @Value("${app.cache.spin-retries:3}") int spinRetries,
                         @Value("${app.cache.spin-millis:50}") long spinMillis) {
        this.l1CacheManager = cacheManagerProvider.getIfAvailable();
        this.redis = redis;
        this.distributedLock = distributedLock;
        this.enabled = !"none".equalsIgnoreCase(cacheType);
        this.l2Ttl = Duration.ofSeconds(l2TtlSeconds);
        this.sentinelTtl = Duration.ofSeconds(sentinelTtlSeconds);
        this.lockTtlSeconds = lockTtlSeconds;
        this.spinRetries = spinRetries;
        this.spinMillis = spinMillis;
    }

    /**
     * 三级读流：L1 hit → 返回；L1 miss → L2 hit → 回填 L1 → 返回；L2 命中空值哨兵 → 返回 null（不打 DB）；
     * L2 miss → 互斥重建（见 {@link #rebuild}）→ 回填 L2 + L1 → 返回。
     *
     * 返回 null 的两种语义（调用方按「资源不存在」处理，如抛 ResourceNotFoundException）：
     * ① loader 真的查无结果（本次写入了哨兵）；② 命中了此前写入的哨兵（本次未查 DB）。
     *
     * @param cacheName 缓存名（同时决定 L1 的 Caffeine 缓存与 L2 的 key 命名空间）
     * @param key       业务键（如 postId / userId）
     * @param type      L2 反序列化目标类型
     * @param loader    L2 也 miss 时的 DB 重建逻辑；返回 null 表示「查无此资源」
     */
    public <T> T get(String cacheName, String key, Class<T> type, Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }
        Cache l1 = l1CacheManager != null ? l1CacheManager.getCache(cacheName) : null;

        // L1：进程内命中，纳秒级返回
        if (l1 != null) {
            Cache.ValueWrapper wrapper = l1.get(key);
            if (wrapper != null) {
                T hit = type.cast(wrapper.get());
                if (hit != null) {
                    return hit;
                }
            }
        }

        // L2：跨节点共享。三态——命中真实值 / 命中空值哨兵 / 未命中
        String l2Key = l2Key(cacheName, key);
        L2Result<T> l2 = readL2(l2Key, type);
        if (l2.state() == L2State.HIT) {
            if (l1 != null) {
                l1.put(key, l2.value());
            }
            return l2.value();
        }
        if (l2.state() == L2State.NULL_SENTINEL) {
            // 命中空值哨兵：DB 里就是没有，直接返回 null（不打 DB）——防穿透
            return null;
        }

        // L2 MISS：互斥重建，防击穿
        return rebuild(cacheName, key, l2Key, type, loader, l1);
    }

    /**
     * 失效一个键（写路径内容变更时调用）：清本节点 L1 + 删共享 L2（含可能存在的空值哨兵）+ 广播通知其他节点清各自 L1（rp-03）。
     *
     * 三步的顺序与理由：
     * 1. 先清本节点 L1（同步、不依赖 pub/sub 往返）——写节点自身第一时间不会读到旧值；
     * 2. 删 L2（跨节点共享层）——其他节点 L1 miss 后回源会看到「已失效」；
     * 3. 广播失效消息——让「其他节点」准实时清掉各自仍持有的 L1（否则要等 L1 短 TTL 到期）。
     * pub/sub 可能丢消息（Redis 瞬断），此时 L1 短 TTL（10s，见 CacheConfig）作为兜底上界自然收敛。
     */
    public void evict(String cacheName, String key) {
        if (!enabled) {
            return;
        }
        evictLocalL1(cacheName, key);
        try {
            redis.delete(l2Key(cacheName, key));
        } catch (RuntimeException e) {
            log.warn("L2 缓存删除失败 cacheName={} key={}: {}", cacheName, key, e.getMessage());
        }
        publishEviction(cacheName, key);
    }

    /**
     * 只清「本节点」的 L1，不触碰 L2、也不再广播——供 pub/sub 订阅者收到其他节点的失效消息时调用。
     * 为什么单独拆出？订阅者收到消息时 L2 早已被发布方删除，此处若再删 L2/再广播会造成重复操作与消息风暴。
     */
    public void evictLocalL1(String cacheName, String key) {
        if (!enabled) {
            return;
        }
        Cache l1 = l1CacheManager != null ? l1CacheManager.getCache(cacheName) : null;
        if (l1 != null) {
            l1.evict(key);
        }
    }

    /** 向 cache:evict:{cacheName} 频道广播失效键。广播失败不阻断写：其他节点由 L1 短 TTL 兜底收敛。 */
    private void publishEviction(String cacheName, String key) {
        try {
            redis.convertAndSend(evictChannel(cacheName), key);
        } catch (RuntimeException e) {
            log.warn("缓存失效广播失败 cacheName={} key={}: {}", cacheName, key, e.getMessage());
        }
    }

    /** 失效广播频道名。public 供订阅方拼接监听模式与解析 cacheName。 */
    public static String evictChannel(String cacheName) {
        return EVICT_CHANNEL_PREFIX + cacheName;
    }

    /**
     * L2 miss 后的互斥重建（rp-02 防击穿核心）：
     * <ol>
     *   <li>抢重建锁。抢到 → 双重检查 L2（可能已被别的线程/节点重建）→ 仍 miss 才查 DB → 回填 L2+L1（或写哨兵）→ 释放锁。</li>
     *   <li>没抢到 → 说明已有人在重建，自旋重读 L2 等它回填；命中即返回。</li>
     *   <li>自旋耗尽仍 miss（重建太慢 / Redis 抖动）→ 降级直查 DB，绝不无限等待。</li>
     * </ol>
     * 全程任何 Redis/锁异常都退回「直查 DB」，保证读路径不被缓存设施拖垮。
     */
    private <T> T rebuild(String cacheName, String key, String l2Key, Class<T> type, Supplier<T> loader, Cache l1) {
        String lockKey = LOCK_PREFIX + cacheName + ":" + key;
        String token = tryLockQuietly(lockKey);
        if (token != null) {
            try {
                // 双重检查：抢到锁后重读 L2，可能就在排队抢锁的间隙别的持锁者已重建完
                L2Result<T> recheck = readL2(l2Key, type);
                if (recheck.state() == L2State.HIT) {
                    if (l1 != null) {
                        l1.put(key, recheck.value());
                    }
                    return recheck.value();
                }
                if (recheck.state() == L2State.NULL_SENTINEL) {
                    return null;
                }
                // 真正重建：只有一个请求走到这里（击穿被收敛为单次 DB 查询）
                T loaded = loader.get();
                if (loaded != null) {
                    writeL2(l2Key, loaded);
                    if (l1 != null) {
                        l1.put(key, loaded);
                    }
                } else {
                    writeNullSentinel(l2Key);
                }
                return loaded;
            } finally {
                unlockQuietly(lockKey, token);
            }
        }

        // 没抢到锁：自旋重读 L2，等持锁者回填
        for (int i = 0; i < spinRetries; i++) {
            if (!sleepQuietly()) {
                break; // 被中断，停止自旋走降级
            }
            L2Result<T> retry = readL2(l2Key, type);
            if (retry.state() == L2State.HIT) {
                if (l1 != null) {
                    l1.put(key, retry.value());
                }
                return retry.value();
            }
            if (retry.state() == L2State.NULL_SENTINEL) {
                return null;
            }
        }

        // 自旋耗尽仍未见重建结果 → 降级直查 DB（不回填，交给持锁者回填，避免与锁竞争写）
        return loader.get();
    }

    private String tryLockQuietly(String lockKey) {
        try {
            return distributedLock.tryLock(lockKey, lockTtlSeconds);
        } catch (RuntimeException e) {
            // Redis 故障：抢锁失败不应阻断读，返回 null 让调用方走自旋→降级直查 DB
            log.warn("缓存重建锁获取失败，降级为直查 lockKey={}: {}", lockKey, e.getMessage());
            return null;
        }
    }

    private void unlockQuietly(String lockKey, String token) {
        try {
            distributedLock.unlock(lockKey, token);
        } catch (RuntimeException e) {
            log.warn("缓存重建锁释放失败（将随 TTL 自动过期）lockKey={}: {}", lockKey, e.getMessage());
        }
    }

    /** @return true 表示正常等待完成；false 表示线程被中断（调用方应停止自旋）。 */
    private boolean sleepQuietly() {
        try {
            Thread.sleep(spinMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** L2 读取的三态结果：区分「命中真实值」「命中空值哨兵」「未命中」，避免用 null 混淆哨兵与 miss。 */
    private enum L2State {
        HIT, NULL_SENTINEL, MISS
    }

    private record L2Result<T>(L2State state, T value) {
        static <T> L2Result<T> hit(T value) {
            return new L2Result<>(L2State.HIT, value);
        }

        static <T> L2Result<T> nullSentinel() {
            return new L2Result<>(L2State.NULL_SENTINEL, null);
        }

        static <T> L2Result<T> miss() {
            return new L2Result<>(L2State.MISS, null);
        }
    }

    private <T> L2Result<T> readL2(String l2Key, Class<T> type) {
        try {
            String json = redis.opsForValue().get(l2Key);
            if (json == null) {
                return L2Result.miss();
            }
            if (NULL_SENTINEL.equals(json)) {
                return L2Result.nullSentinel();
            }
            return L2Result.hit(MAPPER.readValue(json, type));
        } catch (RuntimeException | IOException e) {
            // Redis 不可达或旧结构反序列化失败 → 降级为 miss，请求继续走 DB（缓存不阻断读）
            log.warn("L2 缓存读取/反序列化失败 key={}: {}", l2Key, e.getMessage());
            return L2Result.miss();
        }
    }

    private void writeL2(String l2Key, Object value) {
        try {
            redis.opsForValue().set(l2Key, MAPPER.writeValueAsString(value), l2Ttl);
        } catch (RuntimeException | IOException e) {
            log.warn("L2 缓存写入/序列化失败 key={}: {}", l2Key, e.getMessage());
        }
    }

    /** 写入空值哨兵（短 TTL）：DB 查无结果时占位，后续读命中哨兵直接返回 null、不打 DB——防穿透。 */
    private void writeNullSentinel(String l2Key) {
        try {
            redis.opsForValue().set(l2Key, NULL_SENTINEL, sentinelTtl);
        } catch (RuntimeException e) {
            log.warn("空值哨兵写入失败 key={}: {}", l2Key, e.getMessage());
        }
    }

    private String l2Key(String cacheName, String key) {
        return L2_KEY_PREFIX + cacheName + ":" + key;
    }
}
