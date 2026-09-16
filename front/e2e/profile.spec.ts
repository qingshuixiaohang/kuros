import { expect, test, type Page } from "@playwright/test";

const user = {
  id: "user-100",
  phone: "13800000001",
  nickname: "潮声档案员",
  avatarUrl: null,
  bio: "记录版本变化，也记录每一次实战尝试。",
  role: "USER",
};

const post = {
  id: "10000000-0000-0000-0000-000000000001",
  type: "GUIDE",
  category: "配队攻略",
  title: "长离焚火队：从零到毕业的配队思路",
  excerpt: "围绕共鸣效率、轮切节奏与副 C 选择，整理一套能直接照着练的实战框架。",
  coverImageUrl: "/art/guide-tower.png",
  author: { id: user.id, nickname: user.nickname, avatarUrl: null, bio: user.bio },
  publishedAt: "2026-09-15T10:24:00",
  viewCount: 18000,
  likeCount: 3700,
  favoriteCount: 12,
  commentCount: 4,
  tags: ["配队", "实战思路", "长离"],
};

const pageMeta = { page: 1, pageSize: 20, totalItems: 1, totalPages: 1 };
const comment = {
  id: "comment-100",
  postId: post.id,
  postTitle: post.title,
  parentId: null,
  content: "这套循环的容错点写得很清楚。",
  deleted: false,
  createdAt: "2026-09-15T11:20:00",
};
const following = { id: "user-200", nickname: "今汐的留声机", avatarUrl: null, bio: "记录鸣潮实战", postCount: 2, likeCount: 88 };
const fan = { id: "user-300", nickname: "无音区夜行者", avatarUrl: null, bio: "深塔低配研究中", postCount: 4, likeCount: 126 };

async function mockMyProfileApi(page: Page) {
  await page.route("http://localhost:8080/api/v1/auth/me", (route) => route.fulfill({ json: { data: user } }));
  await page.route("http://localhost:8080/api/v1/users/me/profile?*", (route) => route.fulfill({
    json: {
      data: {
        profile: { id: user.id, nickname: user.nickname, avatarUrl: null, bio: user.bio, postCount: 1, likeCount: 3700 },
        stats: { postCount: 1, commentCount: 3, likeCount: 3700 },
        posts: { items: [post], meta: pageMeta },
        comments: { items: [comment], meta: pageMeta },
        favorites: { items: [post], meta: pageMeta },
        following: { items: [following], meta: pageMeta },
        fans: { items: [fan], meta: pageMeta },
      },
    },
  }));
}

test("个人中心桌面端展示资料概览、五个导航和帖子管理入口", async ({ page }) => {
  await mockMyProfileApi(page);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/profile");

  await expect(page.getByRole("region", { name: "个人资料概览" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "潮声档案员" }).first()).toBeVisible();
  await expect(page.getByRole("complementary", { name: "个人中心导航" }).getByRole("link")).toHaveCount(5);
  await expect(page.getByRole("heading", { name: "帖子" }).last()).toBeVisible();
  const profileHomeLink = page.getByRole("region", { name: "个人资料概览" }).getByRole("link", { name: "个人中心", exact: true });
  await expect(profileHomeLink).toHaveAttribute("href", "/profile");
  await expect(profileHomeLink).toHaveCSS("display", "inline-flex");
  await expect(page.locator(".profile-post-thumbnail img").first()).toHaveAttribute("src", /guide-tower\.png/);

  const profileBox = await page.locator(".profile-page").boundingBox();
  expect(profileBox?.width).toBeGreaterThanOrEqual(1200);
  const panelBox = await page.locator(".profile-panel").boundingBox();
  expect(panelBox?.height).toBeLessThan(430);
  await expect(page.getByRole("link", { name: /编辑《长离焚火队/ })).toHaveAttribute("href", /\/publish\?edit=/);
  await expect(page.getByRole("button", { name: /删除《长离焚火队/ })).toBeVisible();

  const width = await page.evaluate(() => ({ client: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth }));
  expect(width.scroll).toBeLessThanOrEqual(width.client);
});

test("个人中心帖子可以在新标签页打开详情，导航切换保持公开 URL", async ({ page }) => {
  await mockMyProfileApi(page);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/profile");

  const postLink = page.locator(".profile-post-row-main").first();
  const popupPromise = page.waitForEvent("popup");
  await postLink.click();
  const popup = await popupPromise;
  expect(popup.url()).toContain("/guides/changli-team");
  await popup.close();

  await page.getByRole("link", { name: "评论", exact: true }).click();
  await expect(page).toHaveURL(/\/profile\?tab=comments$/);
  await expect(page.getByRole("heading", { name: "评论" }).last()).toBeVisible();
});

test("个人中心五个分区都能通过公开 URL 切换并展示对应内容", async ({ page }) => {
  await mockMyProfileApi(page);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/profile");

  const tabs = [
    { key: "comments", label: "评论", content: comment.content },
    { key: "favorites", label: "收藏", content: post.title },
    { key: "following", label: "关注", content: following.nickname },
    { key: "fans", label: "粉丝", content: fan.nickname },
  ];
  for (const tab of tabs) {
    const link = page.getByRole("complementary", { name: "个人中心导航" }).getByRole("link", { name: tab.label, exact: true });
    await link.click();
    await expect(page).toHaveURL(new RegExp(`/profile\\?tab=${tab.key}$`));
    await expect(link).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("heading", { name: tab.label }).last()).toBeVisible();
    await expect(page.getByText(tab.content, { exact: true })).toBeVisible();
  }
});

test("个人中心桌面操作可以进入编辑页并删除帖子", async ({ page }) => {
  await mockMyProfileApi(page);
  await page.route("http://localhost:8080/api/v1/auth/csrf", (route) => route.fulfill({ status: 204 }));
  await page.route("http://localhost:8080/api/v1/posts/10000000-0000-0000-0000-000000000001", (route) => {
    if (route.request().method() === "DELETE") return route.fulfill({ status: 204 });
    return route.continue();
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/profile");

  await page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: /删除《长离焚火队/ }).click();
  await expect(page.getByText("帖子已删除", { exact: true })).toBeVisible();

  await page.getByRole("link", { name: /编辑《长离焚火队/ }).click();
  await expect(page).toHaveURL(/\/publish\?edit=10000000-0000-0000-0000-000000000001$/);
});

test("公开个人中心桌面端展示资料和帖子且没有横向滚动", async ({ page }) => {
  await page.route("http://localhost:8080/api/v1/users/public-user", (route) => route.fulfill({ json: { data: { id: "public-user", nickname: "今汐的留声机", avatarUrl: null, bio: "记录鸣潮实战", postCount: 1, likeCount: 88 } } }));
  await page.route("http://localhost:8080/api/v1/users/public-user/posts?*", (route) => route.fulfill({ json: { data: [post], meta: pageMeta } }));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/users/public-user");

  await expect(page.getByRole("heading", { name: "今汐的留声机" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "公开帖子" })).toBeVisible();
  const width = await page.evaluate(() => ({ client: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth }));
  expect(width.scroll).toBeLessThanOrEqual(width.client);
});

test("个人中心移动端保持单列且没有页面级横向滚动", async ({ page }) => {
  await mockMyProfileApi(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/profile");

  await expect(page.getByRole("region", { name: "个人资料概览" })).toBeVisible();
  await expect(page.getByRole("complementary", { name: "个人中心导航" })).toBeVisible();
  const width = await page.evaluate(() => ({ client: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth }));
  expect(width.scroll).toBeLessThanOrEqual(width.client);
});

for (const viewport of [{ width: 1024, height: 900, singleColumn: false }, { width: 768, height: 900, singleColumn: true }]) {
  test(`个人中心在 ${viewport.width}px 桌面断点保持布局且没有横向滚动`, async ({ page }) => {
    await mockMyProfileApi(page);
    await page.setViewportSize(viewport);
    await page.goto("/profile");

    await expect(page.getByRole("region", { name: "个人资料概览" })).toBeVisible();
    const contentGrid = await page.locator(".profile-content-grid").evaluate((element) => getComputedStyle(element).gridTemplateColumns);
    expect(contentGrid.split(" ").filter(Boolean)).toHaveLength(viewport.singleColumn ? 1 : 2);
    const width = await page.evaluate(() => ({ client: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth }));
    expect(width.scroll).toBeLessThanOrEqual(width.client);
  });
}

test("游客进入个人中心时看到登录引导而不是空白资料页", async ({ page }) => {
  await page.route("http://localhost:8080/api/v1/auth/me", (route) => route.fulfill({ status: 401, json: { message: "未登录" } }));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/profile");

  await expect(page.getByText("登录后查看你的社区资料", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "立即登录" })).toBeVisible();
  await expect(page.getByRole("region", { name: "个人资料概览" })).toBeHidden();
});
