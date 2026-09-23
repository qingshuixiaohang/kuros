const API_BASE_URL = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080").replace(/\/$/, "");

export type ApiAuthor = { id: string; nickname: string; avatarUrl: string | null; bio: string | null };
export type ApiPostMedia = { id: string; url: string; sortOrder: number; isCover: boolean };

export type ApiPost = {
  id: string;
  type: "GUIDE" | "GENERAL";
  category: string;
  title: string;
  excerpt: string;
  content?: string;
  /** 列表接口可选返回的首图；旧后端未返回时保持无图卡片。 */
  coverImageUrl?: string | null;
  /** 新版接口可选返回的全部配图；未返回时回退到封面或正文图片。 */
  mediaUrls?: string[] | null;
  media?: ApiPostMedia[] | null;
  author: ApiAuthor;
  publishedAt: string;
  viewCount: number;
  likeCount: number;
  favoriteCount: number;
  commentCount: number;
  tags: string[];
};

export type ApiPageMeta = { page: number; pageSize: number; totalItems: number; totalPages: number };
type ApiEnvelope<T> = { data: T; meta?: ApiPageMeta };

/**
 * 游标分页结果（切片 #13 / rp-06）。与后端 CursorPageResult 对齐：
 * items 为当页数据，nextCursor 为下一页游标（hasMore=false 时为 null），
 * hasMore 表示是否还有更多——不含 COUNT(*)，深翻页代价恒定。
 */
export type CursorPageResult<T> = { items: T[]; nextCursor: string | null; hasMore: boolean };

export type AuthUser = { id: string; phone: string; nickname: string; avatarUrl: string | null; bio: string | null; role: "USER" | "ADMIN" };
export type VerificationCode = { expiresIn: number; retryAfter: number; devCode?: string | null };

export type PublicProfile = {
  id: string;
  nickname: string;
  avatarUrl: string | null;
  bio: string | null;
  postCount: number;
  likeCount: number;
};

export type ProfileComment = {
  id: string;
  postId: string;
  postTitle: string;
  parentId: string | null;
  content: string;
  deleted: boolean;
  createdAt: string;
};

export type ProfileOverview = {
  profile: PublicProfile;
  stats: { postCount: number; likeCount: number; commentCount: number };
  posts: { items: ApiPost[]; meta?: ApiPageMeta };
  comments: { items: ProfileComment[]; meta?: ApiPageMeta };
  favorites: { items: ApiPost[]; meta?: ApiPageMeta };
  following: { items: PublicProfile[]; meta?: ApiPageMeta };
  fans: { items: PublicProfile[]; meta?: ApiPageMeta };
};

export class ApiError extends Error {
  constructor(public readonly status: number, public readonly code?: string, message = "API request failed") {
    super(message);
  }
}

function csrfToken() {
  if (typeof document === "undefined") return undefined;
  return document.cookie.split("; ").find((item) => item.startsWith("XSRF-TOKEN="))?.split("=")[1];
}

async function ensureCsrfToken() {
  if (typeof document === "undefined" || csrfToken()) return;
  await requestEnvelope<void>("/api/v1/auth/csrf");
}

async function requestEnvelope<T>(path: string, init: RequestInit = {}): Promise<ApiEnvelope<T> | undefined> {
  const isFormDataBody = typeof FormData !== "undefined" && init.body instanceof FormData;
  const headers = { ...(init.body && !isFormDataBody ? { "Content-Type": "application/json" } : {}), ...init.headers } as Record<string, string>;
  const method = (init.method ?? "GET").toUpperCase();
  const token = csrfToken();
  if (token && !path.startsWith("/api/v1/auth/") && method !== "GET" && method !== "HEAD") headers["X-XSRF-TOKEN"] = decodeURIComponent(token);
  const response = await fetch(API_BASE_URL + path, {
    ...init,
    credentials: "include",
    headers,
  });
  if (!response.ok) {
    let error: { code?: string; message?: string } = {};
    try { error = await response.json() as { code?: string; message?: string }; } catch { /* Keep the HTTP status. */ }
    throw new ApiError(response.status, error.code, error.message ?? "API request failed");
  }
  if (response.status === 204) return undefined as unknown as ApiEnvelope<T>;
  return await response.json() as ApiEnvelope<T>;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const envelope = await requestEnvelope<T>(path, init);
  return envelope?.data as T;
}

export async function fetchPostPage(options: { category?: string; keyword?: string; tag?: string; page?: number; pageSize?: number; sort?: "latest" | "hot" } = {}) {
  const params = new URLSearchParams();
  params.set("page", String(options.page ?? 1));
  params.set("pageSize", String(options.pageSize ?? 20));
  params.set("sort", options.sort ?? "latest");
  if (options.category && options.category !== "全部") params.set("category", options.category);
  if (options.keyword?.trim()) params.set("keyword", options.keyword.trim());
  if (options.tag?.trim()) params.set("tag", options.tag.trim());
  const envelope = await requestEnvelope<ApiPost[]>("/api/v1/posts?" + params.toString());
  return { items: envelope?.data ?? [], meta: envelope?.meta };
}

export async function fetchPosts(options: { category?: string; keyword?: string; tag?: string; page?: number; pageSize?: number; sort?: "latest" | "hot" } = {}) {
  return (await fetchPostPage(options)).items;
}

/**
 * 关注流（游标分页）：GET /api/v1/feed/following?cursor&limit
 * 后端以是否传 limit 区分游标/offset 模式；cursor 为空表示第一页。
 * 返回 CursorPageResult<ApiPost>：调用方用 nextCursor 透传请求下一页并追加，
 * 不依赖 totalItems，深翻不变慢。未登录时后端返回 401，调用方应引导登录。
 */
export async function fetchFollowingFeedByCursor(options: { cursor?: string | null; limit?: number } = {}): Promise<CursorPageResult<ApiPost>> {
  const params = new URLSearchParams();
  params.set("limit", String(options.limit ?? 20));
  if (options.cursor) params.set("cursor", options.cursor);
  const envelope = await requestEnvelope<CursorPageResult<ApiPost>>("/api/v1/feed/following?" + params.toString());
  const data = envelope?.data;
  return { items: data?.items ?? [], nextCursor: data?.nextCursor ?? null, hasMore: data?.hasMore ?? false };
}

/**
 * 关注流（offset 分页，保留兼容）：GET /api/v1/feed/following?page&pageSize
 * 返回当前登录用户关注的人发布的帖子（按时间倒序，来自后端 Redis ZSet timeline）。
 * 未登录时后端返回 401，调用方应引导登录。
 */
export async function fetchFollowingFeed(options: { page?: number; pageSize?: number } = {}) {
  const params = new URLSearchParams();
  params.set("page", String(options.page ?? 1));
  params.set("pageSize", String(options.pageSize ?? 20));
  const envelope = await requestEnvelope<ApiPost[]>("/api/v1/feed/following?" + params.toString());
  return { items: envelope?.data ?? [], meta: envelope?.meta };
}

export function fetchPost(id: string) {
  return request<ApiPost>("/api/v1/posts/" + encodeURIComponent(id));
}

export type CreatePostInput = { type: "GUIDE" | "GENERAL"; category: string; title: string; excerpt?: string; content: string; tags: string[]; mediaAssetIds?: string[] };

export function createPost(input: CreatePostInput) {
  return ensureCsrfToken().then(() => request<ApiPost>("/api/v1/posts", { method: "POST", body: JSON.stringify(input) }));
}

export function updatePost(postId: string, input: CreatePostInput) {
  return ensureCsrfToken().then(() => request<ApiPost>(`/api/v1/posts/${encodeURIComponent(postId)}`, { method: "PUT", body: JSON.stringify(input) }));
}

export function deletePost(postId: string) {
  return ensureCsrfToken().then(() => request<void>(`/api/v1/posts/${encodeURIComponent(postId)}`, { method: "DELETE" }));
}

export type ReportTargetType = "POST" | "COMMENT";
export type ReportReason = "SPAM" | "ABUSE" | "MISINFORMATION" | "OTHER";
export type ReportStatus = "PENDING" | "CONFIRMED" | "REJECTED";
export type ApiReport = { id: string; reporterId: string; targetType: ReportTargetType; targetId: string; reason: ReportReason; status: ReportStatus; handledBy: string | null; handledAt: string | null; handlingNote: string | null; createdAt: string };

export function createReport(targetType: ReportTargetType, targetId: string, reason: ReportReason) {
  return ensureCsrfToken().then(() => request<ApiReport>(`/api/v1/reports/${targetType}/${encodeURIComponent(targetId)}`, {
    method: "POST", body: JSON.stringify({ reason }),
  }));
}

export function fetchAdminReports(status?: ReportStatus) {
  const params = new URLSearchParams({ page: "1", pageSize: "50" });
  if (status) params.set("status", status);
  return request<ApiReport[]>(`/api/v1/admin/reports?${params.toString()}`);
}

export function handleReport(reportId: string, action: "CONFIRM" | "REJECT", note?: string) {
  return ensureCsrfToken().then(() => request<ApiReport>(`/api/v1/admin/reports/${encodeURIComponent(reportId)}/handle`, {
    method: "POST", body: JSON.stringify({ action, note: note?.trim() || null }),
  }));
}

export type UploadedImage = { assetId: string; url: string; originalName: string | null; contentType: string; size: number };

export function uploadImage(file: File) {
  const formData = new FormData();
  formData.append("file", file);
  return ensureCsrfToken().then(() => request<UploadedImage>("/api/v1/files/images", { method: "POST", body: formData }));
}

export function deleteImage(assetId: string) {
  return ensureCsrfToken().then(() => request<void>(`/api/v1/files/images/${encodeURIComponent(assetId)}`, { method: "DELETE" }));
}

export type PostInteraction = {
  postId: string;
  likeCount: number;
  favoriteCount: number;
  liked: boolean;
  favorited: boolean;
};

export function fetchPostInteractions(postId: string) {
  return request<PostInteraction>(`/api/v1/posts/${encodeURIComponent(postId)}/interactions`);
}

export function likePost(postId: string) {
  return ensureCsrfToken().then(() => request<PostInteraction>(`/api/v1/posts/${encodeURIComponent(postId)}/interactions/like`, { method: "POST" }));
}

export function unlikePost(postId: string) {
  return ensureCsrfToken().then(() => request<PostInteraction>(`/api/v1/posts/${encodeURIComponent(postId)}/interactions/like`, { method: "DELETE" }));
}

export function favoritePost(postId: string) {
  return ensureCsrfToken().then(() => request<PostInteraction>(`/api/v1/posts/${encodeURIComponent(postId)}/interactions/favorite`, { method: "POST" }));
}

export function unfavoritePost(postId: string) {
  return ensureCsrfToken().then(() => request<PostInteraction>(`/api/v1/posts/${encodeURIComponent(postId)}/interactions/favorite`, { method: "DELETE" }));
}

export type ApiComment = {
  id: string;
  parentId: string | null;
  author: ApiAuthor;
  content: string;
  deleted: boolean;
  likeCount: number;
  createdAt: string;
};

export async function fetchComments(postId: string, options: { page?: number; pageSize?: number; sort?: "latest" | "hot" } = {}) {
  const params = new URLSearchParams({
    page: String(options.page ?? 1),
    pageSize: String(options.pageSize ?? 20),
    sort: options.sort ?? "hot",
  });
  const envelope = await requestEnvelope<ApiComment[]>(`/api/v1/posts/${encodeURIComponent(postId)}/comments?${params.toString()}`);
  return { items: envelope?.data ?? [], meta: envelope?.meta };
}

export function createComment(postId: string, content: string, parentId?: string | null) {
  return ensureCsrfToken().then(() => request<ApiComment>(`/api/v1/posts/${encodeURIComponent(postId)}/comments`, {
      method: "POST",
      body: JSON.stringify({ content, parentId: parentId ?? null }),
    }));
}

export function deleteComment(postId: string, commentId: string) {
  return ensureCsrfToken().then(() => request<void>(`/api/v1/posts/${encodeURIComponent(postId)}/comments/${encodeURIComponent(commentId)}`, { method: "DELETE" }));
}

export function requestVerificationCode(phone: string) {
  return request<VerificationCode>("/api/v1/auth/code", { method: "POST", body: JSON.stringify({ phone }) });
}

export function loginWithPhone(phone: string, code: string) {
  return request<AuthUser>("/api/v1/auth/login", { method: "POST", body: JSON.stringify({ phone, code }) });
}

export async function fetchCurrentUser() {
  try { return await request<AuthUser>("/api/v1/auth/me"); } catch (error) { if (error instanceof ApiError && error.status === 401) return null; throw error; }
}

export function fetchPublicProfile(userId: string) {
  return request<PublicProfile>(`/api/v1/users/${encodeURIComponent(userId)}`);
}

export type UserFollow = { targetUserId: string; followerCount: number; followed: boolean };

export function fetchUserFollow(userId: string) {
  return request<UserFollow>(`/api/v1/users/${encodeURIComponent(userId)}/follow`);
}

export function followUser(userId: string) {
  return ensureCsrfToken().then(() => request<UserFollow>(`/api/v1/users/${encodeURIComponent(userId)}/follow`, { method: "POST" }));
}

export function unfollowUser(userId: string) {
  return ensureCsrfToken().then(() => request<UserFollow>(`/api/v1/users/${encodeURIComponent(userId)}/follow`, { method: "DELETE" }));
}

export async function fetchPublicProfilePosts(userId: string, options: { page?: number; pageSize?: number } = {}) {
  const params = new URLSearchParams({
    page: String(options.page ?? 1),
    pageSize: String(options.pageSize ?? 20),
  });
  const envelope = await requestEnvelope<ApiPost[]>(`/api/v1/users/${encodeURIComponent(userId)}/posts?${params.toString()}`);
  return { items: envelope?.data ?? [], meta: envelope?.meta };
}

export async function fetchMyProfile(options: { page?: number; pageSize?: number } = {}) {
  const params = new URLSearchParams({
    page: String(options.page ?? 1),
    pageSize: String(options.pageSize ?? 20),
  });
  return request<ProfileOverview>(`/api/v1/users/me/profile?${params.toString()}`);
}

export function logoutFromApi() {
  return request<void>("/api/v1/auth/logout", { method: "POST" });
}
