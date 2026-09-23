package com.kuros.kurosbackend.post;

import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.post.api.PostSummaryResponse;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.domain.PostStatus;
import com.kuros.kurosbackend.post.domain.PostType;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.post.service.CommunityPostService;
import com.kuros.kurosbackend.shared.api.CursorPageResult;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.shared.exception.AuthRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 帖子列表 keyset 游标分页集成测试（切片 #13 / rp-05）。
 *
 * 只验证外部可观测行为：
 * 1. latest / hot 两种排序键的游标遍历——不丢、不重、顺序与「全量按同排序键排序」完全一致（深翻页 == 浅翻页拼接）；
 * 2. 边界去重：同 published_at（latest）/ 同热度（hot）时靠 id 字典序兜底全序；
 * 3. hasMore/nextCursor 语义；
 * 4. offset 端点（findPublished）仍兼容；
 * 5. 非法游标 → INVALID_CURSOR。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PostListCursorIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("post-list-cursor"));
    }

    @Autowired CommunityPostService postService;
    @Autowired CommunityPostRepository postRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final String AUTHOR = "cursor-list-author";
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 21, 10, 0);

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM post_media");
        jdbcTemplate.update("DELETE FROM post_tags");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM post_likes");
        jdbcTemplate.update("DELETE FROM post_favorites");
        postRepository.deleteAll();
    }

    @Test
    void latest游标遍历不丢不重且顺序正确() {
        for (int i = 0; i < 10; i++) {
            seedPost("最新" + i, BASE.plusMinutes(i), 0, 0);
        }
        List<String> expected = orderedIds(latestComparator());
        List<String> traversed = collectByCursor("latest", 3);
        assertThat(traversed).containsExactlyElementsOf(expected);
        assertThat(traversed).doesNotHaveDuplicates();
    }

    @Test
    void latest同发布时间靠id兜底不丢不重() {
        // 6 个「同一 published_at」的帖子 → 排序键第一维相同，翻页只能靠 id 字典序兜底
        for (int i = 0; i < 6; i++) {
            seedPost("同刻" + i, BASE, 0, 0);
        }
        List<String> expected = orderedIds(latestComparator());
        List<String> traversed = collectByCursor("latest", 2);
        assertThat(traversed).hasSize(6).doesNotHaveDuplicates();
        assertThat(traversed).containsExactlyElementsOf(expected);
    }

    @Test
    void hot游标遍历按热度排序键正确() {
        // 制造 (like, comment) 组合差异，含同热度对（靠 published_at、id 兜底）
        seedPost("热A", BASE.plusMinutes(1), 10, 5);
        seedPost("热B", BASE.plusMinutes(2), 10, 5); // 与热A同 like+comment，靠 published_at 区分
        seedPost("热C", BASE.plusMinutes(3), 20, 1);
        seedPost("热D", BASE.plusMinutes(4), 5, 9);
        seedPost("热E", BASE.plusMinutes(5), 10, 3);
        seedPost("热F", BASE.plusMinutes(6), 0, 0);

        List<String> expected = orderedIds(hotComparator());
        List<String> traversed = collectByCursor("hot", 2);
        assertThat(traversed).containsExactlyElementsOf(expected);
        assertThat(traversed).doesNotHaveDuplicates();
    }

    @Test
    void hasMore与nextCursor语义正确() {
        for (int i = 0; i < 5; i++) {
            seedPost("页语义" + i, BASE.plusMinutes(i), 0, 0);
        }
        CursorPageResult<PostSummaryResponse> page1 =
                postService.findPublishedByCursor("latest", null, null, null, null, 2);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.hasMore()).isTrue();
        assertThat(page1.nextCursor()).isNotBlank();

        CursorPageResult<PostSummaryResponse> page3 =
                postService.findPublishedByCursor("latest", null, null, null,
                        postService.findPublishedByCursor("latest", null, null, null, page1.nextCursor(), 2).nextCursor(),
                        2);
        assertThat(page3.items()).hasSize(1);
        assertThat(page3.hasMore()).isFalse();
        assertThat(page3.nextCursor()).isNull();
    }

    @Test
    void offset端点仍兼容() {
        for (int i = 0; i < 5; i++) {
            seedPost("offset" + i, BASE.plusMinutes(i), 0, 0);
        }
        PageResult<PostSummaryResponse> result = postService.findPublished(1, 2, "latest", null, null, null);
        assertThat(result.items()).hasSize(2);
        assertThat(result.meta().totalItems()).isEqualTo(5);
        assertThat(result.meta().totalPages()).isEqualTo(3);
    }

    @Test
    void 非法游标抛InvalidCursor() {
        seedPost("任意", BASE, 0, 0);
        assertThatThrownBy(() -> postService.findPublishedByCursor("latest", null, null, null, "!!!bad!!!", 5))
                .isInstanceOf(AuthRequestException.class)
                .hasMessageContaining("游标");
    }

    private String seedPost(String title, LocalDateTime publishedAt, long like, long comment) {
        CommunityPost post = CommunityPost.publish(
                AUTHOR, PostType.GUIDE, "角色培养", title, "摘要", "内容", publishedAt);
        postRepository.save(post);
        if (like != 0 || comment != 0) {
            jdbcTemplate.update("UPDATE posts SET like_count = ?, comment_count = ? WHERE id = ?",
                    like, comment, post.getId());
        }
        return post.getId();
    }

    private List<String> orderedIds(Comparator<CommunityPost> comparator) {
        return postRepository.findAll().stream()
                .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                .sorted(comparator)
                .map(CommunityPost::getId)
                .toList();
    }

    private Comparator<CommunityPost> latestComparator() {
        return Comparator.comparing(CommunityPost::getPublishedAt).reversed()
                .thenComparing(Comparator.comparing(CommunityPost::getId).reversed());
    }

    private Comparator<CommunityPost> hotComparator() {
        return Comparator.comparingLong(CommunityPost::getLikeCount).reversed()
                .thenComparing(Comparator.comparingLong(CommunityPost::getCommentCount).reversed())
                .thenComparing(Comparator.comparing(CommunityPost::getPublishedAt).reversed())
                .thenComparing(Comparator.comparing(CommunityPost::getId).reversed());
    }

    private List<String> collectByCursor(String sort, int limit) {
        List<String> all = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        do {
            CursorPageResult<PostSummaryResponse> result =
                    postService.findPublishedByCursor(sort, null, null, null, cursor, limit);
            result.items().forEach(item -> all.add(item.id()));
            cursor = result.nextCursor();
            guard++;
        } while (cursor != null && guard < 100);
        return all;
    }
}
