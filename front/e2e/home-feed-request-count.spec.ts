import { expect, test, type Page } from "@playwright/test";

/**
 * 首页 Feed 请求数回归测试（前端审计 H-2 / H-7）。
 *
 * 要锁定的契约：渲染一页 N 条帖子所发的互动请求数是**常数 1**，
 * 不随 N 线性增长。
 *
 * 背景：原实现里每个 PostCard 各自 useEffect 发一次
 * GET /posts/{id}/interactions，一屏 20 帖 = 1 次列表 + 20 次互动 = 21 个
 * 请求。浏览器 HTTP/1.1 只有 6 条并发，实际分 4 批才完成，首屏被拖慢；
 * 移动端弱网下几乎不可用。
 *
 * 而列表接口早已返回 likeCount/favoriteCount，卡片上的计数根本不需要
 * 额外请求——N+1 只发生在"当前用户是否已赞/已藏"这个 per-user 状态上。
 * 现改为一次 GET /api/v1/interactions/batch-states?ids=a,b,c... 覆盖整页。
 *
 * 这个 spec 是 H-2 的验收标准：它数的是真实发出的请求，不是 UI 呈现。
 * UI 全绿也可能带着 20 次隐藏请求，只有数请求能发现。
 */

const POST_COUNT = 12;

/** 造 POST_COUNT 条帖子，id 可预测。 */
function posts(count: number) {
  return Array.from({ length: count }, (_, index) => ({
    id: `n-plus-one-${index}`,
    type: "GUIDE",
    category: "配队攻略",
    title: `请求计数测试帖 ${index}`,
    excerpt: "摘要",
    content: "正文",
    author: { id: "author-1", nickname: "测试作者", avatarUrl: null },
    authorId: "author-1",
    publishedAt: "2026-09-14T10:24:00",
    viewCount: 100,
    likeCount: 10,
    favoriteCount: 2,
    commentCount: 3,
    tags: ["配队"],
    media: [],
  }));
}

/** 记录某路径模式被请求了几次。 */
function countRequests(page: Page, pattern: RegExp) {
  const seen: string[] = [];
  page.on("request", (request) => {
    if (pattern.test(request.url())) seen.push(request.url());
  });
  return seen;
}

test.describe("首页 Feed 请求数", () => {
  test("一页帖子只发一次互动状态请求，不随帖子数增长", async ({ page }) => {
    const perPostRequests = countRequests(page, /\/api\/v1\/posts\/[^/]+\/interactions(?!\/)/);
    const batchRequests = countRequests(page, /\/api\/v1\/interactions\/batch-states/);

    await page.route("**/api/v1/posts?**", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ code: "SUCCESS", data: posts(POST_COUNT), meta: { page: 1, pageSize: 20, totalItems: POST_COUNT, totalPages: 1 } }),
      }),
    );
    await page.route("**/api/v1/interactions/batch-states**", (route) => {
      const ids = new URL(route.request().url()).searchParams.get("ids")?.split(",") ?? [];
      const data: Record<string, [number, number]> = {};
      for (const id of ids) data[id] = [0, 0];
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ code: "SUCCESS", data }),
      });
    });
    // 其余请求（hot-topics、account overview 等）放行到真实 dev server 或落空
    await page.route("**/api/v1/posts/hot-topics**", (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ code: "SUCCESS", data: [] }) }));

    await page.goto("/");
    // 等列表渲染出内容
    await expect(page.getByText(`请求计数测试帖 0`)).toBeVisible();
    await expect(page.getByText(`请求计数测试帖 ${POST_COUNT - 1}`)).toBeVisible();
    // 等批量请求发生
    await expect.poll(() => batchRequests.length).toBeGreaterThan(0);

    // 核心断言 1：零次逐帖互动请求（这是 N+1 的根源，也是本 spec 的主题）
    expect(perPostRequests).toEqual([]);

    // 核心断言 2：恰好一次批量请求覆盖整页
    expect(batchRequests.length).toBe(1);

    // 注：不断言列表请求数——首页本身有多个 fetchPosts 调用方
    // （Feed 主列表 + 右栏"热门话题"聚合），那条路径的请求数不是 H-2 的范围。
    // 本 spec 的主题是"互动状态的请求数不随帖子数增长"。
  });

  test("批量请求带上整页全部帖子 id", async ({ page }) => {
    let requestedIds: string[] = [];
    await page.route("**/api/v1/posts?**", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ code: "SUCCESS", data: posts(POST_COUNT), meta: { page: 1, pageSize: 20, totalItems: POST_COUNT, totalPages: 1 } }),
      }),
    );
    await page.route("**/api/v1/interactions/batch-states**", (route) => {
      requestedIds = new URL(route.request().url()).searchParams.get("ids")?.split(",") ?? [];
      const data: Record<string, [number, number]> = {};
      for (const id of requestedIds) data[id] = [0, 0];
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ code: "SUCCESS", data }),
      });
    });

    await page.goto("/");
    await expect(page.getByText(`请求计数测试帖 0`)).toBeVisible();
    await expect.poll(() => requestedIds.length).toBe(POST_COUNT);

    // 整页每帖都在一次请求里，一个都不能少
    expect(new Set(requestedIds)).toEqual(new Set(posts(POST_COUNT).map((post) => post.id)));
  });

  test("批量互动请求失败时页面仍可渲染（降级不回退到逐帖请求）", async ({ page }) => {
    const perPostRequests = countRequests(page, /\/api\/v1\/posts\/[^/]+\/interactions(?!\/)/);

    await page.route("**/api/v1/posts?**", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ code: "SUCCESS", data: posts(POST_COUNT), meta: { page: 1, pageSize: 20, totalItems: POST_COUNT, totalPages: 1 } }),
      }),
    );
    // 批量接口整体故障
    await page.route("**/api/v1/interactions/batch-states**", (route) => route.abort("failed"));

    await page.goto("/");

    // 帖子照样渲染（互动状态降级为本地 demo，不断渲染）
    await expect(page.getByText(`请求计数测试帖 0`)).toBeVisible();
    await expect(page.getByText(`请求计数测试帖 ${POST_COUNT - 1}`)).toBeVisible();

    // 失败也不能退回逐帖请求——那会把一次失败放大成 N 次失败
    await page.waitForTimeout(1000);
    expect(perPostRequests).toEqual([]);
  });
});
