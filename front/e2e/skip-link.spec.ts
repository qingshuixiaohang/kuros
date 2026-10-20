import { expect, test } from "@playwright/test";

test("键盘用户可跳过频道导航并聚焦主要内容", async ({ page }) => {
  for (const path of ["/", "/guides"]) {
    await page.goto(path);
    await page.keyboard.press("Tab");

    const skipLink = page.getByRole("link", { name: "跳到主要内容" });
    await expect(skipLink).toBeFocused();
    await expect(skipLink).toBeInViewport();

    await page.keyboard.press("Enter");

    const mainContent = page.locator("#main-content");
    await expect(page).toHaveURL(/#main-content$/);
    await expect(mainContent).toBeFocused();

    await page.keyboard.press("Tab");
    await expect.poll(() => mainContent.evaluate((element) => element.contains(document.activeElement))).toBe(true);
  }
});
