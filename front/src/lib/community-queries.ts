import { keepPreviousData, useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { fetchPostPage, searchPosts } from "@/lib/api";

export function useCommunityPostQuery(options: { category?: string; keyword?: string; tag?: string; page?: number; pageSize?: number; sort?: "latest" | "hot" }) {
  const { category = "", keyword = "", tag = "", page = 1, pageSize = 20, sort = "latest" } = options;
  return useQuery({
    queryKey: ["community-posts", category, keyword, tag, page, pageSize, sort],
    queryFn: () => fetchPostPage({ category: category || undefined, keyword: keyword || undefined, tag: tag || undefined, page, pageSize, sort }),
    placeholderData: keepPreviousData,
  });
}

/**
 * ES 全文检索的无限滚动查询（切片 #14 se-06）：走 GET /api/v1/search + #13 游标契约。
 *
 * 为什么用 useInfiniteQuery 而非手写 useEffect + setState：keyword 变化即换 queryKey 自动重新检索，
 * getNextPageParam 透传 nextCursor→search_after 深翻，hasNextPage=false 后停止；分页竞态、缓存、去重
 * 全交给 react-query，避免在 effect 体内同步 setState（React 19 lint 规则 set-state-in-effect 会拦）。
 *
 * retry:false —— ES 软依赖降级(503)/后端不可用需立即冒泡给 UI，由 UI 决定展示「搜索暂不可用」还是
 * 「本地 Demo 回退」，不做无谓重试拖慢失败反馈。
 */
export function useSearchPostsQuery(keyword: string) {
  return useInfiniteQuery({
    queryKey: ["search-posts", keyword],
    queryFn: ({ pageParam }) => searchPosts({ keyword, cursor: pageParam, limit: 10, sort: "relevance" }),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => (lastPage.hasMore ? lastPage.nextCursor : null),
    retry: false,
  });
}
