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

/**
 * 把 ES 的一条高亮片段解析成「文本段 + 是否命中」的有序列表。
 *
 * 抽成纯函数的理由：这段逻辑是 XSS 防护的关键路径，而原先它长在 React
 * 组件里，只能靠渲染测试覆盖——渲染测试断言的是"页面上有没有 <mark>"，
 * 测不出解析器本身的边界（不成对标签、空段、HTML 字符混入）。纯函数
 * 让每条边界都能直接断言，见同目录 __tests__/search-highlight.test.ts。
 *
 * 契约：只切 <mark>/</mark> 这一对已知分隔符，其余内容一律当纯文本。
 * 后端插入的是这两个标签，片段其余部分是**未转义的帖子原文**，所以任何
 * HTML/脚本字符都必须原样留在 marked:false 的段里，交给 React 自动转义，
 * 而不是在这里被解释或清除。
 */
export function parseHighlightFragments(fragments?: string[], fallback = ""): Array<{ text: string; marked: boolean }> {
  if (!fragments?.length || !fragments[0]) return [{ text: fallback, marked: false }];
  // title/excerpt 通常取第一条片段即可（ES 已按匹配度截断，多片段拼接反而割裂语义）。
  const parts = fragments[0].split(MARK_DELIMITER);
  const segments: Array<{ text: string; marked: boolean }> = [];
  let inMark = false;
  parts.forEach((part) => {
    if (part === "<mark>") {
      inMark = true;
      return;
    }
    if (part === "</mark>") {
      inMark = false;
      return;
    }
    if (!part) return;
    segments.push({ text: part, marked: inMark });
  });
  return segments;
}

export function HighlightText({ fragments, fallback }: { fragments?: string[]; fallback: string }) {
  const segments = parseHighlightFragments(fragments, fallback);
  return <>{segments.map((segment, index) => (segment.marked ? <mark key={index}>{segment.text}</mark> : <span key={index}>{segment.text}</span>))}</>;
}
