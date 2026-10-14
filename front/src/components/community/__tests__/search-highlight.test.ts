import { describe, expect, test } from "vitest";
import { parseHighlightFragments } from "@/components/community/search-highlight";

/**
 * ES 高亮片段的解析逻辑——这是 XSS 防护的关键路径。
 *
 * 原实现把解析写在 React 组件里，只能靠渲染测试覆盖，而渲染测试断言的是
 * "页面上有没有 <mark>"，测不出解析器本身的边界。抽成纯函数后，每一条
 * 边界都能直接断言。
 *
 * 契约（来自 search-highlight.tsx 的注释）：
 * - 后端只在命中词两侧插 <mark>/</mark>，片段其余部分是未转义的原文
 * - 因此这里按分隔符切片，命中部分包 <mark>，其余当纯文本交给 React 转义
 * - 绝不让原文以 HTML 形式进入 DOM
 */

describe("parseHighlightFragments", () => {
  test("无片段时回退原文", () => {
    expect(parseHighlightFragments(undefined, "原始标题")).toEqual([{ text: "原始标题", marked: false }]);
    expect(parseHighlightFragments([], "原始标题")).toEqual([{ text: "原始标题", marked: false }]);
  });

  test("命中词被标记，其余部分是纯文本", () => {
    expect(parseHighlightFragments(["长<mark>离</mark>焚火队"], "长离焚火队")).toEqual([
      { text: "长", marked: false },
      { text: "离", marked: true },
      { text: "焚火队", marked: false },
    ]);
  });

  test("多个命中词各自成段", () => {
    expect(parseHighlightFragments(["<mark>长离</mark>与<mark>焚火</mark>"], "长离与焚火")).toEqual([
      { text: "长离", marked: true },
      { text: "与", marked: false },
      { text: "焚火", marked: true },
    ]);
  });

  test("取第一条片段（ES 已按匹配度截断，多片段拼接会割裂语义）", () => {
    expect(parseHighlightFragments(["第一<mark>条</mark>", "第二<mark>条</mark>"], "第一条")).toEqual([
      { text: "第一", marked: false },
      { text: "条", marked: true },
    ]);
  });

  test("空片段串回退原文", () => {
    expect(parseHighlightFragments([""], "原始标题")).toEqual([{ text: "原始标题", marked: false }]);
  });

  test("HTML 字符保持原样不被吞——它们该由 React 转义，不是这里处理", () => {
    // 关键安全契约：解析器只切 <mark>，其余一律当文本。若有人给片段里塞了
    // <script>，它必须原样出现在 marked:false 的段里，让 React 转义成实体
    const result = parseHighlightFragments(["<script>alert(1)</script>长<mark>离</mark>"], "x");
    expect(result).toEqual([
      { text: "<script>alert(1)</script>长", marked: false },
      { text: "离", marked: true },
    ]);
  });

  test("不成对的 mark 标签不导致状态错乱", () => {
    // 后端异常时可能只给了开标签。此时后续文本应仍按 marked 处理，
    // 但绝不能让 inMark 状态泄漏到下一条片段
    expect(parseHighlightFragments(["开头<mark>后面全亮"], "x")).toEqual([
      { text: "开头", marked: false },
      { text: "后面全亮", marked: true },
    ]);
  });

  test("连续空段被丢弃（split 在分隔符相邻时产生空串）", () => {
    expect(parseHighlightFragments(["<mark></mark>"], "x")).toEqual([]);
  });
});
