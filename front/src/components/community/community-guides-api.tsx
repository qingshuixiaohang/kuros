"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Eye, Heart, MessageSquare, MoveRight, PenLine } from "lucide-react";
import { useEffect, useState } from "react";
import { CommunityPageFrame, PageHeader } from "@/components/community/community-pages";
import { fetchPosts, type ApiPost } from "@/lib/api";
import { guides as fallbackGuides } from "@/lib/mock";
import type { Guide } from "@/types/community";

function formatCount(value: number) {
  if (value >= 10000) return (value / 10000).toFixed(value >= 100000 ? 0 : 1).replace(/\.0$/, "") + "w";
  return String(value);
}

function formatPublishedAt(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(date).replace(/\//g, "-");
}

function toGuide(post: ApiPost): Guide {
  return { id: post.id, category: post.category, title: post.title, excerpt: post.excerpt, content: post.content, author: post.author.nickname, authorMark: post.author.nickname.slice(0, 1), avatarTone: "blue", publishedAt: formatPublishedAt(post.publishedAt), views: formatCount(post.viewCount), replies: post.commentCount, likes: formatCount(post.likeCount), tags: post.tags };
}

function filterFallback(category: string, query: string) {
  const keyword = query.trim().toLowerCase();
  return fallbackGuides.filter((guide) => (category === "全部" || guide.category === category) && (guide.title + guide.excerpt + guide.tags.join("")).toLowerCase().includes(keyword));
}

function GuideListItem({ guide }: { guide: Guide }) {
  return <article className="guide-list-item"><div className="guide-list-top"><span className="guide-type">{guide.category}</span><time>{guide.publishedAt}</time></div><Link href={"/guides/" + guide.id}><h2>{guide.title}</h2></Link><p>{guide.excerpt}</p><div className="guide-list-meta"><span>{guide.author}</span><span><Eye size={14} />{guide.views}</span><span><MessageSquare size={14} />{guide.replies}</span><span><Heart size={14} />{guide.likes}</span></div><Link className="read-link" href={"/guides/" + guide.id}>查看攻略 <MoveRight size={15} /></Link></article>;
}

export function GuidesApiPage() {
  const params = useSearchParams();
  const initialCategory = params.get("category") ?? "全部";
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState(initialCategory);
  const [items, setItems] = useState<Guide[]>(fallbackGuides);
  const [loadedKey, setLoadedKey] = useState("");
  const [apiUnavailable, setApiUnavailable] = useState(false);
  const categories = ["全部", "配队攻略", "深塔攻略", "角色培养", "新手攻略"];
  const requestKey = category + "::" + query;

  useEffect(() => {
    let cancelled = false;
    fetchPosts({ category, keyword: query, pageSize: 20 })
      .then((posts) => { if (!cancelled) { setItems(posts.map(toGuide)); setApiUnavailable(false); setLoadedKey(requestKey); } })
      .catch(() => { if (!cancelled) { setItems(filterFallback(category, query)); setApiUnavailable(true); setLoadedKey(requestKey); } });
    return () => { cancelled = true; };
  }, [category, query, requestKey]);

  const visibleItems = apiUnavailable ? filterFallback(category, query) : items;
  const loading = loadedKey !== requestKey;
  return <CommunityPageFrame activeNav="guides" query={query} onQueryChange={setQuery}><PageHeader section="攻略" title="攻略" description="从角色培养到深塔挑战，找到适合当前版本的解法。" action={<Link className="primary-button" href="/publish?type=guide"><PenLine size={16} />发布攻略</Link>} /><div className="filter-bar"><div className="filter-tabs">{categories.map((item) => <button className={category === item ? "is-active" : ""} key={item} onClick={() => setCategory(item)} type="button">{item}</button>)}</div><span className="result-count">{loading ? "正在同步内容…" : "共 " + visibleItems.length + " 篇"}</span></div><section className="guide-list-page">{visibleItems.length ? visibleItems.map((guide) => <GuideListItem guide={guide} key={guide.id} />) : <p className="empty-state">没有找到匹配的攻略。</p>}{apiUnavailable && <p className="api-fallback-note">后端暂不可用，当前显示本地 Demo 数据。</p>}</section></CommunityPageFrame>;
}
