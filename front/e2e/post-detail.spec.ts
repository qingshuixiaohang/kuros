import { expect, test } from "@playwright/test";

const postUrl = "/guides/10000000-0000-0000-0000-000000000001";

test("帖子详情桌面端保留两侧 sticky 阅读辅助栏与帖子目录", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(postUrl);

  await expect(page.getByRole("heading", { name: "长离焚火队：从零到毕业的配队思路" })).toBeVisible();
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
