## Parent

- Spec: #108 — 资讯列表分类筛选无障碍状态

## What to build

As a visitor using assistive technology, I can identify the `/news` category filter and hear which category is selected, while the existing category buttons continue to load and display the same filtered news.

## Acceptance criteria

- [x] The category button set exposes the accessible group name “资讯分类筛选”.
- [x] Every category button exposes an `aria-pressed` boolean and exactly one button is pressed.
- [x] Initially “全部” is pressed; selecting “官方公告” moves the pressed state and preserves the filtered list content.
- [x] Existing button elements, category values, request behavior, text, classes, layout, and visual states remain unchanged.
- [x] Page-level Playwright verifies group semantics and category selection through `/news`.
- [x] Targeted Playwright, lint, TypeScript, format:check, and front-end build pass.

## Blocked by

None (can start immediately).

## Delivery

- PR: #110 (open; not merged)
- Commit: `b9d835126404491a0861ba0df5cba71f760ca541` — `fix(front): announce news category filter state`
- TDD: RED failed because the filter group lacked its accessible name; GREEN passed after implementation.
- Verification: targeted Playwright 1/1, lint, TypeScript, format:check, build, and `git diff --check` passed.
- Reviews: Spec and Standards reviews passed.
- PR mergeability: MERGEABLE; no CI checks were reported at the time of update.
