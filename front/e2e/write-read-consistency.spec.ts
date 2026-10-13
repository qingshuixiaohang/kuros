import { expect, test, type Page } from "@playwright/test";

/**
 * 写后读一致性（前端审计 H-1 / H-3）。
 *
 * 要锁定的契约：在任一位置点赞后，**所有**展示该帖互动状态的位置都读到的
 * 是新值——详情页、首页 Feed 卡片，来回切换不出现"一头新一头旧"。
 *
 * 原实现的缺陷：全仓 0 处 useMutation / invalidateQueries，所有写操作靠
 * 手写 setState。首页与详情页各自 useState<PostInteraction> + 各自 fetch，
 * key 都不进 react-query，所以：
 *   - 在首页点赞（只改首页本地 state）→ 进详情页重新 fetch 到后端旧值
 *   - 在详情页点赞 → 回首页，首页的 useEffect 不会再跑（postId 没变），
 *     显示的仍是挂载时拉到的旧值
 * 用户看到"点赞数对不上"，且没有任何一层会发现。
 *
 * 这个 spec 是改造的验收标准：它断言的不是"点赞能成功"，而是"点赞的结果
 * 在所有视图间一致"。
 */

/**
 * 帖子 id 用真实 UUID：前端 PostCard 靠 UUID 正则识别"这是后端帖子"，
 * 首页的批量互动状态查询也只对 UUID id 生效（enabled 条件）。用非 UUID
 * 的 id 会让整条批量链路静默不跑，测试以"契约违反"的假象失败。
 */
const POST_ID = "11111111-1111-1111-1111-111111111111";
const OTHER_POST_ID = "22222222-2222-2222-2222-222222222222";

function post(id: string, title: string) {
  return {
    id,
    type: "GUIDE",
    category: "配队攻略",
    title,
    excerpt: "摘要",
    content: "正文",
    author: { id: "author-1", nickname: "测试作者", avatarUrl: null },
    authorId: "author-1",
    publishedAt: "2026-09-14T10:24:00",
    viewCount: 100,
    likeCount: 5,
    favoriteCount: 1,
    commentCount: 0,
    tags: ["配队"],
    media: [],
  };
}

/** 服务端的互动状态真相（mock 后端自己维护，模拟真实 DB）。 */
function serverState(): Record<string, { likeCount: number; favoriteCount: number; liked: boolean; favorited: boolean }> {
  return {
    [POST_ID]: { likeCount: 5, favoriteCount: 1, liked: false, favorited: false },
    [OTHER_POST_ID]: { likeCount: 3, favoriteCount: 0, liked: false, favorited: false },
  };
}

async function mockBackend(page: Page) {
  const state = serverState();

  // 帖子详情
  await page.route("**/api/v1/posts/" + POST_ID, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ code: "SUCCESS", data: { ...post(POST_ID, "写后读一致性测试帖"), ...state[POST_ID] } }),
    }),
  );

  // 单帖互动状态（详情页用）
  await page.route(new RegExp(`/api/v1/posts/${POST_ID}/interactions(?!/)`), (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ code: "SUCCESS", data: { postId: POST_ID, ...state[POST_ID] } }),
    }),
  );

  // 批量互动状态（首页用）
  await page.route("**/api/v1/interactions/batch-states**", (route) => {
    const ids = new URL(route.request().url()).searchParams.get("ids")?.split(",") ?? [];
    const data: Record<string, [number, number]> = {};
    for (const id of ids) {
      const s = state[id];
      if (s) data[id] = [s.liked ? 1 : 0, s.favorited ? 1 : 0];
    }
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ code: "SUCCESS", data }) });
  });

  // 首页列表（计数从服务端 state 读，这样"列表重取后计数更新"才可被测）
  await page.route("**/api/v1/posts?**", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        code: "SUCCESS",
        data: [
          { ...post(POST_ID, "写后读一致性测试帖"), likeCount: state[POST_ID].likeCount },
          { ...post(OTHER_POST_ID, "另一篇帖子"), likeCount: state[OTHER_POST_ID].likeCount },
        ],
        meta: { page: 1, pageSize: 20, totalItems: 2, totalPages: 1 },
      }),
    }),
  );

  // 写操作：真正改服务端状态（这样"重新拉取"才能拿到新值）
  await page.route(new RegExp(`/api/v1/posts/${POST_ID}/interactions/like`), async (route) => {
    if (route.request().method() === "POST") {
      state[POST_ID] = { ...state[POST_ID], liked: true, likeCount: state[POST_ID].likeCount + 1 };
    } else {
      state[POST_ID] = { ...state[POST_ID], liked: false, likeCount: Math.max(0, state[POST_ID].likeCount - 1) };
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ code: "SUCCESS", data: { postId: POST_ID, ...state[POST_ID] } }),
    });
  });

  // CSRF 前置（前端 ensureCsrfToken 会打这个）
  await page.route("**/api/v1/auth/csrf", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: { "set-cookie": "XSRF-TOKEN=test-csrf-token; Path=/" },
      body: JSON.stringify({ code: "SUCCESS", data: null }),
    }),
  );

  return state;
}

/** 用登录会话 Cookie（绕开登录链路）。 */
async function seedLogin(page: Page) {
  await page.context().addCookies([{ name: "KUROS_SESSION", value: "test-session-token", domain: "localhost", path: "/" }]);

  // 会话恢复：前端 community-interactions 启动时会打 /auth/me 判断登录态。
  // 不 mock 它 → 前端认为未登录 → 点赞被 requestLogin 弹登录框吃掉，
  // 服务端状态永远不变，测试会以"契约被违反"的假象失败。
  await page.route("**/api/v1/auth/me", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        code: "SUCCESS",
        data: { id: "test-user-1", nickname: "测试用户", role: "USER" },
      }),
    }),
  );
}

test.describe("写后读一致性", () => {
  test("详情页点赞后返回首页，首页卡片显示已赞", async ({ page }) => {
    await seedLogin(page);
    const state = await mockBackend(page);

    // 1. 进详情页点赞
    await page.goto(`/guides/${POST_ID}`);
    await expect(page.getByText("写后读一致性测试帖").first()).toBeVisible();

    const likeButton = page.locator('button[aria-label="点赞"], button[aria-label="取消点赞"]').first();
    if ((await likeButton.count()) === 0) {
      test.skip(true, "详情页未找到点赞按钮（选择器需调整）");
    }
    await likeButton.click();

    // 等服务端状态真的变了
    await expect.poll(() => state[POST_ID].liked).toBe(true);

    // 2. 回首页——首页卡片必须显示"已赞"
    await page.goto("/");
    await expect(page.getByText("写后读一致性测试帖").first()).toBeVisible();

    // 首页卡片的点赞按钮应处于"已赞"态（类名 is-active / 文案变化）
    const homeCard = page.locator("article.post-card").filter({ hasText: "写后读一致性测试帖" }).first();
    await expect(homeCard).toBeVisible();
    const homeLikeButton = homeCard.locator("button").filter({ hasText: /^\d/ }).first();
    await expect(homeLikeButton).toBeVisible();
    // 已赞态的判定：aria-pressed 或 class（与组件实现对齐，见 community-home.tsx）
    const pressedClass = await homeLikeButton.getAttribute("class");
    const pressed = await homeLikeButton.getAttribute("aria-pressed");

    expect(pressed === "true" || (pressedClass ?? "").includes("is-active") || (pressedClass ?? "").includes("is-liked")).toBe(true);
  });

  test("首页点赞后进详情页，详情页显示已赞", async ({ page }) => {
    await seedLogin(page);
    const state = await mockBackend(page);

    // 1. 首页点赞
    await page.goto("/");
    await expect(page.getByText("写后读一致性测试帖").first()).toBeVisible();

    const homeCard = page.locator("article.post-card").filter({ hasText: "写后读一致性测试帖" }).first();
    await expect(homeCard).toBeVisible();
    const homeLikeButton = homeCard.locator("button").filter({ hasText: /^\d/ }).first();
    await homeLikeButton.click();

    await expect.poll(() => state[POST_ID].liked).toBe(true);

    // 2. 进详情页——必须显示已赞
    await page.goto(`/guides/${POST_ID}`);
    await expect(page.getByText("写后读一致性测试帖").first()).toBeVisible();

    const detailLikeButton = page.locator('button[aria-label="点赞"], button[aria-label="取消点赞"]').first();
    const pressedClass = await detailLikeButton.getAttribute("class");
    const pressed = await detailLikeButton.getAttribute("aria-pressed");
    expect(pressed === "true" || (pressedClass ?? "").includes("is-active") || (pressedClass ?? "").includes("is-liked")).toBe(true);
  });

  test("点赞数在首页与详情页一致", async ({ page }) => {
    await seedLogin(page);
    const state = await mockBackend(page);

    // 详情页点赞一次
    await page.goto(`/guides/${POST_ID}`);
    await expect(page.getByText("写后读一致性测试帖").first()).toBeVisible();
    await page.locator('button:has-text("点赞"), button[aria-label*="赞"]').first().click();
    await expect.poll(() => state[POST_ID].likeCount).toBe(6);

    // 两处都应显示 6（列表响应里 likeCount=5 是旧值，
    // 一致性要求它跟着更新而不是各自停留在不同数字）
    await page.goto("/");
    await expect(page.getByText("写后读一致性测试帖").first()).toBeVisible();

    // 在首页卡片内找点赞计数（卡片按钮无 aria-label，靠 Heart 图标 + 数字定位）
    const homeCard = page.locator("article.post-card").filter({ hasText: "写后读一致性测试帖" }).first();
    await expect(homeCard).toBeVisible();
    const homeLikeButton = homeCard.locator("button").filter({ hasText: /^\d/ }).first();
    await expect(homeLikeButton).toContainText("6");
  });
});
