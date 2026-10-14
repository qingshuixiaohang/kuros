import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createComment,
  createPost,
  createReport,
  deleteComment,
  favoritePost,
  fetchAdminReports,
  fetchComments,
  fetchPostInteractions,
  fetchPostPage,
  fetchUserFollow,
  followUser,
  handleReport,
  likePost,
  searchPosts,
  unfavoritePost,
  unfollowUser,
  unlikePost,
  updatePost,
  type ReportReason,
  type ReportStatus,
  type ReportTargetType,
} from "@/lib/api";

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
export function useCommentsQuery(options: { postId: string; sort: "latest" | "hot"; pageSize?: number }) {
  const { postId, sort, pageSize = 20 } = options;
  return useQuery({
    queryKey: ["post-comments", postId, sort, pageSize],
    queryFn: async () => {
      const result = await fetchComments(postId, { page: 1, pageSize, sort });
      return { items: result.items, totalItems: result.meta?.totalItems ?? result.items.length };
    },
    enabled: Boolean(postId),
    // 与同文件的 useSearchPostsQuery / usePostInteractionsQuery 一致：
    // 失败要立即冒泡给 UI。默认重试 3 次会让评论区白转三个周期才显示错误
    retry: false,
  });
}

/**
 * 发评论 mutation（H-12 第三类写操作）。
 *
 * 为什么不像原来那样把返回的评论 unshift 进本地 state：评论区有"最热/最新"
 * 两种排序，本地插入在"最热"下位置就是错的（新评论的赞数是 0，未必排在
 * 前面），而且翻页后的 totalItems 也只是本地 +1。重取一次，让服务端的排序
 * 和分页说话——这也是 useCommentsQuery 读路径存在的意义。
 */
export function useCreateCommentMutation(postId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { content: string; parentId: string | null }) => createComment(postId, input.content, input.parentId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["post-comments", postId] });
    },
  });
}

/** 删评论 mutation：与发布共用同一失效 key。 */
export function useDeleteCommentMutation(postId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (commentId: string) => deleteComment(postId, commentId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["post-comments", postId] });
    },
  });
}

export function useUserFollowQuery(targetUserId?: string, enabled = true) {
  return useQuery({
    queryKey: ["user-follow", targetUserId ?? ""],
    queryFn: async () => {
      const result = await fetchUserFollow(targetUserId as string);
      return result.followed === true;
    },
    enabled: Boolean(targetUserId) && enabled,
  });
}

/**
 * 发布/更新帖子 mutation（H-12 第五类写操作）。
 *
 * 这类写操作和点赞收藏不同：成功后是 router.push 跳到详情页，不在原地
 * 更新视图。所以它的价值不在"当前页同步"，而在**让列表缓存失效**——
 * 否则发完帖回首页，新帖不会出现（社区列表的缓存 key 从没被 invalidate
 * 过，staleTime 内一直用旧数据）。详情页的 post-interactions 一并失效，
 * 因为新帖的互动状态也要按"未赞"重新取。
 */
export function usePublishPostMutation(editPostId?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { type: "GUIDE" | "GENERAL"; category: string; title: string; content: string; tags: string[]; mediaAssetIds: string[] }) =>
      editPostId ? updatePost(editPostId, input) : createPost(input),
    onSuccess: (post) => {
      // hot-topics 也在失效集合里：首页右栏热门话题按标签聚合，新帖的标签
      // 应该有机会进入 top-5。漏了它，发完帖回首页右栏在 staleTime 内还是旧榜
      queryClient.invalidateQueries({ queryKey: ["community-posts"] });
      queryClient.invalidateQueries({ queryKey: ["search-posts"] });
      queryClient.invalidateQueries({ queryKey: ["hot-topics"] });
      queryClient.invalidateQueries({ queryKey: ["post-interactions", post.id] });
    },
  });
}

/**
 * 管理端举报列表（H-12 第六类）。
 *
 * queryKey 带 status：处理完一条举报后 invalidate 同一个 key，列表按服务端
 * 真相重取。原来靠本地 map 把 status 改成 CONFIRMED/REJECTED，但当前过滤
 * tab 是 PENDING，那条被改状态的记录**还留在列表里**——用户看到"已确认"
 * 的条目混在待处理队列中，而服务端其实已经把它过滤掉了。
 */
export function useAdminReportsQuery(options: { status?: ReportStatus; enabled: boolean }) {
  const { status, enabled } = options;
  return useQuery({
    queryKey: ["admin-reports", status ?? "ALL"],
    queryFn: async () => fetchAdminReports(status),
    enabled,
  });
}

/** 处理举报 mutation：确认或驳回。onSuccess 让管理端列表重取。 */
export function useHandleReportMutation(options: { status?: ReportStatus }) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { reportId: string; action: "CONFIRM" | "REJECT"; note: string }) => handleReport(input.reportId, input.action, input.note),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["admin-reports", options.status ?? "ALL"] });
    },
  });
}

/** 提交举报 mutation（H-12 第七类写操作，补上"直调归零"的缺口）。 */
export function useCreateReportMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { targetType: ReportTargetType; targetId: string; reason: ReportReason }) =>
      createReport(input.targetType, input.targetId, input.reason),
    onSuccess: () => {
      // 管理端的举报列表要能看到新举报——游客/普通用户提交后，管理员刷新
      // 审核台应出现这条。缺这个失效，staleTime 内审核台看不到新举报
      queryClient.invalidateQueries({ queryKey: ["admin-reports"] });
    },
  });
}

/**
 * 关注/取关 mutation（H-12 第四类写操作）。
 *
 * 与点赞/收藏同一套思路：写成功后 invalidate 关注状态，让页面上所有
 * 指向同一用户的关注按钮一起重取。原来靠 setFollow(result) 只更新当前
 * 这一个按钮实例，同一个用户在别处出现时还是旧值。
 */
export function useFollowUserMutation(targetUserId?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (next: "follow" | "unfollow") => (next === "follow" ? followUser(targetUserId as string) : unfollowUser(targetUserId as string)),
    onSuccess: () => {
      // 只失效这一个 key。曾经也 invalidate 了 feed-interaction-states，
      // 但那个查询返回的是 liked/favorited，关注写操作不改它的内容——
      // 多打一次批量请求纯属噪音。失效集合要按"这个写操作会影响哪些读"
      // 推导，不是把邻居的 key 抄一遍。
      queryClient.invalidateQueries({ queryKey: ["user-follow", targetUserId ?? ""] });
    },
  });
}

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
