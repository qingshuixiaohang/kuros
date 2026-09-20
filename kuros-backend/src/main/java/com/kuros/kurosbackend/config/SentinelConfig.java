package com.kuros.kurosbackend.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Sentinel 限流规则配置。
 *
 * 为什么不用 Sentinel Dashboard？
 * 学习阶段只需要几个固定的 QPS 规则，代码中硬编码即可。
 * Dashboard 适合生产环境动态调整规则，当前阶段引入是过度设计。
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

    @Value("${app.sentinel.posts-list-qps:100}")
    private int postsListQps;

    @Value("${app.sentinel.post-detail-qps:50}")
    private int postDetailQps;

    @Value("${app.sentinel.auth-code-qps:10}")
    private int authCodeQps;

    @Value("${app.sentinel.default-qps:200}")
    private int defaultQps;

    /**
     * 应用启动时注册限流规则。
     * FlowRule 的 grade 设为 FLOW_GRADE_QPS（按 QPS 限流），
     * 超过阈值的请求会被直接拒绝（快速失败策略）。
     */
    @PostConstruct
    public void registerRules() {
        List<FlowRule> rules = List.of(
                // 帖子列表：高频读，QPS 上限较高
                rule("api-posts-list", postsListQps),
                // 帖子详情：聚合查询较重，QPS 上限较低
                rule("api-post-detail", postDetailQps),
                // 验证码发送：防刷，QPS 极低
                rule("api-auth-code", authCodeQps),
                // 兜底规则：覆盖其余 API
                rule("api-default", defaultQps)
        );
        FlowRuleManager.loadRules(rules);
    }

    @Bean
    public SentinelRateLimitInterceptor sentinelRateLimitInterceptor() {
        return new SentinelRateLimitInterceptor();
    }

    private FlowRule rule(String resource, int qps) {
        FlowRule rule = new FlowRule();
        rule.setResource(resource);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(qps);
        return rule;
    }
}
