import { expect, test } from "@playwright/test";

const posts = [
  {
    id: "creation-1",
    type: "GENERAL",
    category: "同人",
    title: "API 星炬插画",
    excerpt: "一幅描绘星炬的原创插画。",
    content: "作品介绍。",
    media: [{ id: "media-1", url: "/art/修-守岸人 唤取动画.webp", sortOrder: 0, isCover: true }],
    author: { id: "author-1", nickname: "画师漂泊者", avatarUrl: null, bio: null },
    publishedAt: "2026-10-18T10:00:00",
    viewCount: 120,
    likeCount: 8,
    favoriteCount: 2,
    commentCount: 1,
    tags: ["插画"],
  },
  {
    id: "creation-2",
    type: "GENERAL",
    category: "同人",
    title: "API 无配图短篇",
    excerpt: "这条同人帖没有配图。",
    content: "短篇正文。",
    author: { id: "author-2", nickname: "文字漂泊者", avatarUrl: null, bio: null },
    publishedAt: "2026-10-17T10:00:00",
    viewCount: 30,
    likeCount: 3,
    favoriteCount: 0,
    commentCount: 0,
    tags: [],
  },
  {
    id: "post-guide",
    type: "GUIDE",
    category: "配队攻略",
    title: "API 配队攻略",
    excerpt: "这不是同人帖。",
    content: "攻略正文。",
    author: { id: "author-3", nickname: "攻略作者", avatarUrl: null, bio: null },
    publishedAt: "2026-10-16T10:00:00",
    viewCount: 200,
    likeCount: 10,
    favoriteCount: 2,
    commentCount: 4,
    tags: [],
  },
];

test("同人列表按 API 分类展示作品并链接到对应帖子详情", async ({ page }) => {
  let requestedUrl = "";
  await page.route("**/api/v1/posts?*", (route) => {
    requestedUrl = route.request().url();
    const params = new URL(requestedUrl).searchParams;
    const category = params.get("category");
    const filtered = category ? posts.filter((post) => post.category === category) : posts;
    return route.fulfill({
      json: {
        data: filtered,
        meta: { page: 1, pageSize: 20, totalItems: filtered.length, totalPages: 1 },
      },
    });
  });
  await page.route("**/api/v1/posts/creation-1", (route) => route.fulfill({ json: { data: posts[0] } }));
  await page.route("**/api/v1/posts/creation-2", (route) => route.fulfill({ json: { data: posts[1] } }));

  await page.goto("/creations");

  const list = page.locator(".creation-list");
  await expect(list.getByRole("heading", { name: "API 星炬插画" })).toBeVisible();
  await expect(list.getByRole("heading", { name: "API 无配图短篇" })).toBeVisible();
  await expect(list.getByText(/画师漂泊者/)).toBeVisible();
  await expect(list.getByText("一幅描绘星炬的原创插画。", { exact: true })).toBeVisible();
  const imageSrc = await list.getByRole("img", { name: "API 星炬插画创作预览" }).getAttribute("src");
  expect(new URL(imageSrc!, "http://localhost").searchParams.get("url")).toBe("/art/修-守岸人 唤取动画.webp");
  await expect(list.getByText("API 配队攻略")).toHaveCount(0);
  await list.getByRole("link", { name: /API 无配图短篇/ }).click();
  await expect(page).toHaveURL(/\/guides\/creation-2$/);
  await expect(page.getByRole("heading", { level: 1, name: "API 无配图短篇" })).toBeVisible();
  expect(new URL(requestedUrl).searchParams.get("category")).toBe("同人");
  expect(new URL(requestedUrl).searchParams.get("page")).toBe("1");
  expect(new URL(requestedUrl).searchParams.get("pageSize")).toBe("20");
  expect(new URL(requestedUrl).searchParams.get("sort")).toBe("latest");
});

test("同人列表在等待帖子 API 时显示加载状态", async ({ page }) => {
  let releaseResponse: (() => void) | undefined;
  const responseGate = new Promise<void>((resolve) => {
    releaseResponse = resolve;
  });
  await page.route("**/api/v1/posts?*", async (route) => {
    await responseGate;
    await route.fulfill({ json: { data: [], meta: { page: 1, pageSize: 20, totalItems: 0, totalPages: 0 } } });
  });

  await page.goto("/creations");
  await expect(page.getByText("正在加载同人创作…")).toBeVisible();
  releaseResponse?.();
  await expect(page.getByText("暂时还没有同人创作。")).toBeVisible();
});

test("同人列表 API 失败时显示错误且不显示 Mock 角色", async ({ page }) => {
  await page.route("**/api/v1/posts?*", (route) => route.fulfill({ status: 503, body: "unavailable" }));

  await page.goto("/creations");

  await expect(page.getByText("同人创作加载失败，请检查后端服务。")).toBeVisible();
  await expect(page.getByText("守岸人", { exact: true })).toHaveCount(0);
  await expect(page.locator(".creation-card")).toHaveCount(0);
});

test("同人列表在 API 返回空数组时显示空状态", async ({ page }) => {
  await page.route("**/api/v1/posts?*", (route) => route.fulfill({ json: { data: [], meta: { page: 1, pageSize: 20, totalItems: 0, totalPages: 0 } } }));

  await page.goto("/creations");

  await expect(page.getByText("暂时还没有同人创作。")).toBeVisible();
});

test("其他社区页面不会请求同人帖子列表", async ({ page }) => {
  let requestCount = 0;
  await page.route("**/api/v1/posts?*", (route) => {
    requestCount += 1;
    return route.fulfill({ json: { data: [], meta: { page: 1, pageSize: 20, totalItems: 0, totalPages: 0 } } });
  });

  await page.goto("/news");

  expect(requestCount).toBe(0);
});
