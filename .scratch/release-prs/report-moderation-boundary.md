## Summary

Move report disposition writes behind the post and comment domain services.

- Visibility checks and confirmed-content deletion now stay in each content domain.
- The report domain owns report records and workflow state only.
- Pending report handling locks the row so concurrent administrators cannot transition it twice.
- `POST_NOT_FOUND` and `COMMENT_NOT_FOUND` remain stable during report creation.
- Already-deleted targets remain idempotent during confirmation.
- Post detail cache invalidation remains owned by the content domain after transaction commit.

Closes #147
Closes #148

This branch was rebased after #151 merged, so its not-found codes use the new explicit API contract.

## TDD evidence

- The architecture boundary test was RED because `ContentReportService` directly depended on
  post/comment repositories and `TwoLevelCache`; it is GREEN after routing writes through domain
  services.
- Removing `@Lock(PESSIMISTIC_WRITE)` made the two-transaction lock test RED because the second
  transaction no longer blocked before the first commit; restoring the lock made it GREEN.
- Rollback coverage bypasses the warm post-detail cache and directly reads
  `CommunityPostRepository`, proving the post row remains `PUBLISHED` as well as the report status,
  L1/L2 cache state, and broadcast suppression.

## Verification

| Check | Result |
| --- | --- |
| `ContentReportServiceBoundaryTest` after rebase | 5/5 |
| `ReportDispositionCacheEvictionIntegrationTest` | 7 cases, lock/cache/rollback paths |
| `ContentReportLockIntegrationTest` | 1/1 during TDD mutation, real two-transaction lock |
| backend `test-compile` | 47 test sources |
| `git diff --check` | passed |

The integration cases require the CI Docker runner. Local Windows execution of the Spring
Testcontainers suite remains environment-limited by the JDK loopback selector issue recorded in the
learning note; the manual CI run is the authoritative container result.

## Manual CI

The full `ci.yml` run is executed explicitly before merge; its run URL and final result are added
to this section after completion.
