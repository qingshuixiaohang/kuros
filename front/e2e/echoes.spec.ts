import { expect, test } from "@playwright/test";

/**
 * 声骸域 e2e（前端审计 H-9 的第二块，news 域见 news.spec.ts）。
 *
 * 此前 /echoes 读的是 mock.ts 里 4 条硬编码数据，没有任何测试覆盖这条链路。
 * 接真 API 后要锁住的契约：列表按定位过滤、详情按 slug 读、未知 slug 走
 * 不存在态而不是回退到第一条冒充（echoes-page.tsx 原本就做对了这一条，
 * 但那是前端 find 的行为；接真 API 后由后端 404 保证，前端负责显示失败态）。
 */

const ECHO_LIST = {
  code: "SUCCESS",
  data: [
    {
      id: "60000000-0000-0000-0000-000000000001",
      slug: "floating-stars",
      name: "浮星祛暗",
      effect: "湮灭伤害提升",
      location: "荒石高地 / 无音区",
      role: "输出",
      description: "适合围绕湮灭伤害展开的主输出构筑。",
      guideSlug: null,
    },
    {
      id: "60000000-0000-0000-0000-000000000002",
      slug: "thunder",
      name: "彻空冥雷",
      effect: "导电伤害提升",
      location: "虎口矿场 / 归墟港市",
      role: "输出",
      description: "以导电伤害为核心的输出向套装。",
      guideSlug: null,
    },
    {
      id: "60000000-0000-0000-0000-000000000003",
      slug: "light-cloud",
      name: "轻云出月",
      effect: "共鸣解放增益",
      location: "瑝珑各地",
      role: "协同",
      description: "给轮切队提供稳定的共鸣解放增益。",
      guideSlug: null,
    },
    {
      id: "60000000-0000-0000-0000-000000000004",
      slug: "hidden-light",
      name: "隐世回光",
      effect: "治疗效果与协同",
      location: "云陵谷 / 乘霄山",
      role: "治疗",
      description: "适合治疗与协同角色的功能性选择。",
      guideSlug: null,
    },
  ],
  meta: { page: 1, pageSize: 20, totalItems: 4, totalPages: 1 },
};

/** mock 声骸列表与详情，返回一个可按 slug 查询详情的函数。 */
async function mockEchoes(page: import("@playwright/test").Page) {
  // role 过滤由服务端做（后端 EchoController 支持 ?role=），mock 必须照做
  await page.route(/\/api\/v1\/echoes\?/, (route) => {
    const role = new URL(route.request().url()).searchParams.get("role");
    const items = role ? ECHO_LIST.data.filter((item) => item.role === role) : ECHO_LIST.data;
    route.fulfill({ json: { ...ECHO_LIST, data: items, meta: { ...ECHO_LIST.meta, totalItems: items.length } } });
  });
  await page.route("**/api/v1/echoes", (route) => route.fulfill({ json: ECHO_LIST }));
  await page.route("**/api/v1/echoes/floating-stars", (route) => route.fulfill({ json: { code: "SUCCESS", data: ECHO_LIST.data[0] } }));
  // 未上线/未知 slug：后端返回 404 + 业务码
  await page.route("**/api/v1/echoes/disabled-set", (route) =>
    route.fulfill({ status: 404, json: { code: "ECHO_NOT_FOUND", message: "声骸套装不存在或已下架" } }),
  );
}

test("声骸列表展示后端返回的套装", async ({ page }) => {
  await mockEchoes(page);
  await page.goto("/echoes");
  await expect(page.getByRole("heading", { name: "声骸图鉴" })).toBeVisible();
  await expect(page.getByText("浮星祛暗")).toBeVisible();
  await expect(page.getByText("彻空冥雷")).toBeVisible();
  // 计数走接口 meta 而不是本地数组长度
  await expect(page.getByText("已收录 4 套")).toBeVisible();
});

test("声骸列表视角 tab 只换显示列，不过滤数据", async ({ page }) => {
  await mockEchoes(page);
  await page.goto("/echoes");
  const list = page.locator(".echo-table");
  await expect(list.getByText("浮星祛暗")).toBeVisible();

  // 三个 tab 是「视角」不是「过滤」：切 tab 只改第二列显示 effect 还是 location，
  // 数据行不变。mock 时代就是这个语义（只切 mode），接真 API 后刻意保留——
  // 加 role 过滤属于新功能不是 H-9 的范围。这条用例钉住语义别漂。
  await page.getByRole("button", { name: "掉落位置" }).click();
  await expect(list.getByText("浮星祛暗")).toBeVisible();
  await expect(list.getByText("彻空冥雷")).toBeVisible();
  await expect(list.getByText("轻云出月")).toBeVisible();

  await page.getByRole("button", { name: "单体声骸" }).click();
  await expect(list.getByText("浮星祛暗")).toBeVisible();
  await expect(list.getByText("彻空冥雷")).toBeVisible();
});

test("声骸搜索框按名称/效果/位置本地过滤", async ({ page }) => {
  await mockEchoes(page);
  await page.goto("/echoes");
  await page.getByPlaceholder("搜索帖子、攻略、玩家...").fill("导电");
  await expect(page.locator(".echo-table").getByText("彻空冥雷")).toBeVisible();
  await expect(page.locator(".echo-table").getByText("浮星祛暗")).toHaveCount(0);
  await expect(page.getByText("已收录 1 套")).toBeVisible();
});

test("声骸详情按 slug 展示完整字段", async ({ page }) => {
  await mockEchoes(page);
  await page.goto("/echoes/floating-stars");
  await expect(page.getByRole("heading", { level: 1, name: "浮星祛暗" })).toBeVisible();
  await expect(page.getByText("适合围绕湮灭伤害展开的主输出构筑。")).toBeVisible();
  await expect(page.getByText("荒石高地 / 无音区")).toBeVisible();
});

test("未知声骸 slug 走不存在态，不回退到第一条冒充", async ({ page }) => {
  await mockEchoes(page);
  await page.goto("/echoes/disabled-set");
  await expect(page.getByText("声骸套装不存在或已下架。")).toBeVisible();
  // 关键断言：第一条声骸的标题绝不出现
  await expect(page.getByRole("heading", { name: "浮星祛暗" })).toHaveCount(0);
});

test("声骸详情接口失败时显示错误态而不是空白", async ({ page }) => {
  await mockEchoes(page);
  await page.route("**/api/v1/echoes/floating-stars", (route) => route.fulfill({ status: 500, body: "boom" }));
  await page.goto("/echoes/floating-stars");
  await expect(page.getByText("声骸套装不存在或已下架。")).toBeVisible();
});
