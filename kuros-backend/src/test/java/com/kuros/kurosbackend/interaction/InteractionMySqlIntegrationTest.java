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
