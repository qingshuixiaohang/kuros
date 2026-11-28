import { expect, test } from "@playwright/test";

test("攻略分页在大量页码下保持紧凑并保留筛选", async ({ page }) => {
  let totalPages = 955;
  await page.route(/\/api\/v1\/posts(?:\?.*)?$/, async (route) => {
    const url = new URL(route.request().url());
    const pageNumber = Number(url.searchParams.get("page") ?? "1");
    await route.fulfill({
      json: { code: "SUCCESS", data: [], meta: { page: pageNumber, pageSize: 10, totalItems: totalPages * 10, totalPages } },
    });
  });

  await page.goto("/guides");
  const pagination = page.getByRole("navigation", { name: "攻略分页" });
  await expect(pagination.getByRole("button")).toHaveCount(5);
  await expect(pagination.getByRole("button", { name: "上一页" })).toBeDisabled();
  await expect(pagination.getByRole("button", { name: "1", exact: true })).toHaveAttribute("aria-current", "page");
  await expect(pagination.getByText("…")).toHaveCount(1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1280);

  await pagination.getByRole("button", { name: "955", exact: true }).click();
  await expect(pagination.getByRole("button", { name: "955", exact: true })).toHaveAttribute("aria-current", "page");
  await expect(pagination.getByRole("button", { name: "下一页" })).toBeDisabled();
  await pagination.getByRole("button", { name: "上一页" }).click();
  await expect(pagination.getByRole("button", { name: "954", exact: true })).toHaveAttribute("aria-current", "page");

  totalPages = 478;
  await page.getByRole("button", { name: "深塔攻略", exact: true }).click();
  await expect(pagination.getByRole("button", { name: "478", exact: true })).toBeVisible();
  totalPages = 955;
  const request = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return url.pathname === "/api/v1/posts" && url.searchParams.get("page") === "478";
  });
  await pagination.getByRole("button", { name: "478", exact: true }).click();
  expect(new URL((await request).url()).searchParams.get("category")).toBe("深塔攻略");
  await expect(pagination.getByRole("button", { name: "478", exact: true })).toHaveAttribute("aria-current", "page");
  await expect(pagination.getByRole("button")).toHaveCount(7);
  await expect(pagination.getByText("…")).toHaveCount(2);
  await expect(pagination.getByRole("button", { name: "477", exact: true })).toBeVisible();
  await expect(pagination.getByRole("button", { name: "479", exact: true })).toBeVisible();
  await expect(pagination.getByRole("button", { name: "955", exact: true })).toBeVisible();
  await pagination.getByRole("button", { name: "下一页" }).click();
  await expect(pagination.getByRole("button", { name: "479", exact: true })).toHaveAttribute("aria-current", "page");
});
