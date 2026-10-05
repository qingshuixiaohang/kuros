import { expect, test } from "@playwright/test";

// 管理入口（顶栏"管理"，仅 ADMIN 可见）+ 举报审核台访问控制
// 安全边界说明：前端隐藏是体验，真正的门禁是后端 SaToken 的 ADMIN 校验

const adminUser = { id: "user-100", phone: "13800000001", nickname: "潮声档案员", avatarUrl: null, bio: "记录鸣潮实战", role: "ADMIN" };
const normalUser = { ...adminUser, role: "USER" };

function mockAuthMe(page: import("@playwright/test").Page, user: Record<string, unknown> | null) {
  return page.route("http://localhost:8080/api/v1/auth/me", (route) =>
    user ? route.fulfill({ json: { data: user } }) : route.fulfill({ status: 401, json: { code: "UNAUTHORIZED", message: "请先登录" } })
  );
}

test("管理员登录后顶栏出现管理入口，可直达举报审核台", async ({ page }) => {
  await mockAuthMe(page, adminUser);
  await page.route("http://localhost:8080/api/v1/admin/reports**", (route) =>
    route.fulfill({ json: { data: [], meta: { page: 1, pageSize: 10, totalItems: 0, totalPages: 0 } } })
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
