import type { ApiPost } from "@/lib/api";
import type { Guide } from "@/types/community";

const markdownImage = /!\[[^\]]*\]\(([^)\s]+)(?:\s+["'][^)]*["'])?\)/g;

export function extractMarkdownImages(content?: string) {
  if (!content) return [];
  return Array.from(content.matchAll(markdownImage), (match) => match[1])
    .filter((source) => source.startsWith("/media/") || source.startsWith("/art/") || /^https?:\/\//i.test(source));
}

export function formatPostCount(value: number) {
  if (value >= 10000) return (value / 10000).toFixed(value >= 100000 ? 0 : 1).replace(/\.0$/, "") + "w";
  if (value >= 1000) return (value / 1000).toFixed(1).replace(/\.0$/, "") + "k";
  return String(value);
}

export function formatPublishedAt(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(date).replace(/\//g, "-");
}

export function toGuide(post: ApiPost): Guide {
  return {
    id: post.id,
    category: post.category,
    title: post.title,
    excerpt: post.excerpt,
    content: post.content,
    author: post.author.nickname,
    authorMark: post.author.nickname.slice(0, 1),
    avatarTone: "blue",
    publishedAt: formatPublishedAt(post.publishedAt),
    views: formatPostCount(post.viewCount),
    replies: post.commentCount,
    likes: formatPostCount(post.likeCount),
    tags: post.tags,
    mediaUrls: post.media?.length ? post.media.slice().sort((left, right) => left.sortOrder - right.sortOrder).map((item) => item.url) : post.mediaUrls?.length ? post.mediaUrls : post.coverImageUrl ? [post.coverImageUrl] : extractMarkdownImages(post.content),
  };
}
