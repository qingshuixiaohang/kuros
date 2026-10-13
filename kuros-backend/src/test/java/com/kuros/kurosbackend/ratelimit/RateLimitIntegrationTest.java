package com.kuros.kurosbackend.ratelimit;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.kuros.kurosbackend.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sentinel 限流行为集成测试。
 *
 * 审计 M9 / D1：原「超过QPS阈值后返回429」用例是恒绿的假测试——阈值 100
 * 却只发 20 个请求，方法体内一个断言都没有，靠"如果碰巧 429 了就顺带
 * 检查响应体"蒙混过关。它永远不会失败，也就永远证明不了限流真的工作。
 *
 * 改造思路：阈值改成测试内可控（直接操作 FlowRuleManager，这是 Sentinel
 * 官方支持的运行时改规则方式，也是 SentinelRuleRefresher 内部用的同一 API），
 * 于是可以构造"必然触发"与"必然不触发"两个确定性场景，各自带真断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
/**
 * 只覆盖开关，不覆盖阈值：test profile 默认 app.sentinel.enabled=false
 * （见 application-test.properties），不覆盖的话拦截器根本不注册，
 * 所有用例都会以 200 通过——那是另一重假绿。
 * 阈值一律由各用例通过 FlowRuleManager 动态设定，才能构造确定性的
 * "必然触发"与"必然不触发"。
 */
@TestPropertySource(properties = "app.sentinel.enabled=true")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RateLimitIntegrationTest {

    /** 帖子列表的 Sentinel 资源名，须与 SentinelRateLimitInterceptor.resolveResource 一致。 */
    private static final String RESOURCE_POSTS_LIST = "api-posts-list";

    /** 种子数据里的一个已发布帖子 id，用于验证其他资源不受影响。 */
    private static final String SEED_POST_ID = "10000000-0000-0000-0000-000000000001";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // 唯一 H2 库名：库生命周期与本类 context 对齐（详见 TestDatabases 注释）
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("ratelimit"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 保存测试前的全部规则，tearDown 恢复，避免污染同 context 内的其他用例。 */
    private List<FlowRule> originalRules;

    @BeforeEach
    void setUp() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection().serverCommands().flushDb();
        // FlowRuleManager 1.8.x 只有全量 getRules()，没有按资源查的 API
        originalRules = FlowRuleManager.getRules();
        // flushDb 只清 Redis，不会重置 Sentinel 的秒级滑动窗口计数。
        // 同一秒内前一个用例打满的配额会让下一个用例的第一个请求就被 429，
        // 所以每个用例前先把目标资源的统计清零。
        resetStatistics(RESOURCE_POSTS_LIST);
        resetStatistics("api-post-detail");
    }

    @AfterEach
    void tearDown() {
        FlowRuleManager.loadRules(originalRules == null ? List.of() : originalRules);
        resetStatistics(RESOURCE_POSTS_LIST);
        resetStatistics("api-post-detail");
    }

    /**
     * 重置某资源的 Sentinel 统计（秒级滑动窗口与线程数）。
     *
     * 为什么需要它：Sentinel 的 QPS 计数是进程内滑动窗口，跨测试方法累积。
     * 不清零的话，用例 A 打满配额后，同一秒内用例 B 的第一个请求会被
     * 误判为超限——那测的是"统计泄漏"，不是限流行为本身。
     */
    private void resetStatistics(String resource) {
        // getClusterNode 在该版本返回 ClusterNode 本身（非 Optional），
        // 资源从未被访问过时返回 null
        ClusterNode node = ClusterBuilderSlot.getClusterNode(resource);
        if (node != null) {
            node.reset();
        }
    }

    @Test
    void 正常请求不会被限流() throws Exception {
        // 阈值设得远高于请求数：这一条必须全绿，证明限流不误伤正常流量
        setQps(RESOURCE_POSTS_LIST, 1000);

        for (int i = 0; i < 20; i++) {
            mockMvc.perform(get("/api/v1/posts")
                            .param("page", "1")
                            .param("pageSize", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    /**
     * 阈值压到 1：第 1 个请求通过、第 2 个起必然 429。
     *
     * 这个用例是 D1 的核心——它必须能在规则被破坏时失败：
     * 拦截器被删、规则没注册、或 429 响应体格式错了，它都会红。
     */
    @Test
    void 超过QPS阈值后返回429() throws Exception {
        setQps(RESOURCE_POSTS_LIST, 1);

        // 第 1 个请求：配额内，必须放行
        mockMvc.perform(get("/api/v1/posts")
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk());

        // 第 2 个请求：配额已用尽，必须 429
        mockMvc.perform(get("/api/v1/posts")
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("请求过于频繁，请稍后再试"));
    }

    /**
     * 阈值 1 时第 3、4 个请求也持续 429：限流不是"只拦一次"的一次性行为。
     */
    @Test
    void 限流在配额恢复前持续生效() throws Exception {
        setQps(RESOURCE_POSTS_LIST, 1);

        mockMvc.perform(get("/api/v1/posts").param("page", "1").param("pageSize", "10"))
                .andExpect(status().isOk());

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/v1/posts").param("page", "1").param("pageSize", "10"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        }
    }

    /**
     * 别的资源不受本资源限流影响：证明限流是按资源名隔离的，
     * 不会因为帖子列表被打满而误伤帖子详情。
     */
    @Test
    void 其他资源的限流相互隔离() throws Exception {
        setQps(RESOURCE_POSTS_LIST, 1);

        // 打满帖子列表配额
        mockMvc.perform(get("/api/v1/posts").param("page", "1").param("pageSize", "10"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/posts").param("page", "1").param("pageSize", "10"))
                .andExpect(status().isTooManyRequests());

        // 帖子详情走 api-post-detail 资源（阈值仍是启动时的 100），必须放行
        mockMvc.perform(get("/api/v1/posts/" + SEED_POST_ID))
                .andExpect(status().isOk());
    }

    /**
     * 运行时把某资源的 QPS 改成指定值，保留其他资源的规则不变。
     *
     * 与 SentinelRuleRefresher.reload 用的是同一个 FlowRuleManager.loadRules，
     * 只是这里由测试直接驱动——官方支持的运行时改规则方式，不改生产代码。
     *
     * 为什么要保留其他规则：loadRules 是全量替换语义，只传一条会把
     * api-post-detail、api-default 的规则一并清掉，那样"其他资源隔离"
     * 用例就变成在验证"规则不存在所以放行"，而不是"规则存在且互不干扰"。
     */
    private void setQps(String resource, int qps) {
        List<FlowRule> rules = FlowRuleManager.getRules().stream()
                .filter(rule -> !resource.equals(rule.getResource()))
                .collect(Collectors.toCollection(ArrayList::new));

        FlowRule target = new FlowRule();
        target.setResource(resource);
        target.setGrade(RuleConstant.FLOW_GRADE_QPS);
        target.setCount(qps);
        rules.add(target);

        FlowRuleManager.loadRules(rules);
    }
}
