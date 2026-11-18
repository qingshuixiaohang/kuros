## Parent

[#149](https://github.com/qingshuixiaohang/kuros/issues/149)

## What to build

Make image upload metadata-first so a database or process failure cannot create an object that the platform cannot track. Preserve the existing successful upload API and make object-storage failures leave the newly registered temporary asset in a deleted state when compensation succeeds.

## Acceptance criteria

- [ ] A metadata persistence failure returns an upload failure without writing to object storage.
- [ ] A successful upload persists a temporary media asset before writing exactly one object.
- [ ] An object-storage write failure marks the persisted temporary asset deleted and propagates the existing storage error.
- [ ] The successful upload response keeps the existing asset identifier, URL, filename, content type and size fields.
- [ ] Existing upload, ownership, post attachment and temporary cleanup tests remain green.
- [ ] TDD evidence records a failing storage-first regression before implementation.

## Blocked by

None (can start immediately)
