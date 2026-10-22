import { execFileSync } from "node:child_process";
import process from "node:process";

const INTERNAL_SERVICES = new Set([
  "backend",
  "user",
  "mysql",
  "redis",
  "minio",
  "nacos",
  "rocketmq-namesrv",
  "rocketmq-broker",
  "rocketmq-dashboard",
  "elasticsearch",
  "canal-server",
]);

function fail(message) {
  throw new Error(`production configuration: ${message}`);
}

function rejectPlaceholder(name, value) {
  const normalized = (value ?? "").trim();
  if (!normalized) fail(`${name} is required`);
  if (normalized.startsWith("REPLACE_WITH_")) fail(`${name} still contains the template placeholder`);
  return normalized;
}

function environment(service) {
  return service?.environment ?? {};
}

function hostBindings(ports) {
  return (ports ?? []).map((port) => ({
    hostIp: port.host_ip || "0.0.0.0",
    published: String(port.published ?? ""),
  }));
}

const rendered = execFileSync(
  "docker",
  ["compose", "--env-file", process.argv[2] ?? ".env.production", "--profile", "full", "config", "--format", "json"],
  { cwd: process.cwd(), encoding: "utf8" },
);
const config = JSON.parse(rendered);
const services = config.services ?? {};
const userEnv = environment(services.user);
const backendEnv = environment(services.backend);

if (userEnv.SPRING_PROFILES_ACTIVE !== "production" || backendEnv.SPRING_PROFILES_ACTIVE !== "production") {
  fail("backend and user must run with the production profile");
}
if ((userEnv.APP_AUTH_DEV_CODE ?? "").trim()) {
  fail("APP_AUTH_DEV_CODE must be empty");
}
const backendInternalToken = rejectPlaceholder("APP_INTERNAL_TOKEN", backendEnv.APP_INTERNAL_TOKEN);
const userInternalToken = rejectPlaceholder("APP_INTERNAL_TOKEN", userEnv.APP_INTERNAL_TOKEN);
if (backendInternalToken !== userInternalToken) {
  fail("backend and user must use the same non-empty internal token");
}
if (String(userEnv.APP_AUTH_DEV_CODE_EXPOSED) !== "false") fail("verification codes must not be exposed");
if (String(userEnv.APP_AUTH_SESSION_COOKIE_SECURE) !== "true") fail("production cookies must be Secure");
for (const [name, value] of Object.entries(environment(services.mysql))) {
  if (name.includes("PASSWORD")) {
    const password = rejectPlaceholder(name, value);
    if (password.includes("kuros_dev_")) fail(name + " must not use a development password");
  }
}
for (const [name, value] of Object.entries(environment(services.minio))) {
  const credential = rejectPlaceholder(name, value);
  if (credential === "minioadmin") fail(name + " must not use the development credential");
}
if (hostBindings(services.backend?.ports).some(({ published }) => published === "8080")) {
  fail("backend must not claim the gateway's public port 8080");
}

for (const name of INTERNAL_SERVICES) {
  for (const binding of hostBindings(services[name]?.ports)) {
    if (binding.hostIp !== "127.0.0.1") {
      fail(`${name} port ${binding.published} must bind to 127.0.0.1`);
    }
  }
}

for (const name of ["gateway", "frontend"]) {
  const bindings = hostBindings(services[name]?.ports);
  if (!bindings.length) fail(`${name} must expose its public port`);
  if (bindings.some(({ hostIp }) => hostIp === "127.0.0.1")) {
    fail(`${name} must remain reachable as the public entrypoint`);
  }
}

console.log("Production configuration checks passed. A production SmsSender adapter and HTTPS ingress are still required before deployment.");
