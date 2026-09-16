import { expect, test } from "@playwright/test";

const postUrl = "/guides/10000000-0000-0000-0000-000000000001";

test("帖子详情桌面端保留两侧 sticky 阅读辅助栏与帖子目录", async ({ page }) => {
  await page.route("http://localhost:8080/api/v1/posts/10000000-0000-0000-0000-000000000001", (route) => route.fulfill({
    json: {
      data: {
        id: "10000000-0000-0000-0000-000000000001",
        type: "GUIDE",
        category: "配队攻略",
        title: "长离焚火队：从零到毕业的配队思路",
        excerpt: "围绕共鸣效率、轮切节奏与副 C 选择，整理一套能直接照着练的实战框架。",
        content: "# 详情接口封面测试\n\n用于验证帖子详情优先使用后端返回的媒体。\n\n## 一、先确定队伍节奏\n\n先完成一轮稳定循环。\n\n## 二、角色与声骸选择\n\n优先保证协同角色覆盖空窗期。\n\n## 三、实战检查清单\n\n进入战斗前确认资源分配。",
        coverImageUrl: "/art/guide-tower.png",
        author: { id: "user-100", nickname: "潮声档案员", avatarUrl: null, bio: "记录鸣潮实战" },
        publishedAt: "2026-09-15T10:24:00",
        viewCount: 18000,
        likeCount: 3700,
        favoriteCount: 12,
        commentCount: 4,
        tags: ["配队", "实战思路", "长离"],
      },
    },
  }));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(postUrl);

  await expect(page.getByRole("heading", { name: "长离焚火队：从零到毕业的配队思路" })).toBeVisible();
  await expect(page.locator(".post-cover img")).toHaveAttribute("src", /guide-tower\.png/);
  await expect(page.getByRole("complementary", { name: "帖子互动" })).toHaveCSS("position", "sticky");
  await expect(page.getByRole("complementary", { name: "帖子上下文" })).toHaveCSS("position", "sticky");

  const outline = page.getByRole("navigation", { name: "帖子目录" });
  await expect(outline).toBeVisible();
  await expect(outline.getByRole("link", { name: "角色与声骸选择" })).toHaveAttribute("href", "#section-character-echoes");
  await outline.getByRole("link", { name: "角色与声骸选择" }).click();
  await expect(page.locator("#section-character-echoes")).toBeInViewport();

  await page.evaluate(() => window.scrollTo(0, 700));
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
  const reactionBox = await page.getByRole("complementary", { name: "帖子互动" }).boundingBox();
  const contextBox = await page.getByRole("complementary", { name: "帖子上下文" }).boundingBox();
  expect(reactionBox?.y).toBeGreaterThanOrEqual(70);
  expect(reactionBox?.y).toBeLessThan(120);
  expect(contextBox?.y).toBeGreaterThanOrEqual(70);
  expect(contextBox?.y).toBeLessThan(130);
});

test("帖子详情中等桌面隐藏右栏但保留左侧互动栏", async ({ page }) => {
  await page.setViewportSize({ width: 1024, height: 900 });
  await page.goto(postUrl);

  await expect(page.getByRole("complementary", { name: "帖子互动" })).toBeVisible();
  await expect(page.getByRole("complementary", { name: "帖子上下文" })).toBeHidden();
  const pageWidth = await page.evaluate(() => ({ client: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth }));
  expect(pageWidth.scroll).toBeLessThanOrEqual(pageWidth.client);
});

test("帖子详情移动端没有页面级横向滚动并保留互动操作条", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(postUrl);

  const pageWidth = await page.evaluate(() => ({
    client: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }));
  expect(pageWidth.scroll).toBeLessThanOrEqual(pageWidth.client);
  await expect(page.getByRole("complementary", { name: "帖子互动" })).toBeVisible();
  await expect(page.getByRole("complementary", { name: "帖子上下文" })).toBeHidden();
});

test("帖子详情可以渲染混合 Markdown，并且不为无标题正文生成失效目录", async ({ page }) => {
  await page.route("http://localhost:8080/api/v1/posts/plain-markdown", (route) => route.fulfill({
    json: {
      data: {
        id: "plain-markdown",
        type: "GENERAL",
        category: "心得",
        title: "混合 Markdown 帖子",
        excerpt: "验证正文格式展示。",
        content: "# 混合 Markdown 标题\n正文里的 **重点** 需要保留。\n\n> 一段引用\n\n1. 第一项\n2. 第二项",
        author: { id: "user-100", nickname: "潮声档案员", avatarUrl: null, bio: "记录鸣潮实战" },
        publishedAt: "2026-09-15T10:24:00",
        viewCount: 12,
        likeCount: 3,
        favoriteCount: 1,
        commentCount: 0,
        tags: ["心得"],
      },
    },
  }));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/guides/plain-markdown");

  await expect(page.getByRole("heading", { name: "混合 Markdown 标题", level: 1 })).toBeVisible();
  await expect(page.locator(".post-detail-body strong").filter({ hasText: "重点" })).toHaveCount(1);
  await expect(page.locator(".post-detail-body blockquote")).toHaveText("一段引用");
  await expect(page.locator(".post-detail-body ol li")).toHaveCount(2);
  await expect(page.locator(".post-outline")).toHaveCount(1);
  await expect(page.locator(".post-outline li")).toHaveCount(1);
});

test("帖子详情的无标题正文不生成失效目录", async ({ page }) => {
  await page.route("**/api/v1/posts/plain-markdown-no-heading**", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ data: {
        id: "plain-markdown-no-heading", type: "GENERAL", category: "心得", title: "无标题 Markdown 帖子",
        excerpt: "只有正文的帖子", content: "正文没有标题，因此不应该出现空目录。\n\n**重点** 仍然需要保留。", coverImageUrl: null,
        author: { id: "author-plain", nickname: "潮声档案员" }, publishedAt: "2026-09-15T10:24:00Z", viewCount: 12, commentCount: 0, likeCount: 0,
        tags: ["心得"],
      } }),
    });
  });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/guides/plain-markdown-no-heading");
  await expect(page.locator(".post-detail-body strong").filter({ hasText: "重点" })).toHaveCount(1);
  await expect(page.locator(".post-outline")).toHaveCount(0);
});
