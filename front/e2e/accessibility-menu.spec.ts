import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/v1/auth/me", (route) => route.fulfill({ status: 401 }));
  await page.route("**/api/v1/posts?*", (route) =>
    route.fulfill({
      json: {
        data: [
          {
            id: "99999999-9999-9999-9999-000000000001",
            type: "GUIDE",
            category: "配队攻略",
            title: "键盘可操作的帖子",
            excerpt: "菜单交互验证",
            author: { id: "author-1", nickname: "测试作者", avatarUrl: null },
            publishedAt: "2026-10-22T10:00:00",
            viewCount: 10,
            likeCount: 0,
            favoriteCount: 0,
            commentCount: 0,
            tags: [],
            mediaUrls: [],
          },
        ],
        meta: { page: 1, pageSize: 20, totalItems: 1, totalPages: 1 },
      },
    }),
  );
  await page.route("**/api/v1/interactions/batch-states*", (route) => route.fulfill({ json: { data: { "99999999-9999-9999-9999-000000000001": [0, 0] } } }));
  await page.goto("/");
});

test("首页帖子菜单可用键盘打开、Escape 关闭并归还焦点", async ({ page }) => {
  const feed = page.getByRole("region", { name: "社区内容流" });
  await expect(feed.getByRole("heading", { name: "键盘可操作的帖子" })).toBeVisible();
  const trigger = feed.getByRole("button", { name: "更多操作" });
  await trigger.focus();
  await page.keyboard.press("Enter");
  const menu = feed.getByRole("menu", { name: "帖子操作" });
  await expect(trigger).toHaveAttribute("aria-haspopup", "menu");
  await expect(trigger).toHaveAttribute("aria-expanded", "true");
  await expect(menu.getByRole("menuitem", { name: "收藏帖子" })).toBeFocused();
  await expect(menu.getByRole("menuitem", { name: "举报反馈" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(menu).toBeHidden();
  await expect(trigger).toBeFocused();
  await expect(trigger).toHaveAttribute("aria-expanded", "false");
});

test("登录表单以手机号和验证码准确标识输入框", async ({ page }) => {
  await page.getByRole("button", { name: /登录$/ }).click();
  const dialog = page.getByRole("dialog", { name: "登录鸣潮社区" });
  const phone = dialog.getByRole("textbox", { name: "手机号", exact: true });
  const verification = dialog.getByRole("textbox", { name: "验证码", exact: true });
  await phone.fill("13800000001");
  await verification.fill("123456");
  await expect(phone).toHaveValue("13800000001");
  await expect(verification).toHaveValue("123456");
  await expect(dialog.getByRole("button", { name: "获取验证码", exact: true })).toBeVisible();
});

test("帖子菜单在 Tab 和外点关闭后都归还触发按钮焦点", async ({ page }) => {
  const feed = page.getByRole("region", { name: "社区内容流" });
  await expect(feed.getByRole("heading", { name: "键盘可操作的帖子" })).toBeVisible();
  const trigger = feed.getByRole("button", { name: "更多操作" });
  const menu = feed.getByRole("menu", { name: "帖子操作" });

  await trigger.click();
  await expect(menu.getByRole("menuitem", { name: "收藏帖子" })).toBeFocused();
  await page.keyboard.press("Shift+Tab");
  await expect(trigger).toBeFocused();
  await expect(menu).toBeHidden();

  await page.keyboard.press("Enter");
  await expect(menu.getByRole("menuitem", { name: "收藏帖子" })).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(menu.getByRole("menuitem", { name: "举报反馈" })).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(menu).toBeHidden();
  await expect(trigger).toBeFocused();

  await trigger.click();
  await expect(menu).toBeVisible();
  const latestTab = feed.getByRole("button", { name: "最新发布", exact: true });
  await latestTab.click();
  await expect(menu).toBeHidden();
  await expect(trigger).toBeFocused();
});
