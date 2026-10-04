"use client";

/**
 * 后端不可用时的统一降级横幅（架构巡检 #5：此前"后端暂不可用"提示文案
 * 复制粘贴 6 处且没有重试入口）。所有列表页的 demo 降级都用这个组件：
 * 一处改文案、一处加重试。
 */
export function ApiFallbackNote({ onRetry, label = "后端暂不可用，当前显示本地 Demo 数据。" }: {
  onRetry?: () => void;
  label?: string;
}) {
  return <p className="api-fallback-note">
    {label}
    {onRetry && <button className="api-fallback-retry" onClick={onRetry} type="button">重新加载</button>}
  </p>;
}
