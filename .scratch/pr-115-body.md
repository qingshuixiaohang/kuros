## Summary

```diff
-{queryPending && <p>正在整理声骸图鉴…</p>}
-{queryError && <p>声骸图鉴加载失败，请稍后重试。</p>}
-{empty && <p>没有找到匹配的声骸。</p>}
+{queryPending && <p role="status">正在整理声骸图鉴…</p>}
+{queryError && <p role="status">声骸图鉴加载失败，请稍后重试。</p>}
+{empty && <p role="status">没有找到匹配的声骸。</p>}
```

Page-level tests observe the delayed loading response, empty response, and failed list request.

This PR stacks on #113 (which in turn stacks on #82).

## Evidence

- **Before:** two new Playwright checks failed because loading/error messages were not exposed as `status` roles.
- **After:** full `e2e/echoes.spec.ts` passed 8/8; `npm run lint`, `npx tsc --noEmit`, `npm run format:check`, `npm run build`, and `git diff --check` passed.
- Standards and Spec reviews passed.

## Merge Danger

**Door:** Two-way

**Blast Radius:** Page-only; existing asynchronous messages gain polite status semantics.

Blocked by #113; merge order is #82 → #113 → this PR.
