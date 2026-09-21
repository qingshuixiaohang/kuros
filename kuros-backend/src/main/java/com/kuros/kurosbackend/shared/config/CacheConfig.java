package com.kuros.kurosbackend.shared.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cache.annotation.EnableCaching;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存配置。
 *
 * 为什么选 Caffeine 而不是 Redis 做这一层缓存？
 * 1. 帖子详情是读多写少的热数据，本地缓存命中时延迟在纳秒级（Redis 是毫秒级）
 * 2. 当前单体部署，不存在多节点缓存一致性问题
 * 3. Caffeine 基于 W-TinyLFU 淘汰算法，命中率优于 LRU
 *
 * split-07：原 publicProfile 缓存随用户域迁出移除——ProfileService.findPublic
 * 已整端降级为 503（详见该类注释），该缓存不再有读写方。
 * split-08：publicProfile 缓存恢复——findPublic 经 Feign 回填后重新成为高频读路径，
 * 且组合视图跨了一次网络调用（kuros-user），缓存收益比拆分前更大。
 *
 * publicProfile 的失效策略与 postDetail 不同：postDetail 的所有写路径都在本服务内，
 * 可用 @CacheEvict 精确驱逐；而 publicProfile 里的昵称/头像来自 kuros-user，
 * 用户在彼端改资料时本服务无从感知（没有跨服务事件通道），因此只能靠 60s TTL 兜底最终一致。
 * 生产化需要"改资料即失效"的强一致时，须接入事件驱动失效（留待 RocketMQ 切片：
 * kuros-user 发用户变更事件 → backend 消费后驱逐对应 publicProfile 缓存键）。
 *
 * TTL 设 60 秒是兜底策略——即使 @CacheEvict 漏掉了某个写路径，
 * 缓存数据最多落后 60 秒，对社区论坛类应用完全可接受。
 *
 * 对应小哈书第八章：Caffeine 本地缓存。
 */
@Configuration
@EnableCaching
// 当 spring.cache.type=none 时不创建 Caffeine 缓存管理器，
// 否则自定义 Bean 会覆盖 Spring Boot 自动配置的 NoOpCacheManager，
// 导致测试环境（cache.type=none）仍然启用缓存
@ConditionalOnExpression("'${spring.cache.type:caffeine}' != 'none'")
public class CacheConfig {

    /**
     * 缓存管理器：统一管理所有缓存名的 TTL 和容量。
     * maximumSize(1000) 防止内存膨胀——1000 个条目大约占几 MB，安全可控。
     *
     * CaffeineCacheManager 以显式缓存名构造时处于"静态模式"：只托管列出的名字，
     * 未列出的缓存名 getCache 返回 null → @Cacheable 静默不生效（不报错但不缓存）。
     * 因此 split-08 恢复 publicProfile 必须在这里补上名字，否则 findPublic 的 @Cacheable 形同虚设。
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("postDetail", "publicProfile");
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(1000));
        return manager;
    }
}
