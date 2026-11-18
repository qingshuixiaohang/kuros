## Summary

```diff
 <EchoesPage>
-  <div>
-    <button className={active}>mode</button>
+  <div role="group" aria-label="声骸展示视角">
+    <button aria-pressed={active} className={active}>mode</button>
 </EchoesPage>
```

The existing `/echoes` Playwright scenario now checks the unique selected view, unchanged four-row dataset, and second-column content when views change.

This PR stacks on #82 because `/echoes` is not yet present on `main`.

## Evidence

- **Before:** targeted Playwright RED: the named “声骸展示视角” group was not found.
- **After:** full `e2e/echoes.spec.ts` passed 6/6; `npm run lint`, `npx tsc --noEmit`, `npm run format:check`, `npm run build`, and `git diff --check` passed.
- Standards review: no hard findings; one low-risk duplicated assertion pattern retained for local transition clarity.
- Spec review: passed after adding all four row names and second-column value assertions for each view.

## Merge Danger

**Door:** Two-way

**Blast Radius:** Page-only; the existing display-view interaction gains screen-reader group and pressed-state semantics.

Blocked by #82; do not merge directly to `main` before that base is available.
