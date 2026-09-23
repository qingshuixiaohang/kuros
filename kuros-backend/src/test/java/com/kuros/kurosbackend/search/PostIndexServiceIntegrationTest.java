package com.kuros.kurosbackend.search;

import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.UserDirectoryStub;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.domain.ContentTag;
import com.kuros.kurosbackend.post.domain.PostStatus;
import com.kuros.kurosbackend.post.domain.PostType;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.post.repository.ContentTagRepository;
import com.kuros.kurosbackend.shared.api.CursorPageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostIndexService 回源组装 + 幂等 upsert 集成测试（切片 #14 se-03，S2 seam）。
 *
 * <p>Prior art：#13 {@code TwoLevelCacheIntegrationTest}（Testcontainers + H2 + 桩 Feign 的集成测试范式）、
 * se-01 {@code ElasticsearchIkSpikeTest}（自建 ik 镜像 ES 容器）。
 *
 * <p>只验证外部可观测行为（不绑定内部实现）：
 * <ol>
 *   <li>回源组装：index(postId) 后 ES 文档字段正确——tags 来自 post_tags join、authorName 来自 Feign 桩、
 *       status/计数/发布时间来自 DB；</li>
 *   <li>逻辑删（status→DELETED）保留文档，status 字段随之更新（不物理删）；</li>
 *   <li>幂等：重复 index 同一 postId 不产生副本（_id=postId 覆盖写）；</li>
 *   <li>零停机全量重建：reindexAll 切到新版本索引、旧索引删除、文档仍可经别名读到；</li>
 *   <li>物理删/回源查不到：兜底 delete by _id，ES 不留孤儿文档；</li>
 *   <li>恢复可搜（spec S2）：逻辑删后 status 恢复 PUBLISHED → 再索引 → 搜索重新命中（DELETED 期间被 status 过滤排除）；</li>
 *   <li>启动引导（code-review 修复）：全新 ES 无别名时经 {@code ensureAliasIfAvailable} 引导后，搜索返回空结果而非 index_not_found→503。</li>
 * </ol>
 *
 * <p>⚠️ 默认跳过（gated）：需真实 ES 容器（自建 ik 镜像），用 {@code -Dkuros.it.es=true} 显式开启，
 * 与 se-01 spike / InteractionRocketMQIntegrationTest 同款门控——CI 裸 {@code mvn test} 不跑。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("es-index")
@EnabledIfSystemProperty(named = "kuros.it.es", matches = "true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostIndexServiceIntegrationTest {

    private static final String ES_IMAGE = "kuros-es-ik:9.4.5";
    /** 桩种子用户 ...0001 → 昵称「潮声档案员」（见 UserDirectoryStub.SEED_USERS）。 */
    private static final String AUTHOR_ID = "10000000-0000-0000-0000-000000000001";
    private static final String AUTHOR_NAME = "潮声档案员";
    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 9, 23, 10, 0, 0);

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static GenericContainer<?> es = new GenericContainer<>(ES_IMAGE)
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    // static 字段类加载即启动 HttpServer，@DynamicPropertySource 注入端口时已就绪（同 KurosBackendApplicationTests）
    static final UserDirectoryStub userDirectory = new UserDirectoryStub();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("post-index"));
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + es.getHost() + ":" + es.getMappedPort(9200));
        // Feign 直连桩：authorName 回源走 UserDirectoryStub（...0001 → 潮声档案员）
        registry.add("app.feign.kuros-user.url", userDirectory::baseUrl);
    }

    @Autowired PostIndexService postIndexService;
    @Autowired SearchIndexManager indexManager;
    @Autowired ElasticsearchOperations operations;
    @Autowired CommunityPostRepository postRepository;
    @Autowired ContentTagRepository tagRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SearchQueryService searchQueryService;

    @BeforeEach
    void setUp() {
        // DB 清理：严格按外键依赖顺序先清子表再清主表——种子数据（Flyway seed）里帖子带评论/点赞，
        // 直接 deleteAll(posts) 会撞 fk_comments_post 等外键约束（对齐 PostListCursorIntegrationTest 的清理集）
        jdbcTemplate.update("DELETE FROM post_media");
        jdbcTemplate.update("DELETE FROM post_tags");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM post_likes");
        jdbcTemplate.update("DELETE FROM post_favorites");
        postRepository.deleteAll();
        // ES 清理：删掉可能残留的版本索引 + 别名，让每个用例从「无索引」冷态开始（ensureAlias 会重建 v1）
        wipeSearchIndices();
    }

    @Test
    void 回源组装tags与authorName与计数与状态() {
        String postId = seedPost("鸣潮的攻略详解", "声骸搭配与实战思路", List.of("鸣潮", "攻略"), 7, 3);

        postIndexService.index(postId);
        postIndexService.refresh();

        Optional<PostSearchDoc> found = postIndexService.findById(postId);
        assertThat(found).isPresent();
        PostSearchDoc doc = found.get();
        assertThat(doc.getPostId()).isEqualTo(postId);
        assertThat(doc.getTitle()).isEqualTo("鸣潮的攻略详解");
        // tags 来自 post_tags join 表（回源组装的核心证据）；assemble 内排序 → 确定化断言
        assertThat(doc.getTags()).containsExactly("攻略", "鸣潮");
        // authorName 来自 Feign 桩（跨库回源），非 DB 字段
        assertThat(doc.getAuthorId()).isEqualTo(AUTHOR_ID);
        assertThat(doc.getAuthorName()).isEqualTo(AUTHOR_NAME);
        assertThat(doc.getStatus()).isEqualTo(PostStatus.PUBLISHED.name());
        assertThat(doc.getType()).isEqualTo(PostType.GUIDE.name());
        // 计数来自 DB（seed 时 jdbc 改的 like=7/comment=3）
        assertThat(doc.getLikeCount()).isEqualTo(7L);
        assertThat(doc.getCommentCount()).isEqualTo(3L);
        // 发布时间经 JsonpMapper 序列化写入 ES 再回读不丢（se-01 已验日期路径）
        assertThat(doc.getPublishedAt()).isEqualTo(PUBLISHED_AT);
    }

    @Test
    void 逻辑删保留文档且status更新为DELETED() {
        String postId = seedPost("待删帖", "正文", List.of("公告"), 0, 0);
        postIndexService.index(postId);
        postIndexService.refresh();
        assertThat(postIndexService.findById(postId)).isPresent();

        // 逻辑删：status→DELETED（不物理删行）
        CommunityPost post = postRepository.findById(postId).orElseThrow();
        post.delete(LocalDateTime.now());
        postRepository.save(post);

        // CDC 会把这条 UPDATE 同步为再索引 → 文档保留、status 随之变为 DELETED
        postIndexService.index(postId);
        postIndexService.refresh();

        Optional<PostSearchDoc> found = postIndexService.findById(postId);
        assertThat(found).as("逻辑删不物理删文档").isPresent();
        assertThat(found.get().getStatus()).isEqualTo(PostStatus.DELETED.name());
    }

    @Test
    void 重复索引幂等不产生副本() {
        String postId = seedPost("幂等帖", "正文", List.of("幂等"), 0, 0);

        postIndexService.index(postId);
        postIndexService.index(postId); // 重复索引（模拟 CDC 至少一次投递的消息重投）
        postIndexService.index(postId);
        postIndexService.refresh();

        // _id=postId 覆盖写：索引里该帖仍只有 1 篇，无副本
        assertThat(countAll()).isEqualTo(1L);
        assertThat(postIndexService.findById(postId)).isPresent();
    }

    @Test
    void 全量重建零停机切别名且旧索引删除() {
        String a = seedPost("重建帖A", "正文A", List.of("重建"), 0, 0);
        String b = seedPost("重建帖B", "正文B", List.of("重建"), 0, 0);
        // 先建立 v1（ensureAlias）并写入，模拟「已有旧索引」
        postIndexService.index(a);
        assertThat(indexManager.resolveCurrentIndex()).contains("post_search_v1");

        long indexed = postIndexService.reindexAll();

        assertThat(indexed).isEqualTo(2L);
        // 别名原子切到 v2、旧 v1 删除（零停机重建的可观测结果）
        assertThat(indexManager.resolveCurrentIndex()).contains("post_search_v2");
        assertThat(operations.indexOps(IndexCoordinates.of("post_search_v1")).exists()).isFalse();
        postIndexService.refresh();
        // 两篇文档仍经别名可读（重建未丢数据）
        assertThat(postIndexService.findById(a)).isPresent();
        assertThat(postIndexService.findById(b)).isPresent();
        assertThat(countAll()).isEqualTo(2L);
    }

    @Test
    void 物理删除或回源查不到时兜底删文档() {
        String postId = seedPost("将被物理删", "正文", List.of("删除"), 0, 0);
        postIndexService.index(postId);
        postIndexService.refresh();
        assertThat(postIndexService.findById(postId)).isPresent();

        // 物理删 DB 行 → 再 index 时回源查不到 → 兜底 delete by _id
        jdbcTemplate.update("DELETE FROM post_tags WHERE post_id = ?", postId);
        postRepository.deleteById(postId);
        postIndexService.index(postId);
        postIndexService.refresh();

        assertThat(postIndexService.findById(postId)).isEmpty();
        assertThat(countAll()).isZero();
    }

    @Test
    void 逻辑删后恢复PUBLISHED重新可搜() {
        // 唯一数字 token：ik 把连续阿拉伯数字切成单一 token，搜该 token 精确命中本帖，规避中文分词不确定性
        String token = String.valueOf(System.nanoTime());
        String postId = seedPost("鸣潮攻略" + token, "正文", List.of("恢复"), 0, 0);

        // PUBLISHED → 索引后经真实搜索可命中
        postIndexService.index(postId);
        postIndexService.refresh();
        assertThat(searchIds(token)).contains(postId);

        // 逻辑删（status→DELETED）→ 再索引 → 文档保留，但被搜索期 filter status=PUBLISHED 排除
        CommunityPost post = postRepository.findById(postId).orElseThrow();
        post.delete(LocalDateTime.now());
        postRepository.save(post);
        postIndexService.index(postId);
        postIndexService.refresh();
        assertThat(postIndexService.findById(postId)).as("逻辑删不物理删文档").isPresent();
        assertThat(postIndexService.findById(postId).orElseThrow().getStatus()).isEqualTo(PostStatus.DELETED.name());
        assertThat(searchIds(token)).as("DELETED 期间被搜索过滤排除").doesNotContain(postId);

        // 恢复：status 翻回 PUBLISHED（模拟运维恢复 / CDC 拾取该 UPDATE）→ 再索引 → 重新可搜。
        // 领域暂无 restore()，直接改 DB status 模拟外部恢复；索引层「已删帖可恢复」正是逻辑删不物理删的设计目的。
        jdbcTemplate.update("UPDATE posts SET status = ? WHERE id = ?", PostStatus.PUBLISHED.name(), postId);
        postIndexService.index(postId);
        postIndexService.refresh();
        assertThat(postIndexService.findById(postId).orElseThrow().getStatus()).isEqualTo(PostStatus.PUBLISHED.name());
        assertThat(searchIds(token)).as("恢复 PUBLISHED 后重新可搜").contains(postId);
    }

    @Test
    void 全新ES经启动引导后搜索返回空而非降级503() {
        // @BeforeEach 已 wipe 索引 → 别名不存在，模拟「全新部署、尚未发帖也未跑全量重建」的冷态
        assertThat(indexManager.resolveCurrentIndex()).isEmpty();

        // 启动引导（SearchIndexBootstrap 在 ApplicationReadyEvent 后调此）：ES 在线 → 建别名 post_search→v1
        indexManager.ensureAliasIfAvailable();
        assertThat(indexManager.resolveCurrentIndex()).as("引导后别名就绪").isPresent();

        // 别名就绪 → 搜空索引返回空结果，而非 index_not_found 被 SearchQueryService catch 成 503——
        // 这正是 code-review 修复的可观测证据（修复前全新栈搜索恒 503「暂不可用」，误导为 ES 挂了）
        CursorPageResult<PostSearchItem> result =
                searchQueryService.search("任意关键词", null, null, null, "relevance", null, 20);
        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    // ---- helpers ----

    /** 经 SearchQueryService 搜 keyword，返回命中的 postId 列表（走真实 ES 查询 + status=PUBLISHED 过滤）。 */
    private List<String> searchIds(String keyword) {
        return searchQueryService.search(keyword, null, null, null, "relevance", null, 50)
                .items().stream().map(PostSearchItem::id).toList();
    }

    private String seedPost(String title, String content, List<String> tagNames, long like, long comment) {
        CommunityPost post = CommunityPost.publish(
                AUTHOR_ID, PostType.GUIDE, "攻略", title, "摘要", content, PUBLISHED_AT);
        List<ContentTag> tags = tagNames.stream()
                .map(name -> tagRepository.findByName(name).orElseGet(() -> tagRepository.save(new ContentTag(name))))
                .toList();
        post.addTags(tags);
        postRepository.save(post);
        if (like != 0 || comment != 0) {
            jdbcTemplate.update("UPDATE posts SET like_count = ?, comment_count = ? WHERE id = ?",
                    like, comment, post.getId());
        }
        return post.getId();
    }

    private long countAll() {
        NativeQuery query = NativeQuery.builder().withQuery(q -> q.matchAll(m -> m)).build();
        return operations.search(query, PostSearchDoc.class).getTotalHits();
    }

    private void wipeSearchIndices() {
        // 别名本身不可直接删；逐个删可能存在的版本索引（v1..v8），吞掉 not_found
        for (int v = 1; v <= 8; v++) {
            try {
                operations.indexOps(IndexCoordinates.of("post_search_v" + v)).delete();
            } catch (RuntimeException ignored) {
                // 索引不存在，忽略
            }
        }
    }
}
