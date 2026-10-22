import { expect, test } from "@playwright/test";

/**
 * 资讯域 e2e（前端审计 H-9）。
 *
 * 此前 /news 读的是 mock.ts 里 4 条硬编码数据，没有任何测试覆盖这条链路。
 * 接真 API 后要锁住的契约：列表按分类过滤、详情按 slug 读、未知 slug
 * 走不存在态而不是回退到第一条冒充。
 */

const NEWS_LIST = {
  code: "SUCCESS",
  data: [
    {
      id: "50000000-0000-0000-0000-000000000001",
      slug: "version-preview",
      title: "鸣潮 2.4 版本前瞻特别节目总结",
      category: "版本前瞻",
      summary: "新区域、角色体验和版本活动的重点信息已经整理完毕。",
      date: "2025-09-13",
      hot: true,
      content: ["本次前瞻围绕新版本的探索节奏、限时活动和角色试玩展开。", "建议先完成版本主线，再根据自身资源安排角色与武器的养成优先级。"],
    },
    {
      id: "50000000-0000-0000-0000-000000000002",
      slug: "maintenance-notice",
      title: "10 月 14 日服务器维护与补偿说明",
      category: "官方公告",
      summary: "维护期间将暂时无法登录，补偿已发送至全员邮箱。",
      date: "2025-10-14",
      hot: false,
      content: ["维护时间为当日 02:00 至 06:00，届时登录与匹配功能将暂停。", "补偿内容为特制定轨共鸣与结晶单抽凭证，将于维护结束后统一发放。"],
    },
  ],
  meta: { page: 1, pageSize: 20, totalItems: 2, totalPages: 1 },
};

/** mock 资讯列表与详情，返回一个可按 slug 查询详情的函数。 */
async function mockNews(page: import("@playwright/test").Page) {
  // category 过滤由服务端做（后端 NewsController 支持 ?category=），
  // mock 必须照做——否则"切 tab 后旧分类内容还在"这类回归测不出来
  await page.route(/\/api\/v1\/news\?/, (route) => {
    const category = new URL(route.request().url()).searchParams.get("category");
    const items = category ? NEWS_LIST.data.filter((item) => item.category === category) : NEWS_LIST.data;
    route.fulfill({ json: { ...NEWS_LIST, data: items, meta: { ...NEWS_LIST.meta, totalItems: items.length } } });
  });
  await page.route("**/api/v1/news", (route) => route.fulfill({ json: NEWS_LIST }));
  await page.route("**/api/v1/news/version-preview", (route) => route.fulfill({ json: { code: "SUCCESS", data: NEWS_LIST.data[0] } }));
  await page.route("**/api/v1/news/maintenance-notice", (route) => route.fulfill({ json: { code: "SUCCESS", data: NEWS_LIST.data[1] } }));
  // 未发布/未知 slug：后端返回 404 + 业务码
  await page.route("**/api/v1/news/unpublished-draft", (route) =>
    route.fulfill({ status: 404, json: { code: "NEWS_NOT_FOUND", message: "资讯不存在或已下线" } }),
  );
}

test("资讯列表展示后端返回的条目", async ({ page }) => {
  await mockNews(page);
  await page.goto("/news");
  await expect(page.getByRole("heading", { name: "版本资讯" })).toBeVisible();
  await expect(page.getByText("鸣潮 2.4 版本前瞻特别节目总结").first()).toBeVisible();
  await expect(page.getByText("10 月 14 日服务器维护与补偿说明").first()).toBeVisible();
});

test("资讯列表按分类过滤", async ({ page }) => {
  await mockNews(page);
  await page.goto("/news");
  await page.getByRole("button", { name: "官方公告" }).click();

  // 断言限定在列表 section 内：首页右栏「资讯」tab 用的是独立分类（固定"全部"），
  // 切列表分类不影响它——那是另一个组件的数据源，不该被这个用例绑定
  const list = page.locator(".news-page-list");
  // 列表用 keepPreviousData（切分类不清空、避免闪烁），新数据到达前会短暂
  // 显示上一批，所以等最终一致状态而不是切换那一瞬
  await expect.poll(() => list.getByText("鸣潮 2.4 版本前瞻特别节目总结").count(), { timeout: 5000 }).toBe(0);
  await expect(list.getByText("10 月 14 日服务器维护与补偿说明")).toBeVisible();
});

test("资讯详情按 slug 展示正文分段", async ({ page }) => {
  await mockNews(page);
  await page.goto("/news/version-preview");
  await expect(page.getByRole("heading", { level: 1, name: "鸣潮 2.4 版本前瞻特别节目总结" })).toBeVisible();
  await expect(page.getByText("本次前瞻围绕新版本的探索节奏、限时活动和角色试玩展开。")).toBeVisible();
  await expect(page.getByText("建议先完成版本主线，再根据自身资源安排角色与武器的养成优先级。")).toBeVisible();
});

test("未知资讯 slug 走不存在态，不回退到第一条冒充", async ({ page }) => {
  await mockNews(page);
  await page.goto("/news/unpublished-draft");
  // 关键断言：显示不存在提示，且第一条资讯的标题绝不出现
  await expect(page.getByText("资讯不存在或已下架。")).toBeVisible();
  await expect(page.getByRole("heading", { name: "鸣潮 2.4 版本前瞻特别节目总结" })).toHaveCount(0);
});

test("资讯详情接口失败后可重试恢复正文", async ({ page }) => {
  await mockNews(page);
  let unavailable = true;
  await page.route("**/api/v1/news/version-preview", (route) =>
    unavailable ? route.fulfill({ status: 500, body: "boom" }) : route.fulfill({ json: { code: "SUCCESS", data: NEWS_LIST.data[0] } }),
  );
  await page.goto("/news/version-preview");
  await expect(page.getByText("资讯加载失败，请稍后重试。")).toBeVisible();
  await expect(page.getByText("资讯不存在或已下架。")).toHaveCount(0);
  unavailable = false;
  await page.getByRole("button", { name: "重新加载" }).click();
  await expect(page.getByRole("heading", { name: NEWS_LIST.data[0].title, level: 1 })).toBeVisible();
});

test("资讯详情等待响应时只显示加载状态", async ({ page }) => {
  await mockNews(page);
  let release!: () => void;
  const pending = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/v1/news/version-preview", async (route) => {
    await pending;
    await route.fulfill({ json: { code: "SUCCESS", data: NEWS_LIST.data[0] } });
  });
  try {
    await page.goto("/news/version-preview");
    await expect(page.locator("article.detail-page").getByRole("status")).toHaveText("正在加载资讯…");
    await expect(page.getByText("资讯不存在或已下架。")).toHaveCount(0);
  } finally {
    release();
  }
  await expect(page.getByRole("heading", { name: NEWS_LIST.data[0].title, level: 1 })).toBeVisible();
});

test("资讯列表接口失败后可重试恢复条目", async ({ page }) => {
  await mockNews(page);
  let unavailable = true;
  await page.route(/\/api\/v1\/news\?/, (route) => (unavailable ? route.fulfill({ status: 500, body: "boom" }) : route.fulfill({ json: NEWS_LIST })));
  await page.goto("/news");
  const list = page.locator(".news-page-list");
  await expect(list.getByText("资讯加载失败，请稍后重试。")).toBeVisible({ timeout: 15_000 });
  unavailable = false;
  await list.getByRole("button", { name: "重新加载" }).click();
  await expect(list.getByRole("heading", { name: NEWS_LIST.data[0].title })).toBeVisible();
});

for (const failure of ["断网", "非业务404"]) {
  test(`资讯详情${failure}显示可恢复故障`, async ({ page }) => {
    await mockNews(page);
    let unavailable = true;
    await page.route("**/api/v1/news/version-preview", (route) => {
      if (!unavailable) return route.fulfill({ json: { code: "SUCCESS", data: NEWS_LIST.data[0] } });
      return failure === "断网" ? route.abort("failed") : route.fulfill({ status: 404, json: { code: "ROUTE_NOT_FOUND" } });
    });
    await page.goto("/news/version-preview");
    await expect(page.getByText("资讯加载失败，请稍后重试。")).toBeVisible();
    await expect(page.getByText("资讯不存在或已下架。")).toHaveCount(0);
    unavailable = false;
    await page.getByRole("button", { name: "重新加载" }).click();
    await expect(page.getByRole("heading", { name: NEWS_LIST.data[0].title, level: 1 })).toBeVisible();
  });
}
