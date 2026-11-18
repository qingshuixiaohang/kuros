## Parent

- Spec: #117 — 攻略分类筛选无障碍状态

## What to build

As a visitor using assistive technology, I can identify the `/guides` category filter and hear which category is selected, while the existing filter still loads the corresponding guides.

## Acceptance criteria

- [ ] The five category buttons expose the accessible group name “攻略分类筛选”.
- [ ] Every button exposes a boolean `aria-pressed`, and exactly one is pressed.
- [ ] “全部” is initially pressed; selecting “角色培养” moves the pressed state.
- [ ] The selected category still determines the visible guide results.
- [ ] Button elements, labels, CSS classes, request semantics, pagination reset, and layout stay unchanged.
- [ ] Page-level Playwright verifies group semantics and the category transition through the list API seam.
- [ ] Targeted Playwright, lint, TypeScript, format:check, and front-end build pass.

## Blocked by

- #102 — the current guides empty-state PR changes the same component and must be the base for this stacked change.
