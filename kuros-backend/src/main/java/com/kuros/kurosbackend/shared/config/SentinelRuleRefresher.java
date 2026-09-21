package com.kuros.kurosbackend.shared.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * Sentinel 限流规则加载器：启动时从 Environment 读取阈值注册规则，
 * 并监听配置刷新事件（Nacos 控制台改配置 → RefreshEvent → RefreshScopeRefreshedEvent），
 * 在不重启应用的情况下重新 loadRules（切片 #8 / nacos-02 的动态刷新主场景）。
 *
 * 为什么不用 @RefreshScope？
 * @RefreshScope 的 bean 是懒重建语义：refresh 事件只清掉缓存实例，要等下一次有人
 * 访问该 bean 才会重新创建，而规则注册发生在创建期（@PostConstruct）——
 * 没人主动访问这个 bean，改完配置规则就永远不会更新。
 * 直接监听刷新事件重读 Environment 是 eager 路径：配置一变，下一次请求就是新阈值。
 *
 * 对应 SpringDocStatusBridge 的 @RefreshScope lazy 路径，两者构成动态刷新的两种姿势。
 */
public class SentinelRuleRefresher {

    private static final Logger log = LoggerFactory.getLogger(SentinelRuleRefresher.class);

    private final Environment environment;

    public SentinelRuleRefresher(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void loadAtStartup() {
        reload("startup");
    }

    @EventListener(RefreshScopeRefreshedEvent.class)
    public void reloadOnConfigRefresh() {
        reload("config-refresh");
    }

    private void reload(String trigger) {
        List<FlowRule> rules = List.of(
                // 帖子列表：高频读，QPS 上限较高
                rule("api-posts-list", qps("app.sentinel.posts-list-qps", 100)),
                // 帖子详情：聚合查询较重，QPS 上限较低
                rule("api-post-detail", qps("app.sentinel.post-detail-qps", 50)),
                // 兜底规则：覆盖其余 API（原 api-auth-code 随认证链路迁出，split-06）
                rule("api-default", qps("app.sentinel.default-qps", 200))
        );
        FlowRuleManager.loadRules(rules);
        log.info("Sentinel 限流规则已加载（触发：{}）：posts-list={} post-detail={} default={}",
                trigger,
                qps("app.sentinel.posts-list-qps", 100),
                qps("app.sentinel.post-detail-qps", 50),
                qps("app.sentinel.default-qps", 200));
    }

    /**
     * 从 Environment 现值读取：Nacos 配置导入的属性源优先级高于本地
     * application.properties，覆盖值会先被命中；本地默认值兜底。
     */
    private int qps(String key, int defaultValue) {
        return environment.getProperty(key, Integer.class, defaultValue);
    }

    private static FlowRule rule(String resource, int qps) {
        FlowRule rule = new FlowRule();
        rule.setResource(resource);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(qps);
        return rule;
    }
}
