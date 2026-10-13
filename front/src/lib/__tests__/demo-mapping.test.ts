import { describe, expect, test } from "vitest";
import { DEMO_AUTHOR_ID_BY_NAME, DEMO_COVER_BY_SLUG, DEMO_POST_ID_BY_SLUG, DEMO_SLUG_BY_POST_ID } from "@/lib/post-view";
import { guides } from "@/lib/mock";

/**
 * 这四个映射表互相引用但各自独立维护，post-view.ts 的注释自己都说：
 * 「新增演示帖漏同步一处就静默降级」。
 *
 * 静默降级是最难发现的一类 bug——没有报错，只是内容悄悄退回 demo 态。
 * 单测能做的就是把这个契约钉住：集合必须一致。
 */

describe("演示映射表的交叉一致性", () => {
  test("DEMO_SLUG_BY_POST_ID 是 DEMO_POST_ID_BY_SLUG 的精确反演", () => {
    expect(Object.keys(DEMO_SLUG_BY_POST_ID)).toHaveLength(Object.keys(DEMO_POST_ID_BY_SLUG).length);
    for (const [slug, postId] of Object.entries(DEMO_POST_ID_BY_SLUG)) {
      expect(DEMO_SLUG_BY_POST_ID[postId]).toBe(slug);
    }
  });

  test("封面表覆盖的 slug 与帖子表一致——漏同步就会静默降级", () => {
    expect(Object.keys(DEMO_COVER_BY_SLUG).sort()).toEqual(Object.keys(DEMO_POST_ID_BY_SLUG).sort());
  });

  test("作者表覆盖的昵称与帖子表覆盖的一致（同一批演示内容，作者不该多也不该少）", () => {
    // 注意作者表的 key 是「昵称」而不是 slug，所以不能直接和帖子表比 key，
    // 要比的是「数量与集合规模」——少一个作者就意味着某篇演示帖的作者
    // 归属换算会失败
    expect(Object.keys(DEMO_AUTHOR_ID_BY_NAME)).toHaveLength(Object.keys(DEMO_POST_ID_BY_SLUG).length);
  });

  test("所有 postId 都是合法 UUID（前端靠 UUID 正则识别后端帖子）", () => {
    for (const postId of Object.values(DEMO_POST_ID_BY_SLUG)) {
      expect(postId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
    }
  });

  test("postId 互不重复（反演表靠唯一性才能成立）", () => {
    const ids = Object.values(DEMO_POST_ID_BY_SLUG);
    expect(new Set(ids).size).toBe(ids.length);
  });

  test("映射表里的 slug 必须真实存在于 mock 数据", () => {
    const mockSlugs = new Set(guides.map((guide) => guide.id));
    for (const slug of Object.keys(DEMO_POST_ID_BY_SLUG)) {
      expect(mockSlugs, `映射表里的 ${slug} 在 mock.ts 中不存在`).toContain(slug);
    }
  });

  test("映射表覆盖的作者必须真实存在于 mock 数据", () => {
    const mockAuthors = new Set(guides.map((guide) => guide.author));
    for (const name of Object.keys(DEMO_AUTHOR_ID_BY_NAME)) {
      expect(mockAuthors, `映射表里的作者 ${name} 在 mock.ts 中不存在`).toContain(name);
    }
  });
});
