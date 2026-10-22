package com.kuros.kurosbackend.feed;

import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.feed.redis.FeedTimelineStore;
import com.kuros.kurosbackend.feed.service.FeedService;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.domain.PostType;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.shared.api.PageMeta;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.user.client.UserBriefDto;
import com.kuros.kurosbackend.user.client.UserDirectoryFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class FollowingRelationshipIntegrationTest {
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("following-relationship"));
    }

    @Autowired FeedService feed;
    @Autowired FeedTimelineStore timelines;
    @Autowired CommunityPostRepository posts;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean UserDirectoryFacade users;

    @BeforeEach
    void setUp() {
        reset(users);
        when(users.toAuthor(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenAnswer(call -> new com.kuros.kurosbackend.shared.api.AuthorResponse(call.getArgument(0), "作者", null, null));
        jdbcTemplate.update("DELETE FROM post_media");
        jdbcTemplate.update("DELETE FROM post_tags");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM post_likes");
        jdbcTemplate.update("DELETE FROM post_favorites");
        posts.deleteAll();
    }

    @Test
    void newlyFollowedAuthorsExistingPostsAppearWithoutPublishingAgain() {
        String author = "existing-author";
        CommunityPost post = posts.save(CommunityPost.publish(author, PostType.GUIDE, "角色培养",
                "关注前已发布", "摘要", "正文", LocalDateTime.now()));
        when(users.following(eq("new-follower"), anyInt(), anyInt())).thenReturn(new PageResult<>(
                List.of(new UserBriefDto(author, "作者", null, null)), new PageMeta(1, 50, 1, 1)));

        assertThat(feed.findFollowingFeedByCursor("new-follower", null, 20).items())
                .extracting(item -> item.id()).containsExactly(post.getId());
    }

    @Test
    void unfollowRemovesFirstPageAndOldCursorCannotResurrectPosts() {
        String follower = "unfollow-follower";
        String author = "unfollowed-author";
        List<CommunityPost> created = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> posts.save(CommunityPost.publish(author, PostType.GUIDE, "角色培养",
                        "取关帖子" + index, "摘要", "正文", LocalDateTime.of(2026, 9, 21, 10, index))))
                .toList();
        when(users.following(eq(follower), anyInt(), anyInt())).thenReturn(new PageResult<>(
                List.of(new UserBriefDto(author, "作者", null, null)), new PageMeta(1, 50, 1, 1)));

        var firstPage = feed.findFollowingFeedByCursor(follower, null, 2);
        assertThat(firstPage.items()).isNotEmpty();
        assertThat(firstPage.nextCursor()).isNotBlank();

        when(users.following(eq(follower), anyInt(), anyInt())).thenReturn(new PageResult<>(
                List.of(), new PageMeta(1, 50, 0, 0)));

        assertThat(feed.findFollowingFeedByCursor(follower, null, 20).items()).isEmpty();
        assertThat(feed.findFollowingFeedByCursor(follower, firstPage.nextCursor(), 20).items()).isEmpty();
        assertThat(timelines.size(follower)).isZero();
        assertThat(created).hasSize(4);
    }

    @Test
    void redisLossRebuildsFollowingTimelineFromDatabase() {
        String follower = "restart-follower";
        String author = "restart-author";
        CommunityPost post = posts.save(CommunityPost.publish(author, PostType.GUIDE, "角色培养",
                "重启后仍在", "摘要", "正文", LocalDateTime.now()));
        when(users.following(eq(follower), anyInt(), anyInt())).thenReturn(new PageResult<>(
                List.of(new UserBriefDto(author, "作者", null, null)), new PageMeta(1, 50, 1, 1)));
        timelines.clear(follower);

        assertThat(feed.findFollowingFeedByCursor(follower, null, 20).items())
                .extracting(item -> item.id()).containsExactly(post.getId());
    }
}
