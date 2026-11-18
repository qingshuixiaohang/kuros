## Summary

Completes the #159 MVP readiness hardening batch.

- Production configuration now fails closed for fixed login codes, exposed
  verification codes, development credentials, missing internal tokens,
  mixed profiles, and missing SMS adapters.
- Media lifecycle is closed across upload, publish, edit, moderation,
  visibility, scheduled cleanup, and repair of published references.
- Following timelines reconcile unfollows, rebuild after Redis loss, and use
  the full Redis member for same-score cursor boundaries.
- Feed modules share React Query failure semantics. Real posts no longer fall
  back to local demo toggles while interaction state is pending or unavailable.
- Comment/report pagination, category validation, detail error states,
  accessibility focus rules, Next/Actions upgrades, and media host allowlists
  are implemented and covered.

Closes #160
Closes #161
Closes #162
Closes #163
Closes #164
Closes #165
Closes #166
Closes #167

## Verification

- `npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org`: 0 vulnerabilities
- `npm run test`: 42/42
- `npx tsc --noEmit`: passed
- `npm run lint`: passed
- `npm run format:check`: passed
- `npm run build`: Next.js 16.4.2 production build passed
- `node --test scripts/production-config.test.mjs`: 4/4
- backend targeted tests for media, feed, reports: 19/19
- user service security tests: 13/13
- final Playwright suite: 161/161 with one worker

The final commit is `2406b00`.

## Known limits

- A real production SMS provider, sender credentials, and HTTPS ingress are
  deployment inputs; missing configuration fails startup rather than exposing a
  development login path.
- The workflow is manual (`workflow_dispatch`); five-job success is recorded
  after it runs on this PR.
