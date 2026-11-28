## Summary

Restore the verified frontend browser baseline discovered during the final integration run.

- Expose the homepage feed loading copy as a live status message.
- Treat only an echo detail `404 ECHO_NOT_FOUND` as a missing item; ordinary 404s and other API failures become explicit load errors.
- Scope the creations isolation assertion to requests carrying the `同人` category, allowing unrelated right-rail post requests on other pages.
- Validate intercepted HTTP methods and exact pathnames in the affected Playwright mocks.
- Record the final-integration evidence rule and the temporary-worktree build boundary in the project workflow and learning index.

Closes #157

## TDD evidence

The final integration Playwright run was RED at 124/128. The four failures covered the missing homepage status role, conflated echo 404/500 states, and an over-broad creations request assertion.

Standards review added an ordinary non-business 404 regression and obtained RED before the status-plus-error-code guard was introduced. Spec review then tightened every affected API mock to require the expected HTTP method and exact pathname. The final affected specs passed 14/14.

## Verification

| Check | Result |
| --- | --- |
| Final targeted Playwright specs | 14/14 passed |
| Full Playwright suite | 129/129 passed |
| ESLint | passed |
| TypeScript | 0 errors |
| Vitest | 39/39 passed |
| Prettier `format:check` | passed |
| Production build | Webpack build passed |
| `git diff --check` | passed |

The temporary integration worktree uses an external `node_modules` junction. Turbopack
rejects that junction as outside its filesystem root, so production-build verification used
the supported Webpack path (`npx next build --webpack`).
