package com.kuros.kurosbackend.search;

import com.kuros.kurosbackend.shared.api.ApiResponse;
import com.kuros.kurosbackend.shared.api.CursorPageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全文检索端点（切片 #14 se-04）：{@code GET /api/v1/search}。
 *
 * <p>公开只读（游客可搜，见 SaTokenConfigure 白名单）；返回复用 #13 的 {@link CursorPageResult} 游标契约
 * （{@code data.items / nextCursor / hasMore}，{@code meta} 恒为 null 被 NON_NULL 抹掉），
 * 前端搜索框据此复用「加载更多」无限滚动组件（spec D10）。
 *
 * <p>与 {@code /api/v1/posts?keyword=}（MySQL LIKE 轻量筛选）并存、互不影响（spec D6）：
 * 本端点是 ES 全文检索（分词/加权/高亮/search_after 深翻），posts 端点的 LIKE 契约保持不动。
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchQueryService searchQueryService;

    public SearchController(SearchQueryService searchQueryService) {
        this.searchQueryService = searchQueryService;
    }

    /**
     * 全文检索帖子。
     *
     * @param keyword  搜索词（ik 分词，命中 title/excerpt/content）
     * @param category 内容分类过滤（可选）
     * @param tag      标签过滤（可选）
     * @param type     帖子类型过滤 GUIDE|GENERAL（可选）
     * @param sort     排序 relevance|latest|hot，默认 relevance
     * @param cursor   上一页 nextCursor，首页不传
     * @param limit    每页条数，默认 10（上限 50）
     */
    @GetMapping
    public ApiResponse<CursorPageResult<PostSearchItem>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "relevance") String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "10") int limit) {
        CursorPageResult<PostSearchItem> result =
                searchQueryService.search(keyword, category, tag, type, sort, cursor, limit);
        // 游标契约：meta 传 null（被 ApiResponse 的 @JsonInclude(NON_NULL) 抹掉），前端据「无 meta」判定为无限滚动
        return new ApiResponse<>(result, null);
    }
}
