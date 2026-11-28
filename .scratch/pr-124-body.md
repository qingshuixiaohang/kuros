## Summary

- Mark the existing homepage Feed loading message as `role="status"` without changing its copy, condition, or styling.
- Add a deferred page-level API seam that verifies the loading announcement, the rendered post, and that the right-rail pageSize=40 request passes through.

## Evidence

### Before

- The pending Feed request showed loading copy, but assistive technology could not identify it as a status update.

### After

- Playwright observes the status before releasing the API response, then verifies the returned post title.
- The test confirms only the GET `/api/v1/posts` pageSize=20 request is mocked; pageSize=40 continues normally.

### Verification

- Playwright RED before implementation; GREEN after implementation (1/1).
- `npm run lint`, `npx tsc --noEmit`, `npm run format:check`, `npm run build`, and `git diff --check` passed.
- Standards and Spec review passed after replacing fixed delay with a deferred gate and asserting rendered post data.

## Merge Danger

- **Door:** Safe to merge after PR #82; this PR is based on `fix/batch-a-security`.
- **Blast Radius:** Homepage Feed loading semantics and one Playwright spec.
