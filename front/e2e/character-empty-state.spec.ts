import { expect, test } from "@playwright/test";

test("角色图鉴没有匹配角色时将空状态播报为状态消息", async ({ page }) => {
  await page.route(/\/api\/v1\/characters\?.*$/, async (route) => {
    const url = new URL(route.request().url());
    if (route.request().method() !== "GET" || url.pathname !== "/api/v1/characters") {
      await route.continue();
      return;
    }

    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ data: [], meta: { page: 1, pageSize: 12, totalItems: 0, totalPages: 0 } }),
    });
  });

  await page.goto("/characters");

  await expect(page.getByRole("status")).toHaveText("没有找到符合条件的角色。");
});
