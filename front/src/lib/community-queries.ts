import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { favoritePost, fetchPostInteractions, fetchPostPage, likePost, searchPosts, unfavoritePost, unlikePost } from "@/lib/api";

export function useCommunityPostQuery(options: {
  category?: string;
  keyword?: string;
  tag?: string;
  page?: number;
  pageSize?: number;
  sort?: "latest" | "hot";
}) {
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

/**
 * 单帖互动状态查询（详情页用）。
 *
 * 为什么这里也要进 react-query：写后读一致性要求"同一资源只有一份缓存"。
 * 原先首页与详情页各自 useState + 各自 fetch，key 都不进缓存，于是首页
 * 点赞后进详情页拿到的是后端旧值（H-1/H-3）。统一到
 * ["post-interactions", postId] 这一个 key 后，任一侧写入都会让另一侧
 * 的同一份缓存失效重取。
 *
 * retry:false —— 与 useSearchPostsQuery 同理由：失败要立即冒泡给 UI，
 * 不做无谓重试拖慢反馈。
 */
export function usePostInteractionsQuery(postId: string | undefined) {
  return useQuery({
    queryKey: ["post-interactions", postId],
    queryFn: () => fetchPostInteractions(postId!),
    enabled: Boolean(postId),
    retry: false,
  });
}

/**
 * 点赞 mutation（H-1：把写操作纳入 react-query，替换手写 setState）。
 *
 * 为什么必须用 useMutation 而不是 async 函数 + setState：
 * - **失效传播**：onSuccess 里 invalidateQueries 让所有缓存了该资源的
 *   查询（本帖详情、首页列表、批量状态）一起重取。手写 setState 只能改
 *   调用方自己的那一份 state，其他视图停留在旧值——这就是"首页点赞后进
 *   详情页显示未赞"的根因。
 * - **去重与并发控制**：同一 mutation 的重复触发由 react-query 合并，
 *   不会出现连点造成两次请求交错。
 * - **统一的 pending/error 语义**：isPending 直接驱动按钮禁用，
 *   不必每个组件各自维护 loading 布尔。
 *
 * 为什么不用乐观更新（onMutate + rollback）：后端点赞是幂等的翻转操作，
 * 且响应体就带着最新计数，等 onSuccess 拿到结果再显示的延迟可接受；
 * 乐观更新要处理回滚，复杂度更高而收益有限。真要做，下一步是
 * setQueryData 预置 liked + 计数 ±1，失败时 invalidate 拉回真相。
 */
export function useLikeMutation(postId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (next: "like" | "unlike") => (next === "like" ? likePost(postId) : unlikePost(postId)),
    onSuccess: () => {
      // 本帖互动状态 + 所有列表查询一起失效：首页/搜索/关注流的卡片
      // 都会在下一次渲染前重取，不再有"一头新一头旧"
      queryClient.invalidateQueries({ queryKey: ["post-interactions", postId] });
      queryClient.invalidateQueries({ queryKey: ["community-posts"] });
      queryClient.invalidateQueries({ queryKey: ["search-posts"] });
      queryClient.invalidateQueries({ queryKey: ["feed-interaction-states"] });
    },
  });
}

/**
 * 收藏 mutation（H-12：写操作纳入 react-query 的第二类）。
 *
 * 与 useLikeMutation 共享同一套失效集合：点赞和收藏改的是同一个资源
 * （一帖的互动状态），任一侧写入都必须让两侧视图一起重取。
 */
export function useFavoriteMutation(postId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (next: "favorite" | "unfavorite") => (next === "favorite" ? favoritePost(postId) : unfavoritePost(postId)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["post-interactions", postId] });
      queryClient.invalidateQueries({ queryKey: ["community-posts"] });
      queryClient.invalidateQueries({ queryKey: ["search-posts"] });
      queryClient.invalidateQueries({ queryKey: ["feed-interaction-states"] });
    },
  });
}
