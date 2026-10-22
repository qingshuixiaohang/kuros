package com.kuros.kurosbackend.post.service;

import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.media.domain.MediaAsset;
import com.kuros.kurosbackend.media.repository.MediaAssetRepository;
import com.kuros.kurosbackend.post.api.CreatePostRequest;
import com.kuros.kurosbackend.post.api.PostDetailResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 摘要自动生成（summarize 兜底）的回归测试。
 *
 * 真实踩坑（2026-10-06，用户发图片帖发现）：正文插入图片后内容以
 * {@code ![O7.jpg](http://localhost:8080/media/uuid.jpg)} 开头，旧 summarize 只清
 * Markdown 修饰符号、不清图片/链接语法，导致摘要原样展示裸 URL，且 UUID 里的
 * 连字符还被当 Markdown 列表符号替换成空格（"50f219b7 195a 412f ..."）。
 * 前端发布时不传 excerpt，摘要全靠后端兜底，所以必须在 summarize 处剥语法。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.cache.type=none")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostExcerptSummarizeTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final String TEST_USER_ID = "10000000-0000-0000-0000-000000000001";
    private static final String STORAGE_KEY = "50f219b7-195a-412f-b16a-43f32bdb36a9.jpg";

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // 唯一 H2 库名：库生命周期与本类 context 对齐（详见 TestDatabases 注释）
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("excerpt"));
    }

    @Autowired
    private PostPublishingService publishingService;
    @Autowired
    private MediaAssetRepository mediaAssets;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM post_media");
        jdbcTemplate.update("DELETE FROM media_assets");
        mediaAssets.save(MediaAsset.temporary(
                TEST_USER_ID, STORAGE_KEY, "O7.jpg", "image/jpeg", 8, LocalDateTime.now()));
    }

    @Test
    void 图片开头的正文摘要不应包含Markdown图片语法和裸URL() {
        CreatePostRequest request = new CreatePostRequest(
                "GUIDE", "攻略", "图片帖摘要测试", "",
                "![O7.jpg](/media/" + STORAGE_KEY + ")\n\n这是正文说明文字。",
                List.of(), List.of()
        );
        PostDetailResponse published = publishingService.publish(request, TEST_USER_ID);
        String excerpt = published.excerpt();
        assertFalse(excerpt.contains("!["));
        assertFalse(excerpt.contains("http"));
        assertFalse(excerpt.contains("]("));
        assertTrue(excerpt.contains("这是正文说明文字"));
    }

    @Test
    void 纯图片无文字的帖子摘要应回退为图片名() {
        CreatePostRequest request = new CreatePostRequest(
                "GENERAL", "心得分享", "纯图帖摘要测试", "",
                "![O7.jpg](/media/" + STORAGE_KEY + ")",
                List.of(), List.of()
        );
        PostDetailResponse published = publishingService.publish(request, TEST_USER_ID);
        assertEquals("O7.jpg", published.excerpt());
    }

    @Test
    void 链接语法的正文摘要应保留链接文字并剔除URL() {
        CreatePostRequest request = new CreatePostRequest(
                "GUIDE", "配队攻略", "链接帖摘要测试", "",
                "参考[长离培养攻略](http://localhost:3000/guides/changli)的思路。",
                List.of(), List.of()
        );
        PostDetailResponse published = publishingService.publish(request, TEST_USER_ID);
        String excerpt = published.excerpt();
        assertFalse(excerpt.contains("http"));
        assertTrue(excerpt.contains("长离培养攻略"));
    }

    @Test
    void 显式传入的摘要不被改写() {
        CreatePostRequest request = new CreatePostRequest(
                "GUIDE", "攻略", "显式摘要测试", "作者自己写的摘要",
                "正文内容", List.of(), List.of()
        );
        PostDetailResponse published = publishingService.publish(request, TEST_USER_ID);
        assertEquals("作者自己写的摘要", published.excerpt());
    }
}
