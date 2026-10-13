package com.kuros.kurosbackend.feed;

import com.kuros.kurosbackend.feed.redis.FeedTimelineStore;
import com.kuros.kurosbackend.feed.service.FeedService;
import com.kuros.kurosbackend.post.api.PostSummaryResponse;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.domain.PostType;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.shared.api.PageResult;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切片 #12：Feed Timeline 端到端集成测试。
 *
 * 验证：
 * 1. pushToTimelines：帖子推入粉丝 timeline ZSet（ZADD + 容量裁剪 + TTL）
 * 2. readTimeline：按时间倒序返回 postId 列表
 * 3. findFollowingFeed：ZSet → findAllById → 作者回填 → PostSummaryResponse 列表
 * 4. 容量上限：超过 maxSize 的旧帖子被裁剪
 * 5. 已删除帖子不出现在关注流（发帖后被作者删除，DB 查不到 → 自然过滤）
 *
 * 测试基础设施：Testcontainers Redis（与主套件共用镜像策略）+ H2 内存库。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class FeedTimelineIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("feed-timeline"));
    }

    @Autowired FeedTimelineStore timelineStore;
    @Autowired FeedService feedService;
    @Autowired CommunityPostRepository postRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final String USER_A = "user-a";
    private static final String USER_B = "user-b";
    private static final String USER_C = "user-c";
    /** 这些用例只关心 timeline 存取，用统一虚拟作者（member 复合键的作者段）。 */
    private static final String AUTHOR = "timeline-test-author";

    @BeforeEach
    void setUp() {
        timelineStore.clear(USER_A);
        timelineStore.clear(USER_B);
        timelineStore.clear(USER_C);
        // 按外键依赖倒序清理：子表先删，再删主表（Flyway 种子数据有帖子+评论）
        jdbcTemplate.update("DELETE FROM post_media");
        jdbcTemplate.update("DELETE FROM post_tags");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM post_likes");
        jdbcTemplate.update("DELETE FROM post_favorites");
        postRepository.deleteAll();
    }

    @Test
    void pushToTimelines_addsPostToFollowersZSet() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 21, 12, 0);
        timelineStore.pushToTimelines("post-1", AUTHOR, now, List.of(USER_A, USER_B));

        assertThat(timelineStore.contains(USER_A, "post-1")).isTrue();
        assertThat(timelineStore.contains(USER_B, "post-1")).isTrue();
        assertThat(timelineStore.contains(USER_C, "post-1")).isFalse(); // 不是粉丝
        assertThat(timelineStore.size(USER_A)).isEqualTo(1);
    }

    @Test
    void readTimeline_returnsPostIdsInReverseChronologicalOrder() {
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 21, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 9, 21, 11, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 9, 21, 12, 0);

        timelineStore.pushToTimelines("post-old", AUTHOR, t1, List.of(USER_A));
        timelineStore.pushToTimelines("post-mid", AUTHOR, t2, List.of(USER_A));
        timelineStore.pushToTimelines("post-new", AUTHOR, t3, List.of(USER_A));

        List<String> result = timelineStore.readTimeline(USER_A, 0, 10);
        assertThat(result).containsExactly("post-new", "post-mid", "post-old");
    }

    @Test
    void readTimeline_pagination_worksCorrectly() {
        for (int i = 0; i < 5; i++) {
            LocalDateTime time = LocalDateTime.of(2026, 9, 21, 10 + i, 0);
            timelineStore.pushToTimelines("post-" + i, AUTHOR, time, List.of(USER_A));
        }

        List<String> page1 = timelineStore.readTimeline(USER_A, 0, 2);
        List<String> page2 = timelineStore.readTimeline(USER_A, 2, 2);

        assertThat(page1).hasSize(2);
        assertThat(page2).hasSize(2);
        assertThat(page1.get(0)).isEqualTo("post-4"); // 最新的
        assertThat(page2.get(0)).isEqualTo("post-2");
    }

    @Test
    void pushToTimelines_respectsCapacityLimit() {
        // 默认 maxSize=500，这里用更小值测试裁剪逻辑
        // 由于 FeedTimelineStore 的 maxSize 是从配置注入的，我们用 500 默认值
        // 推 501 条，验证第 1 条被裁剪
        for (int i = 0; i < 501; i++) {
            LocalDateTime time = LocalDateTime.of(2026, 9, 21, 0, 0).plusMinutes(i);
            timelineStore.pushToTimelines("post-" + i, AUTHOR, time, List.of(USER_A));
        }

        assertThat(timelineStore.size(USER_A)).isEqualTo(500);
        // 最早的 post-0 应该被裁剪
        assertThat(timelineStore.contains(USER_A, "post-0")).isFalse();
        // 最新的 post-500 应该存在
        assertThat(timelineStore.contains(USER_A, "post-500")).isTrue();
    }

    @Test
    void pushToTimelines_emptyFollowerList_doesNothing() {
        timelineStore.pushToTimelines("post-1", AUTHOR, LocalDateTime.now(), List.of());
        timelineStore.pushToTimelines("post-2", AUTHOR, LocalDateTime.now(), null);

        assertThat(timelineStore.size(USER_A)).isEqualTo(0);
    }

    @Test
    void findFollowingFeed_returnsEmptyWhenTimelineEmpty() {
        PageResult<PostSummaryResponse> result = feedService.findFollowingFeed(USER_A, 1, 20);
        assertThat(result.items()).isEmpty();
        assertThat(result.meta().totalItems()).isEqualTo(0);
    }

    @Test
    void findFollowingFeed_returnsPostsFromTimeline() {
        // 准备：创建 2 个帖子 + 推到 USER_A 的 timeline
        CommunityPost post1 = CommunityPost.publish(USER_B, PostType.GUIDE, "角色培养", "标题1", "摘要1", "内容1", LocalDateTime.of(2026, 9, 21, 10, 0));
        CommunityPost post2 = CommunityPost.publish(USER_C, PostType.GENERAL, "心得分享", "标题2", "摘要2", "内容2", LocalDateTime.of(2026, 9, 21, 11, 0));
        postRepository.save(post1);
        postRepository.save(post2);

        timelineStore.pushToTimelines(post1.getId(), post1.getAuthorId(), post1.getPublishedAt(), List.of(USER_A));
        timelineStore.pushToTimelines(post2.getId(), post2.getAuthorId(), post2.getPublishedAt(), List.of(USER_A));

        // 执行
        PageResult<PostSummaryResponse> result = feedService.findFollowingFeed(USER_A, 1, 20);

        // 验证：2 个帖子按时间倒序
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).title()).isEqualTo("标题2"); // 更新的帖子
        assertThat(result.items().get(1).title()).isEqualTo("标题1");
    }

    /**
     * 软删除的帖子不得出现在关注流里（审计 M9 / D2）。
     *
     * 原用例准备了帖子、软删除、调用服务，然后只有注释、零断言——永远绿。
     * 注释里还留着一个错误假设："findAllById 能找到 DELETED 状态的帖子
     * （JPA 不自动过滤）……这是当前实现的已知局限"。也就是说软删除帖
     * 真的会漏进关注流，而测试选择了不验证。
     *
     * 这里把它变成真断言。若它变红，说明 {@code findPublishedByIds} 名不
     * 副实——只 filter null 不过滤状态——那是要修的生产 bug，不是测试问题。
     */
    @Test
    void findFollowingFeed_filtersOutDeletedPosts() {
        // 准备：创建帖子 + 推到 timeline
        CommunityPost post = CommunityPost.publish(USER_B, PostType.GUIDE, "角色培养", "已删帖", "摘要", "内容", LocalDateTime.now());
        postRepository.save(post);
        timelineStore.pushToTimelines(post.getId(), post.getAuthorId(), post.getPublishedAt(), List.of(USER_A));

        // 帖子被软删除（status → DELETED，行仍在 DB 里）
        post.delete(LocalDateTime.now());
        postRepository.save(post);

        // 执行
        PageResult<PostSummaryResponse> result = feedService.findFollowingFeed(USER_A, 1, 20);

        // 断言：软删除的帖子不得出现在关注流里。
        // findPublishedByIds 的语义就是"只返回已发布"，软删帖必须被过滤。
        assertThat(result.items())
                .as("软删除的帖子不应出现在关注流中")
                .noneMatch(item -> item.id().equals(post.getId()));
    }
}
