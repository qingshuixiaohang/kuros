package com.kuros.kurosbackend.interaction;

import com.kuros.kurosbackend.interaction.event.InteractionEvent;
import com.kuros.kurosbackend.interaction.event.InteractionKind;
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
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
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

    /**
     * #190 真实 MySQL 死锁：不用异常替身，而是让两个真实事务在 InnoDB 上锁竞争。
     *
     * 业务事务（projection.apply 取消点赞）先锁 posts 行，再删除 post_likes 关系行；
     * 竞争事务反过来先对同一关系行加记录锁，再请求 posts 行锁。两者形成环，由 InnoDB 检测并回滚一方。
     * 使用已存在的关系行而不是间隙锁，避免依赖隔离级别对 gap lock 的取舍。
     *
     * 为了让「业务事务是牺牲者」可复现：InnoDB 选择 undo 记录更少的事务回滚，业务事务此时
     * 只写入 interaction_operations 并删除一行关系，竞争事务在同事务内写入 16 行测试表以增加 undo。
     * 测试表仅用于制造该偏置，不参与业务断言。
     *
     * 断言：受害业务事务完整回滚（操作记录、关系、计数均未留下）、异常被翻译为并发失败；
     * 同一事件重投后只应用一次，关系与计数正确。
     *
     * 局限：这是可控死锁，不是生产自然出现的竞争；仍未验证 RocketMQ 消息层重投（由 broker 用例覆盖）。
     */
    @Test
    void 真实死锁牺牲业务事务后完整回滚并可重投() throws Exception {
        String user = UUID.randomUUID().toString();
        var id = new com.kuros.kurosbackend.interaction.domain.PostLikeId(user, POST);
        long before = likeCountInDb();
        jdbc.update("insert into post_likes (user_id, post_id, created_at) values (?, ?, now())", user, POST);
        jdbc.update("update posts set like_count = like_count + 1 where id = ?", POST);
        var event = InteractionEvent.of(POST, user, InteractionType.UNLIKE);
        var operationId = event.eventId();
        jdbc.execute("create table if not exists interaction_deadlock_probe (id int primary key, note varchar(16)) engine=InnoDB");
        jdbc.update("delete from interaction_deadlock_probe");

        var rowLocked = new CountDownLatch(1);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);

        try (var competitorPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "deadlock-competitor"));
             var businessPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "deadlock-business"))) {
            // 竞争事务先锁已有关系行，等业务事务真正阻塞在该行上，最后反序请求 posts 行锁。
            var competitor = competitorPool.submit(() -> {
                tx.executeWithoutResult(status -> {
                    for (int i = 0; i < 16; i++) {
                        jdbc.update("insert into interaction_deadlock_probe (id, note) values (?, 'undo-bias')", 1000 + i);
                    }
                    jdbc.queryForList("select user_id from post_likes where user_id = ? and post_id = ? for update", user, POST);
                    rowLocked.countDown();
                    awaitBusinessBlockedOnRelationRow();
                    jdbc.queryForList("select id from posts where id = ? for update", POST);
                });
                return "COMMITTED";
            });
            assertThat(rowLocked.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var business = businessPool.submit(() -> {
                projection.apply(event);
                return "APPLIED";
            });

            Throwable businessFailure = org.assertj.core.api.Assertions.catchThrowable(() -> business.get(60, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(businessFailure).isInstanceOf(java.util.concurrent.ExecutionException.class);
            Throwable cause = businessFailure.getCause();
            assertThat(cause).isInstanceOf(org.springframework.dao.ConcurrencyFailureException.class);
            assertThat(messagesOf(cause)).containsIgnoringCase("deadlock");
            assertThat(competitor.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("COMMITTED");

            // 受害事务完整回滚：操作记录、关系、计数都不留痕
            assertThat(projection.isApplied(event)).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from interaction_operations where operation_id = ?", Long.class, operationId)).isZero();
            assertThat(likes.existsById(id)).isTrue();
            assertThat(likeCountInDb()).isEqualTo(before + 1);
        }

        // 重投只应用一次
        projection.apply(event);
        assertThat(projection.isApplied(event)).isTrue();
        assertThat(likes.existsById(id)).isFalse();
        assertThat(likeCountInDb()).isEqualTo(before);
        jdbc.execute("drop table if exists interaction_deadlock_probe");
    }

    /**
     * 收藏状态的回填必须带版本围栏：读取者先拿到 DB 旧快照，随后业务提交并失效，
     * 旧快照不能在此之后被装回缓存。
     */
    @Test
    void 收藏状态旧快照不能覆盖并发失效() throws Exception {
        String user = UUID.randomUUID().toString();
        var id = new com.kuros.kurosbackend.interaction.domain.PostFavoriteId(user, POST);
        cache.invalidate(InteractionKind.FAVORITE, POST, user);
        var snapshotTaken = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var read = pool.submit(() -> cache.readState(InteractionKind.FAVORITE, POST, user, () -> {
                boolean exists = favorites.existsById(id);
                snapshotTaken.countDown();
                awaitLatch(resume);
                return exists;
            }));
            assertThat(snapshotTaken.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            jdbc.update("insert into post_favorites (user_id, post_id, created_at) values (?, ?, now())", user, POST);
            cache.invalidate(InteractionKind.FAVORITE, POST, user);
            resume.countDown();
            assertThat(read.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(redisTemplate.opsForValue().get("interaction:favorite:" + POST + ":" + user)).isEqualTo("1");
        } finally {
            jdbc.update("delete from post_favorites where user_id = ? and post_id = ?", user, POST);
            cache.invalidate(InteractionKind.FAVORITE, POST, user);
        }
    }

    /** 收藏计数的回填同样不能安装失效前读到的旧基线。 */
    @Test
    void 收藏计数旧快照不能覆盖并发失效() throws Exception {
        String user = UUID.randomUUID().toString();
        long before = jdbc.queryForObject("select favorite_count from posts where id = ?", Long.class, POST);
        cache.invalidate(InteractionKind.FAVORITE, POST, user);
        var snapshotTaken = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var read = pool.submit(() -> cache.readCount(InteractionKind.FAVORITE, POST, () -> {
                long value = jdbc.queryForObject("select favorite_count from posts where id = ?", Long.class, POST);
                snapshotTaken.countDown();
                awaitLatch(resume);
                return value;
            }));
            assertThat(snapshotTaken.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            jdbc.update("update posts set favorite_count = favorite_count + 1 where id = ?", POST);
            cache.invalidate(InteractionKind.FAVORITE, POST, user);
            resume.countDown();
            assertThat(read.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(before + 1);
            assertThat(redisTemplate.opsForValue().get("interaction:count:favorite:" + POST)).isEqualTo(Long.toString(before + 1));
        } finally {
            jdbc.update("update posts set favorite_count = ? where id = ?", before, POST);
            cache.invalidate(InteractionKind.FAVORITE, POST, user);
        }
    }

    /** 对账读到真相后若发生提交失效，必须重读真相而不是写回旧值。 */
    @Test
    void 对账旧真相不能覆盖并发失效() throws Exception {
        String user = UUID.randomUUID().toString();
        cache.invalidate(InteractionKind.FAVORITE, POST, user);
        var truthRead = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reconcile = pool.submit(() -> cache.reconcileCount(InteractionKind.FAVORITE, POST, () -> {
                int call = calls.getAndIncrement();
                if (call == 0) {
                    truthRead.countDown();
                    awaitLatch(resume);
                    return 3L;
                }
                return 4L;
            }));
            assertThat(truthRead.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            cache.invalidate(InteractionKind.FAVORITE, POST, user);
            resume.countDown();
            var result = reconcile.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.count()).isEqualTo(4);
            assertThat(calls.get()).isEqualTo(2);
            assertThat(redisTemplate.opsForValue().get("interaction:count:favorite:" + POST)).isEqualTo("4");
        } finally {
            cache.invalidate(InteractionKind.FAVORITE, POST, user);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("等待并发屏障超时");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待并发屏障被中断", ex);
        }
    }

    /**
     * 等待业务事务真正阻塞在竞争事务持有的 post_likes 记录锁上。
     *
     * 不通过替身方法插桩：业务事务此刻处于 InnoDB 的 LOCK WAIT 状态，
     * performance_schema.data_locks 可观察到它在 post_likes 上的等待，
     * 竞争事务据此再反序请求 posts 行锁，构成确定性死锁。
     */
    private void awaitBusinessBlockedOnRelationRow() {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "select count(*) from performance_schema.data_locks "
                            + "where OBJECT_SCHEMA = 'kuros' and OBJECT_NAME = 'post_likes' and LOCK_STATUS = 'WAITING'",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待业务事务阻塞被中断", ex);
            }
        }
        var transactions = jdbc.queryForList("select trx_id, trx_state, trx_query from information_schema.innodb_trx");
        var locks = jdbc.queryForList("select LOCK_MODE, LOCK_TYPE, LOCK_STATUS, LOCK_DATA from performance_schema.data_locks where OBJECT_SCHEMA = 'kuros' and OBJECT_NAME = 'post_likes'");
        throw new AssertionError("业务事务未在 post_likes 关系行锁上进入 LOCK WAIT：trx=" + transactions + " locks=" + locks);
    }

    private long likeCountInDb() {
        return posts.findById(POST).orElseThrow().getLikeCount();
    }

    private static String messagesOf(Throwable throwable) {
        var builder = new StringBuilder();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            builder.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage()).append('\n');
        }
        return builder.toString();
    }
}
