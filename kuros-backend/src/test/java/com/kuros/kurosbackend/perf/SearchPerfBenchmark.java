package com.kuros.kurosbackend.perf;

import com.kuros.kurosbackend.TestDatabases;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.domain.PostStatus;
import com.kuros.kurosbackend.post.domain.PostType;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.search.PostIndexService;
import com.kuros.kurosbackend.search.SearchQueryService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 搜索性能基准（切片 #14 se-07 · S4 seam · DoD 硬要求：真实实测，禁止编造 TPS/RT 数字）。
 *
 * <p>Prior art：#13 {@code ReadPathPerfBenchmark}（[PERF] 前缀 + 门控 + **沿某维度扫描打表**看趋势、
 * 耗时对比只报告不断言、确定性证据才 assertThat 的范式）、
 * se-03 {@code PostIndexServiceIntegrationTest} / se-04 {@code SearchHttpContractIntegrationTest}（自建 ik 镜像 ES 容器）。
 *
 * <p>默认被**双门控**跳过——{@code @EnabledIfSystemProperty} 可重复即 AND 语义，需同时
 * {@code -Dperf=true} 且 {@code -Dkuros.it.es=true} 才跑（既避免拖慢常规 CI，也避免纯 perf 跑意外拉起 ES 容器）：
 * <pre>
 *   ./mvnw.cmd test -Dtest=SearchPerfBenchmark -Dperf=true -Dkuros.it.es=true [-Dperf.posts=50000]
 * </pre>
 *
 * <p>采集两组「可复现的结构性证据」，全部 println 到 stdout（前缀 [PERF]，便于 grep 回填 docs/learning/14）：
 * <ol>
 *   <li><b>LIKE 全表扫随规模线性上涨、ES 倒排索引近乎恒定</b>：MySQL {@code lower(title/excerpt) LIKE '%kw%'} 前导通配符令
 *       B+ 树索引失效，每次查询 O(n) 扫描并丢弃不匹配行，耗时随帖子数近线性上涨；ES 经 ik 分词建倒排索引，查询直接定位到
 *       含该词的 posting list，耗时与总文档数近乎解耦。<b>为什么沿规模扫描打表而非只测单点</b>：单点无法体现「随 N 上涨」这一
 *       O(n) 本质，且小规模下 ES 因固定查询开销（HTTP 往返 + 协调 + 打分）反而可能略慢——扫描才能诚实呈现「小规模 ES 略慢 →
 *       交叉点 → 大规模 LIKE 被拉开」的完整分界，这正是 spec「结构性退化」论证的核心。</li>
 *   <li><b>隔字中文召回</b>：LIKE 是子串匹配，"鸣潮攻略" 无法命中 "鸣潮的攻略详解"（中间隔"的"字）；
 *       ES 用 ik_smart 把查询切成 [鸣潮, 攻略] 分词匹配，命中该帖且因 title boost(^3) 排到首位——量化 LIKE 的召回盲区。
 *       这是确定性行为差异，故用 assertThat 断言（对齐 prior art「耗时只报告、确定性才断言」的分工）。</li>
 * </ol>
 *
 * <p>ES 数据经 {@link PostIndexService#reindexAll()}（D5 生产全量重建路径：建 v{n+1} + ik mapping + 原子切别名 + bulk）灌入，
 * 而非直接 {@code operations.save}——既复用生产写入口保证 mapping 一致，也规避跨包访问 {@code SearchIndexManager} 的包级私有方法。
 * authorName 走 Feign，未配桩时 {@code UserDirectoryFacade.findAuthors} 优雅降级为占位昵称，不影响 title/excerpt 关键词检索。
 *
 * <p>环境诚实声明：数据来自 Testcontainers ES(自建 ik 镜像) + 进程内 H2，用于展示「随规模变化的趋势与量级差异」，
 * 非生产 MySQL 8 的绝对 RT（H2 内存扫描无磁盘 I/O，绝对值偏乐观，但 O(n) 增长趋势一致）；生产绝对值需在 compose 环境用同法采集。
 * CDC 端到端延迟（发帖→可搜）不在本基准内——它需真实 Canal→binlog→RocketMQ→consumer→ES 全链路，由 S3
 * {@code scripts/search-cdc-smoke.mjs} 在 compose 栈实测。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("search-perf")
@EnabledIfSystemProperty(named = "perf", matches = "true")
@EnabledIfSystemProperty(named = "kuros.it.es", matches = "true")
class SearchPerfBenchmark {

    private static final String ES_IMAGE = "kuros-es-ik:9.4.5";
    /** 扫描的最大规模（可用 -Dperf.posts 覆盖）。LIKE 全表扫要看得出与 ES 的量级差异，最大点需上万。 */
    private static final int MAX_N = Integer.getInteger("perf.posts", 50000);
    /** 隔字召回用例的种子规模：够用即可（不需全扫描），数千条竞争帖足以证明 title boost 排序稳定。 */
    private static final int RECALL_SCALE = 5000;
    /** 每 10 条埋 1 条 excerpt 含连续关键词 → LIKE 命中约 N/10，模拟真实稀疏命中下的全表扫。 */
    private static final int HIT_STRIDE = 10;
    private static final String KEYWORD = "鸣潮攻略";
    private static final String GEZI_TITLE = "鸣潮的攻略详解"; // 隔字：LIKE 漏、ES ik 命中
    private static final int PAGE_SIZE = 20;
    /** 每个采样点重复计时次数，取均值抹平 JIT/GC/ES 查询缓存抖动。 */
    private static final int REPEAT = 20;
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 23, 10, 0);

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
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("search-perf"));
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + es.getHost() + ":" + es.getMappedPort(9200));
    }

    @Autowired CommunityPostRepository postRepository;
    @Autowired SearchQueryService searchQueryService;
    @Autowired PostIndexService postIndexService;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 当前 DB 里已种的常规帖数（增量种子用，避免每个规模点从零重造）。JUnit 默认 PER_METHOD：每用例独立实例，此状态不跨用例。 */
    private int seededCount = 0;
    private String geziPostId;

    @Test
    void LIKE全表扫随规模线性上涨而ES倒排索引近乎恒定() {
        int[] scales = scales();

        System.out.println("[PERF] ===== LIKE 全表扫 vs ES 倒排索引·随规模的变化（keyword=" + KEYWORD
                + ", pageSize=" + PAGE_SIZE + ", repeat=" + REPEAT + ", env=Testcontainers-ES(ik)+H2）=====");
        System.out.printf("[PERF] %-14s %-18s %-18s %-10s%n", "scale(posts)", "LIKE avg(ms)", "ES avg(ms)", "LIKE/ES");

        for (int scale : scales) {
            seedUpTo(scale);
            // 预热：触发 JIT、连接池、H2 查询计划、ES 查询缓存，避免首次冷启动污染均值
            likeSearch();
            esSearch();

            double likeMs = timeAvgMs(this::likeSearch);
            double esMs = timeAvgMs(this::esSearch);
            String ratio = esMs == 0 ? "n/a" : String.format("%.2fx", likeMs / esMs);
            System.out.printf("[PERF] %-14d %-18.3f %-18.3f %-10s%n", scale, likeMs, esMs, ratio);
        }

        System.out.println("[PERF] 结论：LIKE 前导通配符 '%kw%' 令 B+ 树索引失效走 O(n) 全表扫，耗时随帖子数近线性上涨；"
                + "ES 经 ik 倒排索引直接定位 posting list，耗时与总量近乎解耦。小规模下 ES 因固定查询开销（HTTP 往返+协调+打分）"
                + "可能反而略慢（LIKE/ES < 1），但过交叉点后 LIKE 被越拉越开（LIKE/ES 随 N 放大）——这正是「结构性退化」的分界线。");
    }

    @Test
    void 隔字中文LIKE漏召回而ES分词命中() {
        seedUpTo(RECALL_SCALE);

        // LIKE '%鸣潮攻略%' 只扫 title+excerpt：隔字帖 title="鸣潮的攻略详解" 中间有"的"、excerpt 无连续关键词 → 漏召回
        List<String> likeIds = likeIds(PAGE_SIZE * 50);
        // ES ik_smart 把"鸣潮攻略"切成 [鸣潮,攻略] 分词匹配 → 命中隔字帖，且 title(^3) boost 令其排在 excerpt(^2) 命中之前
        List<String> esIds = searchQueryService.search(KEYWORD, null, null, null, "relevance", null, PAGE_SIZE)
                .items().stream().map(item -> item.id()).toList();

        System.out.println("[PERF] ===== 隔字中文召回（keyword=" + KEYWORD + " vs title=" + GEZI_TITLE + "，N=" + RECALL_SCALE + "）=====");
        System.out.println("[PERF] MySQL LIKE 是否命中隔字帖 : " + likeIds.contains(geziPostId) + "（子串匹配要求连续出现，隔'的'字即漏）");
        System.out.println("[PERF] ES ik 分词是否命中隔字帖  : " + esIds.contains(geziPostId) + "（ik_smart 切成[鸣潮,攻略]分词匹配）");
        System.out.println("[PERF] ES 首位命中 postId        : " + (esIds.isEmpty() ? "n/a" : esIds.get(0)) + "（title boost^3 令隔字帖排最前）");
        System.out.println("[PERF] 结论：LIKE 无法做中文分词，隔字即漏召回；ES ik 分词 + 字段加权既召回又排序，这正是替代 LIKE 的核心动因。");

        assertThat(likeIds).as("LIKE 子串匹配漏掉隔字帖").doesNotContain(geziPostId);
        assertThat(esIds).as("ES ik 分词命中隔字帖").contains(geziPostId);
        assertThat(esIds.get(0)).as("title 命中经 boost 排首位").isEqualTo(geziPostId);
    }

    // ---- helpers ----

    /**
     * 从单一旋钮 {@code -Dperf.posts}（最大规模）派生三点扫描：
     * 小规模（ES 固定开销占优）→ 中规模（交叉点附近）→ 大规模（LIKE O(n) 被明显拉开），
     * 让「LIKE 随 N 上涨、ES 近乎恒定」的趋势在一张表里可见。默认 50000 → {2000, 10000, 50000}。
     */
    private int[] scales() {
        return new int[]{Math.max(1000, MAX_N / 25), Math.max(2000, MAX_N / 5), MAX_N};
    }

    /** LIKE 全表扫（findVisiblePosts 的谓词即 lower(title/excerpt) LIKE '%kw%'）→ 返回命中总数（含 COUNT 全扫，体现 O(n)）。 */
    private long likeSearch() {
        return postRepository.findVisiblePosts(PostStatus.PUBLISHED, null, null, KEYWORD, PageRequest.of(0, PAGE_SIZE))
                .getTotalElements();
    }

    private List<String> likeIds(int pageSize) {
        return postRepository.findVisiblePosts(PostStatus.PUBLISHED, null, null, KEYWORD, PageRequest.of(0, pageSize))
                .getContent().stream().map(CommunityPost::getId).toList();
    }

    /** ES 全文检索（走 se-04 生产读路径 SearchQueryService）→ 返回当页命中数。 */
    private int esSearch() {
        return searchQueryService.search(KEYWORD, null, null, null, "relevance", null, PAGE_SIZE).items().size();
    }

    private double timeAvgMs(Runnable call) {
        long total = 0;
        for (int i = 0; i < REPEAT; i++) {
            long start = System.nanoTime();
            call.run();
            total += System.nanoTime() - start;
        }
        return total / 1_000_000.0 / REPEAT;
    }

    /**
     * 增量种子到 targetN 条常规帖（+ 首批种 1 条隔字帖），再经生产全量重建路径灌进 ES。
     * 递增扫描下只补种差额、不重造已有数据；每次 reindexAll 重建当前全量（v{n+1} 原子切别名），保证 ES 与 DB 规模一致。
     */
    private void seedUpTo(int targetN) {
        boolean firstBatch = seededCount == 0;
        if (firstBatch) {
            // H2 清理（按外键依赖顺序，对齐 PostIndexServiceIntegrationTest）：排除 Flyway 种子帖对命中数/隔字排序断言的干扰
            cleanH2();
        }
        if (targetN <= seededCount) {
            return;
        }
        List<CommunityPost> batch = new ArrayList<>(targetN - seededCount + 1);
        for (int i = seededCount; i < targetN; i++) {
            String excerpt = (i % HIT_STRIDE == 0) ? "鸣潮攻略实战手册" : "普通摘要" + i;
            batch.add(CommunityPost.publish("perf-author", PostType.GUIDE, "角色培养",
                    "压测帖" + i, excerpt, "内容" + i, BASE.plusMinutes(i)));
        }
        if (firstBatch) {
            // 隔字帖只在首批种一次，后续规模点复用（LIKE 应漏、ES 应命中且排首位）
            batch.add(CommunityPost.publish("perf-author", PostType.GUIDE, "配队攻略",
                    GEZI_TITLE, "声骸搭配与实战思路", "隔字召回验证正文", BASE.plusMinutes(MAX_N + 1L)));
        }
        List<CommunityPost> saved = postRepository.saveAll(batch);
        if (firstBatch) {
            geziPostId = saved.stream()
                    .filter(p -> GEZI_TITLE.equals(p.getTitle()))
                    .map(CommunityPost::getId)
                    .findFirst()
                    .orElseThrow();
        }
        seededCount = targetN;

        // 经生产全量重建路径灌进 ES（建 v{n+1} + ik mapping + 原子切别名 + bulk + refresh）
        long indexed = postIndexService.reindexAll();
        System.out.println("[PERF] scale=" + targetN + "：累计帖子 " + postRepository.count()
                + "，reindexAll() 重建 ES 索引 " + indexed + " 篇（geziPostId=" + geziPostId + "）");
    }

    private void cleanH2() {
        jdbcTemplate.update("DELETE FROM post_media");
        jdbcTemplate.update("DELETE FROM post_tags");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM post_likes");
        jdbcTemplate.update("DELETE FROM post_favorites");
        postRepository.deleteAll();
    }
}
