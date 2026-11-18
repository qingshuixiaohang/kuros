## Summary

- Add a global “跳到主要内容” link that appears when keyboard focused.
- Add focusable `#main-content` targets to the homepage and shared community page frame.
- Verify keyboard activation and continued Tab navigation on `/` and `/guides`.

## Evidence

### Before

- No skip link existed; keyboard users had to traverse repeated navigation before reaching page content.

### After

- First Tab focuses a visible skip link on both routes.
- Enter updates the fragment and focuses the correct main-content target; the next Tab remains within main content.

### Verification

- Playwright RED before implementation; GREEN after implementation (1/1 test covering two routes).
- `npm run lint`, `npx tsc --noEmit`, `npm run format:check`, `npm run build`, and `git diff --check` passed.
- Standards and Spec review passed after expanding coverage to both routes and keyboard continuation.

## Merge Danger

- **Door:** Safe to merge after PR #82; this PR is based on `fix/batch-a-security`.
- **Blast Radius:** Global layout link plus main-content focus targets on the homepage and shared community page frame.
