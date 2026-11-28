## Parent

- Spec: #114 — 声骸图鉴异步状态播报

## What to build

As a visitor using assistive technology, I hear when the `/echoes` list is loading, has failed, or has no matching results, while the existing visible text and list behavior remain unchanged.

## Acceptance criteria

- [x] The existing loading message exposes `role="status"`.
- [x] The existing list error message exposes `role="status"`.
- [x] The existing empty-result message exposes `role="status"`.
- [x] Text, display conditions, queries, list behavior, and layout remain unchanged.
- [x] Page-level Playwright covers delayed response/loading, empty response, and failed response through the list API seam.
- [x] Targeted Playwright, lint, TypeScript, format:check, and front-end build pass.

## Blocked by

- #113 — current `/echoes` accessibility PR is stacked on the base implementation in #82.

## Delivery

- PR: #116 (open; not merged), base `codex/echoes-view-a11y`
- Commit: `85094d683699676d0abbe1b13b2f57b6b7f510d5` — `fix(front): announce echoes async states`
- TDD: RED failed because loading/error were absent from the `status` role; GREEN passed after the three existing messages gained `role="status"`.
- Verification: related Playwright spec 8/8, lint, TypeScript, format:check, build, and `git diff --check` passed.
- Reviews: Standards and Spec reviews passed.
- PR state: open; no CI checks reported at the time of update. Merge order: #82 → #113 → #116.
