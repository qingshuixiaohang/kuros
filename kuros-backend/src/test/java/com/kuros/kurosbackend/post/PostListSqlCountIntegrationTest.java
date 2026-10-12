package com.kuros.kurosbackend.post;

import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.UserDirectoryStub;
import com.kuros.kurosbackend.post.api.CreatePostRequest;
import com.kuros.kurosbackend.post.service.CommunityPostService;
import com.kuros.kurosbackend.post.service.PostPublishingService;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 帖子列表读路径的 SQL 数量测量（审计 M5 / C1）。
 *
 * 要锁定的契约：一页 N 条帖子的媒体装载，SQL 数不得随 N 线性增长。
 *
 * 背景：toSummary 对每一帖调用 media(postId)，而 media() 内部是
 * 「findByPostIdOrderBySortOrderAsc + findAllById」两次查询。一页 50 条
 * → 1 + 50×2 = 101 条 SQL。项目在 split-08 专门消除了跨服务 Feign 的 N+1，
 * 但 DB 层 N+1 原样存在——#13 的定位是"读路径加固"，列表读路径的单位
 * 请求 SQL 数却是页大小的线性函数。
 *
 * 测量手段：Hibernate Statistics（测试内开启，不动生产配置）。
 * 它统计的是 JPA 层面的语句数，与"发了多少条 SQL 给数据库"等价。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PostListSqlCountIntegrationTest {

    /** 测试帖数：取一页的上界（生产 MAX_PAGE_SIZE=50），N+1 在此规模下最明显。 */
    private static final int POST_COUNT = 50;

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * 用户域桩：static 是因为 @DynamicPropertySource 只能是 static 方法，
     * 拿不到实例注入。与 KurosBackendApplicationTests 同款做法。
     */
    static final UserDirectoryStub userDirectory = new UserDirectoryStub();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("post-sql-count"));
        // Feign 直连桩，用户域不经网络，避免干扰 SQL 计数
        registry.add("app.feign.kuros-user.url", userDirectory::baseUrl);
        registry.add("app.internal.token", () -> "test-internal-token");
    }

    @Autowired
    private PostPublishingService publishingService;

    @Autowired
    private CommunityPostService postService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection().serverCommands().flushDb();

        SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
        statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);

        String authorId = "10000000-0000-0000-0000-000000000001";
        for (int i = 0; i < POST_COUNT; i++) {
            publishingService.publish(new CreatePostRequest(
                    "GUIDE", "配队攻略", "SQL 计数测试帖 " + i,
                    "摘要 " + i, "正文 " + i, List.of(), List.of()
            ), authorId);
        }
        statistics.clear();
    }

    @Test
    void 一页帖子的媒体装载SQL数不随页大小线性增长() {
        postService.findPublished(1, POST_COUNT, "latest", null, null, null);

        long statements = statistics.getPrepareStatementCount();
        long postLoads = statistics.getEntityLoadCount();

        // 契约：整页的 SQL 数必须是个位数量级（批量装载），而不是 O(页大小)。
        // 修复前实测约 101 条（1 列表 + 50×2 媒体）；修复后应为 3 条左右
        // （1 列表 + 1 关联 + 1 资产）。给 10 的上界留足余量且仍能抓住回归。
        assertThat(statements)
                .as("一页 %d 条帖子的 SQL 数应为常数级（批量装载），实际 %d 条", POST_COUNT, statements)
                .isLessThanOrEqualTo(10);

        assertThat(postLoads)
                .as("CommunityPost 实体加载次数同样不应随页大小线性增长，实际 %d 次", postLoads)
                .isLessThanOrEqualTo(POST_COUNT * 2L);
    }
}
