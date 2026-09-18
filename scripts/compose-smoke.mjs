import { readFile, writeFile } from "node:fs/promises";

const apiBase = process.env.KUROS_API_BASE_URL ?? "http://localhost:8080";
const frontendBase = process.env.KUROS_FRONTEND_BASE_URL ?? "http://localhost:3000";
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

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
