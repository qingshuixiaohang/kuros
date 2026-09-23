package com.kuros.kurosbackend.shared.config;

import com.kuros.kurosbackend.shared.cache.CacheEvictionListener;
import com.kuros.kurosbackend.shared.cache.TwoLevelCache;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 缓存失效 pub/sub 订阅容器（切片 #13 / rp-03 / ADR 0006 D4）。
 *
 * 为什么单独一个 RedisMessageListenerContainer？Spring Boot 不会自动创建它——要接收 Redis 频道消息，
 * 必须显式装配一个监听容器并把 {@link CacheEvictionListener} 注册到目标频道/模式上。这里用 PatternTopic
 * 订阅 cache:evict:*，一个容器即覆盖所有缓存名（postDetail、publicProfile，及未来新增的），无需逐个登记。
 *
 * 为什么用 @ConditionalOnExpression(type != none) 与 CacheConfig 同步门控？
 * 主测试套件用 spring.cache.type=none 禁用两级缓存（每用例直查 DB），此时 TwoLevelCache 整体旁路、evict 不广播，
 * 也就不该启动订阅线程去连 Redis。保持与 CacheConfig 相同的开关条件，避免在禁用缓存的环境里留下无谓的后台容器。
 */
@Configuration
@ConditionalOnExpression("'${spring.cache.type:caffeine}' != 'none'")
public class CachePubSubConfig {

    @Bean
    public RedisMessageListenerContainer cacheEvictionListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheEvictionListener cacheEvictionListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(cacheEvictionListener,
                new PatternTopic(TwoLevelCache.EVICT_CHANNEL_PREFIX + "*"));
        return container;
    }
}
