package com.kuros.kurosbackend.search;

import com.kuros.kurosbackend.TestDatabases;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * se-01 spike：验证 Spring Boot 4.1.1 下 Spring Data Elasticsearch 6.1.x + 自建 ik 镜像 ES 9.4.5
 * 能否 index+search 中文文档且 ik 分词生效——这是切片 #14 全链路的最高风险技术假设（go/no-go gate）。
 *
 * 一次性验证四件事（对应工单 se-01 验收①②③）：
 * 1. Spring Data ES 自动装配的 JsonpMapper（Boot 4 下 Jackson 2/3 共存）能正常序列化 index+search；
 * 2. ik 分词生效："鸣潮攻略"（搜索词，中间无"的"）能命中"鸣潮的攻略详解"（文档，中间隔字）——
 *    这正是 MySQL LIKE '%鸣潮攻略%' 做不到的（子串不连续则不命中），是全文检索替代 LIKE 的核心证据；
 * 3. LocalDateTime 字段经 JsonpMapper 正确序列化/回读（se-03 PostSearchDoc.publishedAt 依赖此）。
 *
 * ⚠️ 默认跳过（gated）：需真实 ES 容器（自建 ik 镜像），用 @EnabledIfSystemProperty 门控——
 * 默认 {@code mvn test} 不跑，需显式 {@code -Dkuros.it.es=true} 且有 Docker 环境 + 已构建
 * kuros-es-ik:9.4.5 镜像（docker build -t kuros-es-ik:9.4.5 docker/elasticsearch/）才执行。
 * 与 InteractionRocketMQIntegrationTest 同款门控范式。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("es-spike")
@EnabledIfSystemProperty(named = "kuros.it.es", matches = "true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ElasticsearchIkSpikeTest {

    /** 自建 ik 镜像（docker/elasticsearch/Dockerfile），se-02 起 compose 复用同一镜像。 */
    private static final String ES_IMAGE = "kuros-es-ik:9.4.5";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static GenericContainer<?> es = new GenericContainer<>(ES_IMAGE)
            .withExposedPorts(9200)
            // 单机模式：跳过生产引导检查（否则 ES 因未配集群/内存阈值拒绝启动）
            .withEnv("discovery.type", "single-node")
            // 关闭 x-pack 安全：dev/spike 无需 TLS+账号，简化客户端连接（se-02 compose 同款）
            .withEnv("xpack.security.enabled", "false")
            // 收敛堆内存防 OOM：与 compose 里 rocketmq-broker 同款思路（本地 Docker ~7.5GiB）
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> TestDatabases.h2Url("es-spike"));
        // Spring Data ES 自动装配读 spring.elasticsearch.uris 建客户端（Boot 4 标准属性）
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + es.getHost() + ":" + es.getMappedPort(9200));
    }

    @Autowired
    private ElasticsearchOperations operations;

    /**
     * spike 文档：只保留验证 ik + JsonpMapper 所需的最小字段（title 走 ik、publishedAt 验日期序列化）。
     * 刻意用 static 嵌套类而非独立 @Document 实体——避免被主应用的实体扫描/索引自动创建波及，
     * 保持 spike 自包含（ElasticsearchOperations 直接按类上的 @Document 注解解析索引名与 mapping）。
     */
    @Document(indexName = "spike_post")
    static class SpikePostDoc {
        @Id
        private String id;

        // 建索引 ik_max_word（细粒度切全，召回优先）、搜索 ik_smart（粗粒度，精度优先）——ik 标准用法
        @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
        private String title;

        @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
        private LocalDateTime publishedAt;

        SpikePostDoc() {
        }

        SpikePostDoc(String id, String title, LocalDateTime publishedAt) {
            this.id = id;
            this.title = title;
            this.publishedAt = publishedAt;
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public LocalDateTime getPublishedAt() {
            return publishedAt;
        }
    }

    @Test
    void ik分词让隔字中文命中而LIKE做不到() {
        IndexOperations indexOps = operations.indexOps(SpikePostDoc.class);
        indexOps.delete();  // 幂等：清掉上一次 spike 残留索引
        indexOps.createWithMapping();  // 建索引 + 按 @Field 注解写 mapping（含 ik 分词器）

        LocalDateTime publishedAt = LocalDateTime.of(2026, 9, 23, 10, 30, 0);
        // doc1 隔字（中间有"的"）、doc2 完全无关，一并索引以同时验证"隔字命中"与"无关不命中"
        operations.save(new SpikePostDoc("1", "鸣潮的攻略详解", publishedAt));
        operations.save(new SpikePostDoc("2", "原神角色强度榜", LocalDateTime.of(2026, 9, 23, 11, 0, 0)));
        indexOps.refresh();  // 强制刷新使文档立即可搜（默认 1s refresh 间隔，spike 不等）

        // 搜索词"鸣潮攻略"中间无"的"：ik_smart 切成 [鸣潮, 攻略]，命中 ik_max_word 建索引的 [鸣潮, 的, 攻略, 详解]
        // 对照：MySQL LIKE '%鸣潮攻略%' 对"鸣潮的攻略详解"返回 0 行（子串不连续）——这正是要替代的退化
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.match(m -> m.field("title").query("鸣潮攻略")))
                .build();
        SearchHits<SpikePostDoc> hits = operations.search(query, SpikePostDoc.class);

        // 只命中 doc1：证明① ik 隔字命中（"鸣潮攻略"→"鸣潮的攻略详解"）、② 按词而非子串匹配（doc2 无关不命中）
        assertThat(hits.getTotalHits()).isEqualTo(1L);
        SpikePostDoc hit = hits.getSearchHit(0).getContent();
        assertThat(hit.getId()).isEqualTo("1");
        assertThat(hit.getTitle()).isEqualTo("鸣潮的攻略详解");
        // 证明③：LocalDateTime 经 JsonpMapper 序列化写入 ES 再回读，值不丢不变形
        assertThat(hit.getPublishedAt()).isEqualTo(publishedAt);
    }
}
