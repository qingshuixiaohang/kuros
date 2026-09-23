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
 * L1 本地缓存（Caffeine）配置——切片 #13 起作为两级缓存的「进程内第一级」。
 *
 * 两级缓存分工（详见 shared.cache.TwoLevelCache 与 ADR 0006）：
 * - L1 Caffeine：进程内、纳秒级、TTL 10s（本类配置）；
 * - L2 Redis：跨节点共享、毫秒级、TTL 60s（TwoLevelCache 用 app.cache.l2.ttl-seconds 配置）。
 *
 * 为什么 L1 TTL 从 60s 收紧到 10s？
 * 多节点部署下，某节点写路径驱逐只能清「本节点 L1 + 共享 L2」，其他节点的 L1 靠 pub/sub 广播失效（rp-03）。
 * pub/sub 可能丢消息（Redis 瞬断），L1 短 TTL 就是「跨节点不一致窗口」的硬上界——最长 10s 自然收敛。
 * 单看本节点，10s L1 已足以吸收热点读；更长的共享窗口交给 L2（60s）兜底，命中 L2 后回填 L1。
 *
 * 为什么选 Caffeine 做 L1 而不是直接全放 Redis？
 * 1. 热数据本地命中延迟纳秒级（Redis 是毫秒级 + 一次网络往返），L1 挡掉绝大多数重复读；
 * 2. Caffeine 基于 W-TinyLFU 淘汰算法，命中率优于 LRU。
 *
 * publicProfile 的失效与 postDetail 不同：postDetail 的写路径都在本服务内，可显式驱逐（TwoLevelCache.evict）；
 * 而 publicProfile 的昵称/头像来自 kuros-user，用户在彼端改资料时本服务无从感知（无跨服务事件通道），
 * 只能靠 L1 10s + L2 60s TTL 兜底最终一致（最长 60s）。生产化需「改资料即失效」的强一致时，
 * 须接入事件驱动失效（留待 RocketMQ 切片：kuros-user 发用户变更事件 → backend 消费后驱逐对应键）。
 *
 * 对应小哈书第八章：Caffeine 本地缓存（#13 升级为两级缓存的 L1）。
 */
@Configuration
@EnableCaching
// 当 spring.cache.type=none 时不创建 Caffeine 缓存管理器，
// 否则自定义 Bean 会覆盖 Spring Boot 自动配置的 NoOpCacheManager，
// 导致测试环境（cache.type=none）仍然启用缓存
@ConditionalOnExpression("'${spring.cache.type:caffeine}' != 'none'")
public class CacheConfig {

    /**
     * L1 缓存管理器：统一管理所有缓存名的 TTL 和容量。
     * maximumSize(1000) 防止内存膨胀——1000 个条目大约占几 MB，安全可控。
     * expireAfterWrite(10s)：L1 短 TTL，作为跨节点不一致窗口的硬上界（见类注释）。
     *
     * CaffeineCacheManager 以显式缓存名构造时处于"静态模式"：只托管列出的名字，
     * 未列出的缓存名 getCache 返回 null。TwoLevelCache 对 null 的 L1 会跳过本地层、只用 L2，
     * 故新增缓存名必须在这里登记，否则该名字退化为「仅 L2」。
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("postDetail", "publicProfile");
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.SECONDS)
                .maximumSize(1000));
        return manager;
    }
}
