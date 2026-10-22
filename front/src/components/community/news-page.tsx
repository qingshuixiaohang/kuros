"use client";
import Link from "next/link";
import { ArrowLeft, Bell, ChevronRight, Share2 } from "lucide-react";
import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { CommunityPageFrame, PageHeader } from "@/components/community/community-pages";
import { useCommunityDemo } from "@/components/community/community-interactions";
import { useNewsDetailQuery, useNewsListQuery } from "@/lib/community-queries";
import { ApiError } from "@/lib/api";

export function NewsPage() {
  const params = useSearchParams();
  const [category, setCategory] = useState(params.get("category") ?? "全部");
  const { requestLogin, notify } = useCommunityDemo();
  const tabs = ["全部", "版本前瞻", "官方公告", "活动资讯"];
  // 资讯接真 API（H-9）：此前是 mock.ts 的硬编码数组，改内容要改代码发版
  const newsQuery = useNewsListQuery(category);
  const visible = newsQuery.data?.items ?? [];
  return (
    <CommunityPageFrame activeNav="news">
      <PageHeader
        section="资讯"
        title="版本资讯"
        description="版本公告、活动前瞻和官方信息集中整理。"
        action={
          <button className="secondary-button" onClick={() => requestLogin(() => notify("已订阅资讯更新。"))} type="button">
            <Bell size={16} />
            订阅更新
          </button>
        }
      />
      <div aria-label="资讯分类筛选" className="news-page-tabs" role="group">
        {tabs.map((item) => (
          <button aria-pressed={category === item} className={category === item ? "is-active" : ""} key={item} onClick={() => setCategory(item)} type="button">
            {item}
          </button>
        ))}
      </div>
      <section className="news-page-list">
        {newsQuery.isPending ? (
          <p className="admin-empty" role="status">
            正在整理资讯…
          </p>
        ) : null}
        {newsQuery.isError ? (
          <div className="empty-state">
            <p role="status">资讯加载失败，请稍后重试。</p>
            <button className="secondary-button" onClick={() => newsQuery.refetch()} type="button">
              重新加载
            </button>
          </div>
        ) : null}
        {!newsQuery.isPending && !newsQuery.isError && visible.length === 0 ? <p className="admin-empty">当前分类下没有资讯。</p> : null}
        {visible.map((item) => (
          <Link href={"/news/" + item.slug} key={item.slug}>
            <article>
              <time>{item.date}</time>
              <div>
                <span>{item.category}</span>
                <h2>{item.title}</h2>
                <p>{item.summary}</p>
              </div>
              <ChevronRight size={18} />
            </article>
          </Link>
        ))}
      </section>
    </CommunityPageFrame>
  );
}

export function NewsDetailPage({ id }: { id: string }) {
  // id 即 slug（对外契约用 slug，不用内部 UUID）
  // 未知/未发布 slug 不再回退到第一条资讯冒充（架构巡检 #2 同模式）——
  // 接真 API 后这条由后端 NEWS_NOT_FOUND 保证，前端只需把失败态显示出来
  const newsQuery = useNewsDetailQuery(id);
  const item = newsQuery.data;
  const unavailable =
    newsQuery.isError && !(newsQuery.error instanceof ApiError && newsQuery.error.status === 404 && newsQuery.error.code === "NEWS_NOT_FOUND");
  const { notify } = useCommunityDemo();
  if (!item)
    return (
      <CommunityPageFrame activeNav="news">
        <article className="detail-page">
          <Link className="back-link" href="/news">
            <ArrowLeft size={15} />
            返回资讯列表
          </Link>
          <div className="empty-state">
            {newsQuery.isPending ? (
              <p role="status">正在加载资讯…</p>
            ) : (
              <>
                <p>{unavailable ? "资讯加载失败，请稍后重试。" : "资讯不存在或已下架。"}</p>
                {unavailable ? (
                  <button className="secondary-button" onClick={() => newsQuery.refetch()} type="button">
                    重新加载
                  </button>
                ) : null}
                <Link className="secondary-button" href="/news">
                  返回资讯列表
                </Link>
              </>
            )}
          </div>
        </article>
      </CommunityPageFrame>
    );
  return (
    <CommunityPageFrame activeNav="news">
      <article className="detail-page">
        <Link className="back-link" href="/news">
          <ArrowLeft size={15} />
          返回资讯列表
        </Link>
        <div className="detail-heading">
          <span className="guide-type">{item.category}</span>
          <h1>{item.title}</h1>
          <p>{item.date} · 社区资讯整理</p>
        </div>
        <main className="article-body news-detail-body">
          {item.content.map((paragraph) => (
            <p key={paragraph}>{paragraph}</p>
          ))}
        </main>
        <button className="share-button detail-share" onClick={() => notify("资讯链接已复制。")} type="button">
          <Share2 size={16} />
          分享资讯
        </button>
      </article>
    </CommunityPageFrame>
  );
}
