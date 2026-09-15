const API_BASE_URL = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080").replace(/\/$/, "");

export type ApiAuthor = { id: string; nickname: string; avatarUrl: string | null; bio: string | null };

export type ApiPost = {
  id: string;
  type: "GUIDE" | "GENERAL";
  category: string;
  title: string;
  excerpt: string;
  content?: string;
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

export type AuthUser = { id: string; phone: string; nickname: string; avatarUrl: string | null; bio: string | null };
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

async function requestEnvelope<T>(path: string, init: RequestInit = {}): Promise<ApiEnvelope<T>> {
  const headers = { ...(init.body ? { "Content-Type": "application/json" } : {}), ...init.headers } as Record<string, string>;
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
  return (await requestEnvelope<T>(path, init)).data;
}

export async function fetchPosts(options: { category?: string; keyword?: string; tag?: string; page?: number; pageSize?: number; sort?: "latest" | "hot" } = {}) {
  const params = new URLSearchParams();
  params.set("page", String(options.page ?? 1));
  params.set("pageSize", String(options.pageSize ?? 20));
  params.set("sort", options.sort ?? "latest");
  if (options.category && options.category !== "全部") params.set("category", options.category);
  if (options.keyword?.trim()) params.set("keyword", options.keyword.trim());
  if (options.tag?.trim()) params.set("tag", options.tag.trim());
  return request<ApiPost[]>("/api/v1/posts?" + params.toString());
}

export function fetchPost(id: string) {
  return request<ApiPost>("/api/v1/posts/" + encodeURIComponent(id));
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
  return { items: envelope.data, meta: envelope.meta };
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

export async function fetchPublicProfilePosts(userId: string, options: { page?: number; pageSize?: number } = {}) {
  const params = new URLSearchParams({
    page: String(options.page ?? 1),
    pageSize: String(options.pageSize ?? 20),
  });
  const envelope = await requestEnvelope<ApiPost[]>(`/api/v1/users/${encodeURIComponent(userId)}/posts?${params.toString()}`);
  return { items: envelope.data, meta: envelope.meta };
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
