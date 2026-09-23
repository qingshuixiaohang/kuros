// 切片 #14 se-07 · S3：搜索 + CDC 端到端冒烟（真实 Canal→binlog→RocketMQ→consumer→ES 全链路）。
//
// 为什么单独成脚本而不并进 compose-smoke.mjs：本冒烟依赖 ES + canal-server + rocketmq 三个额外容器全部就绪，
// 且核心产出是「发帖→可搜」的端到端延迟实测（禁止编造，只能真跑采集）。与只验证 CRUD/持久化的 compose-smoke
// 关注点不同，独立成脚本便于 CI deployment job 单独 gate、失败时定位到搜索链路而非主链路。
//
// 链路：登录 → 发帖（写 MySQL）→ MySQL binlog → canal 伪装 slave 订阅 → RocketMQ(kuros-post-cdc, FlatMessage)
//       → backend postCdcConsumer → PostCdcHandler 回源组装 → PostIndexService.index → ES 文档 → GET /api/v1/search 命中。
// 三步验证：① 发帖→可搜（记录索引延迟）② 改帖→搜到新内容（记录同步延迟）③ 逻辑删→搜索消失（status 过滤，记录延迟）。
//
// 运行：node scripts/search-cdc-smoke.mjs（需 compose 全栈已 up：backend/gateway/user/es/canal/rocketmq/mysql/redis）。
// 环境变量：KUROS_API_BASE_URL（默认 http://localhost:8080 网关）、KUROS_SEARCH_TIMEOUT_MS（默认 90000）。

const apiBase = process.env.KUROS_API_BASE_URL ?? "http://localhost:8080";
const timeoutMs = Number(process.env.KUROS_SEARCH_TIMEOUT_MS ?? 90000);
const pollIntervalMs = Number(process.env.KUROS_SEARCH_POLL_MS ?? 500);
const cookies = new Map();

function rememberCookies(response) {
  for (const value of response.headers.getSetCookie?.() ?? []) {
    const [pair] = value.split(";", 1);
    const separator = pair.indexOf("=");
    if (separator > 0) cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
  }
}

function cookieHeader() {
  return [...cookies.entries()].map(([name, value]) => `${name}=${value}`).join("; ");
}

async function request(path, init = {}) {
  const headers = new Headers(init.headers);
  const cookie = cookieHeader();
  if (cookie) headers.set("Cookie", cookie);
  const response = await fetch(apiBase + path, { ...init, headers });
  rememberCookies(response);
  const text = await response.text();
  let body;
  try {
    body = text ? JSON.parse(text) : undefined;
  } catch {
    body = text;
  }
  if (!response.ok) {
    throw new Error(`${init.method ?? "GET"} ${path} returned ${response.status}: ${text}`);
  }
  return body;
}

function csrfHeaders() {
  const token = cookies.get("XSRF-TOKEN");
  if (!token) throw new Error("XSRF-TOKEN cookie was not issued");
  return { "X-XSRF-TOKEN": decodeURIComponent(token) };
}

/**
 * 搜索命中判定：GET /api/v1/search?keyword= 返回的 items 是否含目标 postId。
 *
 * tolerateUnavailable=true 时把 503（索引尚未由首条 CDC 写入经 ensureAlias 懒创建）当作“暂无命中”返回空数组而非抛错——
 * 专供第①②步的 pollUntil 谓词：发帖后到别名建好前有个窗口，期间 /search 必然 503，若谓词抛异常会直接中止轮询
 * （实测踩坑：门禁容忍了初始 503，却漏了 pollUntil 谓词，发帖成功后仍因轮询期 503 崩在第一次 poll）。
 * 返回空数组让 pollUntil 视作“还没同步好”继续轮询，直到别名建好、命中目标 postId。
 * 第③步删除校验传 false：此时索引必已存在（①②已写入），503 是真故障应暴露，不能误判成“已删除”。
 */
async function searchHits(keyword, tolerateUnavailable = false) {
  try {
    const envelope = await request(`/api/v1/search?keyword=${encodeURIComponent(keyword)}&limit=50`);
    return (envelope?.data?.items ?? []).map((item) => item.id);
  } catch (error) {
    if (tolerateUnavailable && String(error.message).includes("returned 503")) return [];
    throw error;
  }
}

/** 轮询直到 predicate 为真或超时；返回耗时 ms（端到端延迟实测，回填 docs/learning/14，禁止编造）。 */
async function pollUntil(label, predicate) {
  const start = Date.now();
  const deadline = start + timeoutMs;
  let attempt = 0;
  while (Date.now() < deadline) {
    attempt += 1;
    if (await predicate()) {
      const elapsed = Date.now() - start;
      console.log(`[PERF] ${label}: ${elapsed} ms（轮询 ${attempt} 次，间隔 ${pollIntervalMs} ms）`);
      return elapsed;
    }
    await new Promise((resolve) => setTimeout(resolve, pollIntervalMs));
  }
  throw new Error(`${label} 在 ${timeoutMs} ms 内未收敛（CDC 链路可能未打通：检查 canal-server / rocketmq / backend postCdcConsumer 日志）`);
}

async function login() {
  // kuros_user V2 种子号 13800000002（昵称“无音区夜行者”），与 compose-smoke 同一登录路径
  await request("/api/v1/auth/code", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ phone: "13800000002" }),
  });
  await request("/api/v1/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ phone: "13800000002", code: "123456" }),
  });
  await request("/api/v1/auth/csrf");
}

async function main() {
  // 前置：网关 + backend 就绪
  await request("/actuator/health");
  // ES 索引由首条 CDC 写入经 ensureAlias 懒创建（PostIndexService.index 先建别名再写文档）：全新 ES 上
  // post_search 别名尚不存在，/search 会返回 503（se-04 设计：索引异常一律降级“暂不可用”，不静默空、不 500）。
  // 这不是链路故障——下面第①步发帖经 CDC 索引后别名即自动建好。故此处容忍 503，仅把非 503 错误当真故障早失败定位。
  try {
    await searchHits("健康检查");
    console.log("[SMOKE] 搜索端点就绪：post_search 别名已存在（索引此前已初始化）");
  } catch (error) {
    if (String(error.message).includes("returned 503")) {
      console.log("[SMOKE] 搜索端点返回 503：索引尚未初始化（全新 ES），将由首条 CDC 写入经 ensureAlias 懒创建，继续…");
    } else {
      throw error;
    }
  }

  await login();

  // 纯数字唯一 token：ik 把连续阿拉伯数字切成单一 token，搜索该 token 精确命中本帖，规避中文/英文分词不确定性
  const token = String(Date.now());

  // ① 发帖 → 轮询可搜（记录 CDC 索引端到端延迟）
  const created = await request("/api/v1/posts", {
    method: "POST",
    headers: { ...csrfHeaders(), "Content-Type": "application/json" },
    body: JSON.stringify({
      type: "GUIDE",
      category: "配队攻略",
      title: `冒烟${token}`,
      excerpt: "搜索 CDC 端到端冒烟",
      content: `端到端${token}索引验证正文`,
      tags: ["冒烟"],
    }),
  });
  const postId = created?.data?.id;
  if (!postId) throw new Error("发帖未返回 id");
  console.log(`[SMOKE] 已发帖 postId=${postId} token=${token}，等待 CDC 同步进 ES…`);

  const indexLatency = await pollUntil("发帖→可搜（CDC 索引延迟）", async () =>
    (await searchHits(token, true)).includes(postId));

  // ② 改帖（换新 token）→ 轮询搜到新 token（记录 CDC 更新同步延迟）
  const editToken = `${token}9`;
  await request(`/api/v1/posts/${postId}`, {
    method: "PUT",
    headers: { ...csrfHeaders(), "Content-Type": "application/json" },
    body: JSON.stringify({
      type: "GUIDE",
      category: "配队攻略",
      title: `冒烟改${editToken}`,
      excerpt: "搜索 CDC 端到端冒烟（已编辑）",
      content: `改帖后${editToken}索引同步验证`,
      tags: ["冒烟"],
    }),
  });
  const editLatency = await pollUntil("改帖→搜到新内容（CDC 更新延迟）", async () =>
    (await searchHits(editToken, true)).includes(postId));

  // ③ 逻辑删 → 轮询搜索不再返回（status=DELETED 被查询期 filter 排除，文档不物理删）
  await request(`/api/v1/posts/${postId}`, { method: "DELETE", headers: csrfHeaders() });
  const deleteLatency = await pollUntil("逻辑删→搜索消失（CDC 删除同步延迟）", async () =>
    !(await searchHits(editToken)).includes(postId));

  console.log("[PERF] ===== 搜索 CDC 端到端延迟实测（真实 Canal→RocketMQ→backend→ES 全链路，禁止编造）=====");
  console.log(`[PERF] 发帖→可搜   : ${indexLatency} ms`);
  console.log(`[PERF] 改帖→同步   : ${editLatency} ms`);
  console.log(`[PERF] 逻辑删→消失 : ${deleteLatency} ms`);
  console.log(`[SMOKE] 搜索 CDC 端到端冒烟通过（postId=${postId} 全生命周期同步正确）`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
