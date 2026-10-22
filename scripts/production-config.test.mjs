import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { test } from "node:test";

const fixture = {
  ...process.env,
  BACKEND_SPRING_PROFILES_ACTIVE: "production",
  USER_SPRING_PROFILES_ACTIVE: "production",
  APP_AUTH_DEV_CODE: "",
  APP_AUTH_DEV_CODE_EXPOSED: "false",
  APP_AUTH_SESSION_COOKIE_SECURE: "true",
  APP_INTERNAL_TOKEN: "test-only-production-config-token",
  MYSQL_PASSWORD: "test-only-database-password",
  MYSQL_ROOT_PASSWORD: "test-only-database-root-password",
  APP_STORAGE_MINIO_ACCESS_KEY: "test-only-access-key",
  APP_STORAGE_MINIO_SECRET_KEY: "test-only-secret-key",
  BACKEND_PORT: "8090",
  GATEWAY_PORT: "8080",
};

function check(overrides) {
  return spawnSync(process.execPath, ["scripts/validate-production-config.mjs", ".env.production.example"], {
    encoding: "utf8",
    env: { ...fixture, ...overrides },
  });
}

test("rendered production configuration exposes only the public entrypoints", () => {
  const result = check({});
  assert.equal(result.status, 0, result.stderr);
});

test("rendered production configuration rejects fixed login codes", () => {
  const result = check({ APP_AUTH_DEV_CODE: "123456" });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /APP_AUTH_DEV_CODE must be empty/);
});

test("rendered production configuration rejects the example shared secret", () => {
  const result = check({ APP_INTERNAL_TOKEN: "REPLACE_WITH_LONG_RANDOM_INTERNAL_TOKEN" });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /APP_INTERNAL_TOKEN still contains the template placeholder/);
});

test("rendered production configuration rejects the legacy port collision", () => {
  const result = check({ BACKEND_PORT: "8080" });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /backend must not claim/);
});
