## Summary

```diff
 <NewsPage>
-  <div className="news-page-tabs">
-    <button>category</button> // visual active state only
+  <div role="group" aria-label="资讯分类筛选">
+    <button aria-pressed={selected}>category</button>
 </NewsPage>
```

`front/e2e/news-filter-a11y.spec.ts` covers the named filter group, unique pressed state, category switching, and filtered results.

## Evidence

- **Before:** targeted Playwright failed because the filter had no accessible group name.
- **After:** targeted Playwright 1/1 passed; `npm run lint`, `npx tsc --noEmit`, `npm run format:check`, `npm run build`, and `git diff --check` passed.
- Spec and Standards reviews passed.

## Merge Danger

**Door:** Two-way

**Blast Radius:** Page-only; category selection remains unchanged and gains screen-reader state.

Closes #109
