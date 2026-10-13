import { describe, expect, test } from "vitest";
import { extractMarkdownImages, formatPostCount, formatPublishedAt } from "@/lib/post-view";

/**
 * 这三个纯函数是帖子渲染的关键路径，此前零单测（只有 Playwright 间接
 * 覆盖，而且 e2e 里没有任何用例断言过图片提取的结果）。
 *
 * extractMarkdownImages 用正则解析 markdown，是最容易在边缘输入上出错
 * 的一类代码——所以优先给它补边界。
 */
describe("extractMarkdownImages", () => {
  test("提取站内媒体路径", () => {
    expect(extractMarkdownImages("![封面](/media/abc.png)")).toEqual(["/media/abc.png"]);
    expect(extractMarkdownImages("![同人](/art/xyz.webp)")).toEqual(["/art/xyz.webp"]);
  });

  test("提取外链图片", () => {
    expect(extractMarkdownImages("![远程](https://example.com/a.png)")).toEqual(["https://example.com/a.png"]);
    // 协议大小写不敏感
    expect(extractMarkdownImages("![远程](HTTPS://example.com/a.png)")).toEqual(["HTTPS://example.com/a.png"]);
  });

  test("忽略相对路径与非图片引用", () => {
    // 相对路径不在白名单里——它既不是媒体域也不是外链
    expect(extractMarkdownImages("![本地](./a.png)")).toEqual([]);
    // 普通链接不是图片
    expect(extractMarkdownImages("[文字](https://example.com)")).toEqual([]);
  });

  test("带 title 的图片语法", () => {
    expect(extractMarkdownImages('![封面](/media/a.png "标题")')).toEqual(["/media/a.png"]);
  });

  test("空内容与无图片内容", () => {
    expect(extractMarkdownImages()).toEqual([]);
    expect(extractMarkdownImages("")).toEqual([]);
    expect(extractMarkdownImages("纯文本正文")).toEqual([]);
  });

  test("多张图片按出现顺序提取", () => {
    expect(extractMarkdownImages("![一](/media/1.png)\n正文\n![二](/media/2.png)")).toEqual(["/media/1.png", "/media/2.png"]);
  });
});

describe("formatPostCount", () => {
  test("千以下原样", () => {
    expect(formatPostCount(0)).toBe("0");
    expect(formatPostCount(999)).toBe("999");
  });

  test("千位加 k，万位加 w", () => {
    expect(formatPostCount(1000)).toBe("1k");
    expect(formatPostCount(1500)).toBe("1.5k");
    expect(formatPostCount(9999)).toBe("10k");
    expect(formatPostCount(10000)).toBe("1w");
    expect(formatPostCount(12345)).toBe("1.2w");
  });

  test("十万以上不保留小数", () => {
    expect(formatPostCount(123456)).toBe("12w");
  });

  // 这条钉住的是**现状**而不是理想行为：9950 显示 9.9k 而不是 10k，
  // 9999 显示 10k——两者相差 49 却跨过了进位线，因为 toFixed 的四舍五入
  // 和 ">= 10000 换单位" 是两套独立判断。
  // 显示层瑕疵，且帖子计数到 9950 的概率极低，不值得为它改单位判定。
  // 写下来是为了将来有人想"顺手修一下"时，先看到这里已有约定
  test("进位线附近保持现状（文档化既有行为）", () => {
    expect(formatPostCount(9950)).toBe("9.9k");
    expect(formatPostCount(9999)).toBe("10k");
    expect(formatPostCount(99999)).toBe("10w");
  });
});

describe("formatPublishedAt", () => {
  test("格式化为中文月日时分", () => {
    // 注意：这个函数用本地时区，断言里只验证形状不验证具体值
    expect(formatPublishedAt("2026-10-01T10:24:00")).toMatch(/^\d{2}-\d{2} \d{2}:\d{2}$/);
  });

  test("非法日期原样返回，不抛异常", () => {
    expect(formatPublishedAt("not-a-date")).toBe("not-a-date");
  });
});
