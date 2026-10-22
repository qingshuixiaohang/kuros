package com.kuros.kurosbackend.feed;

import com.kuros.kurosbackend.feed.redis.FeedTimelineStore;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.domain.PostType;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.post.api.PostSummaryResponse;
import com.kuros.kurosbackend.feed.service.FeedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取消关注清 timeline 的第一步：timeline member 带作者信息 + 按作者批量清理。
 *
 * 要解决的缺陷（spec D5，自切片 #12 起一直没做）：取消关注只动 kuros-user 的 DB，
 * backend 的 timeline ZSet 里对方的 postId 原样留着——用户取关后仍能在关注流
 * 刷到对方的历史帖子。
 *
 * 为什么必须改 member 格式才能修：ZSet 的 member 此前是纯 postId，**不含作者**。
 * 想删「某作者的所有帖子」只能先反查作者发了哪些帖（跨服务或 DB 查询），再逐条
 * ZREM——既慢又会漏。改成复合键 {@code authorId:postId} 后，同前缀的 member 即
 * 该作者的全部帖子，一次范围删除即可。
 *
 * 这里的用例分三组，分别锁住三个不能破的契约：
 * 1. 写入格式：push 进来的 member 必须带作者前缀（否则 removeByAuthor 无从下手）
 * 2. 按作者清理：只删目标作者的、别人的一条不动、返回值是真实删除条数
 * 3. 存量兼容：改造前写入的纯 postId member 必须仍能正常读出——
 *    线上/本地已有数据不会因为这次改造突然变成空关注流
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class FeedTimelineAuthorCleanupIntegrationTest {

    private static final String FOLLOWER = "cleanup-follower";
    private static final String AUTHOR_B = "cleanup-author-b";
    private static final String AUTHOR_C = "cleanup-author-c";
    private static final String LEGACY_POST_ID = "legacy-post-no-author";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("feed-author-cleanup"));
    }

    @Autowired FeedTimelineStore timelineStore;
    @Autowired FeedService feedService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.kuros.kurosbackend.user.client.UserDirectoryFacade users;
    @Autowired CommunityPostRepository postRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(users.toAuthor(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenAnswer(call -> new com.kuros.kurosbackend.shared.api.AuthorResponse(call.getArgument(0), "作者", null, null));
        org.mockito.Mockito.when(users.following(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new com.kuros.kurosbackend.shared.api.PageResult<>(
                        List.of(new com.kuros.kurosbackend.user.client.UserBriefDto(AUTHOR_B, "作者", null, null),
                                new com.kuros.kurosbackend.user.client.UserBriefDto(AUTHOR_C, "作者", null, null)),
                        new com.kuros.kurosbackend.shared.api.PageMeta(1, 50, 2, 1)));
        timelineStore.clear(FOLLOWER);
        jdbcTemplate.update("DELETE FROM post_media");
        jdbcTemplate.update("DELETE FROM post_tags");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM post_likes");
        jdbcTemplate.update("DELETE FROM post_favorites");
        postRepository.deleteAll();
    }

    // ---------- 1. 写入格式 ----------

    /**
     * push 进去的 member 必须是「作者:帖子」复合键。
     *
     * 直接查 Redis 原始 member 而不是绕 readTimeline——readTimeline 会 decode，
     * 那是下游视角；这里要锁的是**存储格式本身**，否则将来有人把 encode 去掉、
     * 读路径又恰好能跑，这个契约就悄悄没了。
     */
    @Test
    void pushToTimelines_writesCompositeMemberWithAuthorPrefix() {
        timelineStore.pushToTimelines("post-b1", AUTHOR_B, LocalDateTime.of(2026, 9, 21, 10, 0), List.of(FOLLOWER));

        Set<String> rawMembers = redisTemplate.opsForZSet()
                .range("feed:timeline:" + FOLLOWER, 0, -1);

        assertThat(rawMembers)
                .as("member 必须带作者前缀，removeByAuthor 才能按前缀删除")
                .containsExactly(AUTHOR_B + ":post-b1");
    }

    // ---------- 2. 按作者清理 ----------

    /** 核心用例：取关 B 后，B 的帖子全删、C 的帖子一条不动。 */
    @Test
    void removeByAuthor_deletesOnlyThatAuthorsPosts() {
        timelineStore.pushToTimelines("post-b1", AUTHOR_B, LocalDateTime.of(2026, 9, 21, 10, 0), List.of(FOLLOWER));
        timelineStore.pushToTimelines("post-b2", AUTHOR_B, LocalDateTime.of(2026, 9, 21, 11, 0), List.of(FOLLOWER));
        timelineStore.pushToTimelines("post-c1", AUTHOR_C, LocalDateTime.of(2026, 9, 21, 12, 0), List.of(FOLLOWER));

        long removed = timelineStore.removeByAuthor(FOLLOWER, AUTHOR_B);

        assertThat(removed).as("删除条数必须如实返回，调用方要能据此判断是否真的清了").isEqualTo(2);
        assertThat(timelineStore.readTimeline(FOLLOWER, 0, 10))
                .as("取关后只剩 C 的帖子")
                .containsExactly("post-c1");
    }

    /** 作者没有帖子时返回 0 且不报错——取关一个从未发过帖的人是正常路径。 */
    @Test
    void removeByAuthor_returnsZeroWhenAuthorHasNoPosts() {
        timelineStore.pushToTimelines("post-c1", AUTHOR_C, LocalDateTime.of(2026, 9, 21, 12, 0), List.of(FOLLOWER));

        long removed = timelineStore.removeByAuthor(FOLLOWER, AUTHOR_B);

        assertThat(removed).isZero();
        assertThat(timelineStore.readTimeline(FOLLOWER, 0, 10)).containsExactly("post-c1");
    }

    /** timeline 是空的也要能调用（新用户直接取关的场景）。 */
    @Test
    void removeByAuthor_onEmptyTimeline_returnsZero() {
        assertThat(timelineStore.removeByAuthor(FOLLOWER, AUTHOR_B)).isZero();
    }

    /**
     * 前缀匹配不能误伤：作者 B 的 id 是作者 C 的 id 的前缀时（UUID 不会，但
     * 测试数据会），不能用 startsWith 裸比而漏掉分隔符。
     */
    @Test
    void removeByAuthor_doesNotMatchAuthorIdThatIsAPrefixOfAnother() {
        // 两个 id 刻意构成长度不同的包含关系，验证分隔符边界
        String shortAuthor = "author-x";
        String longAuthor = "author-x-long";
        timelineStore.pushToTimelines("p1", shortAuthor, LocalDateTime.of(2026, 9, 21, 10, 0), List.of(FOLLOWER));
        timelineStore.pushToTimelines("p2", longAuthor, LocalDateTime.of(2026, 9, 21, 11, 0), List.of(FOLLOWER));

        long removed = timelineStore.removeByAuthor(FOLLOWER, shortAuthor);

        assertThat(removed).as("只能删 shortAuthor 自己的那一条").isEqualTo(1);
        assertThat(timelineStore.readTimeline(FOLLOWER, 0, 10))
                .as("longAuthor 的帖子必须完好——它只是 id 以 shortAuthor 开头")
                .containsExactly("p2");
    }

    // ---------- 3. 存量兼容 ----------

    /**
     * 改造前写入的纯 postId member（无作者前缀）必须仍能正常读出。
     *
     * 这是这次格式变更唯一的真实风险：Redis 里已有数据不会自动变形。若读路径
     * 对无分隔符的 member 抛异常或返回 null，所有老用户的关注流会在升级后
     * 瞬间变空。兜底策略是「不含分隔符 → 当作无作者信息，postId 就是 member 本身」。
     */
    @Test
    void readTimeline_stillReadsLegacyMembersWithoutAuthorPrefix() {
        // 绕过 pushToTimelines，直接按旧格式写入——模拟升级前的存量数据
        redisTemplate.opsForZSet().add(
                "feed:timeline:" + FOLLOWER,
                LEGACY_POST_ID,
                LocalDateTime.of(2026, 9, 21, 10, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli());

        assertThat(timelineStore.readTimeline(FOLLOWER, 0, 10))
                .as("旧格式 member 必须原样读出不报错")
                .containsExactly(LEGACY_POST_ID);
    }

    /** 旧格式数据混在新格式里也要能一起读出（渐进升级窗口期）。 */
    @Test
    void readTimeline_mixesLegacyAndCompositeMembers() {
        redisTemplate.opsForZSet().add(
                "feed:timeline:" + FOLLOWER,
                LEGACY_POST_ID,
                LocalDateTime.of(2026, 9, 21, 10, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli());
        timelineStore.pushToTimelines("post-b1", AUTHOR_B, LocalDateTime.of(2026, 9, 21, 12, 0), List.of(FOLLOWER));

        assertThat(timelineStore.readTimeline(FOLLOWER, 0, 10))
                .as("新的在前，旧格式按时间戳正常参与排序")
                .containsExactly("post-b1", LEGACY_POST_ID);
    }

    // ---------- 4. 端到端：读路径不因格式变更而坏 ----------

    /** 改格式后，关注流读路径（含作者回填）必须仍然返回真实帖子。 */
    @Test
    void findFollowingFeed_stillReturnsPostsAfterFormatChange() {
        CommunityPost post = CommunityPost.publish(
                AUTHOR_B, PostType.GUIDE, "角色培养", "格式变更后", "摘要", "内容",
                LocalDateTime.of(2026, 9, 21, 12, 0));
        postRepository.save(post);
        timelineStore.pushToTimelines(post.getId(), AUTHOR_B, post.getPublishedAt(), List.of(FOLLOWER));

        PageResult<PostSummaryResponse> result = feedService.findFollowingFeed(FOLLOWER, 1, 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).title()).isEqualTo("格式变更后");
    }

    /**
     * 游标翻页在复合键下仍要不丢不重——同分比较的语义不能被打乱。
     *
     * 注意：帖子必须真的入库（CommunityPost.publish + save），不能只往 ZSet
     * push 假 ID——读路径 findPublishedByIds 会按 DB 查不到的行过滤，
     * 用假 ID 会得到空列表，那是 fixture 错了不是实现错了。
     */
    @Test
    void cursorPagination_stillTraversesWithoutLossOrDuplication() {
        LocalDateTime base = LocalDateTime.of(2026, 9, 21, 10, 0);
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            CommunityPost post = CommunityPost.publish(
                    AUTHOR_B, PostType.GUIDE, "角色培养", "游标" + i, "摘要", "内容", base.plusMinutes(i));
            postRepository.save(post);
            ids.add(post.getId());
            timelineStore.pushToTimelines(post.getId(), AUTHOR_B, post.getPublishedAt(), List.of(FOLLOWER));
        }

        // 权威顺序：一次性全量倒序
        List<String> expected = timelineStore.readTimeline(FOLLOWER, 0, 100);

        // 每页 3 条游标翻页，拼接全部
        List<String> traversed = new java.util.ArrayList<>();
        String cursor = null;
        int guard = 0;
        while (guard++ < 20) {
            var page = feedService.findFollowingFeedByCursor(FOLLOWER, cursor, 3);
            traversed.addAll(page.items().stream().map(PostSummaryResponse::id).toList());
            if (!page.hasMore()) break;
            cursor = page.nextCursor();
        }

        assertThat(traversed)
                .as("复合键下游标翻页必须与全量倒序完全一致")
                .containsExactlyElementsOf(expected);
        assertThat(ids).as("7 条帖子都应被遍历到").hasSize(7);
    }

    @Test
    void cursorPagination_handlesSameScoreAcrossDifferentAuthors() {
        LocalDateTime same = LocalDateTime.of(2026, 9, 21, 10, 0);
        double score = same.toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        String key = "feed:timeline:" + FOLLOWER;
        redisTemplate.opsForZSet().add(key, "z-author:post-2", score);
        redisTemplate.opsForZSet().add(key, "z-author:post-1", score);
        redisTemplate.opsForZSet().add(key, "a-author:post-4", score);
        redisTemplate.opsForZSet().add(key, "a-author:post-3", score);

        List<String> expected = timelineStore.readTimeline(FOLLOWER, 0, 10);
        var firstPage = timelineStore.readTimelineByCursor(FOLLOWER, null, null, 2);
        var secondPage = timelineStore.readTimelineByCursor(
                FOLLOWER,
                firstPage.get(firstPage.size() - 1).score(),
                firstPage.get(firstPage.size() - 1).member(),
                2
        );
        List<String> traversed = java.util.stream.Stream.concat(firstPage.stream(), secondPage.stream())
                .map(FeedTimelineStore.TimelineEntry::postId)
                .toList();

        assertThat(traversed)
                .as("同分不同作者的复合 member 必须按 Redis 实际成员顺序继续翻页")
                .containsExactlyElementsOf(expected);
    }
}
