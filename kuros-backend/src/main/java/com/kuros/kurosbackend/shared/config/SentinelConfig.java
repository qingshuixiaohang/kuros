package com.kuros.kurosbackend.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Sentinel 限流规则配置。
 *
 * 为什么不用 Sentinel Dashboard？
 * 学习阶段只需要几个固定的 QPS 规则，代码中硬编码即可。
 * Dashboard 适合生产环境动态调整规则，当前阶段引入是过度设计。
 * 切片 #8 之后规则阈值可从 Nacos 配置中心动态刷新（SentinelRuleRefresher），
 * 取代了 Dashboard 的核心场景。
 *
 * 为什么用 @ConditionalOnProperty 而不是 @Profile？
 * 与 CacheConfig 同理：@ConditionalOnProperty 是细粒度开关，
 * test profile 可以通过 app.sentinel.enabled=false 关闭限流。
 *
 * 对应小哈书第九章：Sentinel 限流熔断。
 */
@Configuration
@ConditionalOnProperty(name = "app.sentinel.enabled", havingValue = "true", matchIfMissing = true)
public class SentinelConfig {

    /**
     * 限流规则注册与刷新职责在 SentinelRuleRefresher：
     * 不能用 @RefreshScope 承担（懒重建语义，无人访问不会重新注册规则），
     * 详见该类的 javadoc。
     */
    @Bean
    public SentinelRuleRefresher sentinelRuleRefresher(org.springframework.core.env.Environment environment) {
        return new SentinelRuleRefresher(environment);
    }

    @Bean
    public SentinelRateLimitInterceptor sentinelRateLimitInterceptor() {
        return new SentinelRateLimitInterceptor();
    }
}
