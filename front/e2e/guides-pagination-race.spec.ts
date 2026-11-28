import { expect, test } from "@playwright/test";

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

test("筛选切换期间旧分页不可操作，迟到响应不触发越界请求", async ({ page }) => {
  const oldResponse = deferred();
  const oldDelivered = deferred();
  const newResponse = deferred();
  const requested: string[] = [];
  await page.route(/\/api\/v1\/posts(?:\?.*)?$/, async (route) => {
    const url = new URL(route.request().url());
    const category = url.searchParams.get("category");
    const number = Number(url.searchParams.get("page"));
    if (url.searchParams.get("pageSize") !== "10") {
      await route.fulfill({ json: { code: "SUCCESS", data: [], meta: { page: number, pageSize: 5, totalItems: 0, totalPages: 0 } } });
      return;
    }
    requested.push(`${category}:${number}`);
    if (category === "深塔攻略") await newResponse.promise;
    else if (number === 10) await oldResponse.promise;
    const totalPages = category === "深塔攻略" ? 2 : 10;
    await route.fulfill({ json: { code: "SUCCESS", data: [], meta: { page: number, pageSize: 10, totalItems: totalPages * 10, totalPages } } });
    if (category !== "深塔攻略" && number === 10) oldDelivered.resolve();
  });

  try {
    await page.goto("/guides");
    const pagination = page.getByRole("navigation", { name: "攻略分页" });
    await pagination.getByRole("button", { name: "10", exact: true }).click();
    await expect.poll(() => requested.some((request) => request.endsWith(":10"))).toBe(true);
    await page.getByRole("button", { name: "深塔攻略", exact: true }).click();
    await expect.poll(() => requested.includes("深塔攻略:1")).toBe(true);
    await expect(pagination.getByRole("button", { name: "10", exact: true })).toBeDisabled();
    oldResponse.resolve();
    await oldDelivered.promise;
    await expect(pagination.getByRole("button", { name: "10", exact: true })).toBeDisabled();
    newResponse.resolve();
    await expect(pagination.getByRole("button", { name: "1", exact: true })).toHaveAttribute("aria-current", "page");
    await expect(pagination.getByRole("button", { name: "2", exact: true })).toBeEnabled();
    expect(requested.filter((request) => request.startsWith("深塔攻略:"))).toEqual(["深塔攻略:1"]);
  } finally {
    oldResponse.resolve();
    newResponse.resolve();
  }
});

for (const remaining of [3, 1, 0]) {
  test(`总页数减少到 ${remaining} 时恢复到有效页`, async ({ page }) => {
    const requested: number[] = [];
    let reduced = false;
    await page.route(/\/api\/v1\/posts(?:\?.*)?$/, async (route) => {
      const url = new URL(route.request().url());
      const number = Number(url.searchParams.get("page"));
      if (url.searchParams.get("pageSize") !== "10") {
        await route.fulfill({ json: { code: "SUCCESS", data: [], meta: { page: number, pageSize: 5, totalItems: 0, totalPages: 0 } } });
        return;
      }
      requested.push(number);
      if (number === 10) reduced = true;
      const totalPages = reduced ? remaining : 10;
      await route.fulfill({ json: { code: "SUCCESS", data: [], meta: { page: number, pageSize: 10, totalItems: totalPages * 10, totalPages } } });
    });
    await page.goto("/guides");
    const pagination = page.getByRole("navigation", { name: "攻略分页" });
    await pagination.getByRole("button", { name: "10", exact: true }).click();
    await expect.poll(() => requested).toEqual([1, 10, Math.max(1, remaining)]);
    if (remaining > 1) {
      await expect(pagination.getByRole("button", { name: String(remaining), exact: true })).toHaveAttribute("aria-current", "page");
      await expect(pagination.getByRole("button", { name: "下一页" })).toBeDisabled();
    } else {
      await expect(pagination).toHaveCount(0);
      await expect(page.getByText("没有找到匹配的攻略。")).toBeVisible();
    }
  });
}
