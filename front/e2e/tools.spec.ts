import { expect, test } from "@playwright/test";

const characters = [
  {
    id: "character-1",
    slug: "shorekeeper",
    name: "API 守岸人",
    role: "辅助",
    rarity: 5,
    attribute: "衍射",
    weaponType: "音感仪",
    version: "1.3",
    imageUrl: "/art/修-守岸人 唤取动画.webp",
    description: "稳定队伍循环并提供持续支援。",
  },
  {
    id: "character-2",
    slug: "augustus",
    name: "API 奥古斯都",
    role: "输出",
    rarity: 5,
    attribute: "湮灭",
    weaponType: "长刃",
    version: "3.6",
    imageUrl: "/art/修-奥古斯都  唤取动画.webp",
    description: "围绕技能循环和爆发窗口展开输出。",
  },
];

test("配队模拟器展示 API 游戏角色并允许将其加入队伍", async ({ page }) => {
  await page.route("**/api/v1/characters?*", (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        data: characters,
        meta: { page: 1, pageSize: 50, totalItems: 2, totalPages: 1 },
      }),
    }),
  );

  await page.goto("/tools/team-builder");

  await expect(page.getByRole("button", { name: "API 守岸人" })).toBeVisible();
  await expect(page.getByRole("button", { name: "守岸人", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "API 守岸人" }).click();
  await expect(page.locator(".team-slot").first()).toHaveText("API 守岸人");
});

test("配队模拟器在等待角色 API 时显示加载状态", async ({ page }) => {
  let releaseResponse: (() => void) | undefined;
  const responseGate = new Promise<void>((resolve) => {
    releaseResponse = resolve;
  });
  await page.route("**/api/v1/characters?*", async (route) => {
    await responseGate;
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ data: characters, meta: { page: 1, pageSize: 50, totalItems: 2, totalPages: 1 } }),
    });
  });

  await page.goto("/tools/team-builder");
  await expect(page.getByText("角色列表加载中…")).toBeVisible();
  releaseResponse?.();
  await expect(page.getByRole("button", { name: "API 守岸人" })).toBeVisible();
});

test("配队模拟器 API 失败时显示错误和重试，不展示 Mock 游戏角色", async ({ page }) => {
  let requestCount = 0;
  await page.route("**/api/v1/characters?*", (route) => {
    requestCount += 1;
    if (requestCount === 1) return route.fulfill({ status: 503, body: "unavailable" });
    return route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        data: characters,
        meta: { page: 1, pageSize: 50, totalItems: 2, totalPages: 1 },
      }),
    });
  });

  await page.goto("/tools/team-builder");
  await expect(page.getByText("角色资料暂时无法加载，请检查后端服务。")).toBeVisible();
  await expect(page.getByRole("button", { name: "守岸人", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "重试" }).click();
  await expect(page.getByRole("button", { name: "API 守岸人" })).toBeVisible();
  expect(requestCount).toBe(2);
});

test("配队模拟器在角色 API 返回空列表时显示空状态", async ({ page }) => {
  await page.route("**/api/v1/characters?*", (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ data: [], meta: { page: 1, pageSize: 50, totalItems: 0, totalPages: 0 } }),
    }),
  );

  await page.goto("/tools/team-builder");

  await expect(page.getByText("暂无可用于配队的角色。")).toBeVisible();
});

test("工具目录和养成计算器不会请求配队用游戏角色数据", async ({ page }) => {
  let requestCount = 0;
  await page.route("**/api/v1/characters?*", (route) => {
    requestCount += 1;
    return route.fulfill({ contentType: "application/json", body: JSON.stringify({ data: [], meta: {} }) });
  });

  await page.goto("/tools");
  await page.goto("/tools/calculator");

  expect(requestCount).toBe(0);
});
