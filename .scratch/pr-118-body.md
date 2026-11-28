## Summary

- Add the accessible name “攻略分类筛选” to the `/guides` category button group and expose the selected category with `aria-pressed`.
- Add page-level Playwright coverage for the initial selection, category transition, and API-backed result update.
- Stacked on PR #102, which changes the same component.

## Evidence

### Before

- Assistive technology could not identify the category button group or its selected category.
- The existing filter had no page-level assertion for its accessible state.

### After

- All five category buttons expose boolean `aria-pressed`; exactly one is selected before and after a filter change.
- Selecting “角色培养” updates the visible results through the mocked GET `/api/v1/posts` list endpoint.

### Verification

- Playwright RED before implementation; GREEN after implementation.
- Guide-related Playwright: 2/2 passed, including PR #102's empty-state spec.
- `npm run lint`, `npx tsc --noEmit`, `npm run format:check`, `npm run build`, and `git diff --check` passed.
- Standards and Spec review passed after adding the post-transition uniqueness assertion.

## Merge Danger

- **Door:** Safe to merge after PR #102; this PR is based on `codex/guides-empty-state-a11y`.
- **Blast Radius:** `/guides` category filter accessibility semantics and its Playwright coverage only.
