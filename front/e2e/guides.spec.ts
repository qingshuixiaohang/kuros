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

test("攻略列表筛选分类后翻页时保留分类并展示对应内容", async ({ page }) => {
  await page.route(/\/api\/v1\/posts(?:\?.*)?$/, async (route) => {
    const url = new URL(route.request().url());
    const pageNumber = Number(url.searchParams.get("page") ?? "1");
    const pageSize = Number(url.searchParams.get("pageSize") ?? "20");
    const category = url.searchParams.get("category");

    const items =
      pageSize !== 10
        ? []
        : category === "深塔攻略"
          ? [guide(`tower-page-${pageNumber}`, pageNumber === 1 ? "深塔攻略第一页" : "深塔攻略第二页", "深塔攻略")]
          : [guide("team-page-1", "配队攻略第一页", "配队攻略")];

    await route.fulfill({
      json: {
        code: "SUCCESS",
        data: items,
        meta: { page: pageNumber, pageSize, totalItems: 20, totalPages: 2 },
      },
    });
  });

  await page.goto("/guides");
  const list = page.locator(".guide-list-page");
  await expect(list.getByRole("heading", { name: "配队攻略第一页" })).toBeVisible();

  const categoryRequest = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return url.pathname === "/api/v1/posts" && url.searchParams.get("category") === "深塔攻略";
  });
  await page.locator(".filter-tabs").getByRole("button", { name: "深塔攻略" }).click();
  const categoryUrl = new URL((await categoryRequest).url());
  expect(categoryUrl.searchParams.get("page")).toBe("1");
  await expect(list.getByRole("heading", { name: "深塔攻略第一页" })).toBeVisible();
  await expect(list.getByRole("heading", { name: "配队攻略第一页" })).toHaveCount(0);

  const secondPageRequest = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return url.pathname === "/api/v1/posts" && url.searchParams.get("page") === "2";
  });
  const pagination = page.getByRole("navigation", { name: "攻略分页" });
  await pagination.getByRole("button", { name: "2", exact: true }).click();
  const secondPageUrl = new URL((await secondPageRequest).url());
  expect(secondPageUrl.searchParams.get("category")).toBe("深塔攻略");
  await expect(pagination.getByRole("button", { name: "2", exact: true })).toHaveClass(/is-active/);
  await expect(list.getByRole("heading", { name: "深塔攻略第二页" })).toBeVisible();
  await expect(list.getByRole("heading", { name: "深塔攻略第一页" })).toHaveCount(0);
});
