import { expect, test } from "@playwright/test";

test("首页 Feed API 等待期间将加载文案播报为状态消息", async ({ page }) => {
  let rightRailRequestPassedThrough = false;
  let releaseFeedResponse = () => {};
  const feedResponseGate = new Promise<void>((resolve) => {
    releaseFeedResponse = resolve;
  });
  await page.route(/\/api\/v1\/posts(?:\?.*)?$/, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() !== "GET" || url.pathname !== "/api/v1/posts" || url.searchParams.get("pageSize") !== "20") {
      if (request.method() === "GET" && url.pathname === "/api/v1/posts" && url.searchParams.get("pageSize") === "40") {
        rightRailRequestPassedThrough = true;
      }
      await route.continue();
      return;
    }

    await feedResponseGate;
    await route.fulfill({
      json: {
        code: "SUCCESS",
        data: [
          {
            id: "feed-status-post",
            type: "GUIDE",
            category: "配队攻略",
            title: "首页加载验证帖子",
            excerpt: "首页加载验证摘要",
            author: { id: "author-1", nickname: "攻略作者", avatarUrl: null, bio: null },
            publishedAt: "2026-10-19T10:00:00",
            viewCount: 1,
            likeCount: 0,
            favoriteCount: 0,
            commentCount: 0,
            tags: [],
          },
        ],
        meta: { page: 1, pageSize: 20, totalItems: 1, totalPages: 1 },
      },
    });
  });

  await page.goto("/");

  await expect(page.getByRole("status")).toHaveText("正在整理漂泊者的最新内容…");
  releaseFeedResponse();
  await expect(page.getByRole("heading", { name: "首页加载验证帖子" })).toBeVisible();
  expect(rightRailRequestPassedThrough).toBe(true);
});
