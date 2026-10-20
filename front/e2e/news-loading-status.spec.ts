import { expect, test } from "@playwright/test";

test("资讯列表等待 API 时播报加载状态并显示响应条目", async ({ page }) => {
  let releaseNewsResponse = () => {};
  const responseGate = new Promise<void>((resolve) => {
    releaseNewsResponse = resolve;
  });

  await page.route(/\/api\/v1\/news(?:\?.*)?$/, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() !== "GET" || url.pathname !== "/api/v1/news") {
      await route.continue();
      return;
    }

    await responseGate;
    await route.fulfill({
      json: {
        code: "SUCCESS",
        data: [
          {
            id: "50000000-0000-0000-0000-000000000021",
            slug: "loading-status-preview",
            title: "加载状态验证资讯",
            category: "版本前瞻",
            summary: "验证加载完成后的资讯内容。",
            date: "2026-10-19",
            hot: false,
            content: ["资讯正文。"],
          },
        ],
        meta: { page: 1, pageSize: 20, totalItems: 1, totalPages: 1 },
      },
    });
  });

  await page.goto("/news");

  await expect(page.getByRole("status")).toHaveText("正在整理资讯…");
  releaseNewsResponse();
  await expect(page.getByRole("heading", { name: "加载状态验证资讯" })).toBeVisible();
});
