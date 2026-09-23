package com.kuros.kurosbackend.feed;

import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.feed.redis.FeedTimelineStore;
import com.kuros.kurosbackend.feed.service.FeedService;
import com.kuros.kurosbackend.post.api.PostSummaryResponse;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.domain.PostType;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.shared.api.CursorPageResult;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 关注流游标分页集成测试（切片 #13 / rp-04）。
 *
 * 只验证外部可观测行为（翻页结果的正确性、hasMore/nextCursor 语义、异常契约），不绑定内部实现：
 * 1. 游标遍历整个 timeline：不丢、不重、顺序与「一次性全量倒序读」完全一致（深翻页 == 浅翻页拼接）；
 * 2. 同分（同一发布毫秒）帖子翻页：靠 postId 字典序兜底去重，仍然不丢不重；
 * 3. hasMore/nextCursor：还有下一页时 hasMore=true 且 nextCursor 非空，末页 hasMore=false 且 nextCursor=null；
 * 4. 空 timeline → 空页；非法游标 → INVALID_CURSOR。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class FeedCursorIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("feed-cursor"));
    }

    @Autowired FeedTimelineStore timelineStore;
    @Autowired FeedService feedService;
    @Autowired CommunityPostRepository postRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final String USER_A = "cursor-user-a";
    private static final String AUTHOR = "cursor-author";

    @BeforeEach
    void setUp() {
        timelineStore.clear(USER_A);
        jdbcTemplate.update("DELETE FROM post_media");
        jdbcTemplate.update("DELETE FROM post_tags");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM post_likes");
        jdbcTemplate.update("DELETE FROM post_favorites");
        postRepository.deleteAll();
    }

    @Test
    void 游标遍历整个timeline不丢不重且顺序与全量倒序一致() {
        // 10 个不同发布时间的帖子（分钟递增），推入 USER_A timeline
        for (int i = 0; i < 10; i++) {
            pushPost("标题" + i, LocalDateTime.of(2026, 9, 21, 10, 0).plusMinutes(i));
        }
        // 权威顺序：一次性全量倒序读（score DESC）
        List<String> expected = timelineStore.readTimeline(USER_A, 0, 100);

        // 每页 3 条游标翻页，拼接全部
        List<String> traversed = collectAllByCursor(3);

        assertThat(traversed).containsExactlyElementsOf(expected);
        assertThat(traversed).doesNotHaveDuplicates();
    }

    @Test
    void 同分帖子翻页靠postId兜底不丢不重() {
        // 6 个「同一发布毫秒」的帖子 → ZSet 里 score 完全相同，翻页只能靠 postId 字典序兜底
        LocalDateTime same = LocalDateTime.of(2026, 9, 21, 12, 0);
        for (int i = 0; i < 6; i++) {
            pushPost("同分" + i, same);
        }
        List<String> expected = timelineStore.readTimeline(USER_A, 0, 100);

        List<String> traversed = collectAllByCursor(2);

        assertThat(traversed).hasSize(6).doesNotHaveDuplicates();
        assertThat(traversed).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void hasMore与nextCursor语义正确() {
        for (int i = 0; i < 5; i++) {
            pushPost("页语义" + i, LocalDateTime.of(2026, 9, 21, 10, 0).plusMinutes(i));
        }

        CursorPageResult<PostSummaryResponse> page1 = feedService.findFollowingFeedByCursor(USER_A, null, 2);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.hasMore()).isTrue();
        assertThat(page1.nextCursor()).isNotBlank();

        CursorPageResult<PostSummaryResponse> page2 =
                feedService.findFollowingFeedByCursor(USER_A, page1.nextCursor(), 2);
        assertThat(page2.items()).hasSize(2);
        assertThat(page2.hasMore()).isTrue();

        // 末页：只剩 1 条，hasMore=false，nextCursor=null
        CursorPageResult<PostSummaryResponse> page3 =
                feedService.findFollowingFeedByCursor(USER_A, page2.nextCursor(), 2);
        assertThat(page3.items()).hasSize(1);
        assertThat(page3.hasMore()).isFalse();
        assertThat(page3.nextCursor()).isNull();
    }

    @Test
    void 空timeline返回空页() {
        CursorPageResult<PostSummaryResponse> result = feedService.findFollowingFeedByCursor(USER_A, null, 10);
        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void 非法游标抛InvalidCursor() {
        pushPost("任意", LocalDateTime.of(2026, 9, 21, 10, 0));
        assertThatThrownBy(() -> feedService.findFollowingFeedByCursor(USER_A, "!!!not-base64!!!", 5))
                .isInstanceOf(AuthRequestException.class)
                .hasMessageContaining("游标");
    }

    private void pushPost(String title, LocalDateTime publishedAt) {
        CommunityPost post = CommunityPost.publish(
                AUTHOR, PostType.GUIDE, "角色培养", title, "摘要", "内容", publishedAt);
        postRepository.save(post);
        timelineStore.pushToTimelines(post.getId(), post.getPublishedAt(), List.of(USER_A));
    }

    private List<String> collectAllByCursor(int limit) {
        List<String> all = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        do {
            CursorPageResult<PostSummaryResponse> result = feedService.findFollowingFeedByCursor(USER_A, cursor, limit);
            result.items().forEach(item -> all.add(item.id()));
            cursor = result.nextCursor();
            guard++;
        } while (cursor != null && guard < 100);
        return all;
    }
}
