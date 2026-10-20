package com.kuros.kurosbackend.echoes;

import com.kuros.kurosbackend.TestDatabases;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 声骸域（echoes）的 HTTP 契约测试 - 前端审计 H-9 的第二块。
 *
 * 契约与 news / character 两域对齐：游客可读、按 role 过滤、软下线不可见、
 * 未知 slug 返回业务错误码。单独一个测试类而不是并进
 * KurosBackendApplicationTests——每个域的接入要有自己的失败信号。
 *
 * 用 H2 而非 MySQL 容器：echoes 是纯只读静态内容，不碰 Redis/MQ。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EchoApiIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("echoes"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 清理排序测试夹具() {
        jdbcTemplate.update("DELETE FROM echo_sets WHERE id LIKE '70000000-%'");
    }

    @Test
    void 游客可读声骸列表且只返回已上线条目() throws Exception {
        mockMvc.perform(get("/api/v1/echoes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(4)))
                .andExpect(jsonPath("$.data[0].slug").value("floating-stars"))
                .andExpect(jsonPath("$.data[1].slug").value("thunder"))
                .andExpect(jsonPath("$.meta.totalItems").value(4));
    }

    @Test
    void 声骸列表按排序值和名称稳定排序() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO echo_sets
                    (id, slug, name, effect, location, role, description, guide_slug, sort_order, enabled, created_at, updated_at)
                VALUES
                    ('70000000-0000-0000-0000-000000000003', 'sort-a2', 'Alpha', '效果', '位置', '输出', '描述', NULL, 5, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    ('70000000-0000-0000-0000-000000000002', 'sort-a', 'Alpha', '效果', '位置', '输出', '描述', NULL, 5, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    ('70000000-0000-0000-0000-000000000001', 'sort-z', 'Beta', '效果', '位置', '输出', '描述', NULL, 5, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);

        mockMvc.perform(get("/api/v1/echoes").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].slug").value("sort-a"))
                .andExpect(jsonPath("$.data[1].slug").value("sort-a2"))
                .andExpect(jsonPath("$.data[2].slug").value("sort-z"))
                .andExpect(jsonPath("$.data[3].slug").value("floating-stars"));
    }

    @Test
    void 声骸列表支持按定位过滤() throws Exception {
        mockMvc.perform(get("/api/v1/echoes").param("role", "协同"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].slug").value("light-cloud"));
    }

    @Test
    void 声骸详情按slug可读且带完整字段() throws Exception {
        mockMvc.perform(get("/api/v1/echoes/floating-stars"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("floating-stars"))
                .andExpect(jsonPath("$.data.name").value("浮星祛暗"))
                .andExpect(jsonPath("$.data.effect").value("湮灭伤害提升"))
                .andExpect(jsonPath("$.data.location").value("荒石高地 / 无音区"))
                .andExpect(jsonPath("$.data.role").value("输出"));
    }

    @Test
    void 未关联攻略的声骸返回空guideSlug而不是假链接() throws Exception {
        mockMvc.perform(get("/api/v1/echoes/hidden-light"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.guideSlug").isEmpty());
    }

    @Test
    void 未上线与未知声骸一律返回声骸未找到() throws Exception {
        mockMvc.perform(get("/api/v1/echoes/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ECHO_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/echoes/disabled-set"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ECHO_NOT_FOUND"));
    }
}
