## Summary

- Mark the existing `/news` list loading message as `role="status"` without changing its copy or condition.
- Add a deferred page-level list API seam that checks the loading announcement before the response and the returned article afterward.

## Evidence

### Before

- The pending news request displayed “正在整理资讯…” without exposing the update as a status message.

### After

- Playwright observes the loading status while the list request is held, then verifies the returned article title after release.
- The route mock is limited to GET `/api/v1/news`; other paths continue.

### Verification

- Playwright RED before implementation; GREEN after implementation (1/1).
- `npm run lint`, `npx tsc --noEmit`, `npm run format:check`, `npm run build`, and `git diff --check` passed.
- Standards and Spec review passed.

## Merge Danger

- **Door:** Safe to merge after PR #110; this PR is based on `codex/news-filter-a11y`.
- **Blast Radius:** `/news` list loading semantics and one Playwright spec.
