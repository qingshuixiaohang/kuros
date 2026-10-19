import { expect, test, type Locator } from "@playwright/test";

const newsItems = [
  {
    id: "news-1",
    slug: "version-preview",
    title: "版本前瞻消息",
    category: "版本前瞻",
    summary: "版本前瞻摘要",
    date: "2026-10-01",
    hot: false,
    content: ["版本前瞻正文"],
  },
  {
    id: "news-2",
    slug: "official-notice",
    title: "官方公告消息",
    category: "官方公告",
    summary: "官方公告摘要",
    date: "2026-10-02",
    hot: false,
    content: ["官方公告正文"],
  },
];

async function expectOnlySelectedButtonIsPressed(group: Locator, selectedName: string) {
  const states = await group.getByRole("button").evaluateAll((buttons) => buttons.map((button) => button.getAttribute("aria-pressed")));
  expect(states.every((state) => state === "true" || state === "false")).toBe(true);
  expect(states.filter((state) => state === "true")).toHaveLength(1);
  await expect(group.getByRole("button", { name: selectedName })).toHaveAttribute("aria-pressed", "true");
}

test("资讯分类筛选播报组名和当前选项", async ({ page }) => {
  await page.route(/\/api\/v1\/news(?:\?.*)?$/, async (route) => {
    const url = new URL(route.request().url());
    if (route.request().method() !== "GET" || url.pathname !== "/api/v1/news") {
      await route.continue();
      return;
    }

    const category = url.searchParams.get("category");
    const items = category ? newsItems.filter((item) => item.category === category) : newsItems;
    await route.fulfill({
      json: {
        code: "SUCCESS",
        data: items,
        meta: { page: 1, pageSize: 20, totalItems: items.length, totalPages: 1 },
      },
    });
  });

  await page.goto("/news");

  const group = page.getByRole("group", { name: "资讯分类筛选" });
  await expectOnlySelectedButtonIsPressed(group, "全部");

  await group.getByRole("button", { name: "官方公告" }).click();

  await expectOnlySelectedButtonIsPressed(group, "官方公告");
  const list = page.locator(".news-page-list");
  await expect(list.getByRole("heading", { name: "官方公告消息" })).toBeVisible();
  await expect(list.getByRole("heading", { name: "版本前瞻消息" })).toHaveCount(0);
});
