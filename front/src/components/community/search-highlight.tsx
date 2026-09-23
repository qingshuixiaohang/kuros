import type { ReactNode } from "react";

/**
 * ES 高亮片段的安全渲染（切片 #14 se-06）。
 *
 * 为什么不用 dangerouslySetInnerHTML：后端 highlight 只在命中词两侧插入 <mark>/</mark>，片段其余部分是
 * **未转义的帖子原文**——若标题/正文本身含 HTML/脚本，直接 innerHTML 会形成 XSS。这里按已知分隔符
 * <mark>/</mark> 切片：命中片段包进真正的 <mark> 元素，其余当纯文本交给 React 自动转义，既拿到高亮效果
 * 又不给注入留口子。
 *
 * fragments 缺失或为空（该字段没进 highlight map）时回退展示 fallback 原文（纯文本），保证「无命中不高亮、
 * 但内容照常可读」，不会出现空白。
 */
const MARK_DELIMITER = /(<mark>|<\/mark>)/g;

export function HighlightText({ fragments, fallback }: { fragments?: string[]; fallback: string }) {
  if (!fragments?.length) return <>{fallback}</>;
  // title/excerpt 通常取第一条片段即可（ES 已按匹配度截断，多片段拼接反而割裂语义）。
  const parts = fragments[0].split(MARK_DELIMITER);
  const nodes: ReactNode[] = [];
  let inMark = false;
  parts.forEach((part, index) => {
    if (part === "<mark>") { inMark = true; return; }
    if (part === "</mark>") { inMark = false; return; }
    if (!part) return;
    nodes.push(inMark ? <mark key={index}>{part}</mark> : <span key={index}>{part}</span>);
  });
  return <>{nodes}</>;
}
