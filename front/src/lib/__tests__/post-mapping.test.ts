import { describe, expect, test } from "vitest";
import type { ApiPost, PostSearchItem } from "@/lib/api";
import { searchItemToGuide, toGuide } from "@/lib/post-view";

/**
 * toGuide / searchItemToGuide 是 API 数据到 UI 模型的唯一转换点。
 * 此前零单测，只靠 e2e 间接覆盖——而 e2e 没有一条用例断言过
 * mediaUrls 的结果，等于这条回退链完全是裸奔。
 */

const basePost: ApiPost = {
  id: "10000000-0000-0000-0000-000000000001",
  type: "GUIDE",
  category: "配队攻略",
  title: "长离焚火队",
  excerpt: "摘要",
  content: "正文",
  author: { id: "user-1", nickname: "潮声档案员", avatarUrl: null, bio: "记录鸣潮实战" },
  publishedAt: "2026-10-01T10:24:00",
  viewCount: 1200,
  likeCount: 88,
  favoriteCount: 12,
  commentCount: 3,
  tags: ["配队", "长离"],
};

describe("toGuide 的媒体回退链", () => {
  test("优先用 media 数组，且按 sortOrder 排序", () => {
    const guide = toGuide({
      ...basePost,
      media: [
        { id: "m2", url: "/media/second.webp", sortOrder: 2, isCover: false },
        { id: "m1", url: "/media/first.webp", sortOrder: 1, isCover: true },
      ],
      // media 存在时，下面的字段应被忽略
      mediaUrls: ["/media/should-be-ignored.webp"],
      coverImageUrl: "/media/also-ignored.webp",
    });
    expect(guide.mediaUrls).toEqual(["/media/first.webp", "/media/second.webp"]);
  });

  test("media 为空时回退到 mediaUrls", () => {
    expect(toGuide({ ...basePost, media: [], mediaUrls: ["/media/a.webp", "/media/b.webp"] }).mediaUrls).toEqual(["/media/a.webp", "/media/b.webp"]);
  });

  test("media 和 mediaUrls 都缺时回退到 coverImageUrl", () => {
    expect(toGuide({ ...basePost, coverImageUrl: "/art/cover.webp" }).mediaUrls).toEqual(["/art/cover.webp"]);
  });

  test("连封面都没有时从正文 markdown 提取", () => {
    expect(toGuide({ ...basePost, content: "正文\n![配图](/media/from-markdown.webp)\n更多正文" }).mediaUrls).toEqual(["/media/from-markdown.webp"]);
  });

  test("四级全空时是无图卡片，不抛异常", () => {
    expect(toGuide({ ...basePost, content: "没有任何图片的正文" }).mediaUrls).toEqual([]);
    expect(toGuide({ ...basePost, content: undefined }).mediaUrls).toEqual([]);
  });
});

describe("toGuide 的字段格式化", () => {
  test("计数走 formatPostCount，不是原始数字", () => {
    const guide = toGuide({ ...basePost, viewCount: 1200, likeCount: 12345 });
    expect(guide.views).toBe("1.2k");
    expect(guide.likes).toBe("1.2w");
  });

  test("replies 用 commentCount 原值（前端要拼「条」字，不能被 k/w 化）", () => {
    expect(toGuide({ ...basePost, commentCount: 3 }).replies).toBe(3);
  });

  test("authorMark 取昵称首字", () => {
    expect(toGuide(basePost).authorMark).toBe("潮");
    expect(toGuide(basePost).author).toBe("潮声档案员");
  });
});

describe("searchItemToGuide", () => {
  const searchItem: PostSearchItem = {
    id: "10000000-0000-0000-0000-000000000002",
    type: "GENERAL",
    category: "心得",
    title: "声骸词条选择",
    excerpt: "摘要",
    authorId: "10000000-0000-0000-0000-000000000005",
    authorName: "漂泊者",
    publishedAt: "2026-10-02T08:00:00",
    viewCount: 800,
    likeCount: 20,
    favoriteCount: 4,
    commentCount: 1,
    tags: ["声骸"],
  };

  test("用索引快照的 authorName，不要求 author 对象", () => {
    const guide = searchItemToGuide(searchItem);
    expect(guide.author).toBe("漂泊者");
    expect(guide.authorMark).toBe("漂");
  });

  test("authorName 为空串时 authorMark 兜底为「漂」", () => {
    // 类型上 authorName 是必填 string，所以这里测的是空串——生产代码的
    // `?.slice(0, 1) || "漂"` 对空串同样要能兜住（slice(0,1) 返回 ""，
    // 空串是 falsy，所以走 || 分支）
    expect(searchItemToGuide({ ...searchItem, authorName: "" }).authorMark).toBe("漂");
    expect(searchItemToGuide({ ...searchItem, authorName: "" }).author).toBe("");
  });

  test("搜索结果不返回配图（避免为图片再回一趟 DB）", () => {
    expect(searchItemToGuide(searchItem).mediaUrls).toBeUndefined();
  });

  test("透传 highlight 供渲染层包 <mark>", () => {
    expect(searchItemToGuide({ ...searchItem, highlight: { title: ["声<mark>骸</mark>"] } }).highlight).toEqual({ title: ["声<mark>骸</mark>"] });
  });
});
