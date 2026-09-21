import { readFile, writeFile } from "node:fs/promises";

const apiBase = process.env.KUROS_API_BASE_URL ?? "http://localhost:8080";
const frontendBase = process.env.KUROS_FRONTEND_BASE_URL ?? "http://localhost:3000";
const nacosBase = process.env.KUROS_NACOS_BASE_URL ?? "http://localhost:8848";
const nacosConsoleBase = process.env.KUROS_NACOS_CONSOLE_URL ?? "http://localhost:8081";
const statePath = process.env.KUROS_SMOKE_STATE ?? ".compose-smoke-state.json";
const cookies = new Map();
const smokePng = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);

function rememberCookies(response) {
  const setCookies = response.headers.getSetCookie?.() ?? [];
  for (const value of setCookies) {
    const [pair] = value.split(";", 1);
    const separator = pair.indexOf("=");
    if (separator > 0) cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
  }
}

function cookieHeader() {
  return [...cookies.entries()].map(([name, value]) => `${name}=${value}`).join("; ");
}

async function request(base, path, init = {}) {
  const headers = new Headers(init.headers);
  const cookie = cookieHeader();
  if (cookie) headers.set("Cookie", cookie);
  const response = await fetch(base + path, { ...init, headers });
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
  return { response, body };
}

function csrfHeaders() {
  const token = cookies.get("XSRF-TOKEN");
  if (!token) throw new Error("XSRF-TOKEN cookie was not issued");
  return { "X-XSRF-TOKEN": decodeURIComponent(token) };
}

async function readState() {
  try {
    return JSON.parse(await readFile(statePath, "utf8"));
  } catch (error) {
    if (error.code === "ENOENT") return undefined;
    throw error;
  }
}

async function writeState(state) {
  await writeFile(statePath, `${JSON.stringify(state, null, 2)}\n`, "utf8");
}

async function main() {
  await request(apiBase, "/actuator/health");
  const frontend = await fetch(frontendBase + "/");
  if (!frontend.ok) throw new Error(`GET / returned ${frontend.status}`);

  await verifyNacosRegistration();

  const state = await readState();

  await request(apiBase, "/api/v1/auth/code", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ phone: "13800000008" }),
  });
  await request(apiBase, "/api/v1/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ phone: "13800000008", code: "123456" }),
  });
  await request(apiBase, "/api/v1/auth/csrf");

  let persisted = state;
  if (!persisted) {
    const title = `Compose smoke ${Date.now()}`;
    const post = await request(apiBase, "/api/v1/posts", {
      method: "POST",
      headers: { ...csrfHeaders(), "Content-Type": "application/json" },
      body: JSON.stringify({
        type: "GUIDE",
        category: "配队攻略",
        title,
        excerpt: "Compose smoke test",
        content: "Compose smoke test content",
        tags: ["compose"],
      }),
    });
    const postId = post.body?.data?.id;
    if (!postId) throw new Error("Post creation did not return an id");

    await request(apiBase, `/api/v1/posts/${postId}/comments`, {
      method: "POST",
      headers: { ...csrfHeaders(), "Content-Type": "application/json" },
      body: JSON.stringify({ content: "Compose smoke comment" }),
    });

    const form = new FormData();
    form.append("file", new Blob([smokePng], { type: "image/png" }), "smoke.png");
    const image = await request(apiBase, "/api/v1/files/images", {
      method: "POST",
      headers: csrfHeaders(),
      body: form,
    });
    const imageUrl = image.body?.data?.url;
    if (!imageUrl) throw new Error("Image upload did not return a URL");

    persisted = { postId, title, imageUrl };
    await writeState(persisted);
    console.log(`Compose smoke created persistence fixture for post ${postId}`);
  }

  const afterRestart = await request(apiBase, `/api/v1/posts/${persisted.postId}`);
  if (afterRestart.body?.data?.title !== persisted.title) throw new Error("Post was not persisted");
  const comments = await request(apiBase, `/api/v1/posts/${persisted.postId}/comments?pageSize=50`);
  if (!comments.body?.data?.some((comment) => comment.content === "Compose smoke comment")) {
    throw new Error("Comment was not persisted");
  }
  const imageResponse = await fetch(
    persisted.imageUrl.startsWith("http") ? persisted.imageUrl : apiBase + persisted.imageUrl,
  );
  if (!imageResponse.ok) throw new Error(`Uploaded image returned ${imageResponse.status} after restart`);

  console.log(`Compose smoke passed for persisted post ${persisted.postId}`);
}

/**
 * 切片 #8：Nacos 注册发现端到端验证；切片 #9 扩展为同时校验网关注册。
 *
 * v3 client OpenAPI 查实例无需鉴权（实测行为）；console 独立监听 8080，
 * compose 映射到宿主机 8081。backend healthy 后注册应已存在（注册发生在
 * WebServerInitializedEvent，早于 compose 的 healthcheck 放行）；gateway
 * 同理（Q2 决策：仅多查一个服务名，业务断言不经网关重复跑——但下方既有
 * 请求的 apiBase 已指向 8080 正门，事实上已天然经过网关）。
 */
async function verifyNacosRegistration() {
  const consoleResponse = await fetch(nacosConsoleBase + "/v3/console/health/readiness");
  if (!consoleResponse.ok) throw new Error(`Nacos console readiness returned ${consoleResponse.status}`);

  for (const serviceName of ["kuros-backend", "kuros-gateway"]) {
    const url = `${nacosBase}/nacos/v3/client/ns/instance/list?serviceName=${serviceName}&groupName=DEFAULT_GROUP&namespaceId=public`;
    const response = await fetch(url);
    if (!response.ok) throw new Error(`Nacos instance list returned ${response.status}`);
    const body = await response.json();
    const registered = body.data?.some(
      (instance) => instance.healthy && instance.serviceName === `DEFAULT_GROUP@@${serviceName}`,
    );
    if (!registered) {
      throw new Error(`${serviceName} is not registered as a healthy instance in Nacos: ${JSON.stringify(body)}`);
    }
  }
  console.log("Nacos smoke passed: kuros-backend & kuros-gateway registered as healthy ephemeral instances");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
