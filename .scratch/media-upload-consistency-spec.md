## Problem Statement

用户上传图片时，系统先写对象存储、再保存媒体资产元数据。如果保存元数据失败或进程在这两步之间退出，对象存储里会留下一条数据库完全不知道的文件。现有临时资产清理只能遍历数据库中的临时记录，因此无法发现或删除这类孤儿对象，磁盘和对象存储会被永久占用。

成功上传和现有 REST 响应必须保持不变。本次修复的是失败与进程中断后的最终可收敛性。

## Solution

上传流程改为“先登记临时媒体资产，再写对象存储”：

1. 校验图片并生成唯一存储键。
2. 先保存状态为临时的媒体资产元数据。
3. 元数据保存失败时立即失败，且不写对象存储。
4. 元数据保存成功后写对象存储。
5. 对象写入失败时仍返回原有存储错误语义，并尽力把已登记资产标记为删除，供后续清理复用。

这样即使进程在元数据提交后、对象写入前退出，也只会留下可被现有临时资产清理发现的记录，不会出现数据库无法追踪的物理对象。自动清理调度仍留给后续 XXL-JOB 切片，本次不新增定时任务。

## User Stories

1. As a user, I want a successful image upload to return the same asset identifier and URL as before, so that publishing posts does not change.
2. As a user, I want an upload to fail cleanly when media metadata cannot be saved, so that I am not told an unusable asset was accepted.
3. As a user, I want an upload to fail cleanly when object storage rejects the file, so that no temporary asset remains active after the failure.
4. As a platform operator, I want every written object to have a discoverable metadata record, so that future cleanup can identify it.
5. As a platform operator, I want metadata save failures to happen before object writes, so that database outages cannot create untracked files.
6. As a platform operator, I want process interruption between metadata and object writes to leave a temporary record, so that cleanup can converge the state later.
7. As a platform operator, I want object write failure to mark the registered asset deleted when possible, so that normal failures do not wait for retention cleanup.
8. As a developer, I want upload validation, response shape, ownership and post attachment behavior to remain unchanged, so that this reliability slice does not alter API contracts.
9. As a developer, I want the failure ordering locked by tests, so that a future refactor cannot silently restore storage-first behavior.

## Implementation Decisions

- Keep the existing media asset states: temporary, attached and deleted.
- Persist a temporary media asset before invoking object storage.
- Do not introduce a database transaction spanning the object-storage write. The object store is not part of the database transaction; committing metadata first is what makes interruption states discoverable.
- If temporary metadata persistence fails, propagate the failure without calling object storage.
- If object storage write fails, attempt to mark the persisted temporary asset deleted, then propagate the storage failure. A failed compensation save is acceptable because the record remains temporary and cleanup can retry.
- Keep the upload request and response contract unchanged.
- Reuse the existing temporary asset cleanup behavior. Automatic periodic invocation remains outside this slice.
- Document “媒体资产”, “临时媒体资产” and “孤儿对象” in the project glossary if they are not already defined.

## Testing Decisions

- Test at the media asset service seam: callers observe upload success, failure and the resulting metadata state without reaching into private steps.
- The highest-priority regression test injects a metadata persistence failure and proves object storage is never called. This catches the current storage-first ordering.
- A second failure test injects object-storage failure after metadata persistence and proves the asset is marked deleted before the storage exception propagates.
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
