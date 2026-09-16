"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Eye, Heart, MessageSquare, MoveRight, PenLine } from "lucide-react";
import { useState } from "react";
import { CommunityPageFrame, PageHeader } from "@/components/community/community-pages";
import { useCommunityPostQuery } from "@/lib/community-queries";
import { guides as fallbackGuides } from "@/lib/mock";
import { toGuide } from "@/lib/post-view";
import type { Guide } from "@/types/community";

function filterFallback(category: string, query: string, tag = "") {
  const keyword = query.trim().toLowerCase();
  const tagKeyword = tag.trim().toLowerCase();
  return fallbackGuides.filter((guide) => (category === "全部" || guide.category === category) && (!tagKeyword || guide.tags.some((item) => item.toLowerCase().includes(tagKeyword))) && (guide.title + guide.excerpt + guide.tags.join("")).toLowerCase().includes(keyword));
}

function GuideListItem({ guide }: { guide: Guide }) {
  return <article className="guide-list-item"><div className="guide-list-top"><span className="guide-type">{guide.category}</span><time>{guide.publishedAt}</time></div><Link href={"/guides/" + guide.id} rel="noopener noreferrer" target="_blank"><h2>{guide.title}</h2></Link><p>{guide.excerpt}</p><div className="guide-list-meta"><span>{guide.author}</span><span><Eye size={14} />{guide.views}</span><span><MessageSquare size={14} />{guide.replies}</span><span><Heart size={14} />{guide.likes}</span></div><Link className="read-link" href={"/guides/" + guide.id} rel="noopener noreferrer" target="_blank">查看攻略 <MoveRight size={15} /></Link></article>;
}

export function GuidesApiPage() {
  const params = useSearchParams();
  const initialCategory = params.get("category") ?? "全部";
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState(initialCategory);
  const [tag, setTag] = useState("");
  const [sort, setSort] = useState<"latest" | "hot">("latest");
  const [page, setPage] = useState(1);
  const categories = ["全部", "配队攻略", "深塔攻略", "角色培养", "新手攻略"];
  const result = useCommunityPostQuery({ category, keyword: query, tag, page, pageSize: 10, sort });
  const apiUnavailable = Boolean(result.error);
  const visibleItems = apiUnavailable ? filterFallback(category, query, tag) : result.data?.items.map(toGuide) ?? [];
  const totalItems = apiUnavailable ? visibleItems.length : result.data?.meta?.totalItems ?? visibleItems.length;
  const totalPages = apiUnavailable ? 1 : result.data?.meta?.totalPages ?? 1;
  function changeCategory(value: string) { setCategory(value); setPage(1); }
  function changeQuery(value: string) { setQuery(value); setPage(1); }
  function changeTag(value: string) { setTag(value); setPage(1); }
  return <CommunityPageFrame activeNav="guides" query={query} onQueryChange={changeQuery}><PageHeader section="攻略" title="攻略" description="从角色培养到深塔挑战，找到适合当前版本的解法。" action={<Link className="primary-button" href="/publish?type=guide"><PenLine size={16} />发布攻略</Link>} /><div className="filter-bar"><div className="filter-tabs">{categories.map((item) => <button className={category === item ? "is-active" : ""} key={item} onClick={() => changeCategory(item)} type="button">{item}</button>)}</div><label className="filter-keyword"><span>标签</span><input onChange={(event) => changeTag(event.target.value)} placeholder="如：长离" value={tag} /></label><label className="filter-sort"><span>排序</span><select onChange={(event) => { setSort(event.target.value as "latest" | "hot"); setPage(1); }} value={sort}><option value="latest">最新发布</option><option value="hot">热门内容</option></select></label><span className="result-count">{result.isLoading ? "正在同步内容…" : "共 " + totalItems + " 篇"}</span></div><section className="guide-list-page">{visibleItems.length ? visibleItems.map((guide) => <GuideListItem guide={guide} key={guide.id} />) : <p className="empty-state">没有找到匹配的攻略。</p>}{apiUnavailable && <p className="api-fallback-note">后端暂不可用，当前显示本地 Demo 数据。</p>}{totalPages > 1 && <nav className="pagination" aria-label="攻略分页">{Array.from({ length: totalPages }, (_, index) => index + 1).map((pageNumber) => <button className={page === pageNumber ? "is-active" : ""} key={pageNumber} onClick={() => setPage(pageNumber)} type="button">{pageNumber}</button>)}</nav>}</section></CommunityPageFrame>;
}
