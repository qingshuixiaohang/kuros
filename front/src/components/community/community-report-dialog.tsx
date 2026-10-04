"use client";

import { Flag, X } from "lucide-react";
import { useRef, useState, type FormEvent } from "react";
import { ApiError, createReport, type ReportReason, type ReportTargetType } from "@/lib/api";
import { useOverlay } from "@/hooks/use-overlay";
import { useCommunityDemo } from "@/components/community/community-interactions";

const reasons: Array<{ value: ReportReason; label: string }> = [
  { value: "SPAM", label: "垃圾广告" },
  { value: "ABUSE", label: "人身攻击" },
  { value: "MISINFORMATION", label: "错误信息" },
  { value: "OTHER", label: "其他问题" },
];

export function CommunityReportDialog({ targetType, targetId, onClose, onSuccess }: {
  targetType: ReportTargetType;
  targetId: string;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const { notify } = useCommunityDemo();
  const closeRef = useRef<HTMLButtonElement>(null);
  const [reason, setReason] = useState<ReportReason>("SPAM");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  // 浮层行为与登录框同源（useOverlay）：Esc 可关但提交中守卫、焦点陷阱、初始聚焦；
  // 遮罩不绑定点击关闭——与登录框同一防误触策略，避免丢掉已选理由
  useOverlay({ open: true, onClose, surfaceSelector: ".report-dialog", initialFocusRef: closeRef, blocked: submitting });

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      await createReport(targetType, targetId, reason);
      notify("举报已提交，感谢你帮助维护社区秩序。");
      onSuccess();
    } catch (requestError) {
      const message = requestError instanceof ApiError && requestError.code === "REPORT_DUPLICATE"
        ? "你已经举报过该内容，请等待管理员处理。"
        : requestError instanceof Error ? requestError.message : "举报失败，请稍后重试";
      setError(message);
    } finally {
      setSubmitting(false);
    }
  }

  return <div className="dialog-backdrop report-backdrop" role="presentation">
    <section className="report-dialog" role="dialog" aria-modal="true" aria-labelledby="report-title">
      <header><div><span><Flag size={15} />社区反馈</span><h2 id="report-title">举报{targetType === "POST" ? "帖子" : "评论"}</h2></div><button type="button" ref={closeRef} onClick={onClose} aria-label="关闭举报弹窗"><X size={19} /></button></header>
      <p className="report-dialog-hint">请选择最符合当前情况的理由，管理员会尽快查看。</p>
      <form onSubmit={submit}>
        <div className="report-reason-grid">{reasons.map((item) => <label className={reason === item.value ? "is-selected" : ""} key={item.value}><input checked={reason === item.value} name="report-reason" onChange={() => setReason(item.value)} type="radio" value={item.value} /><span>{item.label}</span></label>)}</div>
        {error && <p className="login-form-error" role="alert">{error}</p>}
        <footer><button className="secondary-button" disabled={submitting} type="button" onClick={onClose}>取消</button><button className="primary-button" disabled={submitting} type="submit">{submitting ? "提交中…" : "提交举报"}</button></footer>
      </form>
    </section>
  </div>;
}
