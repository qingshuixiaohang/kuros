"use client";
import { Search } from "lucide-react";
import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { CommunityPageFrame, PageHeader, GuideListItem } from "@/components/community/community-pages";
import { guides } from "@/lib/mock";
import { useCommunityPostQuery, useSearchPostsQuery } from "@/lib/community-queries";
import { ApiError } from "@/lib/api";
import { searchItemToGuide, toGuide } from "@/lib/post-view";
import type { Guide } from "@/types/community";

/** 本地 Demo 回退：后端整体不可用时用 mock 数据做包含匹配，保证页面不空白（对齐原搜索页行为）。 */
function demoFallback(keyword: string): Guide[] {
  const needle = keyword.toLowerCase();
  return guides.filter((guide) => (guide.title + guide.excerpt + guide.tags.join("")).toLowerCase().includes(needle));
}

export function SearchResultsPage() {
  const params = useSearchParams();
  const initialTerm = params.get("q")?.trim() ?? "";
  const [term, setTerm] = useState(initialTerm);
  const keyword = term.trim();
  return (
    <CommunityPageFrame
      query={term}
      onQueryChange={(value) => {
        setTerm(value);
        window.history.replaceState(null, "", value ? "/search?q=" + encodeURIComponent(value) : "/search");
      }}
    >
      <PageHeader
        section="搜索"
        title={keyword ? "\u201c" + keyword + "\u201d 的结果" : "搜索"}
        description={keyword ? "优先展示匹配的攻略与社区内容。" : "输入关键词寻找攻略、声骸和玩家讨论。"}
      />
      {/* 切片 #14 se-06：keyword 非空走 ES 全文检索（/api/v1/search），为空保持原 /api/v1/posts 浏览行为不变 */}
      {keyword ? <EsSearchResults keyword={keyword} /> : <BrowseResults />}
    </CommunityPageFrame>
  );
}

/**
 * ES 全文检索结果（切片 #14 se-06）：keyword 非空时的搜索路径。
 *
 * 复用 #13 游标契约做「加载更多」无限滚动（透传 nextCursor→search_after），命中词经 HighlightText 包成 <mark>。
 * 三态降级：ES 软依赖返回 503 → 「搜索暂不可用」友好提示（不崩、不空白）；其他错误（后端整体不可用）→
 * 本地 Demo 回退；正常无命中 → 空态引导。翻页/竞态/缓存由 useInfiniteQuery 托管，此处只做纯派生渲染。
 */
function EsSearchResults({ keyword }: { keyword: string }) {
  const query = useSearchPostsQuery(keyword);
  const error = query.error;
  const unavailable = error instanceof ApiError && error.status === 503;
  const demoFallbackActive = Boolean(error) && !unavailable;
  const items: Guide[] = error
    ? (demoFallbackActive ? demoFallback(keyword) : [])
    : query.data?.pages.flatMap((page) => page.items.map(searchItemToGuide)) ?? [];
  return (
    <section className="guide-list-page">
      {query.isLoading ? (
        <div className="feed-status">正在搜索鸣潮社区…</div>
      ) : unavailable ? (
        <div className="empty-state">
          <Search size={20} />
          <p>搜索暂不可用，请稍后重试。</p>
        </div>
      ) : items.length ? (
        <>
          {items.map((guide) => <GuideListItem guide={guide} key={guide.id} />)}
          {query.hasNextPage ? (
            <button className="feed-load-more" disabled={query.isFetchingNextPage} onClick={() => query.fetchNextPage()} type="button">
              {query.isFetchingNextPage ? "正在加载更多…" : "加载更多"}
            </button>
          ) : (
            <p className="feed-end-note">已经到底啦，相关内容都看完了。</p>
          )}
        </>
      ) : (
        <div className="empty-state">
          <Search size={20} />
          <p>没有找到匹配内容，试试角色名、声骸或攻略标签。</p>
        </div>
      )}
      {demoFallbackActive && <p className="api-fallback-note">后端暂不可用，当前显示本地 Demo 数据。</p>}
    </section>
  );
}

/**
 * 空关键词浏览（保留原行为）：keyword 为空时仍走 /api/v1/posts 的 offset 分页，
 * 与原搜索页一致（含后端不可用时的本地 Demo 回退与分页导航）。
 */
function BrowseResults() {
  const [page, setPage] = useState(1);
  const result = useCommunityPostQuery({ keyword: "", page, pageSize: 10, sort: "latest" });
  const items = result.error ? demoFallback("") : result.data?.items.map(toGuide) ?? [];
  const totalPages = result.error ? 1 : result.data?.meta?.totalPages ?? 1;
  return (
    <section className="guide-list-page">
      {result.isLoading ? (
        <div className="feed-status">正在搜索鸣潮社区…</div>
      ) : items.length ? (
        items.map((guide) => <GuideListItem guide={guide} key={guide.id} />)
      ) : (
        <div className="empty-state">
          <Search size={20} />
          <p>没有找到匹配内容，试试角色名、声骸或攻略标签。</p>
        </div>
      )}
      {result.error && <p className="api-fallback-note">后端暂不可用，当前显示本地 Demo 数据。</p>}
      {totalPages > 1 && (
        <nav className="pagination" aria-label="搜索结果分页">
          {Array.from({ length: totalPages }, (_, index) => index + 1).map((pageNumber) => (
            <button
              className={page === pageNumber ? "is-active" : ""}
              key={pageNumber}
              onClick={() => setPage(pageNumber)}
              type="button"
            >
              {pageNumber}
            </button>
          ))}
        </nav>
      )}
    </section>
  );
}
