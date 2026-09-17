import { expect, test } from "@playwright/test";

for (const viewport of [
  { name: "desktop", width: 1440, height: 1000 },
  { name: "wide-tablet", width: 1280, height: 900 },
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

test("首页快捷工具使用鸣潮主题图标素材", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");

  const tools = page.getByRole("region", { name: "快捷工具" });
  const expectedIcons = [
    ["养成计算器", "upgrade-calculator.png"],
    ["声骸图鉴", "echo-archive.png"],
    ["配队模拟", "team-builder.png"],
    ["角色图鉴", "character-archive.png"],
    ["版本资讯", "version-news.png"],
    ["新手指南", "exploration-map.png"],
  ] as const;

  for (const [title, fileName] of expectedIcons) {
    const image = tools.getByRole("link", { name: title }).locator("img");
    await expect(image).toHaveAttribute("src", new RegExp(fileName));
    await expect.poll(async () => image.evaluate((element) => (element as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
  }
});

test("工具箱页面使用对应的主题图标素材", async ({ page }) => {
  await page.goto("/tools");

  const expectedIcons = [
    ["养成计算器", "upgrade-calculator.png"],
    ["声骸图鉴", "echo-archive.png"],
    ["配队模拟", "team-builder.png"],
  ] as const;

  for (const [title, fileName] of expectedIcons) {
    const image = page.getByRole("link", { name: new RegExp(title) }).locator("img");
    await expect(image).toHaveAttribute("src", new RegExp(fileName));
    await expect.poll(async () => image.evaluate((element) => (element as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
  }
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

test("首页多图帖子可以通过轮播控件逐张查看", async ({ page }) => {
  await page.route("http://localhost:8080/api/v1/posts?*", (route) => route.abort());
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");

  const carousel = page.getByRole("region", { name: /长离焚火队.*帖子配图轮播/ });
  await expect(carousel).toBeVisible();
  await expect(carousel.getByRole("img")).toHaveAttribute("alt", /第 1 张/);
  await carousel.getByRole("button", { name: "下一张" }).click();
  await expect(carousel.getByRole("img")).toHaveAttribute("alt", /第 2 张/);
  await expect(carousel.getByRole("tab", { name: "第 2 张" })).toHaveAttribute("aria-current", "true");
});

test("首页 Banner 支持桌面端层叠轮播并在移动端收起侧卡", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");

  const banner = page.getByRole("region", { name: "社区头图轮播" });
  await expect(banner).toBeVisible();
  await expect(banner.locator(".community-banner-slide--current img")).toBeVisible();
  await expect(banner.locator(".community-banner-slide--previous")).toHaveCSS("opacity", "0.68");
  await banner.getByRole("button", { name: "下一张 Banner" }).click();
  await expect(banner.getByRole("tab", { name: "社区头图轮播第 2 张" })).toHaveAttribute("aria-current", "true");

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(banner.locator(".community-banner-slide--previous")).toHaveCSS("opacity", "0");
  await expect(banner.locator(".community-banner-slide--next")).toHaveCSS("opacity", "0");
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

test("窄桌面端可以通过菜单打开频道抽屉", async ({ page }) => {
  await page.setViewportSize({ width: 768, height: 900 });
  await page.goto("/guides");

  await expect(page.getByRole("button", { name: "打开导航" })).toBeVisible();
  await page.getByRole("button", { name: "打开导航" }).click();
  await expect(page.locator(".drawer-sidebar")).toBeVisible();
  await page.getByRole("button", { name: "关闭导航" }).click();
  await expect(page.locator(".drawer-sidebar")).toBeHidden();
});
