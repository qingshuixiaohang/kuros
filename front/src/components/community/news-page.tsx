"use client";
import Link from "next/link";
import { ArrowLeft, Bell, ChevronRight, Share2 } from "lucide-react";
import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { CommunityPageFrame, PageHeader } from "@/components/community/community-pages";
import { useCommunityDemo } from "@/components/community/community-interactions";
import { newsItems } from "@/lib/mock";

export function NewsPage() {
  const params = useSearchParams();
  const [category, setCategory] = useState(params.get("category") ?? "全部");
  const { requestLogin, notify } = useCommunityDemo();
  const tabs = ["全部", "版本前瞻", "官方公告", "活动资讯"];
  const visible = newsItems.filter((item) => category === "全部" || item.category === category);
  return (
    <CommunityPageFrame activeNav="news">
      <PageHeader section="资讯" title="版本资讯" description="版本公告、活动前瞻和官方信息集中整理。" action={<button className="secondary-button" onClick={() => requestLogin(() => notify("已订阅资讯更新。"))} type="button"><Bell size={16} />订阅更新</button>} />
      <div className="news-page-tabs">{tabs.map((item) => <button className={category === item ? "is-active" : ""} key={item} onClick={() => setCategory(item)} type="button">{item}</button>)}</div>
      <section className="news-page-list">{visible.map((item) => <Link href={"/news/" + item.id} key={item.id}><article><time>{item.date}</time><div><span>{item.category}</span><h2>{item.title}</h2><p>{item.summary}</p></div><ChevronRight size={18} /></article></Link>)}</section>
    </CommunityPageFrame>
  );
}

export function NewsDetailPage({ id }: { id: string }) {
  const item = newsItems.find((entry) => entry.id === id) ?? newsItems[0];
  const { notify } = useCommunityDemo();
  return (
    <CommunityPageFrame activeNav="news">
      <article className="detail-page">
        <Link className="back-link" href="/news"><ArrowLeft size={15} />返回资讯列表</Link>
        <div className="detail-heading"><span className="guide-type">{item.category}</span><h1>{item.title}</h1><p>{item.date} · 社区资讯整理</p></div>
        <main className="article-body news-detail-body">{item.content.map((paragraph) => <p key={paragraph}>{paragraph}</p>)}</main>
        <button className="share-button detail-share" onClick={() => notify("资讯链接已复制。")} type="button"><Share2 size={16} />分享资讯</button>
      </article>
    </CommunityPageFrame>
  );
}
