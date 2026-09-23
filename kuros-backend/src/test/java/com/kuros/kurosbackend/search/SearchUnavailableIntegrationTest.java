package com.kuros.kurosbackend.search;

import com.kuros.kurosbackend.TestDatabases;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 搜索降级集成测试（切片 #14 se-04，S1 seam 的降级分支）。
 *
 * <p>验证 ADR 0007 D8 的核心承诺——ES 是 backend 的「软依赖」：
 * <ol>
 *   <li>ES 不可用时 Spring 上下文照常启动（懒连接，不在启动期连 ES）；</li>
 *   <li>{@code GET /api/v1/search} 捕获 ES 连接失败 → 明确降级为 503 {@code SERVICE_UNAVAILABLE}
 *       「搜索服务暂不可用」，而非静默返回空结果、也非 500 崩溃——前端据此渲染降级提示，主链路（MySQL+Redis）零影响。</li>
 * </ol>
 *
 * <p>为什么单独一个测试类：本类刻意把 {@code spring.elasticsearch.uris} 指向一个**关闭的端口**制造「ES 不可用」，
 * 与 {@code SearchHttpContractIntegrationTest}（真实 ES 容器）的上下文互斥，故拆开（每类独立 JVM，reuseForks=false）。
 *
 * <p>⚠️ 默认跳过（gated）：与其它 ES 测试同款 {@code -Dkuros.it.es=true} 门控——虽不需要真实 ES，
 * 但需要 Docker 起 redis 容器供上下文装配，统一进门控档避免拖慢 CI 裸 {@code mvn test}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("es-search")
@EnabledIfSystemProperty(named = "kuros.it.es", matches = "true")
class SearchUnavailableIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("search-unavailable"));
        // 指向一个必定关闭的端口 → 连接被拒（connection refused，快速失败），模拟 ES 不可用
        registry.add("spring.elasticsearch.uris", () -> "http://127.0.0.1:59999");
    }

    @Autowired MockMvc mockMvc;

    @Test
    void esDown时搜索降级为503SERVICE_UNAVAILABLE() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("keyword", "鸣潮"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }
}
