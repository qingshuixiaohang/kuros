"use client";

import Link from "next/link";
import { ArrowLeft, Check, Flag, RotateCw, X } from "lucide-react";
import { useState } from "react";
import { CommunityPageFrame } from "@/components/community/community-pages";
import { useCommunityDemo } from "@/components/community/community-interactions";
import { ApiError, type ReportStatus } from "@/lib/api";
import { useAdminReportsQuery, useHandleReportMutation } from "@/lib/community-queries";

const filters: Array<{ value?: ReportStatus; label: string }> = [
  { label: "全部" },
  { value: "PENDING", label: "待处理" },
  { value: "CONFIRMED", label: "已确认" },
  { value: "REJECTED", label: "已驳回" },
];
const reasonLabels = { SPAM: "垃圾广告", ABUSE: "人身攻击", MISINFORMATION: "错误信息", OTHER: "其他问题" };
const statusLabels = { PENDING: "待处理", CONFIRMED: "已确认", REJECTED: "已驳回" };

export function CommunityAdminReportsPage() {
  const { loggedIn, user, notify, requestLogin } = useCommunityDemo();
  const [filter, setFilter] = useState<ReportStatus | undefined>("PENDING");
  const [activeId, setActiveId] = useState<string | null>(null);
  const [note, setNote] = useState("");

  // 举报列表读走 react-query：queryKey 带 filter，切 tab 就是换 key，
  // 不再需要 refreshKey 这种手动 force-refresh 的手段
  const isAdmin = loggedIn && user?.role === "ADMIN";
  const reportsQuery = useAdminReportsQuery({ status: filter, enabled: isAdmin });
  const handleMutation = useHandleReportMutation({ status: filter });
  const reports = reportsQuery.data?.pages.flatMap((page) => page.items) ?? [];
  const loading = reportsQuery.isPending;
  // 403 要有专属文案：管理端最常见的失败就是"登录了但不是管理员"，
  // 通用错误提示会让用户以为自己网断了。ApiError 带 status，这里判一下
  const error = reportsQuery.isError
    ? reportsQuery.error instanceof ApiError && reportsQuery.error.status === 403
      ? "当前账号没有管理员权限。"
      : "举报列表加载失败，请稍后重试"
    : "";

  function openLogin() {
    requestLogin(() => undefined);
  }

  function selectFilter(value: ReportStatus | undefined) {
    setFilter(value);
  }

  async function process(reportId: string, action: "CONFIRM" | "REJECT") {
    try {
      // 不本地 map 改 status：当前过滤 tab 是 PENDING，改完它还会留在列表里，
      // 用户看到"已确认"混在待处理队列。invalidate 让服务端按真相重取
      await handleMutation.mutateAsync({ reportId, action, note });
      setActiveId(null);
      setNote("");
      notify(action === "CONFIRM" ? "举报已确认，内容已处置。" : "举报已驳回。");
    } catch (requestError) {
      notify(requestError instanceof Error ? requestError.message : "处理失败，请稍后重试");
    }
  }

  if (!loggedIn)
    return (
      <CommunityPageFrame activeNav="admin" hideRail hideSidebar>
        <section className="admin-state">
          <Flag size={25} />
          <h1>社区审核台</h1>
          <p>请先登录管理员账号后查看举报记录。</p>
          <button className="primary-button" type="button" onClick={openLogin}>
            登录管理员账号
          </button>
        </section>
      </CommunityPageFrame>
    );
  if (user?.role !== "ADMIN")
    return (
      <CommunityPageFrame activeNav="admin" hideRail hideSidebar>
        <section className="admin-state">
          <X size={25} />
          <h1>没有访问权限</h1>
          <p>该页面只对管理员开放，请切换到管理员账号。</p>
          <Link className="secondary-button" href="/">
            返回社区首页
          </Link>
        </section>
      </CommunityPageFrame>
    );

  return (
    <CommunityPageFrame activeNav="admin" hideRail hideSidebar>
      <section className="admin-page">
        <header className="admin-page-header">
          <div>
            <Link className="back-link" href="/">
              <ArrowLeft size={15} />
              返回社区
            </Link>
            <p className="page-breadcrumb">
              <Flag size={13} />
              内容治理
            </p>
            <h1>举报审核台</h1>
            <p>处理玩家反馈，维护鸣潮社区的讨论秩序。</p>
          </div>
          <button className="secondary-button" disabled={loading} type="button" onClick={() => void reportsQuery.refetch()}>
            <RotateCw size={15} />
            刷新
          </button>
        </header>
        <nav className="admin-filters" aria-label="举报状态筛选">
          {filters.map((item) => (
            <button className={filter === item.value ? "is-active" : ""} key={item.label} type="button" onClick={() => selectFilter(item.value)}>
              {item.label}
            </button>
          ))}
        </nav>
        {error && (
          <p className="admin-inline-error" role="alert">
            {error}
          </p>
        )}
        {loading ? (
          <p className="admin-empty">正在整理举报记录…</p>
        ) : error ? null : reports.length === 0 ? (
          <p className="admin-empty">当前筛选下没有举报记录。</p>
        ) : (
          <div className="report-list">
            {reports.map((report) => (
              <article className="report-row" key={report.id}>
                <div className="report-row-main">
                  <div className="report-row-meta">
                    <span className={"report-status report-status--" + report.status}>{statusLabels[report.status]}</span>
                    <span>
                      {report.targetType === "POST" ? "帖子" : "评论"} · {reasonLabels[report.reason]}
                    </span>
                    <time>{report.createdAt.slice(0, 16).replace("T", " ")}</time>
                  </div>
                  {report.targetTitle && report.targetPostId ? (
                    <Link
                      aria-label={`查看目标：${report.targetTitle}`}
                      className="report-target"
                      href={`/guides/${encodeURIComponent(report.targetPostId)}${report.targetType === "COMMENT" ? "#comments" : ""}`}
                    >
                      <strong>{report.targetTitle}</strong>
                      {report.targetExcerpt && <span>{report.targetExcerpt}</span>}
                    </Link>
                  ) : (
                    <div className="report-target">
                      <strong>{report.targetTitle ?? `目标 ID：${report.targetId}`}</strong>
                      {report.targetExcerpt && <span>{report.targetExcerpt}</span>}
                    </div>
                  )}
                  <small>
                    {report.targetTitle && report.targetPostId ? `目标 ID：${report.targetId} · ` : ""}
                    举报人：{report.reporterId}
                  </small>
                  {report.handlingNote && <p>处理备注：{report.handlingNote}</p>}
                </div>
                {report.status === "PENDING" && (
                  <div className="report-row-actions">
                    {activeId === report.id && (
                      <input aria-label="处理备注" onChange={(event) => setNote(event.target.value)} placeholder="可填写处理备注" value={note} />
                    )}
                    {activeId === report.id ? (
                      <>
                        <button className="report-confirm" type="button" onClick={() => void process(report.id, "CONFIRM")}>
                          <Check size={14} />
                          确认处置
                        </button>
                        <button type="button" onClick={() => void process(report.id, "REJECT")}>
                          驳回
                        </button>
                      </>
                    ) : (
                      <button type="button" onClick={() => setActiveId(report.id)}>
                        处理
                      </button>
                    )}
                  </div>
                )}
              </article>
            ))}
            {reportsQuery.isFetchNextPageError && (
              <p className="admin-inline-error" role="alert">
                后续举报加载失败，请重试。
              </p>
            )}
            {reportsQuery.hasNextPage && (
              <div className="report-pagination">
                <button disabled={reportsQuery.isFetchingNextPage} type="button" onClick={() => void reportsQuery.fetchNextPage()}>
                  {reportsQuery.isFetchingNextPage ? "正在加载…" : "加载更多举报"}
                </button>
              </div>
            )}
          </div>
        )}
      </section>
    </CommunityPageFrame>
  );
}
