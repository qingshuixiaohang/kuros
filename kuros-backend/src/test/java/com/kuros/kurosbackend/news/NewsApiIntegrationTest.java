package com.kuros.kurosbackend.news;

import com.kuros.kurosbackend.TestDatabases;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 资讯域（news）的 HTTP 契约测试 - 前端审计 H-9 的第一块。
 *
 * 为什么单独一个测试类而不是并进 KurosBackendApplicationTests：news 是
 * 一个独立域的接入，它的契约（游客可读、按分类过滤、软下线不可见、
 * 未知 id 返回业务错误码）应该有自己的失败信号，混在大类里一旦红很难
 * 看出是哪个域的问题。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NewsApiIntegrationTest {

    /**
     * news 域只读静态内容，不碰 Redis / MQ，所以用 H2 而不是 MySQL 容器。
     * 每个测试类一个唯一库名（TestDatabases）——沿用切片 #10 的修复，
     * 否则全量跑时会和别的测试类串库。
     */
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("news"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 游客可读资讯列表且只返回已发布条目() throws Exception {
        mockMvc.perform(get("/api/v1/news"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].slug").value("version-preview"))
                .andExpect(jsonPath("$.data[1].slug").value("maintenance-notice"))
                .andExpect(jsonPath("$.meta.totalItems").value(2));
    }

    @Test
    void 资讯列表支持按分类过滤() throws Exception {
        mockMvc.perform(get("/api/v1/news").param("category", "官方公告"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].slug").value("maintenance-notice"));
    }

    @Test
    void 资讯详情按slug可读且正文分段返回() throws Exception {
        mockMvc.perform(get("/api/v1/news/version-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("version-preview"))
                .andExpect(jsonPath("$.data.title").value("鸣潮 2.4 版本前瞻特别节目总结"))
                .andExpect(jsonPath("$.data.content", hasSize(2)));
    }

    @Test
    void 未发布与未知资讯一律返回资讯未找到() throws Exception {
        mockMvc.perform(get("/api/v1/news/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NEWS_NOT_FOUND"));

        // 软下线的条目不能因为 id 可猜就绕过 - 与 character 域同一契约
        mockMvc.perform(get("/api/v1/news/unpublished-draft"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NEWS_NOT_FOUND"));
    }
}
