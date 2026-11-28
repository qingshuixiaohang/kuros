## Summary

Unify API error envelopes across `kuros-backend` and `kuros-user` for malformed JSON, request binding, validation, upload limits, resource-not-found, and unexpected errors.

- Require explicit domain error codes at `ResourceNotFoundException` call sites.
- Keep `fieldErrors` only for validation responses.
- Preserve the 10 MB business-level upload check while mapping the container limit to `413 UPLOAD_TOO_LARGE`.
- Map a missing multipart `file` part to `400 INVALID_REQUEST`.
- Return `406 NOT_ACCEPTABLE` with an explicit JSON error envelope.
- Extend the compose smoke to assert the container-level response.

Closes #145
Closes #146

## TDD evidence

The original contract cases were observed RED: backend 3 cases produced 2 failures and 1 error; user 2 cases produced 1 failure and 1 error.

Review follow-up exposed two edge cases:

- A request missing the required multipart `file` part returned HTTP 500 before the fix and now returns `400 INVALID_REQUEST`.
- An incompatible `Accept` header had no JSON error envelope before the fix and now returns `406 NOT_ACCEPTABLE` with `application/json`.

The new multipart, 405, 415, and 406 contract tests were observed RED before their handlers were added, then GREEN after the minimal implementation.

## Verification

| Check | Result |
| --- | --- |
| `ApiExceptionHandlerContractTest` (backend, after rebase) | 9/9 |
| `ApiExceptionHandlerContractTest` (user, after rebase) | 8/8 |
| `KurosBackendApplicationTests` (backend) | 41/41 |
| `AuthLoginIntegrationTest` (user) | 7/7 |
| `kuros-backend` full suite | 216/216 passed, 21 skipped |
| `kuros-user` full suite | 51/51 passed |
| frontend gates | ESLint, TypeScript, Vitest 39/39, Prettier, Playwright 129/129, Webpack build passed |
| Backend and user `test-compile` | passed |
| `node --check scripts/compose-smoke.mjs` | passed |
| Compose backend oversized upload | HTTP 413, `UPLOAD_TOO_LARGE` |
| `git diff --check` | passed |

The application, login, and full-suite runs used Testcontainers Redis/MySQL on Docker Desktop
with a short workspace `TEMP` path. The minimal Compose stack (MySQL, Redis, Nacos, backend)
rejected the 15 MB multipart request with an HTTP 413 error envelope and code `UPLOAD_TOO_LARGE`.

## Known limitations

- The user service validation starter is test-scoped. Its contract test exercises the handler
  seam with a synthetic controller; production user APIs do not currently consume field
  validation annotations.
- The frontend reads `code` and `message` but does not yet render `fieldErrors` for forms.

## Manual CI

Manual run [39624369196](https://github.com/qingshuixiaohang/kuros/actions/runs/39624369196)
completed successfully for `7f72ca1a8a15623e10a3271ff19291bc6c213d6f`. All five jobs passed:
backend, user, gateway, frontend lint/build, and deployment configuration including Compose smoke.
