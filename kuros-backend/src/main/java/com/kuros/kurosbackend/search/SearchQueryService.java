package com.kuros.kurosbackend.search;

import com.kuros.kurosbackend.post.domain.PostStatus;
import com.kuros.kurosbackend.shared.api.CursorCodec;
import com.kuros.kurosbackend.shared.api.CursorPageResult;
import com.kuros.kurosbackend.shared.exception.ServiceUnavailableException;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightParameters;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索读路径服务（切片 #14 se-04）：把 {@code GET /api/v1/search} 的参数翻译成一次 ES 查询，
 * 返回复用 #13 的 {@link CursorPageResult} 游标契约。
 *
 * <p>查询结构（ADR 0007 D7）：{@code bool} = must(多字段打分) + filter(精确过滤，不算分、可缓存)。
 * <ul>
 *   <li><b>must</b>：{@code multi_match}(title^3, excerpt^2, content^1)——boost 让标题命中排在正文命中前；
 *       分词器不显式指定，走各字段 mapping 的 {@code searchAnalyzer=ik_smart}（se-01 spike 已验证此路径命中隔字中文）；
 *       keyword 为空时退化为 {@code match_all}（仍可配合过滤/排序做筛选浏览）。</li>
 *   <li><b>filter</b>：{@code term status=PUBLISHED}（逻辑删的帖子在此被排除，呼应 se-03 删除语义）
 *       + 可选 {@code term category/type} + {@code term tags}（keyword 数组上 term 命中即「包含该标签」）。</li>
 * </ul>
 *
 * <p>深翻用 ES {@code search_after}（非 from-size）：cursor 编码上一页最后一条的 sortValues，
 * 翻到多深都是 O(limit)，不退化（呼应 #13 拒 offset）。hasMore 靠「多取一条」（limit+1）探测，不查总数
 * （故 {@code trackTotalHits=false}，与 CursorPageResult「不带 total」一致）。
 *
 * <p>降级（ADR 0007 D8）：ES 是 backend 软依赖，查询失败（连不上/超时）时抛 {@link ServiceUnavailableException}
 * → 503「搜索服务暂不可用」，不静默返回空、不 500，前端据此渲染降级提示，主链路（MySQL+Redis）零影响。
 */
@Service
public class SearchQueryService {

    private static final Logger log = LoggerFactory.getLogger(SearchQueryService.class);

    /** 与 #13 帖子列表一致的每页上限，防超大 limit 拖垮 ES。 */
    private static final int MAX_LIMIT = 50;

    private final ElasticsearchOperations operations;

    public SearchQueryService(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    /**
     * 全文检索。
     *
     * @param keyword  搜索词（ik_smart 分词）；空白则 match_all
     * @param category 内容分类过滤（可空）
     * @param tag      标签过滤（可空）
     * @param type     帖子类型过滤 GUIDE|GENERAL（可空）
     * @param sort     排序模式串 relevance|latest|hot（非法值回退 relevance）
     * @param cursor   上一页返回的 nextCursor（首页传 null/空）
     * @param limit    期望条数（1..50）
     * @return 游标分页结果：items + nextCursor + hasMore（不含 total）
     */
    public CursorPageResult<PostSearchItem> search(
            String keyword, String category, String tag, String type,
            String sort, String cursor, int limit) {

        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        SearchSort searchSort = SearchSort.fromParam(sort);
        String kw = blankToNull(keyword);
        String categoryFilter = blankToNull(category);
        String tagFilter = blankToNull(tag);
        String typeFilter = blankToNull(type);

        // 游标解码在 ES 调用之前：非法 cursor 抛 AuthRequestException → 400（与降级 503 区分开，不被下面的 catch 吞）
        List<Object> searchAfter = decodeSearchAfter(cursor, searchSort);

        NativeQuery query = buildQuery(kw, categoryFilter, tagFilter, typeFilter, searchSort, searchAfter, size);

        SearchHits<PostSearchDoc> hits;
        try {
            hits = operations.search(query, PostSearchDoc.class);
        } catch (RuntimeException e) {
            // 软依赖降级：ES 不可用/超时/索引异常一律对外明确「暂不可用」，而非 500 或静默空；
            // 打完整堆栈（含 ES 返回的 root cause）而非仅 e.toString()，否则「all shards failed」这类顶层信息会掩盖真正的失败字段/原因
            log.warn("ES 搜索失败，降级为搜索服务暂不可用", e);
            throw new ServiceUnavailableException("搜索服务暂不可用，请稍后重试");
        }

        return toPage(hits, size);
    }

    private NativeQuery buildQuery(
            String keyword, String category, String tag, String type,
            SearchSort sort, List<Object> searchAfter, int size) {

        // filter 子句：status=PUBLISHED 恒定；category/type/tag 按需追加（filter 不算分、ES 可缓存，比 must 更高效）
        List<Query> filters = new ArrayList<>();
        filters.add(Query.of(q -> q.term(t -> t.field("status").value(PostStatus.PUBLISHED.name()))));
        if (category != null) {
            filters.add(Query.of(q -> q.term(t -> t.field("category").value(category))));
        }
        if (type != null) {
            filters.add(Query.of(q -> q.term(t -> t.field("type").value(type))));
        }
        if (tag != null) {
            filters.add(Query.of(q -> q.term(t -> t.field("tags").value(tag))));
        }

        // must 子句：有关键词 → multi_match 加权打分；无关键词 → match_all（仍可过滤/排序）
        Query must = keyword == null
                ? Query.of(q -> q.matchAll(m -> m))
                : Query.of(q -> q.multiMatch(mm -> mm
                        .query(keyword)
                        .fields("title^3", "excerpt^2", "content^1")));

        NativeQueryBuilder builder = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b.must(must).filter(filters)))
                .withSort(sort.sortOptions())
                .withHighlightQuery(highlightQuery())
                // from 固定 0：ES 要求 search_after 时 from 必须为 0；size 多取一条用于探测 hasMore
                .withPageable(PageRequest.of(0, size + 1))
                .withTrackTotalHits(false);
        if (searchAfter != null) {
            builder.withSearchAfter(searchAfter);
        }
        return builder.build();
    }

    /** 高亮 title/excerpt/content，用 {@code <mark>} 包裹命中词（spec D10：前端直接渲染 mark）。 */
    private HighlightQuery highlightQuery() {
        HighlightParameters params = HighlightParameters.builder()
                .withPreTags("<mark>")
                .withPostTags("</mark>")
                .withNumberOfFragments(1)
                .withFragmentSize(150)
                .build();
        Highlight highlight = new Highlight(params, List.of(
                new HighlightField("title"),
                new HighlightField("excerpt"),
                new HighlightField("content")));
        return new HighlightQuery(highlight, PostSearchDoc.class);
    }

    private CursorPageResult<PostSearchItem> toPage(SearchHits<PostSearchDoc> hits, int size) {
        List<SearchHit<PostSearchDoc>> searchHits = hits.getSearchHits();
        if (searchHits.isEmpty()) {
            return CursorPageResult.empty();
        }
        boolean hasMore = searchHits.size() > size;
        List<SearchHit<PostSearchDoc>> page = hasMore ? searchHits.subList(0, size) : searchHits;
        List<PostSearchItem> items = page.stream().map(this::toItem).toList();
        // nextCursor 编码本页最后一条的 sortValues（不透明 base64url，呼应 #13 CursorCodec）
        String nextCursor = hasMore ? CursorCodec.encode(stringify(page.get(page.size() - 1).getSortValues())) : null;
        return new CursorPageResult<>(items, nextCursor, hasMore);
    }

    private PostSearchItem toItem(SearchHit<PostSearchDoc> hit) {
        PostSearchDoc doc = hit.getContent();
        return new PostSearchItem(
                doc.getPostId(), doc.getType(), doc.getCategory(), doc.getTitle(), doc.getExcerpt(),
                doc.getAuthorId(), doc.getAuthorName(), doc.getPublishedAt(),
                doc.getViewCount(), doc.getLikeCount(), doc.getFavoriteCount(), doc.getCommentCount(),
                doc.getTags(), hit.getHighlightFields());
    }

    /** sortValues（Float/Long/String 混合）→ 全 String token，交 CursorCodec 编码；解码时由 SearchSort 按位还原类型。 */
    private List<String> stringify(List<Object> sortValues) {
        return sortValues.stream().map(String::valueOf).toList();
    }

    private List<Object> decodeSearchAfter(String cursor, SearchSort sort) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        // CursorCodec.decode 对非法 base64 抛 INVALID_CURSOR；SearchSort.searchAfter 对 token 数/类型不符抛 INVALID_CURSOR
        return sort.searchAfter(CursorCodec.decode(cursor));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
