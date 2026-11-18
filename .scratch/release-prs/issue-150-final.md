## Parent

[#149](https://github.com/qingshuixiaohang/kuros/issues/149)

## What to build

Make image upload metadata-first so a database or process failure cannot create an object that the platform cannot track. Preserve the existing successful upload API. On object-storage failure, remove the same storage key; mark the registered asset deleted only when cleanup succeeds, otherwise keep it temporary for retry, and preserve the original storage error.

## Acceptance criteria

- [ ] A metadata persistence failure returns an upload failure without writing to object storage.
- [ ] A successful upload persists a temporary media asset before writing exactly one object.
- [ ] An object-storage write failure attempts to delete the exact same storage key, marks the persisted temporary asset deleted only after cleanup succeeds, keeps it temporary when cleanup fails, and propagates the existing storage error.
- [ ] The successful upload response keeps the existing asset identifier, URL, filename, content type and size fields.
- [ ] Temporary metadata is visible to an independent connection before object I/O, including when upload runs inside a caller transaction.\n- [ ] Existing upload, ownership, post attachment and temporary cleanup tests remain green.
- [ ] TDD evidence records a failing storage-first regression before implementation.

## Blocked by

None (can start immediately)

