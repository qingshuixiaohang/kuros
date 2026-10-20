import { expect, test } from "@playwright/test";

test("攻略列表等待 API 时播报加载状态并显示响应条目", async ({ page }) => {
  let releaseResponse = () => {};
  let rightRailRequestPassedThrough = false;
  const responseGate = new Promise<void>((resolve) => {
    releaseResponse = resolve;
  });

  await page.route(/\/api\/v1\/posts(?:\?.*)?$/, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() !== "GET" || url.pathname !== "/api/v1/posts" || url.searchParams.get("pageSize") !== "10") {
      if (request.method() === "GET" && url.pathname === "/api/v1/posts" && url.searchParams.get("pageSize") === "40") {
        rightRailRequestPassedThrough = true;
      }
      await route.continue();
      return;
    }

    await responseGate;
    await route.fulfill({
      json: {
        code: "SUCCESS",
        data: [
          {
            id: "guide-loading-status",
            type: "GUIDE",
            category: "配队攻略",
            title: "攻略加载验证帖子",
            excerpt: "验证攻略加载完成。",
            author: { id: "author-1", nickname: "攻略作者", avatarUrl: null, bio: null },
            publishedAt: "2026-10-19T10:00:00",
            viewCount: 1,
            likeCount: 0,
            favoriteCount: 0,
            commentCount: 0,
            tags: [],
          },
        ],
        meta: { page: 1, pageSize: 10, totalItems: 1, totalPages: 1 },
      },
    });
  });

  await page.goto("/guides");

  const loadingStatus = page.locator('.result-count[role="status"]');
  await expect(loadingStatus).toHaveText("正在同步内容…");
  releaseResponse();
  await expect(page.getByRole("heading", { name: "攻略加载验证帖子" })).toBeVisible();
  expect(rightRailRequestPassedThrough).toBe(true);
});
