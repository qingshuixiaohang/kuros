package com.kuros.kurosbackend.interaction;

import com.kuros.kurosbackend.interaction.event.InteractionEvent;
import com.kuros.kurosbackend.interaction.event.InteractionType;
import com.kuros.kurosbackend.interaction.service.InteractionProjectionService;
import com.kuros.kurosbackend.interaction.service.PostInteractionService;
import com.kuros.kurosbackend.shared.exception.AuthRequestException;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class InteractionMySqlIntegrationTest {
    private static final String POST = "10000000-0000-0000-0000-000000000001";
    @Container
    static GenericContainer<?> mysql = new GenericContainer<>("mysql:8.0")
            .withEnv("MYSQL_ROOT_PASSWORD", "test-password")
            .withEnv("MYSQL_DATABASE", "kuros")
            .withExposedPorts(3306)
            .waitingFor(Wait.forSuccessfulCommand("mysqladmin ping -h 127.0.0.1 -uroot -ptest-password")
                    .withStartupTimeout(Duration.ofMinutes(2)));
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/kuros?allowPublicKeyRetrieval=true&useSSL=false");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "test-password");
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired InteractionProjectionService projection;
    @Autowired CommunityPostRepository posts;
    @Autowired PostInteractionService interactions;
    @Autowired com.kuros.kurosbackend.interaction.redis.InteractionRedisStore cache;
    @Autowired com.kuros.kurosbackend.interaction.repository.PostLikeRepository likes;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.kuros.kurosbackend.interaction.repository.PostFavoriteRepository favorites;
    @Autowired jakarta.persistence.EntityManagerFactory emf;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Test
    void 批量状态同负载测量() {
        var ids = java.util.stream.IntStream.rangeClosed(1, 4)
                .mapToObj(i -> "10000000-0000-0000-0000-%012d".formatted(i)).toList();
        var users = java.util.stream.IntStream.range(0, 20).mapToObj(i -> UUID.randomUUID().toString()).toList();
        for (String user : users) for (String id : ids)
            jdbc.update("insert into post_likes (user_id, post_id, created_at) values (?, ?, now())", user, id);
        var statistics = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        for (int phase = 0; phase < 2; phase++) {
            statistics.clear();
            long[] times = new long[200];
            for (int i = 0; i < times.length; i++) {
                String user = users.get(i % users.size());
                long start = System.nanoTime();
                if (phase == 0) {
                    var state = new java.util.HashMap<String, boolean[]>();
                    ids.forEach(id -> state.put(id, new boolean[]{false, false}));
                    likes.findLikedPostIds(user, ids).forEach(id -> state.get(id)[0] = true);
                    favorites.findFavoritedPostIds(user, ids).forEach(id -> state.get(id)[1] = true);
                } else {
                    interactions.findStatesFor(ids, user);
                }
                times[i] = System.nanoTime() - start;
            }
            java.util.Arrays.sort(times);
            java.util.Properties info;
            long keys;
            try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
                info = connection.serverCommands().info("memory");
                keys = connection.serverCommands().dbSize();
            }
            System.out.printf("PERF phase=%s requests=%d users=%d batch=%d sql=%d keys=%d used_memory=%s p50_us=%d p95_us=%d p99_us=%d%n",
                    phase == 0 ? "before" : "after", times.length, users.size(), ids.size(), statistics.getPrepareStatementCount(),
                    keys, info.getProperty("used_memory"),
                    times[99] / 1000, times[189] / 1000, times[197] / 1000);
        }
        statistics.clear();
        long[] writes = new long[20];
        for (int i = 0; i < writes.length; i++) {
            String user = users.get(i);
            long start = System.nanoTime();
            cache.invalidate(com.kuros.kurosbackend.interaction.event.InteractionKind.LIKE, ids.get(0), user);
            cache.recentLikes(user, () -> likes.findByUserIdOrderByCreatedAtDescPostIdDesc(user, org.springframework.data.domain.PageRequest.of(0, cache.likesMaxSize())));
            writes[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(writes);
        System.out.printf("PERF rebuilds=20 sql=%d p50_us=%d p95_us=%d version_ttl=%d%n", statistics.getPrepareStatementCount(), writes[9] / 1000, writes[18] / 1000,
                redisTemplate.getExpire("user:likes:patten:" + users.getFirst() + ":post:version"));
        statistics.setStatisticsEnabled(false);
    }

    @Test
    void 旧数据库快照不能覆盖并发取消() throws Exception {
        String user = UUID.randomUUID().toString();
        jdbc.update("insert into post_likes (user_id, post_id, created_at) values (?, ?, now())", user, POST);
        var snapshotTaken = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> cache.recentLikes(user, () -> {
                var rows = likes.findByUserIdOrderByCreatedAtDescPostIdDesc(user, org.springframework.data.domain.PageRequest.of(0, 3));
                snapshotTaken.countDown();
                try { resume.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return rows;
            }));
            try {
                assertThat(snapshotTaken.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                jdbc.update("delete from post_likes where user_id = ? and post_id = ?", user, POST);
                cache.invalidate(com.kuros.kurosbackend.interaction.event.InteractionKind.LIKE, POST, user);
            } finally { resume.countDown(); }
            assertThat(result.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEmpty();
        }
    }

    @Test
    void 取消后窗口补齐且相同时间分页稳定() {
        String user = UUID.randomUUID().toString();
        var ids = java.util.stream.IntStream.rangeClosed(1, 4)
                .mapToObj(i -> "10000000-0000-0000-0000-%012d".formatted(i)).toList();
        for (String id : ids) jdbc.update("insert into post_likes (user_id, post_id, created_at) values (?, ?, '2026-01-01 12:00:00')", user, id);
        var first = likes.findByUserIdOrderByCreatedAtDescPostIdDesc(user, org.springframework.data.domain.PageRequest.of(0, 2));
        var second = likes.findByUserIdOrderByCreatedAtDescPostIdDesc(user, org.springframework.data.domain.PageRequest.of(1, 2));
        assertThat(java.util.stream.Stream.concat(first.stream(), second.stream()).map(x -> x.getPostId()).toList())
                .containsExactly(ids.get(3), ids.get(2), ids.get(1), ids.get(0));
        java.util.function.Supplier<java.util.List<com.kuros.kurosbackend.interaction.domain.PostLike>> load =
                () -> likes.findByUserIdOrderByCreatedAtDescPostIdDesc(user, org.springframework.data.domain.PageRequest.of(0, 3));
        assertThat(cache.recentLikes(user, load)).containsExactly(ids.get(3), ids.get(2), ids.get(1));
        jdbc.update("delete from post_likes where user_id = ? and post_id = ?", user, ids.get(3));
        cache.invalidate(com.kuros.kurosbackend.interaction.event.InteractionKind.LIKE, ids.get(3), user);
        assertThat(cache.recentLikes(user, load)).containsExactly(ids.get(2), ids.get(1), ids.get(0));
    }

    @Test
    void 并发重投和取消后重放保持正确状态() throws Exception {
        String user = UUID.randomUUID().toString();
        long before = posts.findById(POST).orElseThrow().getLikeCount();
        var like = InteractionEvent.of(POST, user, InteractionType.LIKE);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var gate = new CountDownLatch(1);
            var tasks = IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> {
                gate.await();
                projection.apply(like);
                return null;
            })).toList();
            gate.countDown();
            for (var task : tasks) task.get();
        }
        assertThat(projection.findOperation(like.eventId(), user).state().likeCount()).isEqualTo(before + 1);
        projection.apply(InteractionEvent.of(POST, user, InteractionType.UNLIKE));
        projection.apply(like);
        assertThat(projection.findOperation(like.eventId(), user).state().liked()).isFalse();
        assertThat(projection.findOperation(like.eventId(), user).state().likeCount()).isEqualTo(before);
    }

    @Test
    void 操作编号并发冲突由数据库唯一约束持久绑定() throws Exception {
        String operation = UUID.randomUUID().toString();
        var users = java.util.List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var tasks = users.stream().map(user -> pool.submit(() -> {
                gate.await();
                try {
                    interactions.submit(POST, user, operation, InteractionType.LIKE);
                    return "SENT";
                } catch (AuthRequestException conflict) {
                    return "CONFLICT";
                }
            })).toList();
            gate.countDown();
            assertThat(java.util.List.of(tasks.get(0).get(), tasks.get(1).get())).containsExactlyInAnyOrder("SENT", "CONFLICT");
        }
    }
}
