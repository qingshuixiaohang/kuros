"use client";
import type { ReactNode } from "react";

/**
 * 站内 Markdown 渲染器（详情页正文与发布页实时预览共用）。
 * 从 community-post-detail.tsx 抽出：发布编辑器加"预览"模式时需要同一套渲染规则，
 * 两处各抄一份必然漂移（预览和实际详情长得不一样比没有预览更糟）。
 */

export function isSafeMarkdownImageUrl(value: string) {
  return value.startsWith("/media/") || /^https?:\/\//i.test(value);
}

export function MarkdownImage({ src, alt }: { src: string; alt: string }) {
  // eslint-disable-next-line @next/next/no-img-element
  return <img alt={alt} loading="lazy" src={src} />;
}

export function isSafeMarkdownLinkUrl(value: string) {
  return value.startsWith("/") || /^https?:\/\//i.test(value);
}

export function sectionIdForTitle(title: string, index: number) {
  if (title.includes("先确定") || title.includes("队伍节奏")) return "section-team-rhythm";
  if (title.includes("角色与声骸")) return "section-character-echoes";
  if (title.includes("实战检查")) return "section-checklist";
  const normalized = title.toLowerCase().replace(/[^a-z0-9\u4e00-\u9fff]+/g, "-").replace(/^-|-$/g, "");
  return `section-${normalized || index + 1}`;
}

export function parseMarkdownHeading(value: string) {
  const match = value.trim().match(/^#{1,2}\s+(.+)$/);
  return match?.[1].trim() ?? null;
}

function renderInlineMarkdown(value: string, keyPrefix: string): ReactNode[] {
  const pattern = /(\*\*([^*]+)\*\*)|(\*([^*]+)\*)|(`([^`]+)`)|(\[([^\]]+)\]\(([^)\s]+)\))/g;
  const nodes: ReactNode[] = [];
  let cursor = 0;
  let match: RegExpExecArray | null;
  let nodeIndex = 0;
  while ((match = pattern.exec(value)) !== null) {
    if (match.index > cursor) nodes.push(value.slice(cursor, match.index));
    if (match[2]) nodes.push(<strong key={`${keyPrefix}-${nodeIndex}`}>{match[2]}</strong>);
    else if (match[4]) nodes.push(<em key={`${keyPrefix}-${nodeIndex}`}>{match[4]}</em>);
    else if (match[6]) nodes.push(<code key={`${keyPrefix}-${nodeIndex}`}>{match[6]}</code>);
    else if (match[8] && isSafeMarkdownLinkUrl(match[9])) nodes.push(<a href={match[9]} key={`${keyPrefix}-${nodeIndex}`}>{match[8]}</a>);
    else nodes.push(match[0]);
    cursor = match.index + match[0].length;
    nodeIndex += 1;
  }
  if (cursor < value.length) nodes.push(value.slice(cursor));
  return nodes;
}

function renderMarkdownText(lines: string[], keyPrefix: string) {
  return lines.flatMap((line, index) => index === 0 ? renderInlineMarkdown(line, `${keyPrefix}-${index}`) : [<br key={`${keyPrefix}-br-${index}`} />, ...renderInlineMarkdown(line, `${keyPrefix}-${index}`)]);
}

export function renderMarkdown(content: string): ReactNode[] {
  let headingIndex = 0;
  return content.split(/\n\s*\n/).map((block, index) => {
    const lines = block.split("\n");
    const image = block.match(/^!\[([^\]]*)\]\(([^)\s]+)(?:\s+["'][^)]*["'])?\)$/);
    if (image && isSafeMarkdownImageUrl(image[2])) return <figure className="post-markdown-image" key={index}><MarkdownImage alt={image[1] || "帖子配图"} src={image[2]} /></figure>;
    if (lines.every((line) => line.startsWith("- "))) return <ul key={index}>{lines.map((line, lineIndex) => <li key={`${index}-${lineIndex}`}>{renderInlineMarkdown(line.slice(2), `${index}-${lineIndex}`)}</li>)}</ul>;
    if (lines.every((line) => /^\d+\.\s/.test(line))) return <ol key={index}>{lines.map((line, lineIndex) => <li key={`${index}-${lineIndex}`}>{renderInlineMarkdown(line.replace(/^\d+\.\s/, ""), `${index}-${lineIndex}`)}</li>)}</ol>;
    if (lines.every((line) => line.startsWith("> "))) return <blockquote key={index}>{renderMarkdownText(lines.map((line) => line.slice(2)), `${index}-quote`)}</blockquote>;
    if (lines.some((line) => parseMarkdownHeading(line))) {
      const nodes: ReactNode[] = [];
      let paragraphLines: string[] = [];
      const flushParagraph = () => {
        if (paragraphLines.length > 0) {
          nodes.push(<p key={`${index}-paragraph-${nodes.length}`}>{renderMarkdownText(paragraphLines, `${index}-paragraph-${nodes.length}`)}</p>);
          paragraphLines = [];
        }
      };
      lines.forEach((line, lineIndex) => {
        const heading = parseMarkdownHeading(line);
        if (!heading) { paragraphLines.push(line); return; }
        flushParagraph();
        const id = sectionIdForTitle(heading, headingIndex);
        headingIndex += 1;
        nodes.push(line.trimStart().startsWith("# ") ? <h1 id={id} key={`${index}-heading-${lineIndex}`}>{heading}</h1> : <h2 id={id} key={`${index}-heading-${lineIndex}`}>{heading}</h2>);
      });
      flushParagraph();
      return nodes;
    }
    return <p key={index}>{renderMarkdownText(lines, `${index}-paragraph`)}</p>;
  }).flat();
}

/** 详情页/预览共用的文章体容器：className 可附加页面特有样式。 */
export function MarkdownArticle({ content, className = "" }: { content?: string; className?: string }) {
  return <div className={"post-detail-body" + (className ? " " + className : "")}>{content ? renderMarkdown(content) : null}</div>;
}
