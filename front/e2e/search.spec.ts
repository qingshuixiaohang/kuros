import { expect, test, type Page } from "@playwright/test";

/**
 * 搜索页全流程 e2e（前端审计 H-10：此前零覆盖）。
 *
 * /search 是全仓最复杂的客户端逻辑，却没有任何测试：
 * - keyword 非空走 ES 全文检索（/api/v1/search）+ #13 游标契约
 * - useInfiniteQuery 的 getNextPageParam 透传 nextCursor → search_after 深翻
 * - 三态降级：503 → 「搜索暂不可用」；其他错误 → 本地 demo 回退；无命中 → 空态引导
 * - keyword 切换即换 queryKey，有竞态风险
 *
 * 这里用 page.route mock 后端，把四条路径都钉死：
 * ① 命中 + 高亮 + 加载更多 + 到底态
 * ② 503 → 友好提示不崩
 * ③ 500 → demo 回退列表
 * ④ 快速切换 keyword → 列表与当前 keyword 一致（竞态）
 */

const POST_A = {
  id: "search-post-a",
  type: "GUIDE",
  category: "配队攻略",
  title: "长离焚火队配队思路",
  excerpt: "围绕共鸣效率与轮切节奏的实战框架",
  authorId: "u1",
  authorName: "漂泊者小王",
  publishedAt: "2026-09-14T10:24:00",
  viewCount: 1800,
  likeCount: 370,
  favoriteCount: 120,
  commentCount: 46,
  tags: ["长离", "配队"],
  // highlight 契约：按字段分组的片段数组，命中词由后端用 <mark> 包裹
  // （前端 HighlightText 按 <mark>/</mark> 切片的，见 search-highlight.tsx）
  highlight: {
    title: ["长离<mark>焚火队</mark>配队思路"],
    excerpt: ["围绕<mark>共鸣效率</mark>与轮切节奏的实战框架"],
  },
};

const POST_B = {
  ...POST_A,
  id: "search-post-b",
  title: "共鸣效率进阶：轮切节奏详解",
  excerpt: "进阶向的共鸣效率与循环分析",
  highlight: {
    title: ["<mark>共鸣效率</mark>进阶：轮切节奏详解"],
    excerpt: ["进阶向的<mark>共鸣效率</mark>与循环分析"],
  },
};

const POST_C = {
  ...POST_A,
  id: "search-post-c",
  title: "深塔速通低配阵容",
  excerpt: "低配队伍的稳定循环思路",
  tags: ["深塔"],
  highlight: {
    title: ["<mark>深塔</mark>速通低配阵容"],
    excerpt: ["低配队伍的稳定循环思路"],
  },
};

/** 命中响应（第一页，还有下一页）。 */
function pageOne() {
  return {
    code: "SUCCESS",
    data: {
      items: [POST_A, POST_B],
      nextCursor: "cursor-page-2",
      hasMore: true,
    },
  };
}

/** 命中响应（第二页，到底）。 */
function pageTwo() {
  return {
    code: "SUCCESS",
    data: {
      items: [POST_C],
      nextCursor: null,
      hasMore: false,
    },
  };
}

async function mockSearchSuccess(page: Page) {
  await page.route("**/api/v1/search**", async (route) => {
    const url = new URL(route.request().url());
    const cursor = url.searchParams.get("cursor");
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(cursor ? pageTwo() : pageOne()),
    });
  });
}

test.describe("搜索页 ES 检索路径", () => {
  test("命中关键词后展示结果、高亮与加载更多", async ({ page }) => {
    await mockSearchSuccess(page);
    await page.goto("/search?q=共鸣效率");

    // 第一页两条命中
    await expect(page.getByText("长离焚火队配队思路")).toBeVisible();
    await expect(page.getByText("共鸣效率进阶：轮切节奏详解")).toBeVisible();

    // 命中词经 HighlightText 包成 <mark>（后端用 <mark> 包裹，前端切片渲染）
    const marks = page.locator("mark");
    await expect(marks.first()).toBeVisible();
    // mark 内容应为帖子里的真实片段，而非空壳
    await expect(marks.first()).not.toBeEmpty();

    // 还有下一页 → 显示「加载更多」而不是到底态
    await expect(page.getByRole("button", { name: "加载更多" })).toBeVisible();
    await expect(page.getByText("已经到底啦")).toHaveCount(0);
  });

  test("点击加载更多翻页并展示到底态", async ({ page }) => {
    await mockSearchSuccess(page);
    await page.goto("/search?q=共鸣效率");

    await page.getByRole("button", { name: "加载更多" }).click();

    // 第二页内容出现
    await expect(page.getByText("深塔速通低配阵容")).toBeVisible();
    // 第一页内容仍在（useInfiniteQuery 累积）
    await expect(page.getByText("长离焚火队配队思路")).toBeVisible();

    // hasMore=false → 加载更多按钮消失，出现到底提示
    await expect(page.getByRole("button", { name: "加载更多" })).toHaveCount(0);
    await expect(page.getByText("已经到底啦，相关内容都看完了。")).toBeVisible();
  });

  test("深翻时把游标透传给后端", async ({ page }) => {
    const seenCursors: (string | null)[] = [];
    await page.route("**/api/v1/search**", async (route) => {
      const url = new URL(route.request().url());
      seenCursors.push(url.searchParams.get("cursor"));
      const cursor = url.searchParams.get("cursor");
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(cursor ? pageTwo() : pageOne()),
      });
    });

    await page.goto("/search?q=共鸣效率");
    await page.getByRole("button", { name: "加载更多" }).click();
    await expect(page.getByText("深塔速通低配阵容")).toBeVisible();

    // 第一次无 cursor，第二次带上后端返回的 nextCursor
    expect(seenCursors).toEqual([null, "cursor-page-2"]);
  });
});

test.describe("搜索页降级路径", () => {
  test("ES 返回 503 时显示「搜索暂不可用」且不崩", async ({ page }) => {
    await page.route("**/api/v1/search**", (route) =>
      route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({ code: "SEARCH_UNAVAILABLE", message: "ES 不可用" }),
      }),
    );

    await page.goto("/search?q=共鸣效率");

    await expect(page.getByText("搜索暂不可用，请稍后重试。")).toBeVisible();
    // 提供重试入口
    await expect(page.getByRole("button", { name: "重新加载" })).toBeVisible();
    // 不显示 demo 回退数据
    await expect(page.getByText("长离焚火队配队思路")).toHaveCount(0);
  });

  test("后端 500 时回退到本地 demo 数据", async ({ page }) => {
    await page.route("**/api/v1/search**", (route) =>
      route.fulfill({
        status: 500,
        contentType: "application/json",
        body: JSON.stringify({ code: "INTERNAL_ERROR", message: "boom" }),
      }),
    );

    await page.goto("/search?q=长离");

    // demo 回退：mock 数据里有标题含「长离」的条目
    await expect(page.getByText(/长离/).first()).toBeVisible();
    // 不显示 503 专属文案
    await expect(page.getByText("搜索暂不可用，请稍后重试。")).toHaveCount(0);
  });

  test("无命中时显示空态引导", async ({ page }) => {
    await page.route("**/api/v1/search**", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ code: "SUCCESS", data: { items: [], nextCursor: null, hasMore: false } }),
      }),
    );

    await page.goto("/search?q=绝对不存在的关键词xyz");

    // 空态引导（不白屏、不报错）
    await expect(page.locator(".empty-state").first()).toBeVisible();
  });
});

test.describe("搜索页竞态", () => {
  test("快速切换关键词后列表与当前关键词一致", async ({ page }) => {
    // 两个 keyword 返回不同的帖子，用于识别"列表是否跟上了当前 keyword"。
    // 不设 highlight：让标题走 fallback 原文渲染，断言才能绑定到 title 本身
    // （highlight 存在时 HighlightText 优先渲染片段，显示的是片段里的文本）。
    await page.route("**/api/v1/search**", async (route) => {
      const url = new URL(route.request().url());
      const keyword = url.searchParams.get("keyword");
      const item = keyword === "深塔"
        ? { ...POST_C, title: "深塔专属结果", highlight: undefined }
        : { ...POST_A, title: "共鸣效率专属结果", highlight: undefined };
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          code: "SUCCESS",
          data: { items: [item], nextCursor: null, hasMore: false },
        }),
      });
    });

    await page.goto("/search?q=共鸣效率");
    await expect(page.getByText("共鸣效率专属结果")).toBeVisible();

    // 切到另一个 keyword
    const searchInput = page.locator('input[type="search"], input[name="query"], input[placeholder*="搜索"]').first();
    await searchInput.fill("深塔");
    await searchInput.press("Enter");

    // 列表必须只包含新 keyword 的结果，不残留旧 keyword 的条目
    await expect(page.getByText("深塔专属结果")).toBeVisible();
    await expect(page.getByText("共鸣效率专属结果")).toHaveCount(0);
  });
});
