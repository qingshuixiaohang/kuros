## Parent

- Spec: #111 — 声骸图鉴展示视角无障碍状态

## What to build

As a visitor using assistive technology, I can identify the `/echoes` display-view controls and hear which view is selected, while switching views keeps the current table rows and only changes the displayed second-column information.

## Acceptance criteria

- [x] The three view buttons expose the accessible group name “声骸展示视角”.
- [x] Every view button exposes a boolean `aria-pressed`, and exactly one is pressed.
- [x] “套装” is initially pressed; selecting “掉落位置” moves pressed state to that button.
- [x] Switching view preserves all existing rows and their data.
- [x] Existing button elements, labels, CSS classes, API behavior, row count, and layout remain unchanged.
- [x] Page-level Playwright verifies the group name, selected state changes, and preserved rows.
- [x] Targeted Playwright, lint, TypeScript, format:check, and front-end build pass.

## Blocked by

- #82 — `/echoes` implementation branch has not been merged to `main`; this ticket stacks on `fix/batch-a-security`.

## Delivery

- PR: #113 (open; not merged), base `fix/batch-a-security`
- Commit: `89507c2bc0c22ad18b71906acf3083bc0a1b02e3` — `fix(front): announce echoes display mode state`
- TDD: RED failed because the named group did not exist; GREEN passed after adding the group and `aria-pressed`.
- Verification: related Playwright spec 6/6, lint, TypeScript, format:check, build, and `git diff --check` passed.
- Reviews: Standards and Spec reviews passed; earlier test coverage gaps were fixed before final approval.
- PR state: open, mergeable; no CI checks reported at the time of update.
