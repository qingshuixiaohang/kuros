import { expect, test, type Locator, type Page, type Response } from "@playwright/test";

/**
 * 真实全栈互动联调（PR #191 / #190 验收的最后一项）。
 *
 * 为什么必须真实栈：write-read-consistency.spec.ts 用 page.route 伪造后端和登录
 * Cookie，只能证明前端状态机在"假服务"下自洽，证明不了：
 *   浏览器登录 Cookie/CSRF → gateway :8080 → kuros-user / kuros-backend → MySQL/Redis
 * 这条链路真的把点赞、收藏写进数据库，并被另一个账号和另一个页面读到。本文件
 * 没有任何 page.route / route.fulfill；page.on("response") 只做只读断言监听。
 *
 * 前置（测试不负责启动这些服务）：
 *   - 中间件：docker compose up -d（MySQL/Redis/MinIO/Nacos/RocketMQ/Elasticsearch）。
 *   - kuros-user :8091、kuros-backend :8090、kuros-gateway :8080 已启动。
 *     kuros-user 必须处于 dev profile 且显式配置 APP_AUTH_DEV_CODE=123456。根 .env 与
 *     compose full profile 会注入该值；.run/ 里的 IDE 裸跑配置没有带这个环境变量，
 *     需要手动补上，否则验证码是随机值且不回显，下面的登录会失败。
 *   - 前端 :3000；未启动时 playwright.config.ts 的 webServer 会执行 npm run dev。
 *   - 前端默认 API 地址是 http://localhost:8080（NEXT_PUBLIC_API_BASE_URL）。
 *
 * 运行（只跑本文件，默认套件里它是 skip）：
 *   cd front
 *   KUROS_REAL_STACK=1 npx playwright test e2e/interaction-real-stack.spec.ts --workers=1
 * 必须单 worker：本文件写共享账号和共享帖子计数，多个 worker（例如默认的 --repeat-each）
 * 会并发登录同一手机号，后一次登录会因验证码已被消费而失败，也会让 ±1 计数断言互相干扰。
 *
 * 种子账号（kuros-user/src/main/resources/db/migration/V2__user_seed.sql）：
 *   13800000002 无音区夜行者（账号 A，默认）
 *   13800000003 今汐的留声机（账号 B，默认）
 *   可用 KUROS_REAL_STACK_PHONE_A / KUROS_REAL_STACK_PHONE_B 覆盖；手机号在 dev
 *   固定码模式下登录，未注册手机号会自动注册。验证码固定为 123456。
 *
 * 目标帖子：
 *   默认动态取首页"推荐"（后端 sort=hot）第一篇，读取真实标题和 UUID，不硬编码。
 *   种子固定回退：KUROS_REAL_STACK_POST_ID=10000000-0000-0000-0000-000000000004
 *   （V2__community_seed.sql 中点赞 8600、评论 112 的热门第一篇；首页排序/种子
 *   变化时可用它固定目标，帖子仍须出现在首页内容流第一页）。
 *
 * 断言口径：首页卡片把 >=1000 的计数缩写为 k/w（community-feed.tsx 的 feedCount），
 * 详情侧栏直接显示原始计数。为了能精确验证 ±1，这里监听页面自己发出的真实
 * GET /posts/{id}/interactions 响应取原始计数，首页按 feedCount 生成期望文本、
 * 详情按原始数字断言；不通过展示文本反推计数。该接口失败时等不到状态，测试会
 * 失败——这本身就是真实栈不可用的证据，不做降级。
 *
 * 首页计数口径：API 列表在 Guide.likeCount 保留原始值，卡片用原始值初始化；
 * 演示/搜索快照没有原始值时 parsePostCount 反解 "8.6k"/"1.2w" 缩写。此前
 * parseLikeCount 用 Number("8.6k") 得到 0（首页显示 0、详情显示 8600），
 * 本文件第 4 步在点赞前断言首页标签等于详情基线，钉住该一致性。
 */

const PHONE_A = process.env.KUROS_REAL_STACK_PHONE_A?.trim() || "13800000002";
const PHONE_B = process.env.KUROS_REAL_STACK_PHONE_B?.trim() || "13800000003";
const DEV_CODE = "123456";
const TARGET_POST_ID = process.env.KUROS_REAL_STACK_POST_ID?.trim() || "";

type InteractionState = {
  postId: string;
  likeCount: number;
  favoriteCount: number;
  liked: boolean;
  favorited: boolean;
};

function isInteractionState(value: unknown): value is InteractionState {
  if (!value || typeof value !== "object") return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record.postId === "string" &&
    typeof record.likeCount === "number" &&
    Number.isSafeInteger(record.likeCount) &&
    record.likeCount >= 0 &&
    typeof record.favoriteCount === "number" &&
    typeof record.liked === "boolean" &&
    typeof record.favorited === "boolean"
  );
}

/**
 * 监听页面自己发出的真实互动查询响应。监听器不拦截、不改写请求，只把
 * {postId, likeCount, favorited...} 追加进数组；mark() 用来只看某个动作之后
 * 到达的响应，避免把上一个账号/上一页的旧响应当成当前断言依据。
 */
function watchInteractionStates(page: Page) {
  const states: InteractionState[] = [];
  page.on("response", (response: Response) => {
    let path: string;
    try {
      path = new URL(response.url()).pathname;
    } catch {
      return;
    }
    // 只认单个帖子的互动读接口；/interactions/favorite 是写接口，batch-states 不带计数。
    if (response.request().method() !== "GET" || !/^\/api\/v1\/posts\/[^/]+\/interactions$/.test(path) || !response.ok()) return;
    void response
      .json()
      .then((body: unknown) => {
        const data = body && typeof body === "object" ? (body as { data?: unknown }).data : undefined;
        if (isInteractionState(data)) states.push(data);
      })
      .catch(() => {
        /* 非 JSON 响应（网关错误页等）不构成状态；等不到状态时断言会超时失败。 */
      });
  });
  let markIndex = 0;
  async function waitFor(predicate: (state: InteractionState) => boolean, message: string, timeout = 30_000) {
    await expect.poll(() => states.slice(markIndex).some(predicate), { message, timeout }).toBe(true);
    return states.slice(markIndex).filter(predicate).at(-1)!;
  }
  return {
    /** 标记进度；之后 waitFor 只考察此刻之后到达的响应。 */
    mark() {
      markIndex = states.length;
      return markIndex;
    },
    waitFor,
  };
}

/** 与 community-feed.tsx 的 feedCount 逐字一致（首页卡片 1000/10000 缩写）。 */
function feedCountLabel(value: number) {
  return value >= 10000
    ? (value / 10000).toFixed(1).replace(/\.0$/, "") + "w"
    : value >= 1000
      ? (value / 1000).toFixed(1).replace(/\.0$/, "") + "k"
      : String(value);
}

/**
 * LikeCoordinator 在 window pointerdown / focus 时 resume 未确认的操作
 * （like-operations-provider.tsx）。事件投影可能慢于客户端首轮轮询；轮询期间
 * 合成一次 pointerdown，让超过首个重试窗口的 pending 点赞继续确认。
 */
async function nudgeLikeCoordinator(page: Page) {
  try {
    await page.evaluate(() => {
      window.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true }));
    });
  } catch {
    /* 页面导航中的 evaluate 失败不构成断言失败；下一轮轮询会重新尝试。 */
  }
}

function communityFeed(page: Page) {
  return page.getByRole("region", { name: "社区内容流" });
}

/**
 * PostCard 的根节点是 <article className="post-card">，没有可访问名。不能用
 * getByRole("article") 取第一篇：内容流顶部的 Banner 轮播同样是 <article> 且带
 * 自己的 h2（community-carousel.tsx），会先命中轮播。这里用最小限度的 CSS 类
 * article.post-card 限定帖子卡，再用标题链接的 /guides/{id} 确定目标帖。
 */
function homePostCard(page: Page, postId: string) {
  return communityFeed(page)
    .locator("article.post-card")
    .filter({ has: page.locator(`a[href="/guides/${postId}"]`) });
}

/**
 * 首页点赞按钮没有 aria-label，只有 aria-pressed 和数字文本（community-feed.tsx）；
 * 按钮同时承载 pending 状态，所以 enabled + 没有"点赞待确认"才算服务端已确认。
 */
async function expectHomeLikeSettled(page: Page, card: Locator, expected: { pressed: boolean; label?: string }) {
  const button = card.locator("button[aria-pressed]");
  await expect
    .poll(
      async () => {
        await nudgeLikeCoordinator(page);
        if ((await button.count()) !== 1) return { state: "missing" };
        return {
          state: "present",
          pressed: await button.getAttribute("aria-pressed"),
          enabled: await button.isEnabled(),
          pending: await card.getByText("点赞待确认").count(),
        };
      },
      { message: `首页点赞按钮未稳定到 pressed=${expected.pressed}`, timeout: 60_000, intervals: [500, 1_000, 2_000, 5_000] },
    )
    .toEqual({ state: "present", pressed: String(expected.pressed), enabled: true, pending: 0 });
  if (expected.label !== undefined) await expect(button).toHaveText(expected.label, { timeout: 30_000 });
}

/**
 * 详情侧栏的点赞按钮用 aria-label 表达已赞/未赞，数字是原始计数（无 k/w 缩写）。
 * 按钮在 coordinator pending 期间 disabled；enabled + 无待确认 = 操作已 APPLIED。
 */
async function expectDetailLikeSettled(page: Page, expected: { liked: boolean; label: string }) {
  const rail = page.getByRole("complementary", { name: "帖子互动" });
  await expect(rail).toBeVisible();
  const button = rail.getByRole("button", { name: expected.liked ? "取消点赞" : "点赞", exact: true });
  await expect
    .poll(
      async () => {
        await nudgeLikeCoordinator(page);
        if ((await button.count()) !== 1) return { state: "missing" };
        return {
          state: "present",
          enabled: await button.isEnabled(),
          pending: await rail.getByText("点赞待确认").count(),
        };
      },
      { message: `详情页未稳定到${expected.liked ? "已赞" : "未赞"}态`, timeout: 60_000, intervals: [500, 1_000, 2_000, 5_000] },
    )
    .toEqual({ state: "present", enabled: true, pending: 0 });
  await expect(button).toHaveText(expected.label, { timeout: 30_000 });
}

/**
 * 真实 UI 登录：顶栏登录按钮 → 对话框 → 手机号 → 获取验证码（dev 固定码 123456）
 * → 勾选协议 → 登录 → 账户菜单出现。不 seed Cookie、不 mock /auth/me。
 */
async function loginAs(page: Page, phone: string) {
  await page.getByRole("button", { name: "登录", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "登录鸣潮社区" });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("textbox", { name: "手机号", exact: true }).fill(phone);

  const codeRequest = page.waitForResponse(
    (response) => {
      const url = new URL(response.url());
      return url.pathname === "/api/v1/auth/code" && response.request().method() === "POST";
    },
    { timeout: 30_000 },
  );
  await dialog.getByRole("button", { name: /获取验证码|重新获取/ }).click();
  const codeResponse = await codeRequest;
  expect(codeResponse.ok(), "验证码发送失败：确认 kuros-user 以 dev profile 启动并设置 APP_AUTH_DEV_CODE=123456").toBe(true);

  // dev 模式会把固定码回填到输入框；这里显式再填一次，兼容 dev-code-exposed=false。
  await dialog.getByRole("textbox", { name: "验证码", exact: true }).fill(DEV_CODE);
  await dialog.getByRole("checkbox", { name: /我已阅读并同意/ }).check();
  await dialog.getByRole("button", { name: "登录", exact: true }).click();

  await expect(dialog).toBeHidden({ timeout: 30_000 });
  await expect(page.getByRole("button", { name: "打开账户菜单" })).toBeVisible({ timeout: 30_000 });
}

/** 真实退出：账户菜单 → 退出登录 → 顶栏回到登录按钮。 */
async function logout(page: Page) {
  await page.getByRole("button", { name: "打开账户菜单" }).click();
  const accountMenu = page.getByRole("menu", { name: "账户面板" });
  await expect(accountMenu).toBeVisible();
  await accountMenu.getByRole("menuitem", { name: "退出登录" }).click();
  await expect(page.getByRole("button", { name: "登录", exact: true })).toBeVisible({ timeout: 30_000 });
}

/** 打开详情页；等互动侧栏出现，说明帖子详情请求已真实返回。 */
async function openPostDetail(page: Page, postId: string) {
  await page.goto(`/guides/${postId}`);
  await expect(page.getByRole("complementary", { name: "帖子互动" })).toBeVisible({ timeout: 30_000 });
}

test.describe("真实全栈互动一致性", () => {
  // 会真实写库：串行执行，避免同文件内的并发账号操作互相污染计数。
  test.describe.configure({ mode: "serial" });
  test.skip(process.env.KUROS_REAL_STACK !== "1", "需要真实全栈，设置 KUROS_REAL_STACK=1");

  test("账号 A 点赞收藏跨页面一致，账号 B 状态隔离，取消后计数回到基线", async ({ page }) => {
    test.setTimeout(240_000);
    const interactions = watchInteractionStates(page);

    // 1. 账号 A 真实登录（会话 Cookie 由登录接口写入，不伪造）
    await page.goto("/");
    await expect(page.getByRole("button", { name: "登录", exact: true })).toBeVisible({ timeout: 30_000 });
    await loginAs(page, PHONE_A);

    // 2. 首页取第一篇真实帖子：标题与 UUID 都从内容流读取，不硬编码
    const feed = communityFeed(page);
    const initialCard = TARGET_POST_ID ? homePostCard(page, TARGET_POST_ID) : feed.locator("article.post-card").first();
    await expect(initialCard, `首页内容流未找到目标帖子（${TARGET_POST_ID || "第一篇"}）；若排序变化可用 KUROS_REAL_STACK_POST_ID 指向种子 UUID`).toBeVisible({
      timeout: 30_000,
    });
    const title = (await initialCard.getByRole("heading", { level: 2 }).innerText()).trim();
    // 标题 h2 被指向详情页的 Link 包裹（community-feed.tsx）；标题链接没有可访问名，
    // 用 href 前缀取到锚点，再从 URL 末段读取 UUID。
    const href = await initialCard.locator('a[href^="/guides/"]').first().getAttribute("href");
    const postId = TARGET_POST_ID || (href ? (href.split("/").pop() ?? "") : "");
    expect(postId, "首页第一篇没有 /guides/{uuid} 链接，后端内容流可能不可用").toMatch(/^[0-9a-f]{8}-[0-9a-f-]{27}$/i);
    expect(title.length, "首页第一篇标题为空").toBeGreaterThan(0);
    expect(href).toBe(`/guides/${postId}`);

    const card = homePostCard(page, postId);
    await expect(card).toHaveCount(1);
    const homeLike = card.locator("button[aria-pressed]");
    // enabled 等价于 A 的批量互动状态已加载：interaction 未就绪时按钮 disabled。
    await expect(homeLike).toBeEnabled({ timeout: 30_000 });
    await expect(homeLike).toHaveAttribute("aria-pressed", /^(true|false)$/);

    // 上次运行可能已经点过赞：先把 A 的旧赞取消，保证基线确定（串行测试可重跑）。
    if ((await homeLike.getAttribute("aria-pressed")) === "true") {
      await homeLike.click();
      await expectHomeLikeSettled(page, card, { pressed: false });
    }

    // 3. 进详情读取数据库当前值作为基线（此时 A 未赞）
    interactions.mark();
    await openPostDetail(page, postId);
    const baselineState = await interactions.waitFor(
      (state) => state.postId === postId && state.liked === false,
      "详情页未收到未赞的互动查询响应（互动服务或网关不可用？）",
    );
    const baseline = baselineState.likeCount;
    await expect(page.getByRole("heading", { level: 1, name: title, exact: true })).toBeVisible();
    await expectDetailLikeSettled(page, { liked: false, label: String(baseline) });

    // 4. 回首页点赞：按钮进入已赞态、待确认消失，计数按首页格式 +1。
    //    首页对 >=1000 的计数做 k 缩写，精确 +1 在第 5 步用详情原始值断言。
    //    点赞前不断言卡片数字：它来自被缩写的 guide.likes 兜底解析（见文件头），
    //    本次操作确认后由 LikeCoordinator 的 APPLIED 结果提供权威计数再断言。
    await page.goto("/");
    await expect(page.getByRole("button", { name: "打开账户菜单" })).toBeVisible({ timeout: 30_000 });
    const likeCard = homePostCard(page, postId);
    await expect(likeCard).toHaveCount(1);
    const likeButton = likeCard.locator("button[aria-pressed]");
    await expect(likeButton).toBeEnabled({ timeout: 30_000 });
    await expect(likeButton).toHaveAttribute("aria-pressed", "false");
    // 点赞前首页计数必须与详情基线一致：API 原始 likeCount 是精确值，
    // 不能用 Number("8.6k") 解析成 0。
    await expect(likeButton).toHaveText(feedCountLabel(baseline), { timeout: 30_000 });
    await likeButton.click();
    const likeCountAfterA = baseline + 1;
    await expectHomeLikeSettled(page, likeCard, { pressed: true, label: feedCountLabel(likeCountAfterA) });

    // 5. 详情页读回已赞：liked=true 的响应只能来自 A 的用户 key，计数必须 +1
    interactions.mark();
    await openPostDetail(page, postId);
    const afterLikeState = await interactions.waitFor(
      (state) => state.postId === postId && state.liked,
      "详情页未读到账号 A 的已赞状态（首页点赞未落库或账号串了？）",
    );
    expect(afterLikeState.likeCount).toBe(likeCountAfterA);
    await expectDetailLikeSettled(page, { liked: true, label: String(afterLikeState.likeCount) });

    // 6. 详情页收藏：读回 favorited=true 再断言按钮进入"取消收藏/已藏"。
    //    若重跑时已收藏，先取消一次，保证这次真的经历 favorite=false → true。
    const rail = page.getByRole("complementary", { name: "帖子互动" });
    if (afterLikeState.favorited) {
      interactions.mark();
      await rail.getByRole("button", { name: "取消收藏", exact: true }).click();
      await interactions.waitFor((state) => state.postId === postId && !state.favorited, "取消收藏后未读到未收藏状态");
      await expect(rail.getByRole("button", { name: "收藏", exact: true })).toBeVisible({ timeout: 30_000 });
    }
    interactions.mark();
    await rail.getByRole("button", { name: "收藏", exact: true }).click();
    await interactions.waitFor((state) => state.postId === postId && state.favorited, "收藏写后未读到 favorited=true");
    await expect(rail.getByRole("button", { name: "取消收藏", exact: true })).toHaveText("已藏", { timeout: 30_000 });
    await expect(rail.getByRole("button", { name: "取消收藏", exact: true })).toBeEnabled();

    // 7. 个人中心"收藏"tab 必须能看到该帖标题
    await page.goto("/profile");
    await expect(page.getByRole("region", { name: "个人资料概览" })).toBeVisible({ timeout: 30_000 });
    const profileNav = page.getByRole("complementary", { name: "个人中心导航" });
    await profileNav.getByRole("link", { name: "收藏", exact: true }).click();
    await expect(page).toHaveURL(/\/profile\?tab=favorites$/);
    await expect(profileNav.getByRole("link", { name: "收藏", exact: true })).toHaveAttribute("aria-current", "page");
    // 收藏面板是唯一带 profile-panel 的 section（community-profile.tsx），没有可访问名。
    const favoritesPanel = page.locator("section.profile-panel");
    await expect(favoritesPanel.getByRole("heading", { name: "收藏", exact: true })).toBeVisible();
    await expect(favoritesPanel.getByRole("heading", { name: title, exact: true })).toBeVisible({ timeout: 30_000 });

    // 8. 回详情确认 A 的点赞/收藏跨页面保留
    interactions.mark();
    await openPostDetail(page, postId);
    const persistedState = await interactions.waitFor((state) => state.postId === postId && state.liked, "从个人中心返回详情后未读到 A 的已赞状态");
    expect(persistedState.likeCount).toBe(likeCountAfterA);
    expect(persistedState.favorited).toBe(true);
    await expectDetailLikeSettled(page, { liked: true, label: String(likeCountAfterA) });

    // 9. 退出 A → 登录 B：同一帖详情必须未赞，计数不能低于 A 点赞后的值（A 的赞还在库里）
    interactions.mark();
    await logout(page);
    // QueryClient 的 staleTime 是 30s：退出只改 userId，游客 key 若刚被访问过会命中缓存
    // 不发新请求。reload 保证这里读的是服务端按新身份返回的状态，而不是上一个账号的缓存。
    await page.reload();
    await expect(page.getByRole("complementary", { name: "帖子互动" })).toBeVisible({ timeout: 30_000 });
    const guestState = await interactions.waitFor((state) => state.postId === postId && !state.liked, "退出登录后未读到游客互动状态");
    expect(guestState.likeCount).toBeGreaterThanOrEqual(likeCountAfterA);
    await expectDetailLikeSettled(page, { liked: false, label: String(guestState.likeCount) });

    interactions.mark();
    await loginAs(page, PHONE_B);
    const bState = await interactions.waitFor(
      (state) => state.postId === postId && !state.liked && state.likeCount >= likeCountAfterA,
      "账号 B 登录后未读到未赞且包含 A 点赞计数的互动状态",
    );
    expect(bState.liked).toBe(false);
    expect(bState.likeCount).toBeGreaterThanOrEqual(likeCountAfterA);
    expect(bState.favorited).toBe(false);
    await expectDetailLikeSettled(page, { liked: false, label: String(bState.likeCount) });
    await expect(rail.getByRole("button", { name: "收藏", exact: true })).toBeVisible({ timeout: 30_000 });

    // 10. 退出 B → 切回 A：已赞态仍在；取消点赞后计数回到基线
    interactions.mark();
    await logout(page);
    await page.reload();
    await expect(page.getByRole("complementary", { name: "帖子互动" })).toBeVisible({ timeout: 30_000 });
    await interactions.waitFor((state) => state.postId === postId && !state.liked, "退出 B 后未读到游客互动状态");

    interactions.mark();
    await loginAs(page, PHONE_A);
    // 同一步骤 9：A 的 key 在步骤 8 取过，30s 内可能仍fresh；reload 强制按 A 身份重取。
    await page.reload();
    await expect(page.getByRole("complementary", { name: "帖子互动" })).toBeVisible({ timeout: 30_000 });
    const aAgainState = await interactions.waitFor((state) => state.postId === postId && state.liked, "账号 A 重新登录后未读到已赞状态");
    expect(aAgainState.likeCount).toBe(likeCountAfterA);
    await expectDetailLikeSettled(page, { liked: true, label: String(aAgainState.likeCount) });

    interactions.mark();
    await rail.getByRole("button", { name: "取消点赞", exact: true }).click();
    const unlikedState = await interactions.waitFor(
      (state) => state.postId === postId && !state.liked && state.likeCount === baseline,
      "取消点赞后未读到回到基线的权威未赞状态",
      60_000,
    );
    expect(unlikedState.likeCount).toBe(baseline);
    await expectDetailLikeSettled(page, { liked: false, label: String(baseline) });

    // 11. 首页与详情都回到基线，两个视图一致
    await page.goto("/");
    await expect(page.getByRole("button", { name: "打开账户菜单" })).toBeVisible({ timeout: 30_000 });
    const finalCard = homePostCard(page, postId);
    await expect(finalCard).toHaveCount(1);
    await expectHomeLikeSettled(page, finalCard, { pressed: false, label: feedCountLabel(baseline) });

    interactions.mark();
    await openPostDetail(page, postId);
    const finalState = await interactions.waitFor((state) => state.postId === postId && !state.liked, "最终详情页未读到未赞状态");
    expect(finalState.likeCount).toBe(baseline);
    await expectDetailLikeSettled(page, { liked: false, label: String(baseline) });
  });
});
