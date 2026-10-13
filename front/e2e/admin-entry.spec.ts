import { expect, test } from "@playwright/test";

// 管理入口（顶栏"管理"，仅 ADMIN 可见）+ 举报审核台访问控制
// 安全边界说明：前端隐藏是体验，真正的门禁是后端 SaToken 的 ADMIN 校验

const adminUser = { id: "user-100", phone: "13800000001", nickname: "潮声档案员", avatarUrl: null, bio: "记录鸣潮实战", role: "ADMIN" };
const normalUser = { ...adminUser, role: "USER" };

function mockAuthMe(page: import("@playwright/test").Page, user: Record<string, unknown> | null) {
  return page.route("http://localhost:8080/api/v1/auth/me", (route) =>
    user ? route.fulfill({ json: { data: user } }) : route.fulfill({ status: 401, json: { code: "UNAUTHORIZED", message: "请先登录" } }),
  );
}

test("管理员登录后顶栏出现管理入口，可直达举报审核台", async ({ page }) => {
  await mockAuthMe(page, adminUser);
  await page.route("http://localhost:8080/api/v1/admin/reports**", (route) =>
    route.fulfill({ json: { data: [], meta: { page: 1, pageSize: 10, totalItems: 0, totalPages: 0 } } }),
  );

  await page.goto("/");
  const adminLink = page.getByRole("link", { name: "管理入口" });
  await expect(adminLink).toBeVisible();

  await adminLink.click();
  await expect(page).toHaveURL(/\/admin\/reports$/);
  await expect(page.getByRole("heading", { name: "举报审核台" })).toBeVisible();
});

test("普通用户看不到管理入口，直连审核台显示无权限页", async ({ page }) => {
  await mockAuthMe(page, normalUser);

  await page.goto("/");
  await expect(page.getByRole("link", { name: "管理入口" })).toHaveCount(0);

  await page.goto("/admin/reports");
  await expect(page.getByRole("heading", { name: "没有访问权限" })).toBeVisible();
});

test("游客同样看不到管理入口", async ({ page }) => {
  await mockAuthMe(page, null);

  await page.goto("/");
  await expect(page.getByRole("link", { name: "管理入口" })).toHaveCount(0);
});

// ── 举报处理写操作 ────────────────────────────────────────────────
// 覆盖 H-12 最后一类 mutation。此前管理端只有"入口可见性"测试，
// "处理举报"这个写操作零覆盖——而它恰好有过一个真 bug：
// 成功后本地 map 改 status，但 PENDING 过滤下那条记录还留在列表里，
// 用户看到"已确认"混在待处理队列。

const pendingReport = {
  id: "report-1",
  reporterId: "user-200",
  targetType: "POST",
  targetId: "post-1",
  reason: "SPAM",
  status: "PENDING",
  handledBy: null,
  handledAt: null,
  handlingNote: null,
  createdAt: "2026-10-01T10:00:00",
};

/** 造 count 条待处理举报。 */
function reports(count: number) {
  return Array.from({ length: count }, (_, index) => ({ ...pendingReport, id: `report-${index}`, targetId: `post-${index}` }));
}

test("确认举报后，该条从待处理列表消失（服务端过滤，不是本地改状态）", async ({ page }) => {
  await mockAuthMe(page, adminUser);

  // 服务端真相：处理完成后 PENDING 列表只剩 1 条。
  // 如果前端还靠本地 map 改 status，这一条会继续显示"已确认"混在队列里
  let remaining = reports(2);
  await page.route("**/api/v1/admin/reports**", async (route) => {
    if (route.request().method() === "GET") {
      await route.fulfill({ json: { data: remaining, meta: { page: 1, pageSize: 50, totalItems: remaining.length, totalPages: 1 } } });
      return;
    }
    const url = route.request().url();
    const reportId = url.split("/").slice(-2)[0];
    remaining = remaining.filter((report) => report.id !== reportId);
    await route.fulfill({ json: { data: { ...pendingReport, id: reportId, status: "CONFIRMED" } } });
  });
  // CSRF 前置
  await page.route("**/api/v1/auth/csrf", (route) =>
    route.fulfill({ status: 200, headers: { "set-cookie": "XSRF-TOKEN=test-csrf-token; Path=/" }, json: { code: "SUCCESS", data: null } }),
  );

  await page.goto("/admin/reports");
  // UI 显示的是 targetId（"目标 ID：post-N"），不是举报记录自己的 id
  await expect(page.getByText("目标 ID：post-0")).toBeVisible();
  await expect(page.getByText("目标 ID：post-1")).toBeVisible();

  // 确认第一条：先点"处理"展开，再点"确认处置"
  await page.getByRole("button", { name: "处理", exact: true }).first().click();
  await page.getByRole("button", { name: /确认处置/ }).click();
  await expect.poll(() => remaining.length).toBe(1);

  // 关键断言：列表按服务端真相重取，被处理的那条不再出现
  await expect(page.getByText("目标 ID：post-0")).toHaveCount(0);
  await expect(page.getByText("目标 ID：post-1")).toBeVisible();
});

test("切换过滤 tab 会按该状态重新请求", async ({ page }) => {
  await mockAuthMe(page, adminUser);

  const requestedStatuses: string[] = [];
  await page.route("**/api/v1/admin/reports**", async (route) => {
    const status = new URL(route.request().url()).searchParams.get("status");
    if (status) requestedStatuses.push(status);
    await route.fulfill({ json: { data: [], meta: { page: 1, pageSize: 50, totalItems: 0, totalPages: 1 } } });
  });

  await page.goto("/admin/reports");
  await expect(page.getByRole("heading", { name: "举报审核台" })).toBeVisible();

  // 默认 PENDING 已请求过一次；点"已驳回"应带 status=REJECTED 再请求
  await page.getByRole("button", { name: /已驳回/ }).click();
  await expect.poll(() => requestedStatuses).toContain("REJECTED");
});
