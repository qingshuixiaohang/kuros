import { expect, test, type Page } from "@playwright/test";
import { DETAIL_POST_ID, mockPostDetail } from "./helpers/post-detail-mock";

function comments(count: number) {
  return Array.from({ length: count }, (_, index) => ({
    id: `comment-${index + 1}`,
    parentId: null,
    author: { id: `author-${index + 1}`, nickname: `评论者${index + 1}`, avatarUrl: null },
    content: `分页评论 ${index + 1}`,
    deleted: false,
    likeCount: 0,
    createdAt: `2026-10-22T${String(10 + (index % 10)).padStart(2, "0")}:00:00`,
  }));
}

async function mockCommentPages(page: Page) {
  await mockPostDetail(page);
  const all = comments(25);
  await page.route(`**/api/v1/posts/${DETAIL_POST_ID}/comments?*`, (route) => {
    const url = new URL(route.request().url());
    const pageNumber = Number(url.searchParams.get("page"));
    const sort = url.searchParams.get("sort");
    const ordered = sort === "latest" ? [...all].reverse() : all;
    const start = (pageNumber - 1) * 20;
    return route.fulfill({
      json: {
        data: ordered.slice(start, start + 20),
        meta: { page: pageNumber, pageSize: 20, totalItems: ordered.length, totalPages: 2 },
      },
    });
  });
}

test("评论首屏 20 条可追加剩余 5 条且排序切换不串页", async ({ page }) => {
  await mockCommentPages(page);
  await page.goto(`/guides/${DETAIL_POST_ID}`);
  const section = page.getByRole("region", { name: "评论区" });

  await expect(section.locator(".comment-item")).toHaveCount(20);
  await section.getByRole("button", { name: "加载更多评论" }).click();
  await expect(section.locator(".comment-item")).toHaveCount(25);
  await expect(section.getByText("分页评论 1", { exact: true })).toHaveCount(1);
  await expect(section.getByText("分页评论 25", { exact: true })).toHaveCount(1);

  await section.getByRole("button", { name: "最新", exact: true }).click();
  await expect(section.locator(".comment-item")).toHaveCount(20);
  await expect(section.getByText("分页评论 25", { exact: true })).toBeVisible();
  await section.getByRole("button", { name: "加载更多评论" }).click();
  await expect(section.locator(".comment-item")).toHaveCount(25);
  await expect(section.getByText("分页评论 25", { exact: true })).toHaveCount(1);
  await expect(section.getByText("分页评论 1", { exact: true })).toHaveCount(1);
});

test("零评论显示明确空态而不是空白列表", async ({ page }) => {
  await mockPostDetail(page);
  await page.route(`**/api/v1/posts/${DETAIL_POST_ID}/comments?*`, (route) =>
    route.fulfill({ json: { data: [], meta: { page: 1, pageSize: 20, totalItems: 0, totalPages: 0 } } }),
  );
  await page.goto(`/guides/${DETAIL_POST_ID}`);

  await expect(page.getByRole("region", { name: "评论区" }).getByText("还没有评论，来抢沙发吧。")).toBeVisible();
});

test("举报超过 50 条可访问后续页并显示目标摘要与链接", async ({ page }) => {
  await page.route("**/api/v1/auth/me", (route) =>
    route.fulfill({
      json: { data: { id: "admin-1", nickname: "审核员", avatarUrl: null, role: "ADMIN" } },
    }),
  );
  const allReports = Array.from({ length: 51 }, (_, index) => ({
    id: `report-${index + 1}`,
    reporterId: `reporter-${index + 1}`,
    targetType: index % 2 === 0 ? "POST" : "COMMENT",
    targetId: index % 2 === 0 ? `post-${index + 1}` : `comment-${index + 1}`,
    targetTitle: `目标帖子 ${index + 1}`,
    targetExcerpt: `目标摘要 ${index + 1}`,
    targetPostId: `post-${index + 1}`,
    reason: "SPAM",
    status: "PENDING",
    handledBy: null,
    handledAt: null,
    handlingNote: null,
    createdAt: "2026-10-22T10:00:00",
  }));
  await page.route("**/api/v1/admin/reports**", (route) => {
    const pageNumber = Number(new URL(route.request().url()).searchParams.get("page") ?? "1");
    const start = (pageNumber - 1) * 20;
    return route.fulfill({
      json: {
        data: allReports.slice(start, start + 20),
        meta: { page: pageNumber, pageSize: 20, totalItems: 51, totalPages: 3 },
      },
    });
  });
  await page.goto("/admin/reports");

  await expect(page.locator(".report-row")).toHaveCount(20);
  await expect(page.getByText("目标帖子 1", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "查看目标：目标帖子 1", exact: true })).toHaveAttribute("href", "/guides/post-1");
  await page.getByRole("button", { name: "加载更多举报" }).click();
  await expect(page.locator(".report-row")).toHaveCount(40);
  await page.getByRole("button", { name: "加载更多举报" }).click();
  await expect(page.locator(".report-row")).toHaveCount(51);
  await expect(page.getByText("目标帖子 51", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "加载更多举报" })).toHaveCount(0);
});
