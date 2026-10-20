import { expect, test } from "@playwright/test";

test("攻略筛选无结果时将空状态播报为状态消息", async ({ page }) => {
  await page.route(/\/api\/v1\/posts(?:\?.*)?$/, async (route) => {
    if (route.request().method() !== "GET") {
      await route.continue();
      return;
    }

    await route.fulfill({
      json: {
        code: "SUCCESS",
        data: [],
        meta: { page: 1, pageSize: 10, totalItems: 0, totalPages: 0 },
      },
    });
  });

  await page.goto("/guides");

  await expect(page.getByRole("status")).toHaveText("没有找到匹配的攻略。");
});
