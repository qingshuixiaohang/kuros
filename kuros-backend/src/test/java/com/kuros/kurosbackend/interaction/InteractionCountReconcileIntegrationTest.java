package com.kuros.kurosbackend.interaction;

import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.UserDirectoryStub;
import com.kuros.kurosbackend.interaction.event.InteractionEvent;
import com.kuros.kurosbackend.interaction.event.InteractionKind;
import com.kuros.kurosbackend.interaction.event.InteractionType;
import com.kuros.kurosbackend.interaction.redis.InteractionRedisStore;
import com.kuros.kurosbackend.interaction.repository.PostFavoriteRepository;
import com.kuros.kurosbackend.interaction.repository.PostLikeRepository;
import com.kuros.kurosbackend.interaction.service.InteractionCountReconciler;
import com.kuros.kurosbackend.interaction.service.InteractionProjectionService;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.domain.PostType;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 互动计数一致性（审计 M5 / B3：计数无对账）。
 *
 * 要锁定的契约：Redis 实时计数与 DB 冗余计数列不得长期不一致，
 * 且必须存在一条能把两者拉回一致的对账路径。
 *
 * 背景（不一致是怎么产生的）：
 * - 写路径即时翻转 Redis 计数，DB 计数列靠 MQ 异步投影更新。两处是
 *   「最终一致」，中间必然有窗口。
 * - 更要紧的是没有兜底：MQ 投递失败时虽有同步降级落库，但降级也失败
 *   （DB 抖动）时事件就永久丢了——DB 计数永久偏低，没人会发现。
 * - Redis 计数 TTL 到期后按 DB 基线回填。若 DB 基线本身是错的，
 *   错误会被当作真相固化下来。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class InteractionCountReconcileIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final UserDirectoryStub userDirectory = new UserDirectoryStub();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("interaction-reconcile"));
        registry.add("app.feign.kuros-user.url", userDirectory::baseUrl);
        registry.add("app.internal.token", () -> "test-internal-token");
    }

    private static final String AUTHOR_ID = "10000000-0000-0000-0000-000000000001";
    private static final String LIKER_ID = "10000000-0000-0000-0000-000000000002";
    private static final String LIKER_ID_2 = "10000000-0000-0000-0000-000000000003";

    @Autowired
    private InteractionRedisStore redisStore;

    @Autowired
    private InteractionCountReconciler reconciler;

    @Autowired
    private InteractionProjectionService projectionService;

    @Autowired
    private PostLikeRepository likeRepository;

    @Autowired
    private PostFavoriteRepository favoriteRepository;

    @Autowired
    private CommunityPostRepository postRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String postId;

    @BeforeEach
    void setUp() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection().serverCommands().flushDb();

        CommunityPost post = CommunityPost.publish(
                AUTHOR_ID, PostType.GUIDE, "配队攻略", "对账测试帖", "摘要", "正文", LocalDateTime.now());
        postRepository.save(post);
        postId = post.getId();
    }

    private String likeCountKey() {
        return "interaction:count:like:" + postId;
    }

    private String favoriteCountKey() {
        return "interaction:count:favorite:" + postId;
    }

    /**
     * 人为把点赞冗余列改成错值（模拟 MQ 事件丢失后 incrementLikeCount 没执行）。
     *
     * 用 TransactionTemplate 而不是给 helper 加 @Transactional：同类内自调用
     * 不过 Spring 代理，@Transactional 会静默失效（经典 AOP 坑），然后
     * updateLikeCount 的 clearAutomatically 要 flush 就抛
     * TransactionRequiredException。这与生产代码里对账走 TransactionTemplate
     * 是同一个理由。
     */
    void corruptLikeColumn(long wrongValue) {
        transactionTemplate.executeWithoutResult(status ->
                postRepository.updateLikeCount(postId, wrongValue));
    }

    /** 同上，收藏列。 */
    void corruptFavoriteColumn(long wrongValue) {
        transactionTemplate.executeWithoutResult(status ->
                postRepository.updateFavoriteCount(postId, wrongValue));
    }

    /**
     * 场景：事件丢了（MQ 投递失败 + 降级也没跑），DB 计数停在旧值，
     * 而 Redis 已经翻了。对账必须能把 Redis 拉回关系表的真相。
     */
    @Test
    void 对账把Redis计数拉回关系表真相() {
        // DB 里真实有 1 个赞（投影正常落库）
        projectionService.apply(InteractionEvent.of(postId, LIKER_ID, InteractionType.LIKE));
        long truth = likeRepository.countByPostId(postId);
        assertThat(truth).isEqualTo(1);

        // 人为制造偏差：Redis 计数比真相多 1（模拟事件丢失后 Redis 已翻）
        redisTemplate.opsForValue().set(likeCountKey(), Long.toString(truth + 1));
        assertThat(redisTemplate.opsForValue().get(likeCountKey()))
                .isEqualTo(Long.toString(truth + 1));

        InteractionRedisStore.ReconcileResult result = redisStore.reconcileCount(
                InteractionKind.LIKE, postId, () -> likeRepository.countByPostId(postId));

        assertThat(result.count()).isEqualTo(truth);
        assertThat(result.corrected()).isTrue();
        assertThat(redisTemplate.opsForValue().get(likeCountKey()))
                .isEqualTo(Long.toString(truth));
    }

    /**
     * 场景：DB 冗余计数列本身是错的（增量更新丢了事件），但关系表是对的。
     * 对账以关系表 COUNT 为准，Redis 与冗余列都应被拉回真相。
     */
    @Test
    void 对账以关系表为准并修正DB冗余列() {
        projectionService.apply(InteractionEvent.of(postId, LIKER_ID, InteractionType.LIKE));
        long truth = likeRepository.countByPostId(postId);

        // 人为把冗余计数列改错（模拟 MQ 事件丢失后 incrementLikeCount 没执行）
        corruptLikeColumn(99);
        // Redis 也偏离
        redisTemplate.opsForValue().set(likeCountKey(), "88");

        Map<InteractionKind, InteractionCountReconciler.CountDelta> deltas =
                reconciler.reconcile(postId);
        InteractionCountReconciler.CountDelta like = deltas.get(InteractionKind.LIKE);

        assertThat(like.after()).isEqualTo(truth);
        assertThat(like.dbBefore()).isEqualTo(99);
        assertThat(redisTemplate.opsForValue().get(likeCountKey()))
                .isEqualTo(Long.toString(truth));
        assertThat(postRepository.findById(postId).orElseThrow().getLikeCount())
                .isEqualTo(truth);
    }

    /**
     * 幂等：真相没变时对账不应改动 Redis 值，corrected 为 false。
     */
    @Test
    void 无偏差时对账是幂等的() {
        projectionService.apply(InteractionEvent.of(postId, LIKER_ID, InteractionType.LIKE));
        long truth = likeRepository.countByPostId(postId);

        // 先建立一致的 Redis 值
        redisTemplate.opsForValue().set(likeCountKey(), Long.toString(truth));

        InteractionRedisStore.ReconcileResult result = redisStore.reconcileCount(
                InteractionKind.LIKE, postId, () -> likeRepository.countByPostId(postId));

        assertThat(result.count()).isEqualTo(truth);
        assertThat(result.corrected())
                .as("值本来就一致时不应报告为已修正")
                .isFalse();
        assertThat(redisTemplate.opsForValue().get(likeCountKey()))
                .isEqualTo(Long.toString(truth));
    }

    /**
     * 收藏链路同样要对账：LIKE/FAVORITE 两条链路共用逻辑，不能只修一条。
     */
    @Test
    void 收藏计数同样可对账() {
        projectionService.apply(InteractionEvent.of(postId, LIKER_ID, InteractionType.FAVORITE));
        projectionService.apply(InteractionEvent.of(postId, LIKER_ID_2, InteractionType.FAVORITE));
        long truth = favoriteRepository.countByPostId(postId);
        assertThat(truth).isEqualTo(2);

        // 制造偏差：Redis 与冗余列都错
        redisTemplate.opsForValue().set(favoriteCountKey(), "7");
        corruptFavoriteColumn(5);

        Map<InteractionKind, InteractionCountReconciler.CountDelta> deltas =
                reconciler.reconcile(postId);
        InteractionCountReconciler.CountDelta favorite = deltas.get(InteractionKind.FAVORITE);

        assertThat(favorite.after()).isEqualTo(truth);
        assertThat(favorite.dbBefore()).isEqualTo(5);
        assertThat(redisTemplate.opsForValue().get(favoriteCountKey()))
                .isEqualTo(Long.toString(truth));
        assertThat(postRepository.findById(postId).orElseThrow().getFavoriteCount())
                .isEqualTo(truth);
    }

    /**
     * 对账后读路径拿到的是修正值：证明对账真的影响了用户可见的计数，
     * 而不只是内部状态被改对了。
     */
    @Test
    void 对账后读路径返回修正值() {
        projectionService.apply(InteractionEvent.of(postId, LIKER_ID, InteractionType.LIKE));
        long truth = likeRepository.countByPostId(postId);

        // 制造偏差后直接读（此时读到的是错值）
        redisTemplate.opsForValue().set(likeCountKey(), "50");
        long beforeReconcile = redisStore.readCount(InteractionKind.LIKE, postId,
                () -> postRepository.findById(postId).orElseThrow().getLikeCount());
        assertThat(beforeReconcile).isEqualTo(50);

        reconciler.reconcile(postId);

        long afterReconcile = redisStore.readCount(InteractionKind.LIKE, postId,
                () -> postRepository.findById(postId).orElseThrow().getLikeCount());
        assertThat(afterReconcile).isEqualTo(truth);
    }
}
