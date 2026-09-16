import { expect, test, type Page } from "@playwright/test";

const user = {
  id: "user-100",
  phone: "13800000001",
  nickname: "潮声档案员",
  avatarUrl: null,
  bio: "记录版本变化，也记录每一次实战尝试。",
  role: "USER",
};

const postId = "10000000-0000-0000-0000-000000000001";
const post = {
  id: postId,
  type: "GUIDE",
  category: "配队攻略",
  title: "长离焚火队：从零到毕业的配队思路",
  excerpt: "围绕共鸣效率、轮切节奏与副 C 选择，整理一套能直接照着练的实战框架。",
  content: "## 队伍节奏\n\n先完成一轮稳定循环。",
  author: { id: user.id, nickname: user.nickname, avatarUrl: null, bio: user.bio },
  publishedAt: "2026-09-15T10:24:00",
  viewCount: 18000,
  likeCount: 3700,
  favoriteCount: 12,
  commentCount: 4,
  tags: ["配队", "长离"],
};

async function mockAuth(page: Page) {
  await page.route("**/api/v1/auth/me", (route) => route.fulfill({ json: { data: user } }));
}

test("发布编辑器桌面端使用聚焦布局并保留核心字段", async ({ page }) => {
  await mockAuth(page);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/publish");
  await expect(page.getByRole("button", { name: "退出" })).toBeVisible();

  const editor = page.getByRole("form", { name: "发布编辑器" });
  await expect(editor).toBeVisible();
  await expect(editor.getByRole("button", { name: "撤销" })).toBeVisible();
  await expect(editor.getByRole("button", { name: "加粗" })).toBeVisible();
  await expect(editor.getByRole("button", { name: "插入图片" })).toBeVisible();
  await expect(editor.getByRole("textbox", { name: "帖子标题" })).toBeVisible();
  await expect(editor.getByRole("textbox", { name: "帖子正文" })).toBeVisible();
  await expect(editor.getByLabel("内容类型")).toBeVisible();
  await expect(editor.getByRole("textbox", { name: "内容标签" })).toBeVisible();
  await expect(editor.getByRole("button", { name: "保存草稿" })).toBeVisible();
  await expect(editor.getByRole("button", { name: "发布" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "发布内容" })).toHaveCount(0);
  await expect(page.locator(".right-rail")).toHaveCount(0);

  const editorBox = await editor.boundingBox();
  expect(editorBox?.width).toBeGreaterThanOrEqual(1000);
  const width = await page.evaluate(() => ({ client: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth }));
  expect(width.scroll).toBeLessThanOrEqual(width.client);
});

test("Markdown 工具栏修改选区且本地草稿可恢复", async ({ page }) => {
  await page.addInitScript(() => {
    if (!window.sessionStorage.getItem("publish-draft-test-initialized")) {
      window.localStorage.removeItem("wuthering-community-publish-draft-v2");
      window.sessionStorage.setItem("publish-draft-test-initialized", "1");
    }
  });
  await mockAuth(page);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/publish");

  const editor = page.getByRole("form", { name: "发布编辑器" });
  const title = editor.getByRole("textbox", { name: "帖子标题" });
  const content = editor.getByRole("textbox", { name: "帖子正文" });
  await title.fill("一份值得保存的长离配队笔记");
  await content.fill("先完成稳定循环，再补充协同角色。");
  await content.evaluate((element) => {
    const textarea = element as HTMLTextAreaElement;
    textarea.setSelectionRange(0, 3);
  });
  await editor.getByRole("button", { name: "加粗" }).click();
  await expect(content).toHaveValue("**先完成**稳定循环，再补充协同角色。");

  await editor.getByRole("button", { name: "保存草稿" }).click();
  await expect(page.getByText("草稿已保存", { exact: true })).toBeVisible();
  await page.reload();
  await expect(editor.getByRole("textbox", { name: "帖子标题" })).toHaveValue("一份值得保存的长离配队笔记");
  await expect(editor.getByRole("textbox", { name: "帖子正文" })).toHaveValue("**先完成**稳定循环，再补充协同角色。");
  await expect(page.getByText("已恢复本地草稿", { exact: true })).toBeVisible();
});

test("Markdown 工具栏的标题、斜体、引用、列表和链接都修改正文选区", async ({ page }) => {
  await mockAuth(page);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/publish");
  const editor = page.getByRole("form", { name: "发布编辑器" });
  const content = editor.getByRole("textbox", { name: "帖子正文" });

  await content.fill("长离配队");
  await content.evaluate((element) => (element as HTMLTextAreaElement).setSelectionRange(0, 2));
  await editor.getByRole("button", { name: "斜体" }).click();
  await expect(content).toHaveValue("*长离*配队");

  await content.fill("稳定循环");
  await content.evaluate((element) => (element as HTMLTextAreaElement).setSelectionRange(0, 4));
  await editor.getByRole("button", { name: "引用" }).click();
  await expect(content).toHaveValue("> 稳定循环");

  await content.fill("声骸选择");
  await content.evaluate((element) => (element as HTMLTextAreaElement).setSelectionRange(0, 4));
  await editor.getByRole("button", { name: "无序列表" }).click();
  await expect(content).toHaveValue("- 声骸选择");

  await content.fill("第一步");
  await editor.getByRole("button", { name: "一级标题" }).click();
  await expect(content).toHaveValue("# 第一步");

  await content.fill("查看攻略");
  await content.evaluate((element) => (element as HTMLTextAreaElement).setSelectionRange(0, 4));
  await editor.getByRole("button", { name: "插入链接" }).click();
  await expect(content).toHaveValue("[查看攻略](https://)");
});

test("图片工具按钮上传后将 Markdown 插入正文并完成发布", async ({ page }) => {
  await mockAuth(page);
  await page.route("**/api/v1/auth/csrf", (route) => route.fulfill({ status: 204 }));
  await page.route("**/api/v1/files/images", (route) => route.fulfill({ json: { data: { url: "/media/wave-guide.png", originalName: "wave-guide.png" } } }));
  await page.route("**/api/v1/posts", (route) => route.request().method() === "POST"
    ? route.fulfill({ json: { data: { ...post, id: "created-post" } } })
    : route.continue());
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/publish");

  const editor = page.getByRole("form", { name: "发布编辑器" });
  const content = editor.getByRole("textbox", { name: "帖子正文" });
  await editor.getByRole("textbox", { name: "帖子标题" }).fill("鸣潮探索路线中的图片记录");
  await content.fill("这是正文。\n\n");
  await editor.getByRole("button", { name: "插入图片" }).click();
  await editor.locator("input[type=file]").setInputFiles({ name: "wave-guide.png", mimeType: "image/png", buffer: Buffer.from("fake-image") });
  await expect(content).toHaveValue(/!\[wave-guide\.png\]\(\/media\/wave-guide\.png\)/);

  await page.evaluate(() => window.localStorage.setItem("wuthering-community-publish-draft-v2", "test-draft"));
  await editor.getByRole("button", { name: "发布" }).click();
  await expect(page).toHaveURL(/\/guides\/created-post$/);
  await expect.poll(() => page.evaluate(() => window.localStorage.getItem("wuthering-community-publish-draft-v2"))).toBeNull();
});

test("编辑模式回填现有帖子并使用保存修改提交", async ({ page }) => {
  await mockAuth(page);
  await page.route(`**/api/v1/posts/${postId}`, (route) => {
    if (route.request().method() === "GET") return route.fulfill({ json: { data: post } });
    if (route.request().method() === "PUT") return route.fulfill({ json: { data: post } });
    return route.continue();
  });
  await page.route("**/api/v1/auth/csrf", (route) => route.fulfill({ status: 204 }));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(`/publish?edit=${postId}`);

  const editor = page.getByRole("form", { name: "发布编辑器" });
  await expect(editor.getByRole("textbox", { name: "帖子标题" })).toHaveValue(post.title);
  await expect(editor.getByRole("textbox", { name: "帖子正文" })).toHaveValue(post.content);
  await expect(editor.getByRole("button", { name: "保存修改" })).toBeVisible();
  await editor.getByRole("button", { name: "保存修改" }).click();
  await expect(page).toHaveURL(new RegExp(`/guides/${postId}$`));
});

for (const viewport of [{ width: 1024, height: 900 }, { width: 768, height: 900 }, { width: 390, height: 844 }]) {
  test(`发布编辑器在 ${viewport.width}px 保持可用且没有横向滚动`, async ({ page }) => {
    await mockAuth(page);
    await page.setViewportSize(viewport);
    await page.goto("/publish");

    await expect(page.getByRole("form", { name: "发布编辑器" })).toBeVisible();
    await expect(page.getByRole("textbox", { name: "帖子正文" })).toBeVisible();
    const width = await page.evaluate(() => ({ client: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth }));
    expect(width.scroll).toBeLessThanOrEqual(width.client);
  });
}
