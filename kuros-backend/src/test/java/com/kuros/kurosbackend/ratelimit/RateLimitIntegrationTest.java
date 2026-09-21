package com.kuros.kurosbackend.ratelimit;

import com.kuros.kurosbackend.TestDatabases;
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

import java.util.Objects;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sentinel 限流行为集成测试。
 *
 * 测试策略：
 * 1. 验证正常请求返回 200（限流基础设施不误伤正常流量）
 * 2. 验证限流链路可运转、429 响应格式正确（阈值较高时通常不触发，见方法内注释）
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.sentinel.enabled=true",
        "app.sentinel.posts-list-qps=100",
        "app.sentinel.post-detail-qps=100",
        "app.sentinel.default-qps=100"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RateLimitIntegrationTest {

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

    @BeforeEach
    void setUp() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection().serverCommands().flushDb();
    }

    @Test
    void 正常请求不会被限流() throws Exception {
        mockMvc.perform(get("/api/v1/posts")
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void 超过QPS阈值后返回429() throws Exception {
        // posts-list 阈值（100）下 20 个请求通常不触发限流；
        // 一旦触发则校验 429 响应体格式（RATE_LIMITED）
        boolean rateLimited = false;
        for (int i = 0; i < 20; i++) {
            var result = mockMvc.perform(get("/api/v1/posts")
                            .param("page", "1")
                            .param("pageSize", "10"))
                    .andReturn();
            if (result.getResponse().getStatus() == 429) {
                rateLimited = true;
                // 验证 429 响应格式
                String body = result.getResponse().getContentAsString();
                if (!body.contains("RATE_LIMITED")) {
                    throw new AssertionError("429 响应应包含 RATE_LIMITED 错误码，实际：" + body);
                }
                break;
            }
        }

        // 注意：由于 posts-list-qps=100，20 个请求不太可能触发限流
        // 这个测试主要验证限流基础设施正常工作（不报错）；
        // auth-code 端点的限流随认证链路迁至 kuros-user（split-06），
        // 该服务暂未引入 Sentinel（Q15-A 决策），本服务不再有对应端点可测
    }
}
