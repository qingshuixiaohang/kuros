## Summary

- Expose the existing `/guides` initial loading message as a status while the list query is pending.
- Add a deferred API test that checks loading before release, a returned guide after release, and pageSize=40 pass-through for the right rail.

## Evidence

### Before

- The result count displayed “正在同步内容…” without status semantics.

### After

- The loading-only result count has `role="status"`; after completion it returns to ordinary count semantics.
- Playwright verifies the loading announcement, rendered guide, and unrelated right-rail request pass-through.

### Verification

- RED before implementation; GREEN after implementation; Playwright 1/1.
- Lint, TypeScript, format:check, and production build passed. The build used a temporary 2-worker cap after the default worker count exhausted system threads; the config was restored before commit.
- Standards and Spec review passed.

## Merge Danger

- **Door:** Safe to merge after PR #119; this PR is based on `codex/guides-filter-a11y` (which depends on #102).
- **Blast Radius:** `/guides` loading announcement and one Playwright spec.
