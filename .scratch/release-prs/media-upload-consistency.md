## Summary

Make media uploads converge after partial failures between metadata and object storage.

- Persist a `TEMPORARY` media asset before writing the object, and suspend caller transactions so
  the metadata is committed before object I/O.
- A metadata persistence failure returns without touching object storage.
- If `put` fails, delete the same storage key first. Mark the asset `DELETED` only after cleanup
  succeeds; otherwise keep `TEMPORARY` so the existing sweeper retries it.
- Preserve and propagate the original `FileStorageException`, with cleanup failures logged at
  `WARN` including their causes.
- Successful uploads keep the existing response shape and write exactly one object.

Closes #149
Closes #150

## TDD evidence

- Original RED: metadata failure still wrote the object; storage failure did not compensate.
- Review follow-up RED: a failed `put` left a potentially complete or partial object behind, and a
  failed cleanup still marked metadata `DELETED`, permanently excluding the object from the
  `TEMPORARY` sweeper.
- Transaction mutation RED: removing `NOT_SUPPORTED` made temporary metadata invisible to an
  independent connection during `put` (0 rows instead of 1).
- GREEN after minimal implementation: media tests 10/10.

## Verification

| Check | Result |
| --- | --- |
| `MediaUploadConsistencyTest` | 5/5 |
| `MediaUploadTransactionVisibilityTest` | 1/1 |
| `TemporaryAssetCleanupTest` | 4/4 |
| Media-domain focused suite | 10/10 |
| backend `clean test-compile` | 49 test sources |
| `git diff --check` | passed |

The transaction test uses a real outer `DataSourceTransactionManager` transaction and an
independent H2 connection during `put`. Compensation tests assert that `put` and cleanup `delete`
use exactly the same storage key.

## Manual CI

- [CI run 39633067675](https://github.com/qingshuixiaohang/kuros/actions/runs/39633067675) passed on
  head `f7488ed8019cbc1a22da39b835bea7a1328c64b8` (`f7488ed`).
- Backend, user, frontend, gateway, and deployment configuration jobs all succeeded.
