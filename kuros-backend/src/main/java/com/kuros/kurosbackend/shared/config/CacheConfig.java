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
 * 1. 帖子详情和用户资料是读多写少的热数据，本地缓存命中时延迟在纳秒级（Redis 是毫秒级）
 * 2. 当前单体部署，不存在多节点缓存一致性问题
 * 3. Caffeine 基于 W-TinyLFU 淘汰算法，命中率优于 LRU
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
     * maximumSize(1000) 防止内存膨胀——1000 个帖子详情大约占几 MB，安全可控。
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
