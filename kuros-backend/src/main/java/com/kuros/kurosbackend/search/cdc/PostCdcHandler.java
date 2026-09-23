package com.kuros.kurosbackend.search.cdc;

import com.kuros.kurosbackend.search.PostIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CDC 变更处理器（切片 #14 se-05）：把一条 Canal {@link FlatMessage} 翻译成对 {@link PostIndexService} 的调用。
 *
 * <p>它是「传输层」与「索引写入口」之间的**纯逻辑翻译层**——不碰 RocketMQ、不碰 ES，只做
 * 「哪个表的哪种变更 → 哪些 postId 该重新索引/删除」的判定，故可用 mock 的 {@code PostIndexService} 做纯单测
 * （见 {@code PostCdcHandlerTest}），无需 canal/broker/ES（Canal→MQ 传输层交 se-07 端到端冒烟覆盖）。
 *
 * <p>判定规则（呼应 ADR 0007 D3/D4）：
 * <ul>
 *   <li>{@code posts} 表：主键列 {@code id} 即 postId。INSERT/UPDATE → {@code index}（回源重组，逻辑删 status→DELETED 也走这里，
 *       由 se-04 搜索期 filter 排除，不物理删文档）；物理 DELETE → {@code delete}（兜底删 ES 孤儿文档）。</li>
 *   <li>{@code post_tags} 表：外键列 {@code post_id} 指向所属帖子。任何变更（含解绑的 DELETE）→ {@code index(post_id)}
 *       重新组装该帖的 tags——**帖子本身还在，绝不能删文档**，故 DELETE 在此表不触发 delete。</li>
 * </ul>
 *
 * <p>回源幂等：{@code index} 读的是 DB **当前态**，故同一批次内多行命中同一 postId 只需索引一次
 * （{@link LinkedHashSet} 去重，保留首次出现顺序便于日志追踪）；消息重投/重试亦只覆盖写不产生副本。
 */
@Component
public class PostCdcHandler {

    private static final Logger log = LoggerFactory.getLogger(PostCdcHandler.class);

    private static final String TABLE_POSTS = "posts";
    private static final String TABLE_POST_TAGS = "post_tags";
    private static final String TYPE_DELETE = "DELETE";

    private final PostIndexService postIndexService;

    public PostCdcHandler(PostIndexService postIndexService) {
        this.postIndexService = postIndexService;
    }

    /**
     * 处理一条 Canal 变更消息。
     *
     * @param message 反序列化后的 FlatMessage（Spring Cloud Stream 由 JSON 转换而来）
     */
    public void handle(FlatMessage message) {
        if (message == null) {
            return;
        }
        // 空批次守卫：DDL（CREATE/ALTER/DROP）事件 data 为空，无需索引；直接跳过
        List<Map<String, String>> rows = message.data();
        if (rows == null || rows.isEmpty()) {
            return;
        }
        // 只有 posts / post_tags 两表在订阅范围内（canal filter.regex 已限定）；其它表理论上不会到达，防御性忽略
        String idColumn = postIdColumn(message.table());
        if (idColumn == null) {
            log.debug("忽略非目标表变更：database={} table={}", message.database(), message.table());
            return;
        }

        Set<String> postIds = extractPostIds(rows, idColumn);
        if (postIds.isEmpty()) {
            return;
        }

        // 物理删 posts → 删 ES 文档；其余（posts 增改、post_tags 任何变更）→ 回源重组
        boolean physicalDelete = TYPE_DELETE.equalsIgnoreCase(message.type()) && TABLE_POSTS.equals(message.table());
        for (String postId : postIds) {
            if (physicalDelete) {
                postIndexService.delete(postId);
                log.debug("CDC 物理删 posts → 删索引文档 postId={}", postId);
            } else {
                postIndexService.index(postId);
                log.debug("CDC {} {} → 回源重组索引 postId={}", message.table(), message.type(), postId);
            }
        }
    }

    /** 据表名定位「能取出 postId 的列」：posts 用主键 id，post_tags 用外键 post_id；非目标表返回 null。 */
    private String postIdColumn(String table) {
        if (TABLE_POSTS.equals(table)) {
            return "id";
        }
        if (TABLE_POST_TAGS.equals(table)) {
            return "post_id";
        }
        return null;
    }

    /** 从变更行里抽出 postId 集合，去重并滤掉空值（binlog 值均为 String）。 */
    private Set<String> extractPostIds(List<Map<String, String>> rows, String idColumn) {
        Set<String> postIds = new LinkedHashSet<>();
        for (Map<String, String> row : rows) {
            String postId = row.get(idColumn);
            if (postId != null && !postId.isBlank()) {
                postIds.add(postId);
            }
        }
        return postIds;
    }
}
