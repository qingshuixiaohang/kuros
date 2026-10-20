import { expect, test } from "@playwright/test";

function guide(id: string, title: string, category: string) {
  return {
    id,
    type: "GUIDE",
    category,
    title,
    excerpt: `${title}摘要`,
    author: { id: "author-1", nickname: "攻略作者", avatarUrl: null, bio: null },
    publishedAt: "2026-10-19T10:00:00",
    viewCount: 12,
    likeCount: 3,
    favoriteCount: 1,
    commentCount: 2,
    tags: ["深塔"],
  };
}

test("攻略分类筛选暴露选择状态并更新列表", async ({ page }) => {
  await page.route(/\/api\/v1\/posts(?:\?.*)?$/, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() !== "GET" || url.pathname !== "/api/v1/posts") {
      await route.continue();
      return;
    }

    const category = url.searchParams.get("category");
    const items = category === "角色培养" ? [guide("character-guide", "角色培养指南", "角色培养")] : [guide("team-guide", "配队攻略指南", "配队攻略")];
    await route.fulfill({
      json: {
        code: "SUCCESS",
        data: items,
        meta: { page: 1, pageSize: 10, totalItems: items.length, totalPages: 1 },
      },
    });
  });

  await page.goto("/guides");

  const group = page.getByRole("group", { name: "攻略分类筛选" });
  const filters = group.getByRole("button");
  await expect(filters).toHaveCount(5);
  await expect
    .poll(() => filters.evaluateAll((buttons) => buttons.every((button) => ["true", "false"].includes(button.getAttribute("aria-pressed") ?? ""))))
    .toBe(true);
  await expect.poll(() => filters.evaluateAll((buttons) => buttons.filter((button) => button.getAttribute("aria-pressed") === "true").length)).toBe(1);
  await expect(group.getByRole("button", { name: "全部" })).toHaveAttribute("aria-pressed", "true");
  await expect(group.getByRole("button", { name: "角色培养" })).toHaveAttribute("aria-pressed", "false");
  await expect(page.getByRole("heading", { name: "配队攻略指南" })).toBeVisible();

  await group.getByRole("button", { name: "角色培养" }).click();

  await expect
    .poll(() => filters.evaluateAll((buttons) => buttons.every((button) => ["true", "false"].includes(button.getAttribute("aria-pressed") ?? ""))))
    .toBe(true);
  await expect.poll(() => filters.evaluateAll((buttons) => buttons.filter((button) => button.getAttribute("aria-pressed") === "true").length)).toBe(1);
  await expect(group.getByRole("button", { name: "角色培养" })).toHaveAttribute("aria-pressed", "true");
  await expect(group.getByRole("button", { name: "全部" })).toHaveAttribute("aria-pressed", "false");
  await expect(page.getByRole("heading", { name: "角色培养指南" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "配队攻略指南" })).toHaveCount(0);
});
