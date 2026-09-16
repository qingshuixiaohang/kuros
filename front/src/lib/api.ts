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

async function request<T>(path: string): Promise<T> {
  const response = await fetch(API_BASE_URL + path, { credentials: "include" });
  if (!response.ok) throw new Error("API request failed: " + response.status);
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
