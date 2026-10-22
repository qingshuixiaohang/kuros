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
  imageUrl: "/art/修-守岸人 唤取动画.webp",
  description: "稳定队伍循环并提供持续支援。",
};

test("角色图鉴从真实 API 加载列表并进入详情", async ({ page }) => {
  await page.route("**/api/v1/characters?*", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        data: [character],
        meta: { page: 1, pageSize: 12, totalItems: 1, totalPages: 1 },
      }),
    });
  });
  await page.route("**/api/v1/characters/shorekeeper", async (route) => {
    await route.fulfill({ contentType: "application/json", body: JSON.stringify({ data: character }) });
  });

  await page.goto("/characters");
  await expect(page.getByRole("heading", { name: "守岸人" })).toBeVisible();
  // 断言限定在卡片内：筛选 tab 按钮也含“衍射”等文本，避免 strict mode 多匹配
  const card = page.locator(".character-card");
  await expect(card).toHaveCount(1);
  await expect(card.getByText("衍射", { exact: true })).toBeVisible();
  await expect(card.getByText("音感仪", { exact: true })).toBeVisible();
  await expect(card.getByText("5★", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: "查看角色资料" }).click();

  await expect(page).toHaveURL(/\/characters\/shorekeeper$/);
  await expect(page.getByRole("heading", { name: "守岸人" })).toBeVisible();
  await expect(page.getByText("基础资料")).toBeVisible();
  await expect(page.getByText("当前页面展示已发布的基础角色资料")).toBeVisible();
});

test("角色图鉴 API 失败时显示明确错误状态而不是 Mock 内容", async ({ page }) => {
  let unavailable = true;
  await page.route("**/api/v1/characters?*", (route) =>
    unavailable
      ? route.fulfill({ status: 503, body: "unavailable" })
      : route.fulfill({ json: { data: [character], meta: { page: 1, pageSize: 12, totalItems: 1, totalPages: 1 } } }),
  );
  await page.goto("/characters");

  await expect(page.getByText("角色资料暂时无法加载，请检查后端服务。")).toBeVisible();
  await expect(page.getByText("守岸人")).toHaveCount(0);
  unavailable = false;
  await page.getByRole("button", { name: "重新加载" }).click();
  await expect(page.getByRole("heading", { name: "守岸人" })).toBeVisible();
});

test("角色详情接口失败后可重试恢复资料", async ({ page }) => {
  let unavailable = true;
  await page.route("**/api/v1/characters/shorekeeper", (route) =>
    unavailable ? route.fulfill({ status: 500, body: "boom" }) : route.fulfill({ json: { data: character } }),
  );
  await page.goto("/characters/shorekeeper");
  await expect(page.getByText("角色资料暂时无法加载，请稍后重试。")).toBeVisible();
  await expect(page.getByText("角色不存在或已下架。")).toHaveCount(0);
  unavailable = false;
  await page.getByRole("button", { name: "重新加载" }).click();
  await expect(page.getByRole("heading", { name: "守岸人", level: 1 })).toBeVisible();
});

test("角色详情业务404显示不存在且不提供故障重试", async ({ page }) => {
  await page.route("**/api/v1/characters/shorekeeper", (route) =>
    route.fulfill({ status: 404, json: { code: "CHARACTER_NOT_FOUND", message: "角色不存在或已下架" } }),
  );
  await page.goto("/characters/shorekeeper");
  const detail = page.locator("article.detail-page");
  await expect(detail.getByText("角色不存在或已下架。")).toBeVisible();
  await expect(detail.getByRole("button", { name: "重新加载" })).toHaveCount(0);
});

test("角色详情等待响应时只显示加载状态", async ({ page }) => {
  let release!: () => void;
  const pending = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/v1/characters/shorekeeper", async (route) => {
    await pending;
    await route.fulfill({ json: { data: character } });
  });
  try {
    await page.goto("/characters/shorekeeper");
    const detail = page.locator("article.detail-page");
    await expect(detail.getByText("正在加载角色资料…")).toBeVisible();
    await expect(detail.getByText("角色不存在或已下架。")).toHaveCount(0);
    await expect(detail.getByText("角色资料暂时无法加载，请稍后重试。")).toHaveCount(0);
  } finally {
    release();
  }
  await expect(page.getByRole("heading", { name: "守岸人", level: 1 })).toBeVisible();
});

for (const failure of ["断网", "非业务404"]) {
  test(`角色详情${failure}显示可恢复故障`, async ({ page }) => {
    let unavailable = true;
    await page.route("**/api/v1/characters/shorekeeper", (route) => {
      if (!unavailable) return route.fulfill({ json: { data: character } });
      return failure === "断网" ? route.abort("failed") : route.fulfill({ status: 404, json: { code: "ROUTE_NOT_FOUND" } });
    });
    await page.goto("/characters/shorekeeper");
    await expect(page.getByText("角色资料暂时无法加载，请稍后重试。")).toBeVisible();
    await expect(page.getByText("角色不存在或已下架。")).toHaveCount(0);
    unavailable = false;
    await page.getByRole("button", { name: "重新加载" }).click();
    await expect(page.getByRole("heading", { name: "守岸人", level: 1 })).toBeVisible();
  });
}
