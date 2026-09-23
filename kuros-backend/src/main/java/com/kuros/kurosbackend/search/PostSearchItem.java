package com.kuros.kurosbackend.search;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 搜索结果条目（切片 #14 se-04）：{@code GET /api/v1/search} 返回的单条命中。
 *
 * <p>为什么字段全部取自 ES 文档（{@link PostSearchDoc}）而不回 DB/Feign 补全：
 * 搜索是高频读，ADR 0007 已把 authorName 反范式冗余进索引，正是为了让结果组装「零跨服务往返」——
 * 直接用索引里的快照字段拼响应即可。代价是计数/作者名允许秒级滞后（CDC 最终一致），对搜索列表可接受；
 * 点进详情页时再由 #13 读路径叠加 #11 实时计数，故列表滞后不影响用户看到的关键数字。
 *
 * <p>相比 #13 {@code PostSummaryResponse}：不含 {@code author}（AuthorResponse）/ {@code coverImageUrl} /
 * {@code media}——这些都需回 DB/Feign，与「搜索结果零往返」目标冲突；改为携带 {@code authorId} +
 * {@code authorName}（索引快照）与 {@code highlight}（命中片段），前端搜索卡片据此即可渲染。
 *
 * @param highlight 命中高亮片段：key=字段名（title/excerpt/content），value=该字段的 {@code <mark>} 包裹片段列表；
 *                  无命中的字段不出现在 map 中（前端按 key 取用、缺失则回退展示原 title/excerpt）
 */
public record PostSearchItem(
        String id,
        String type,
        String category,
        String title,
        String excerpt,
        String authorId,
        String authorName,
        LocalDateTime publishedAt,
        long viewCount,
        long likeCount,
        long favoriteCount,
        long commentCount,
        List<String> tags,
        Map<String, List<String>> highlight
) {
}
