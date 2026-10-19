import { expect, test, type Locator } from "@playwright/test";

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

async function expectOnlySelectedButtonIsPressed(group: Locator, selectedName: string) {
  const states = await group.getByRole("button").evaluateAll((buttons) => buttons.map((button) => button.getAttribute("aria-pressed")));
  expect(states.every((state) => state === "true" || state === "false")).toBe(true);
  expect(states.filter((state) => state === "true")).toHaveLength(1);
  await expect(group.getByRole("button", { name: selectedName })).toHaveAttribute("aria-pressed", "true");
}

test("角色图鉴筛选组播报名称和当前选项", async ({ page }) => {
  await page.route(/\/api\/v1\/characters\?.*$/, async (route) => {
    const url = new URL(route.request().url());
    if (route.request().method() !== "GET" || url.pathname !== "/api/v1/characters") {
      await route.continue();
      return;
    }

    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        data: [character],
        meta: { page: 1, pageSize: 12, totalItems: 1, totalPages: 1 },
      }),
    });
  });

  await page.goto("/characters");

  const roleGroup = page.getByRole("group", { name: "角色定位筛选" });
  const attributeGroup = page.getByRole("group", { name: "属性筛选" });
  const weaponGroup = page.getByRole("group", { name: "武器筛选" });
  const rarityGroup = page.getByRole("group", { name: "稀有度筛选" });

  await expectOnlySelectedButtonIsPressed(roleGroup, "全部角色");
  await expectOnlySelectedButtonIsPressed(attributeGroup, "全部属性");
  await expectOnlySelectedButtonIsPressed(weaponGroup, "全部武器");
  await expectOnlySelectedButtonIsPressed(rarityGroup, "全部稀有度");

  await roleGroup.getByRole("button", { name: "输出" }).click();

  await expectOnlySelectedButtonIsPressed(roleGroup, "输出");
});
