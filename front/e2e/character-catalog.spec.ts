import { expect, test } from "@playwright/test";

const character = {
  id: "character-1",
  slug: "shorekeeper",
  name: "守岸人",
  role: "辅助",
  rarity: 5,
  attribute: "衍射",
  weaponType: "音感仪",
  version: "1.3",
  imageUrl: "/art/修-守岸人 唤取动画.png",
  description: "稳定队伍循环并提供持续支援。",
};

test("角色图鉴从真实 API 加载列表并进入详情", async ({ page }) => {
  await page.route("http://localhost:8080/api/v1/characters?*", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        data: [character],
        meta: { page: 1, pageSize: 12, totalItems: 1, totalPages: 1 },
      }),
    });
  });
  await page.route("http://localhost:8080/api/v1/characters/shorekeeper", async (route) => {
    await route.fulfill({ contentType: "application/json", body: JSON.stringify({ data: character }) });
  });

  await page.goto("/characters");
  await expect(page.getByRole("heading", { name: "守岸人" })).toBeVisible();
  await expect(page.getByText("衍射 · 音感仪 · 1.3")).toBeVisible();
  await page.getByRole("link", { name: "查看角色资料" }).click();

  await expect(page).toHaveURL(/\/characters\/shorekeeper$/);
  await expect(page.getByRole("heading", { name: "守岸人" })).toBeVisible();
  await expect(page.getByText("当前页面展示已发布的基础角色资料")).toBeVisible();
});

test("角色图鉴 API 失败时显示明确错误状态而不是 Mock 内容", async ({ page }) => {
  await page.route("http://localhost:8080/api/v1/characters?*", (route) => route.fulfill({ status: 503, body: "unavailable" }));
  await page.goto("/characters");

  await expect(page.getByText("角色资料暂时无法加载，请检查后端服务。")).toBeVisible();
  await expect(page.getByText("守岸人")).toHaveCount(0);
});
