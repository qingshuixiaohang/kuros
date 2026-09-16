<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->

## Project development workflow

For every new feature, follow the repository workflow in `docs/agents/issue-tracker.md` strictly: `grill-with-docs` → `to-spec` → `to-tickets` → `implement` with TDD → `code-review` → commit/PR/Issue update. Context compaction must preserve the current phase and resume from the written artifacts; it is not a reason to skip a phase.
