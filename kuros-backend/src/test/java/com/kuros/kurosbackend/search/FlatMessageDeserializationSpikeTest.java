package com.kuros.kurosbackend.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * se-01 spike（验证④）：Canal FlatMessage 反序列化结构 + String→类型转换可行性。
 *
 * Canal serverMode=rocketMQ + flatMessage=true 投递的是扁平化 JSON（见 ADR 0007 / CONTEXT 术语）：
 * data[] 是变更行的数组、值**全为 String**（binlog 不保留 Java 类型），old[] 仅 UPDATE 时有（变更前值）。
 * 消费端（se-05）要从中取出变更的 postId 集合触发回源组装。本 spike 用纯 Jackson（无容器、毫秒级、
 * 非门控，进主套件）证明：① FlatMessage JSON 结构可被 Jackson 稳定解析；② data[].id 可提取为 postId 集合；
 * ③ String 值可按需转 LocalDateTime/long（回源组装的兜底路径——尽管 se-03 决策是回查 DB 而非直接用 data）。
 *
 * 为什么用 Jackson 2（com.fasterxml）而非 Boot 4 默认的 Jackson 3（tools.jackson）？
 * 项目已显式引入 Jackson 2（sa-token-redis-jackson 依赖），ES 客户端默认也用 Jackson 2 mapper；
 * CDC 消费端与 ES 写入同栈，统一用 Jackson 2 避免 mapper 混用（spike 顺带验证 Jackson 2 在 test 栈可用）。
 */
class FlatMessageDeserializationSpikeTest {

    /** 一条代表性的 Canal FlatMessage（posts 表 INSERT），值全为 String，贴合真实投递格式。 */
    private static final String FLAT_MESSAGE_JSON = """
            {
              "id": 1,
              "database": "kuros",
              "table": "posts",
              "pkNames": ["id"],
              "isDdl": false,
              "type": "INSERT",
              "es": 1726000000000,
              "ts": 1726000000001,
              "sql": "",
              "sqlType": {"id": 12, "title": 12, "like_count": -5, "published_at": 93},
              "data": [
                {"id": "10000000-0000-0000-0000-000000000001", "title": "鸣潮的攻略详解",
                 "like_count": "42", "published_at": "2026-09-23 10:30:00"}
              ],
              "old": null
            }
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void flatMessage可解析并提取postId集合() throws Exception {
        JsonNode root = mapper.readTree(FLAT_MESSAGE_JSON);

        assertThat(root.get("database").asText()).isEqualTo("kuros");
        assertThat(root.get("table").asText()).isEqualTo("posts");
        assertThat(root.get("type").asText()).isEqualTo("INSERT");
        assertThat(root.get("isDdl").asBoolean()).isFalse();

        // 消费端核心动作：从 data[] 提取变更行的主键 → postId 集合（据 pkNames 定位主键列）
        List<String> pkNames = new ArrayList<>();
        root.get("pkNames").forEach(n -> pkNames.add(n.asText()));
        assertThat(pkNames).containsExactly("id");

        List<String> postIds = new ArrayList<>();
        for (JsonNode row : root.get("data")) {
            postIds.add(row.get(pkNames.get(0)).asText());
        }
        assertThat(postIds).containsExactly("10000000-0000-0000-0000-000000000001");
    }

    @Test
    void data行的String值可转LocalDateTime与long() throws Exception {
        JsonNode row = mapper.readTree(FLAT_MESSAGE_JSON).get("data").get(0);

        // binlog 里计数是字符串 "42"，需转 long（热度排序用）
        long likeCount = Long.parseLong(row.get("like_count").asText());
        assertThat(likeCount).isEqualTo(42L);

        // binlog 里时间是字符串 "2026-09-23 10:30:00"，需按 MySQL datetime 格式转 LocalDateTime
        LocalDateTime publishedAt = LocalDateTime.parse(
                row.get("published_at").asText(),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        assertThat(publishedAt).isEqualTo(LocalDateTime.of(2026, 9, 23, 10, 30, 0));
    }
}
