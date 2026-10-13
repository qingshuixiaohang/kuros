import { expect, test, type Page } from "@playwright/test";

/**
 * 关注流 e2e（前端审计 H-11：此前零覆盖）。
 *
 * /?tab=following 是唯一走游标分页的页面路径，且 community-home.tsx:141-198
 * 那段手搓取数（loadedKey / requestKey / reloadKey / feedIssue 四状态 +
 * tab 切换）是全仓最脆弱的手写逻辑——tab 切换、query 变化、重试三条路径的
 * 组合此前没有任何测试锁定。
 *
 * 覆盖：
 * ① 未登录 → 401 引导（不能伪装成"没关注人"的空态）
 * ② 后端故障 → 错误态 + 重试入口
 * ③ 已登录 → 首屏 + 加载更多（游标透传）+ 到底态
 * ④ tab 从 following 切走再切回 → 状态不串
 */

const FEED_POST_1 = {
  id: "feed-post-1",
  type: "GUIDE",
  category: "配队攻略",
  title: "关注流第一条帖子",
  excerpt: "来自关注创作者的动态",
  content: "正文",
  authorId: "author-1",
  author: { id: "author-1", nickname: "关注的作者甲", avatarUrl: null },
  publishedAt: "2026-09-14T10:24:00",
  viewCount: 100,
  likeCount: 10,
  favoriteCount: 2,
  commentCount: 3,
  tags: ["配队"],
  media: [],
};

const FEED_POST_2 = {
  ...FEED_POST_1,
  id: "feed-post-2",
  title: "关注流第二条帖子",
  excerpt: "第二页内容",
  publishedAt: "2026-09-13T10:24:00",
};

/** 推荐流的帖子（用于验证 tab 切换后不串数据）。 */
const RECOMMEND_POST = {
  ...FEED_POST_1,
  id: "recommend-post-1",
  title: "推荐流专属帖子",
  excerpt: "默认 tab 的内容",
};

function feedPage(items: unknown[], nextCursor: string | null, hasMore: boolean) {
  return {
    code: "SUCCESS",
    data: { items, nextCursor, hasMore },
  };
}

/**
 * 推荐流（GET /api/v1/posts）响应。
 *
 * 契约：envelope.data 直接是帖子数组，分页元信息在 envelope.meta
 * （见 fetchPostPage：`{ items: envelope?.data ?? [], meta: envelope?.meta }`）。
 */
function recommendPage(items: unknown[], meta: Record<string, unknown> = {}) {
  return {
    code: "SUCCESS",
    data: items,
    meta: { page: 1, pageSize: 20, totalItems: items.length, totalPages: 1, ...meta },
  };
}

/** mock 关注流 + 推荐流，两者返回不同内容以便识别串档。 */
async function mockFeeds(page: Page, options: { followingStatus?: number } = {}) {
  const { followingStatus = 200 } = options;

  await page.route("**/api/v1/feed/following**", async (route) => {
    if (followingStatus !== 200) {
      await route.fulfill({
        status: followingStatus,
        contentType: "application/json",
        body: JSON.stringify({ code: followingStatus === 401 ? "UNAUTHORIZED" : "INTERNAL_ERROR", message: "mocked" }),
      });
      return;
    }
    const cursor = new URL(route.request().url()).searchParams.get("cursor");
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(
        cursor ? feedPage([FEED_POST_2], null, false) : feedPage([FEED_POST_1], "cursor-2", true),
      ),
    });
  });

  await page.route("**/api/v1/posts**", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(recommendPage([RECOMMEND_POST])),
    });
  });
}

test.describe("关注流降级路径", () => {
  test("未登录（401）显示登录引导而非空态", async ({ page }) => {
    await mockFeeds(page, { followingStatus: 401 });
    await page.goto("/?tab=following");

    // 必须是 401 专属引导，不能伪装成"还没关注人"
    await expect(page.getByText("登录后查看关注的创作者动态")).toBeVisible();
    await expect(page.getByRole("button", { name: "去登录" })).toBeVisible();
    // 不应出现空态文案
    await expect(page.getByText("还没有关注的创作者。去推荐页看看吧。")).toHaveCount(0);
  });

  test("后端故障显示错误态与重试入口", async ({ page }) => {
    await mockFeeds(page, { followingStatus: 500 });
    await page.goto("/?tab=following");

    await expect(page.getByText("关注流加载失败，请稍后重试。")).toBeVisible();
    await expect(page.getByRole("button", { name: "重新加载" })).toBeVisible();
    // 不能伪装成空态
    await expect(page.getByText("还没有关注的创作者。去推荐页看看吧。")).toHaveCount(0);
  });
});

test.describe("关注流正常路径", () => {
  test("已登录展示首屏内容、加载更多翻页并透传游标", async ({ page }) => {
    const seenCursors: (string | null)[] = [];
    await page.route("**/api/v1/feed/following**", async (route) => {
      const cursor = new URL(route.request().url()).searchParams.get("cursor");
      seenCursors.push(cursor);
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(
          cursor ? feedPage([FEED_POST_2], null, false) : feedPage([FEED_POST_1], "cursor-2", true),
        ),
      });
    });
    await page.route("**/api/v1/posts**", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(recommendPage([RECOMMEND_POST])),
      }),
    );

    await page.goto("/?tab=following");

    // 首屏
    await expect(page.getByText("关注流第一条帖子")).toBeVisible();

    // 加载更多
    await page.getByRole("button", { name: "加载更多" }).click();
    await expect(page.getByText("关注流第二条帖子")).toBeVisible();

    // 第一页内容仍在（追加而非替换）
    await expect(page.getByText("关注流第一条帖子")).toBeVisible();

    // 点击加载更多时带的游标必须是后端返回的 nextCursor。
    // 不断言请求总数：Next.js 默认 reactStrictMode，开发模式下 effect 会
    // 双调用（mount → unmount → mount），首屏多发一次 null 是预期行为。
    expect(seenCursors).toContain("cursor-2");
    // 首屏请求都不带游标
    expect(seenCursors.filter((cursor) => cursor === null).length).toBeGreaterThanOrEqual(1);
    // 带上游标的请求只应发生在点击加载更多之后，且值正确
    expect(seenCursors.filter((cursor) => cursor !== null)).toEqual(["cursor-2"]);

    // hasMore=false → 关注流专属的到底态文案（与 search 页不同）
    await expect(page.getByRole("button", { name: "加载更多" })).toHaveCount(0);
    await expect(page.getByText("已经到底啦，关注的动态都看完了。")).toBeVisible();
  });

  test("tab 从 following 切走再切回不串数据", async ({ page }) => {
    await mockFeeds(page);

    await page.goto("/?tab=following");
    await expect(page.getByText("关注流第一条帖子")).toBeVisible();

    // 切到推荐 tab
    await page.getByRole("link", { name: "推荐" }).first().click();
    await expect(page.getByText("推荐流专属帖子")).toBeVisible();
    // 关注流内容不应残留
    await expect(page.getByText("关注流第一条帖子")).toHaveCount(0);

    // 切回 following
    await page.goto("/?tab=following");
    await expect(page.getByText("关注流第一条帖子")).toBeVisible();
    // 推荐流内容不应残留
    await expect(page.getByText("推荐流专属帖子")).toHaveCount(0);
  });

  test("空关注流显示空态引导", async ({ page }) => {
    await page.route("**/api/v1/feed/following**", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(feedPage([], null, false)),
      }),
    );
    await page.route("**/api/v1/posts**", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(recommendPage([RECOMMEND_POST])),
      }),
    );

    await page.goto("/?tab=following");

    await expect(page.getByText("还没有关注的创作者。去推荐页看看吧。")).toBeVisible();
    // 空态不是错误态
    await expect(page.getByText("关注流加载失败，请稍后重试。")).toHaveCount(0);
    await expect(page.getByText("登录后查看关注的创作者动态")).toHaveCount(0);
  });
});
