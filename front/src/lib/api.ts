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

type ApiEnvelope<T> = { data: T; meta?: { page: number; pageSize: number; totalItems: number; totalPages: number } };

export type AuthUser = { id: string; phone: string; nickname: string; avatarUrl: string | null; bio: string | null };
export type VerificationCode = { expiresIn: number; retryAfter: number; devCode?: string | null };

export class ApiError extends Error {
  constructor(public readonly status: number, public readonly code?: string, message = "API request failed") {
    super(message);
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(API_BASE_URL + path, {
    ...init,
    credentials: "include",
    headers: { ...(init.body ? { "Content-Type": "application/json" } : {}), ...init.headers },
  });
  if (!response.ok) {
    let error: { code?: string; message?: string } = {};
    try { error = await response.json() as { code?: string; message?: string }; } catch { /* Keep the HTTP status. */ }
    throw new ApiError(response.status, error.code, error.message ?? "API request failed");
  }
  if (response.status === 204) return undefined as T;
  const envelope = await response.json() as ApiEnvelope<T>;
  return envelope.data;
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

export function requestVerificationCode(phone: string) {
  return request<VerificationCode>("/api/v1/auth/code", { method: "POST", body: JSON.stringify({ phone }) });
}

export function loginWithPhone(phone: string, code: string) {
  return request<AuthUser>("/api/v1/auth/login", { method: "POST", body: JSON.stringify({ phone, code }) });
}

export async function fetchCurrentUser() {
  try { return await request<AuthUser>("/api/v1/auth/me"); } catch (error) { if (error instanceof ApiError && error.status === 401) return null; throw error; }
}

export function logoutFromApi() {
  return request<void>("/api/v1/auth/logout", { method: "POST" });
}
