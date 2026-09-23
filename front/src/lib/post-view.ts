import type { ApiPost, PostSearchItem } from "@/lib/api";
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

/**
 * 将 ES 搜索命中条目映射为列表可渲染的 {@link Guide}（切片 #14 se-06）。
 *
 * 与 {@link toGuide} 的差异：{@link PostSearchItem} 没有 {@code author} 对象/media（搜索零跨服务往返），
 * 改用索引快照的 {@code authorName}；同时透传 {@code highlight} 供渲染层把命中词包成 {@code <mark>}。
 * 搜索结果列表不展示配图（mediaUrls 留空），避免为了图片再回一趟 DB。
 */
export function searchItemToGuide(item: PostSearchItem): Guide {
  return {
    id: item.id,
    category: item.category,
    title: item.title,
    excerpt: item.excerpt,
    author: item.authorName,
    authorMark: item.authorName?.slice(0, 1) || "漂",
    avatarTone: "blue",
    publishedAt: formatPublishedAt(item.publishedAt),
    views: formatPostCount(item.viewCount),
    replies: item.commentCount,
    likes: formatPostCount(item.likeCount),
    tags: item.tags,
    highlight: item.highlight,
  };
}
