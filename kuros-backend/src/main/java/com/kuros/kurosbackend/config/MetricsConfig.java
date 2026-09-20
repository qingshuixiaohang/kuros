package com.kuros.kurosbackend.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自定义 Micrometer 指标配置。
 *
 * 为什么要有自定义业务指标？
 * Spring Boot Actuator 默认提供 JVM、HTTP 等基础设施指标，
 * 但业务指标（如"今天发了多少帖子"）需要手动埋点。
 * 这些指标可以直接在 Grafana 看板上展示，也可以设置告警阈值。
 *
 * Counter vs Gauge vs Timer：
 * - Counter（计数器）：只增不减，适合统计总发布量
 * - Gauge（仪表盘）：可增可减，适合统计当前在线用户数
 * - Timer（计时器）：记录事件耗时和次数，适合统计接口响应时间
 */
@Configuration
public class MetricsConfig {

    /**
     * 帖子发布计数器：每发布一个帖子 +1，只增不减。
     * 可以在 Grafana 上用 rate() 函数算出"每分钟发帖速率"。
     */
    @Bean
    public Counter postsPublishedCounter(MeterRegistry registry) {
        return Counter.builder("posts.published.total")
                .description("帖子发布总数")
                .register(registry);
    }

    /**
     * 帖子发布耗时计时器：记录每次 publish() 的耗时。
     * 自动统计 p50/p95/p99 响应时间，用于发现性能退化。
     */
    @Bean
    public Timer postsPublishTimer(MeterRegistry registry) {
        return Timer.builder("posts.publish.duration")
                .description("帖子发布耗时")
                .register(registry);
    }
}
