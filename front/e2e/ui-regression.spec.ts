import { expect, test } from "@playwright/test";

const coreRoutes = ["/", "/guides/10000000-0000-0000-0000-000000000001", "/profile", "/publish"];
const targetViewports = [
  { name: "desktop", width: 1440, height: 1000 },
  { name: "wide-tablet", width: 1280, height: 900 },
  { name: "tablet", width: 1024, height: 900 },
  { name: "narrow-desktop", width: 768, height: 900 },
  { name: "mobile", width: 390, height: 844 },
];

for (const route of coreRoutes) {
  for (const viewport of targetViewports) {
    test(`${route} 在 ${viewport.name} 不产生页面级横向滚动`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await page.goto(route);
      const dimensions = await page.evaluate(() => ({
        clientWidth: document.documentElement.clientWidth,
        scrollWidth: document.documentElement.scrollWidth,
      }));
      expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth);
    });
  }
}

test("窄桌面频道抽屉可以用键盘打开并关闭", async ({ page }) => {
  await page.setViewportSize({ width: 768, height: 900 });
  await page.goto("/guides");

  const openButton = page.getByRole("button", { name: "打开导航" });
  await openButton.focus();
  await page.keyboard.press("Enter");
  await expect(page.locator(".drawer-sidebar")).toBeVisible();
  await expect(page.getByRole("dialog", { name: "社区频道" }).getByRole("button", { name: "关闭导航" })).toBeFocused();
  await page.keyboard.press("Tab");
  await expect.poll(() => page.evaluate(() => Boolean(document.activeElement?.closest(".mobile-drawer")))).toBe(true);
  await page.keyboard.press("Escape");
  await expect(page.locator(".drawer-sidebar")).toBeHidden();
  await expect(openButton).toBeFocused();
});

test("更多菜单可以用键盘打开、关闭并恢复焦点", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");

  const moreButton = page.getByRole("button", { name: "更多", exact: true });
  await moreButton.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("menu")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("menu")).toBeHidden();
  await expect(moreButton).toBeFocused();
});

test("登录弹窗打开后焦点留在弹窗内，Escape 关闭并恢复触发焦点", async ({ page }) => {
  await page.route("**/api/v1/auth/me", (route) => route.fulfill({ status: 401 }));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");

  const loginButton = page.getByRole("button", { name: "登录" });
  await loginButton.focus();
  await page.keyboard.press("Enter");
  const dialog = page.getByRole("dialog", { name: "登录鸣潮社区" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("button", { name: "关闭登录" })).toBeFocused();
  await page.keyboard.press("Tab");
  await expect.poll(() => page.evaluate(() => Boolean(document.activeElement?.closest(".login-dialog")))).toBe(true);
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await expect(loginButton).toBeFocused();
});

test("减少动画偏好下发布编辑器设置滚动使用 auto", async ({ page }) => {
  await page.route("**/api/v1/auth/me", (route) => route.fulfill({ json: { data: {
    id: "user-100", phone: "13800000001", nickname: "潮声档案员", avatarUrl: null, bio: "记录鸣潮实战", role: "USER",
  } } }));
  await page.addInitScript(() => {
    const original = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = function (options) {
      const values = (window as typeof window & { scrollBehaviors?: unknown[] }).scrollBehaviors ?? [];
      values.push(typeof options === "object" && options ? (options as ScrollIntoViewOptions).behavior : undefined);
      (window as typeof window & { scrollBehaviors?: unknown[] }).scrollBehaviors = values;
      original.call(this, options);
    };
  });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/publish");
  await page.getByRole("button", { name: "帖子设置" }).click();
  await expect.poll(() => page.evaluate(() => (window as typeof window & { scrollBehaviors?: unknown[] }).scrollBehaviors?.at(-1))).toBe("auto");
});

test("减少动画偏好下帖子互动滚动使用 auto", async ({ page }) => {
  await page.addInitScript(() => {
    const original = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = function (options) {
      const values = (window as typeof window & { scrollBehaviors?: unknown[] }).scrollBehaviors ?? [];
      values.push(typeof options === "object" && options ? (options as ScrollIntoViewOptions).behavior : undefined);
      (window as typeof window & { scrollBehaviors?: unknown[] }).scrollBehaviors = values;
      original.call(this, options);
    };
  });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/guides/10000000-0000-0000-0000-000000000001");

  await page.getByRole("button", { name: /查看 .* 条评论/ }).click();
  await expect.poll(() => page.evaluate(() => (window as typeof window & { scrollBehaviors?: unknown[] }).scrollBehaviors?.at(-1))).toBe("auto");
});

test("桌面端已登录账户区清晰区分个人中心与退出", async ({ page }) => {
  await page.route("**/api/v1/auth/me", (route) => route.fulfill({ json: { data: {
    id: "user-100", phone: "13800000001", nickname: "潮声档案员", avatarUrl: null, bio: "记录鸣潮实战", role: "USER",
  } } }));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");
  const avatarLink = page.getByRole("link", { name: "打开个人中心" });
  const profileLink = page.getByRole("link", { name: "个人中心", exact: true });
  const logoutButton = page.getByRole("button", { name: "退出登录" });
  await expect(profileLink).toBeVisible();
  await expect(logoutButton).toBeVisible();
  await expect(profileLink).toHaveAttribute("href", "/profile");
  await avatarLink.focus();
  await page.keyboard.press("Tab");
  await expect(profileLink).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(logoutButton).toBeFocused();
});

test("评论编辑器提供表情、图片和提及工具", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/guides/10000000-0000-0000-0000-000000000001");
  const composer = page.getByRole("region", { name: "评论区" });
  const textarea = composer.getByRole("textbox", { name: "评论内容" });
  await expect(composer.getByRole("button", { name: "插入表情" })).toBeVisible();
  await expect(composer.getByRole("button", { name: "添加图片" })).toBeVisible();
  await expect(composer.getByRole("button", { name: "提及用户" })).toBeVisible();
  await textarea.focus();
  await page.keyboard.press("Tab");
  await expect(composer.getByRole("button", { name: "插入表情" })).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(composer.getByRole("button", { name: "添加图片" })).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(composer.getByRole("button", { name: "提及用户" })).toBeFocused();
  await textarea.fill("准备出发 ");
  await composer.getByRole("button", { name: "插入表情" }).click();
  const emojiPicker = composer.getByRole("menu", { name: "常用表情" });
  await expect(emojiPicker).toBeVisible();
  const pickerBox = await emojiPicker.boundingBox();
  const toolsBox = await composer.locator(".comment-composer-tools").boundingBox();
  expect(pickerBox).not.toBeNull();
  expect(toolsBox).not.toBeNull();
  expect((pickerBox?.y ?? 0) + (pickerBox?.height ?? 0)).toBeLessThanOrEqual((toolsBox?.y ?? 0) + 1);
  await composer.getByRole("menuitem", { name: "插入🙂" }).click();
  await expect(textarea).toHaveValue(/准备出发/);
  await composer.getByRole("button", { name: "提及用户" }).click();
  await expect(textarea).toHaveValue(/准备出发 .*@$/);
  const chooserPromise = page.waitForEvent("filechooser");
  await composer.getByRole("button", { name: "添加图片" }).click();
  const chooser = await chooserPromise;
  await chooser.setFiles({ name: "echo.png", mimeType: "image/png", buffer: Buffer.from("demo") });
  await expect(page.getByRole("status")).toContainText("评论暂不支持图片附件");
});
