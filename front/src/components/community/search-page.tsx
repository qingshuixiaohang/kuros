"use client";
import { Search } from "lucide-react";
import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { CommunityPageFrame, PageHeader, GuideListItem } from "@/components/community/community-pages";
import { guides } from "@/lib/mock";
import { useCommunityPostQuery } from "@/lib/community-queries";
import { toGuide } from "@/lib/post-view";

export function SearchResultsPage() {
  const params = useSearchParams();
  const initialTerm = params.get("q")?.trim() ?? "";
  const [term, setTerm] = useState(initialTerm);
  const [page, setPage] = useState(1);
  const result = useCommunityPostQuery({ keyword: term, page, pageSize: 10, sort: "latest" });
  const fallback = guides.filter((guide) =>
    (guide.title + guide.excerpt + guide.tags.join("")).toLowerCase().includes(term.toLowerCase())
  );
  const items = result.error ? fallback : result.data?.items.map(toGuide) ?? [];
  const totalPages = result.error ? 1 : result.data?.meta?.totalPages ?? 1;
  return (
    <CommunityPageFrame
      query={term}
      onQueryChange={(value) => {
        setTerm(value);
        setPage(1);
        window.history.replaceState(null, "", value ? "/search?q=" + encodeURIComponent(value) : "/search");
      }}
    >
      <PageHeader
        section="搜索"
        title={term ? "\u201c" + term + "\u201d 的结果" : "搜索"}
        description={term ? "优先展示匹配的攻略与社区内容。" : "输入关键词寻找攻略、声骸和玩家讨论。"}
      />
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
    </CommunityPageFrame>
  );
}
