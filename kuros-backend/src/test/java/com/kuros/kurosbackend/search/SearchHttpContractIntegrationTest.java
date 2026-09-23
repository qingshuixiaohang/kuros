package com.kuros.kurosbackend.search;

import com.jayway.jsonpath.JsonPath;
import com.kuros.kurosbackend.TestDatabases;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 搜索读路径 HTTP 契约集成测试（切片 #14 se-04，S1 seam——最高层，直打 {@code GET /api/v1/search}）。
 *
 * <p>Prior art：#13 {@code CursorHttpContractIntegrationTest}（MockMvc + 游标信封契约）、
 * se-01 {@code ElasticsearchIkSpikeTest} / se-03 {@code PostIndexServiceIntegrationTest}（自建 ik 镜像 ES 容器）。
 *
 * <p>只验证外部可观测行为（HTTP 响应），不绑定内部实现：
 * <ol>
 *   <li>ik 分词命中隔字中文（"鸣潮攻略"→"鸣潮的攻略详解"），LIKE 做不到；逻辑删（status=DELETED）帖子被排除；</li>
 *   <li>multi_match boost：同样命中两词，title(^3) 命中排在 content(^1) 命中前；</li>
 *   <li>高亮：命中词被 {@code <mark>} 包裹；</li>
 *   <li>过滤：category / type / tag 缩小结果集；</li>
 *   <li>三排序：relevance(_score) / latest(publishedAt) / hot(计数)；</li>
 *   <li>search_after 深翻：limit=1 翻 3 页不重不漏、hasMore 收敛、nextCursor 末页为 null；</li>
 *   <li>响应复用 #13 {@code CursorPageResult} 契约（data.items/nextCursor/hasMore，无 meta）；</li>
 *   <li>非法游标 → 400 INVALID_CURSOR。</li>
 * </ol>
 *
 * <p>种子直接写入 ES（{@code operations.save}）而非经 {@code PostIndexService}——回源组装/幂等是 se-03 的职责
 * （已由 {@code PostIndexServiceIntegrationTest} 覆盖），本类聚焦「查得到、排得对、翻得动」的搜索行为，
 * 故直接构造索引文档以精确控制字段、隔离对 DB/Feign 的依赖。
 *
 * <p>⚠️ 默认跳过（gated）：需真实 ES 容器（自建 ik 镜像），用 {@code -Dkuros.it.es=true} 显式开启——CI 裸 {@code mvn test} 不跑。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("es-search")
@EnabledIfSystemProperty(named = "kuros.it.es", matches = "true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SearchHttpContractIntegrationTest {

    private static final String ES_IMAGE = "kuros-es-ik:9.4.5";
    private static final String AUTHOR_ID = "10000000-0000-0000-0000-000000000001";
    private static final String AUTHOR_NAME = "潮声档案员";

    // 4 篇种子（3 PUBLISHED + 1 DELETED）；postId 用 ASCII 便于从 JSON 稳定提取断言（规避中文编码干扰）
    private static final String GUIDE = "mingchao-guide";
    private static final String TALK = "mingchao-talk";
    private static final String YUANSHEN = "yuanshen-rank";
    private static final String DELETED = "deleted-mingchao";

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

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("search-http"));
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + es.getHost() + ":" + es.getMappedPort(9200));
    }

    @Autowired MockMvc mockMvc;
    @Autowired ElasticsearchOperations operations;
    @Autowired SearchIndexManager indexManager;

    @BeforeEach
    void setUp() {
        // 幂等确保别名就绪（首个用例建 post_search_v1）；save 按 _id 覆盖写，故每个用例种子状态一致、无需清库
        indexManager.ensureAlias();
        seed(GUIDE, "鸣潮的攻略详解", "声骸搭配与实战", "配队思路详解",
                "配队攻略", "GUIDE", "PUBLISHED", List.of("鸣潮", "攻略"),
                LocalDateTime.of(2026, 9, 1, 10, 0), 10, 5, 100);
        seed(TALK, "随便聊聊日常", "日常杂谈", "今天聊聊鸣潮攻略的心得体会",
                "杂谈", "GENERAL", "PUBLISHED", List.of("闲聊"),
                LocalDateTime.of(2026, 9, 5, 10, 0), 50, 30, 500);
        seed(YUANSHEN, "原神角色强度榜", "版本强度分析", "原神当前版本角色排行",
                "攻略", "GUIDE", "PUBLISHED", List.of("原神"),
                LocalDateTime.of(2026, 9, 3, 10, 0), 5, 2, 80);
        seed(DELETED, "已删除的鸣潮帖", "摘要", "鸣潮",
                "公告", "GENERAL", "DELETED", List.of("鸣潮"),
                LocalDateTime.of(2026, 9, 4, 10, 0), 0, 0, 0);
        operations.indexOps(IndexCoordinates.of(PostSearchDoc.INDEX_ALIAS)).refresh();
    }

    @Test
    void ik分词命中隔字中文且逻辑删帖子被排除() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/search").param("keyword", "鸣潮攻略"))
                .andExpect(status().isOk())
                .andReturn();
        List<String> ids = idsOf(result);
        // ik_smart 把"鸣潮攻略"切成 [鸣潮, 攻略]：命中 title 隔字的"鸣潮的攻略详解"(GUIDE)
        // —— MySQL LIKE '%鸣潮攻略%' 对隔字串返回 0 行，这正是要替代的退化；也命中 content 含"鸣潮攻略"的 TALK
        assertThat(ids).contains(GUIDE, TALK);
        // status=DELETED 的帖子被 filter status=PUBLISHED 排除（逻辑删可逆语义，se-03 D4）
        assertThat(ids).doesNotContain(DELETED);
    }

    @Test
    void relevanceBoost让title命中排在content命中前() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/search")
                        .param("keyword", "鸣潮攻略")
                        .param("sort", "relevance"))
                .andExpect(status().isOk())
                .andReturn();
        // GUIDE 与 TALK 同样命中 [鸣潮, 攻略] 两词，但 GUIDE 在 title(^3)、TALK 在 content(^1)
        // → query-time boost 令标题命中相关性更高，GUIDE 排第一（D7 多字段加权）
        assertThat(idsOf(result).get(0)).isEqualTo(GUIDE);
    }

    @Test
    void 高亮返回mark包裹的命中片段() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/search").param("keyword", "原神强度"))
                .andExpect(status().isOk())
                .andReturn();
        // "原神强度"只命中 YUANSHEN，items[0] 即它；高亮片段用 <mark> 包裹命中词（前端直接渲染，spec D10）
        assertThat(idsOf(result)).containsExactly(YUANSHEN);
        String titleHighlight = JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data.items[0].highlight.title[0]");
        assertThat(titleHighlight).contains("<mark>");
    }

    @Test
    void category与type过滤缩小结果集() throws Exception {
        // keyword=鸣潮 命中 {GUIDE, TALK}（DELETED 被排除）
        MvcResult byCategory = mockMvc.perform(get("/api/v1/search")
                        .param("keyword", "鸣潮").param("category", "配队攻略"))
                .andExpect(status().isOk())
                .andReturn();
        // category=配队攻略 只剩 GUIDE（TALK 属"杂谈"）
        assertThat(idsOf(byCategory)).containsExactly(GUIDE);

        MvcResult byType = mockMvc.perform(get("/api/v1/search")
                        .param("keyword", "鸣潮").param("type", "GUIDE"))
                .andExpect(status().isOk())
                .andReturn();
        // type=GUIDE 只剩 GUIDE（TALK 是 GENERAL）
        assertThat(idsOf(byType)).containsExactly(GUIDE);
    }

    @Test
    void tag过滤配合空关键词退化为matchAll() throws Exception {
        // 不传 keyword → match_all（仍可过滤/排序）；tag=闲聊 只有 TALK 带此标签
        MvcResult result = mockMvc.perform(get("/api/v1/search").param("tag", "闲聊"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(idsOf(result)).containsExactly(TALK);
    }

    @Test
    void latest排序按发布时间倒序() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/search").param("sort", "latest"))
                .andExpect(status().isOk())
                .andReturn();
        // match_all → 3 条 PUBLISHED，按 publishedAt desc：TALK(09-05) > YUANSHEN(09-03) > GUIDE(09-01)
        assertThat(idsOf(result)).containsExactly(TALK, YUANSHEN, GUIDE);
    }

    @Test
    void hot排序按热度倒序() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/search").param("sort", "hot"))
                .andExpect(status().isOk())
                .andReturn();
        // 按 likeCount desc：TALK(50) > GUIDE(10) > YUANSHEN(5)
        assertThat(idsOf(result)).containsExactly(TALK, GUIDE, YUANSHEN);
    }

    @Test
    void searchAfter深翻不重不漏且hasMore收敛() throws Exception {
        // match_all + latest，limit=1 → 逐页翻完 3 条 PUBLISHED，验证 search_after 游标翻页正确性
        MvcResult page1 = mockMvc.perform(get("/api/v1/search")
                        .param("sort", "latest").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").isNotEmpty())
                .andReturn();
        assertThat(idsOf(page1)).containsExactly(TALK);
        String cursor1 = nextCursorOf(page1);

        MvcResult page2 = mockMvc.perform(get("/api/v1/search")
                        .param("sort", "latest").param("limit", "1").param("cursor", cursor1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andReturn();
        assertThat(idsOf(page2)).containsExactly(YUANSHEN);
        String cursor2 = nextCursorOf(page2);

        MvcResult page3 = mockMvc.perform(get("/api/v1/search")
                        .param("sort", "latest").param("limit", "1").param("cursor", cursor2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andReturn();
        assertThat(idsOf(page3)).containsExactly(GUIDE);
        // 末页无下一页：nextCursor 为 null（CursorPageResult 无 NON_NULL，序列化为显式 null）
        assertThat(nextCursorOf(page3)).isNull();
    }

    @Test
    void 响应复用CursorPageResult契约且无meta() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("keyword", "鸣潮").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.hasMore").isBoolean())
                // 游标契约不带总数：meta 被 ApiResponse 的 @JsonInclude(NON_NULL) 抹掉，前端据此走「加载更多」
                .andExpect(jsonPath("$.meta").doesNotExist());
    }

    @Test
    void 非法游标映射为400与INVALID_CURSOR() throws Exception {
        // 游标解码在 ES 调用之前，故非法 cursor 得 400（而非降级 503）——与 #13 帖子列表同款契约
        mockMvc.perform(get("/api/v1/search")
                        .param("keyword", "鸣潮").param("cursor", "!!!not-base64!!!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    // ---- helpers ----

    private void seed(String postId, String title, String excerpt, String content, String category,
                      String type, String status, List<String> tags, LocalDateTime publishedAt,
                      long like, long comment, long view) {
        operations.save(PostSearchDoc.builder()
                .postId(postId).title(title).excerpt(excerpt).content(content)
                .authorId(AUTHOR_ID).authorName(AUTHOR_NAME)
                .category(category).type(type).status(status).tags(tags)
                .viewCount(view).likeCount(like).favoriteCount(0).commentCount(comment)
                .publishedAt(publishedAt)
                .build());
    }

    private List<String> idsOf(MvcResult result) throws Exception {
        return JsonPath.read(body(result), "$.data.items[*].id");
    }

    private String nextCursorOf(MvcResult result) throws Exception {
        return JsonPath.read(body(result), "$.data.nextCursor");
    }

    private String body(MvcResult result) throws Exception {
        // 显式 UTF-8 解码：MockHttpServletResponse 默认字符集非 UTF-8，直读会令中文高亮片段乱码
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
