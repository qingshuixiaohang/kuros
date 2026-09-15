import { expect, test } from "@playwright/test";

for (const viewport of [
  { name: "desktop", width: 1440, height: 1000 },
  { name: "tablet", width: 1024, height: 900 },
  { name: "mobile", width: 390, height: 844 },
]) {
  test(`首页在 ${viewport.name} 视口没有页面级横向滚动`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto("/");

    const pageWidth = await page.evaluate(() => ({
      client: document.documentElement.clientWidth,
      scroll: document.documentElement.scrollWidth,
      overflowX: getComputedStyle(document.body).overflowX,
    }));

    expect(pageWidth.scroll).toBeLessThanOrEqual(pageWidth.client);
    expect(pageWidth.overflowX).not.toBe("clip");
  });
}

test("首页桌面端保留 320px 推荐工具栏，小屏时收起", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");

  const rail = page.getByRole("complementary", { name: "推荐与工具" });
  await expect(rail).toBeVisible();
  await expect(rail).toHaveCSS("width", "320px");

  await page.setViewportSize({ width: 1024, height: 900 });
  await expect(rail).toBeHidden();
});

test("首页快捷工具提供六个真实功能入口", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");

  const tools = page.getByRole("region", { name: "快捷工具" });
  await expect(tools.getByRole("list", { name: "工具列表" }).getByRole("listitem")).toHaveCount(6);
  await expect(tools.getByRole("link", { name: /角色图鉴/ })).toHaveAttribute("href", "/characters");
  await expect(tools.getByRole("link", { name: /版本资讯/ })).toHaveAttribute("href", "/news");
});

test("首页内容流在有配图时展示可访问的帖子媒体", async ({ page }) => {
  await page.route("http://localhost:8080/api/v1/posts?*", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        data: [{
          id: "media-post",
          type: "GUIDE",
          category: "配队攻略",
          title: "含图片的真实列表数据",
          excerpt: "列表摘要",
          coverImageUrl: "/art/guide-sword.png",
          author: { id: "author-1", nickname: "潮声档案员", avatarUrl: null, bio: null },
          publishedAt: "2026-09-16T10:00:00",
          viewCount: 10,
          likeCount: 3,
          favoriteCount: 1,
          commentCount: 2,
          tags: ["配队"],
        }],
        meta: { page: 1, pageSize: 20, totalItems: 1, totalPages: 1 },
      }),
    });
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");

  const feed = page.getByRole("region", { name: "社区内容流" });
  await expect(feed.getByText("正在整理漂泊者的最新内容…")).toBeHidden({ timeout: 10_000 });
  await expect(feed.getByRole("img", { name: /帖子配图/ }).first()).toBeVisible();
});

test("首页分享会将帖子的独立地址写入剪贴板", async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: (value: string) => { (window as typeof window & { sharedText?: string }).sharedText = value; return Promise.resolve(); } },
    });
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");

  const firstPost = page.locator(".post-card").first();
  const detailLink = firstPost.locator("h2").locator("..");
  const expectedUrl = new URL(await detailLink.getAttribute("href") ?? "", "http://localhost:3000").href;
  await firstPost.getByRole("button", { name: "分享" }).click();
  await expect.poll(() => page.evaluate(() => (window as typeof window & { sharedText?: string }).sharedText)).toBe(expectedUrl);
});
