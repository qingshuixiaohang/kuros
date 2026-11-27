import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ApiError,
  createComment,
  createPost,
  createReport,
  deleteComment,
  favoritePost,
  fetchAdminReports,
  fetchComments,
  fetchEchoPage,
  fetchEchoSet,
  fetchNews,
  fetchNewsPage,
  fetchPostInteractions,
  fetchPostPage,
  fetchUserFollow,
  followUser,
  handleReport,
  searchPosts,
  unfavoritePost,
  unfollowUser,
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
  retry?: boolean;
}) {
  const { category = "", keyword = "", tag = "", page = 1, pageSize = 20, sort = "latest", retry } = options;
  return useQuery({
    queryKey: ["community-posts", category, keyword, tag, page, pageSize, sort],
    queryFn: () => fetchPostPage({ category: category || undefined, keyword: keyword || undefined, tag: tag || undefined, page, pageSize, sort }),
    placeholderData: keepPreviousData,
    retry,
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
export function usePostInteractionsQuery(postId: string | undefined, userId?: string) {
  return useQuery({
    queryKey: ["post-interactions", postId, userId ?? "guest"],
    queryFn: () => fetchPostInteractions(postId!),
    enabled: Boolean(postId),
    retry: false,
  });
}

export function useCommentsQuery(options: { postId: string; sort: "latest" | "hot"; pageSize?: number }) {
  const { postId, sort, pageSize = 20 } = options;
  return useInfiniteQuery({
    queryKey: ["post-comments", postId, sort, pageSize],
    queryFn: ({ pageParam }) => fetchComments(postId, { page: pageParam, pageSize, sort }),
    initialPageParam: 1,
    getNextPageParam: (lastPage) => {
      const meta = lastPage.meta;
      if (!meta || meta.page >= meta.totalPages) return null;
      return meta.page + 1;
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
      queryClient.invalidateQueries({ queryKey: ["community-feed"] });
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
  return useInfiniteQuery({
    queryKey: ["admin-reports", status ?? "ALL"],
    queryFn: ({ pageParam }) => fetchAdminReports(status, pageParam, 20),
    initialPageParam: 1,
    getNextPageParam: (lastPage) => {
      const meta = lastPage.meta;
      if (!meta || meta.page >= meta.totalPages) return null;
      return meta.page + 1;
    },
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
 * 资讯列表查询（前端审计 H-9）。
 *
 * 资讯是低频公开内容，staleTime 给到 5 分钟——没必要每次进 /news 都打一次
 * 接口。按 category 分 key，切 tab 就是换 key。
 */
export function useNewsListQuery(category: string) {
  return useQuery({
    queryKey: ["news-list", category],
    queryFn: async () => {
      const result = await fetchNewsPage({ category });
      return { items: result.items, meta: result.meta };
    },
    // 与帖子列表一致：切分类时保留上一批数据，避免列表瞬间清空造成的闪烁。
    // 注意这会让旧分类的内容在新数据到达前多停留一瞬，所以列表页要能显示
    // "正在加载" 而不是把它当成最终结果
    placeholderData: keepPreviousData,
    staleTime: 300_000,
  });
}

/** 资讯详情查询。 */
export function useNewsDetailQuery(slug: string) {
  return useQuery({
    queryKey: ["news-detail", slug],
    queryFn: async () => await fetchNews(slug),
    enabled: Boolean(slug),
    retry: false,
  });
}

/**
 * 声骸列表查询（前端审计 H-9）。
 *
 * 与资讯同为低频公开内容，staleTime 5 分钟。按 role 分 key——页面上的
 * 定位 tab 就是换 key，不需要本地过滤（mock 时代是本地 filter，现在
 * 服务端过滤，pageSize 上限内的全量结果直接可用）。
 */
export function useEchoListQuery(role?: string) {
  return useQuery({
    queryKey: ["echo-list", role ?? "全部"],
    queryFn: async () => {
      const result = await fetchEchoPage({ role });
      return { items: result.items, meta: result.meta };
    },
    placeholderData: keepPreviousData,
    staleTime: 300_000,
  });
}

/**
 * 声骸详情查询。
 *
 * retry: false——404 是业务结果（套装不存在/已下架）不是瞬时故障，重试
 * 只会让失败态晚出现几秒。失败态由页面显示成「不存在」，与 news 同款。
 */
export function useEchoDetailQuery(slug: string) {
  return useQuery({
    queryKey: ["echo-detail", slug],
    queryFn: async () => {
      try {
        return await fetchEchoSet(slug);
      } catch (error) {
        if (error instanceof ApiError && error.status === 404 && error.code === "ECHO_NOT_FOUND") return null;
        throw error;
      }
    },
    enabled: Boolean(slug),
    retry: false,
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
      queryClient.invalidateQueries({ queryKey: ["community-feed"] });
    },
  });
}

function invalidatePostInteractionViews(queryClient: ReturnType<typeof useQueryClient>, postId: string) {
  queryClient.invalidateQueries({ queryKey: ["post-interactions", postId] });
  queryClient.invalidateQueries({ queryKey: ["community-posts"] });
  queryClient.invalidateQueries({ queryKey: ["community-feed"] });
  queryClient.invalidateQueries({ queryKey: ["search-posts"] });
  queryClient.invalidateQueries({ queryKey: ["feed-interaction-states"] });
}

export function useFavoriteMutation(postId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (next: "favorite" | "unfavorite") => (next === "favorite" ? favoritePost(postId) : unfavoritePost(postId)),
    onSuccess: () => invalidatePostInteractionViews(queryClient, postId),
  });
}
