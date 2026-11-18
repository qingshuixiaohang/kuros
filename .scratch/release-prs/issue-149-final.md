## Problem Statement

用户上传图片时，系统先写对象存储、再保存媒体资产元数据。如果保存元数据失败或进程在这两步之间退出，对象存储里会留下一条数据库完全不知道的文件。现有临时资产清理只能遍历数据库中的临时记录，因此无法发现或删除这类孤儿对象，磁盘和对象存储会被永久占用。

成功上传和现有 REST 响应必须保持不变。本次修复的是失败与进程中断后的最终可收敛性。

## Solution

上传流程改为“先登记临时媒体资产，再写对象存储”：

1. 校验图片并生成唯一存储键。
2. 先保存状态为临时的媒体资产元数据。
3. 元数据保存失败时立即失败，且不写对象存储。
4. 元数据保存成功后写对象存储。
5. 对象写入失败时先删除同一存储键；删除成功才把资产标记为 `DELETED`，删除失败则保留 `TEMPORARY` 交由清理器重试，并始终传播原始存储错误。

这样即使进程在元数据提交后、对象写入前退出，也只会留下可被现有临时资产清理发现的记录，不会出现数据库无法追踪的物理对象。自动清理调度仍留给后续 XXL-JOB 切片，本次不新增定时任务。

## User Stories

1. As a user, I want a successful image upload to return the same asset identifier and URL as before, so that publishing posts does not change.
2. As a user, I want an upload to fail cleanly when media metadata cannot be saved, so that I am not told an unusable asset was accepted.
3. As a user, I want an upload to fail cleanly when object storage rejects the file, so that a successfully cleaned asset is marked deleted and a failed cleanup remains visibly temporary for retry.
4. As a platform operator, I want every written object to have a discoverable metadata record, so that future cleanup can identify it.
5. As a platform operator, I want metadata save failures to happen before object writes, so that database outages cannot create untracked files.
6. As a platform operator, I want process interruption between metadata and object writes to leave a temporary record, so that cleanup can converge the state later.
7. As a platform operator, I want object write failure to remove the same storage key and mark the asset deleted only when that cleanup succeeds, so that failed cleanup remains retryable.
8. As a developer, I want upload validation, response shape, ownership and post attachment behavior to remain unchanged, so that this reliability slice does not alter API contracts.
9. As a developer, I want the failure ordering locked by tests, so that a future refactor cannot silently restore storage-first behavior.

## Implementation Decisions

- Keep the existing media asset states: temporary, attached and deleted.
- Persist a temporary media asset before invoking object storage.
- Persist and commit the temporary metadata before object I/O. Suspend the caller transaction for the storage write because object storage is not part of the database transaction.
- If temporary metadata persistence fails, propagate the failure without calling object storage.
- If object storage write fails, attempt to delete the same storage key. Mark the asset `DELETED` only after cleanup succeeds; if cleanup fails, keep it `TEMPORARY` for the existing sweeper to retry. Always propagate the original storage failure.
- Keep the upload request and response contract unchanged.
- Reuse the existing temporary asset cleanup behavior. Automatic periodic invocation remains outside this slice.
- Document “媒体资产”, “临时媒体资产” and “孤儿对象” in the project glossary if they are not already defined.

## Testing Decisions

- Test at the media asset service seam: callers observe upload success, failure and the resulting metadata state without reaching into private steps.
- The highest-priority regression test injects a metadata persistence failure and proves object storage is never called. This catches the current storage-first ordering.
- Failure tests inject object-storage errors after metadata persistence and prove cleanup uses the same key, marks `DELETED` only on success, keeps `TEMPORARY` on failed cleanup, and preserves the original exception.
- A success test proves the persisted asset is temporary, the object is written once and the response still carries the generated identifier and URL.
- Existing API-level upload tests remain the regression guard for validation, ownership, post attachment and response shape.
- Test names and interface vocabulary use the glossary terms “媒体资产” and “临时媒体资产”.

## Out of Scope

- Adding XXL-JOB or a new scheduler.
- Listing object storage to discover historical untracked objects.
- Presigned direct uploads, multipart uploads or image processing.
- Changing media status transitions for post attachment or deletion.
- Cleaning objects already orphaned before this change; that needs an external storage inventory and is not discoverable from the database alone.

## Further Notes

The project already has best-effort cleanup for database-visible temporary assets. This slice makes the upload order compatible with that cleanup mechanism. A future operations tool can perform a one-time storage inventory for pre-existing untracked objects.

