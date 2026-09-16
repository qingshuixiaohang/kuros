"use client";

import Link from "next/link";
import { ArrowLeft, Check, Flag, RotateCw, X } from "lucide-react";
import { useEffect, useState } from "react";
import { CommunityPageFrame } from "@/components/community/community-pages";
import { useCommunityDemo } from "@/components/community/community-interactions";
import { ApiError, fetchAdminReports, handleReport, type ApiReport, type ReportStatus } from "@/lib/api";

const filters: Array<{ value?: ReportStatus; label: string }> = [
  { label: "全部" }, { value: "PENDING", label: "待处理" }, { value: "CONFIRMED", label: "已确认" }, { value: "REJECTED", label: "已驳回" },
];
const reasonLabels = { SPAM: "垃圾广告", ABUSE: "人身攻击", MISINFORMATION: "错误信息", OTHER: "其他问题" };
const statusLabels = { PENDING: "待处理", CONFIRMED: "已确认", REJECTED: "已驳回" };

export function CommunityAdminReportsPage() {
  const { loggedIn, user, notify, requestLogin } = useCommunityDemo();
  const [filter, setFilter] = useState<ReportStatus | undefined>("PENDING");
  const [reports, setReports] = useState<ApiReport[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshKey, setRefreshKey] = useState(0);
  const [error, setError] = useState("");
  const [activeId, setActiveId] = useState<string | null>(null);
  const [note, setNote] = useState("");

  useEffect(() => {
    if (!loggedIn || user?.role !== "ADMIN") return;
    fetchAdminReports(filter).then((result) => setReports(result)).catch((requestError) => {
      setError(requestError instanceof ApiError && requestError.status === 403 ? "当前账号没有管理员权限。" : "举报列表加载失败，请稍后重试");
    }).finally(() => setLoading(false));
  }, [filter, loggedIn, refreshKey, user?.role]);

  function openLogin() { requestLogin(() => undefined); }

  function selectFilter(value: ReportStatus | undefined) {
    setLoading(true);
    setError("");
    setFilter(value);
  }

  function refresh() {
    setLoading(true);
    setError("");
    setRefreshKey((current) => current + 1);
  }

  async function process(reportId: string, action: "CONFIRM" | "REJECT") {
    try {
      await handleReport(reportId, action, note);
      setReports((current) => current.map((report) => report.id === reportId ? { ...report, status: action === "CONFIRM" ? "CONFIRMED" : "REJECTED", handlingNote: note || null } : report));
      setActiveId(null);
      setNote("");
      notify(action === "CONFIRM" ? "举报已确认，内容已处置。" : "举报已驳回。");
    } catch (requestError) {
      notify(requestError instanceof Error ? requestError.message : "处理失败，请稍后重试");
    }
  }

  if (!loggedIn) return <CommunityPageFrame hideRail hideSidebar><section className="admin-state"><Flag size={25} /><h1>社区审核台</h1><p>请先登录管理员账号后查看举报记录。</p><button className="primary-button" type="button" onClick={openLogin}>登录管理员账号</button></section></CommunityPageFrame>;
  if (user?.role !== "ADMIN") return <CommunityPageFrame hideRail hideSidebar><section className="admin-state"><X size={25} /><h1>没有访问权限</h1><p>该页面只对管理员开放，请切换到管理员账号。</p><Link className="secondary-button" href="/">返回社区首页</Link></section></CommunityPageFrame>;

  return <CommunityPageFrame hideRail hideSidebar><section className="admin-page"><header className="admin-page-header"><div><Link className="back-link" href="/"><ArrowLeft size={15} />返回社区</Link><p className="page-breadcrumb"><Flag size={13} />内容治理</p><h1>举报审核台</h1><p>处理玩家反馈，维护鸣潮社区的讨论秩序。</p></div><button className="secondary-button" disabled={loading} type="button" onClick={refresh}><RotateCw size={15} />刷新</button></header><nav className="admin-filters" aria-label="举报状态筛选">{filters.map((item) => <button className={filter === item.value ? "is-active" : ""} key={item.label} type="button" onClick={() => selectFilter(item.value)}>{item.label}</button>)}</nav>{error && <p className="admin-inline-error" role="alert">{error}</p>}{loading ? <p className="admin-empty">正在整理举报记录…</p> : reports.length === 0 ? <p className="admin-empty">当前筛选下没有举报记录。</p> : <div className="report-list">{reports.map((report) => <article className="report-row" key={report.id}><div className="report-row-main"><div className="report-row-meta"><span className={"report-status report-status--" + report.status}>{statusLabels[report.status]}</span><span>{report.targetType === "POST" ? "帖子" : "评论"} · {reasonLabels[report.reason]}</span><time>{report.createdAt.slice(0, 16).replace("T", " ")}</time></div><strong>目标 ID：{report.targetId}</strong><small>举报人：{report.reporterId}</small>{report.handlingNote && <p>处理备注：{report.handlingNote}</p>}</div>{report.status === "PENDING" && <div className="report-row-actions">{activeId === report.id && <input aria-label="处理备注" onChange={(event) => setNote(event.target.value)} placeholder="可填写处理备注" value={note} />}{activeId === report.id ? <><button className="report-confirm" type="button" onClick={() => void process(report.id, "CONFIRM")}><Check size={14} />确认处置</button><button type="button" onClick={() => void process(report.id, "REJECT")}>驳回</button></> : <button type="button" onClick={() => setActiveId(report.id)}>处理</button>}</div>}</article>)}</div>}</section></CommunityPageFrame>;
}
