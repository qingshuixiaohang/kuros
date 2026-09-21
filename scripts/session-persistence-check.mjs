// 分布式会话持久性验证：本切片（SaToken + Redis）的灵魂测试。
//
// 验证命题：kuros-user Java 进程重启后，用户手里的旧 Cookie 依然有效。
// - 旧架构（Spring Security + 进程内存会话）：重启即全体掉线，check 会得到 401
// - 新架构（SaToken + Redis）：会话存在独立的 Redis 进程里，服务重启不影响，check 得到 200
//
// 为什么单独写脚本而不用 compose-smoke.mjs？
// smoke 脚本每轮都会重新登录，验证的是"数据持久化"（MySQL/文件）；
// 本脚本在 restart 前后复用同一个 Cookie 且不重新登录，验证的是"会话持久化"（Redis）。
//
// 用法（在仓库根目录）：
//   node scripts/session-persistence-check.mjs login    # 登录，保存 Cookie，立即用它访问需登录接口（应 200）
//   docker compose restart user                         # 等 user 重新 healthy
//   node scripts/session-persistence-check.mjs check    # 不重新登录，用保存的 Cookie 再访问（200 = 验证通过）

import { readFile, writeFile } from "node:fs/promises";

const apiBase = process.env.KUROS_API_BASE_URL ?? "http://localhost:8080";
// check 阶段探活直连 user（宿主 8091）：8080 是网关的 health，user 重启期间它一直 200，
// 不能代表 user 已就绪；8091 是 compose 给 user 留的直连调试通道（容器内仍是 8080）
const userBase = process.env.KUROS_USER_BASE_URL ?? "http://localhost:8091";
const statePath = process.env.KUROS_SESSION_STATE ?? ".session-persistence-state.json";
// 手机号取 kuros_user V2 种子 13800000003（与 smoke 脚本的 13800000002 区分，
// 避免测试数据互相干扰）：split-07 起会话探针 /api/v1/auth/me 是 kuros_user
// 自有端点，任意登录用户都可访问，不再依赖"两侧共有种子号"的跨库用户行
const PHONE = "13800000003";
const DEV_CODE = "123456"; // compose dev 环境的固定验证码（APP_AUTH_DEV_CODE）

async function postJson(path, payload, cookie) {
  const headers = { "Content-Type": "application/json" };
  if (cookie) headers.Cookie = cookie;
  const response = await fetch(apiBase + path, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
  });
  const text = await response.text();
  return { response, text };
}

// split-07：/api/v1/users/me/profile 仍属 backend（窗口期整端 503），会话探针改用
// kuros_user 自有的 /api/v1/auth/me——探针与会话同服务同库，验证的正是
// "user 重启后旧 Cookie 是否仍被识别"；经网关 8080 走真实用户路径
async function fetchMe(sessionCookie) {
  const response = await fetch(apiBase + "/api/v1/auth/me", {
    headers: { Cookie: sessionCookie },
  });
  const text = await response.text();
  return { status: response.status, text };
}

// 从 Set-Cookie 响应头里提取 SaToken 会话 Cookie（名字由 sa-token.token-name 配置，当前为 KUROS_SESSION）
function extractSessionCookie(response) {
  for (const value of response.headers.getSetCookie?.() ?? []) {
    const [pair] = value.split(";", 1);
    const separator = pair.indexOf("=");
    const name = pair.slice(0, separator);
    if (name && name !== "XSRF-TOKEN" && pair.slice(separator + 1)) {
      return pair;
    }
  }
  return undefined;
}

const mode = process.argv[2] ?? "login";

if (mode === "login") {
  const code = await postJson("/api/v1/auth/code", { phone: PHONE });
  if (!code.response.ok) throw new Error(`POST /auth/code returned ${code.response.status}: ${code.text}`);

  const login = await postJson("/api/v1/auth/login", { phone: PHONE, code: DEV_CODE });
  if (!login.response.ok) throw new Error(`POST /auth/login returned ${login.response.status}: ${login.text}`);

  const sessionCookie = extractSessionCookie(login.response);
  if (!sessionCookie) throw new Error("登录响应没有返回会话 Cookie（检查 sa-token.is-read-cookie 配置）");

  const before = await fetchMe(sessionCookie);
  if (before.status !== 200) {
    throw new Error(`登录后立即访问 /api/v1/auth/me 就失败了：HTTP ${before.status} ${before.text}`);
  }
  console.log(`[login] 登录成功，/api/v1/auth/me 返回 200，会话 Cookie: ${sessionCookie.slice(0, 24)}...`);

  await writeFile(statePath, `${JSON.stringify({ sessionCookie, phone: PHONE }, null, 2)}\n`, "utf8");
  console.log(`[login] Cookie 已保存到 ${statePath}`);
  console.log("[login] 下一步: docker compose restart user，等 healthy 后运行 check 模式");
} else if (mode === "check") {
  let state;
  try {
    state = JSON.parse(await readFile(statePath, "utf8"));
  } catch {
    throw new Error(`找不到 ${statePath}，请先运行 login 模式`);
  }

  const health = await fetch(userBase + "/actuator/health");
  if (!health.ok) throw new Error(`user 服务还没就绪：GET ${userBase}/actuator/health returned ${health.status}`);

  const after = await fetchMe(state.sessionCookie);
  if (after.status === 200) {
    console.log(`[check] ✅ 会话在 user 重启后依然有效（/api/v1/auth/me 200）——分布式会话验证通过`);
  } else if (after.status === 401) {
    console.error(`[check] ❌ 重启后旧 Cookie 失效（401）——会话没有存进 Redis，检查 sa-token-dao 配置`);
    process.exitCode = 1;
  } else {
    console.error(`[check] ❌ 意外状态码 HTTP ${after.status}: ${after.text}`);
    process.exitCode = 1;
  }
} else {
  console.error(`未知模式 "${mode}"，用法: node scripts/session-persistence-check.mjs <login|check>`);
  process.exitCode = 1;
}
