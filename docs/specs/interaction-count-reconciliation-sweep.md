# Interaction Count Reconciliation Sweep

## Problem Statement

Interaction counts are represented in Redis, denormalized columns on posts, and the like/favorite relationship tables. A per-post reconciler can repair a known post, but there is no recurring path to discover and repair drift across published posts after a failed projection.

## Solution

Run a periodic reconciliation sweep over published posts. Preserve existing Redis realtime counts and repair the denormalized post counts from them. When a Redis count is missing, use the corresponding relationship-table count to backfill it. Process posts in bounded ID-ordered batches so the sweep does not load the full catalog into memory. In a multi-instance backend deployment, a Redis distributed lock coordinates sweep ownership.

## User Stories

1. As a community operator, I want published-post interaction counts to be checked periodically, so that a failed asynchronous projection does not leave counts permanently incorrect.
2. As a community operator, I want the sweep to use the current realtime count when repairing the database, so that an interaction waiting for asynchronous projection is not rolled back.
3. As a community operator, I want the sweep to process bounded batches, so that catalog growth does not require loading every post into memory at once.
4. As an operator of multiple backend instances, I want only one instance to run the sweep at a time, so that replicas do not multiply database work.
5. As an operator, I want a missed or failed sweep to be retried on a later scheduled run, so that transient storage failures do not disable future repair attempts.
6. As a visitor, I want deleted posts to stay outside the public reconciliation sweep, so that maintenance follows the same visibility boundary as public content.

## Implementation Decisions

- The sweep covers posts whose status is `PUBLISHED`; a deleted or otherwise non-public post is not scanned.
- The sweep repairs MySQL denormalized counts from existing Redis realtime counts. It backfills a missing Redis count from relationship rows, but never overwrites a present Redis count with a relationship-table snapshot because RocketMQ projection can lag behind the realtime write.
- Database count updates use a version advanced by every interaction projection. If an interaction projection changes the column during repair, including an ABA count transition, the sweep leaves that newer state intact and retries on a later pass.
- The sweep uses keyset pagination ordered by post ID and a configurable batch size, rather than offset pagination or an all-posts collection.
- A configurable fixed delay and initial delay control scheduling. The default schedule starts five minutes after application startup and repeats every 24 hours.
- A named Redis lock coordinates replicas. The watchdog renews the lock while the scan runs; if renewal reports ownership loss or errors, the scan stops at the next safe boundary.
- The scan checks watchdog ownership before each batch and each post, and stops at the next safe boundary if the lock is lost.
- The scheduled run logs its start, completion, number of scanned posts, and failures. A per-post failure is logged and does not prevent later posts in that run from being checked.
- The sweep is an operational repair path; it does not change the interaction write API, RocketMQ delivery, or user-visible response contract.

## Testing Decisions

- Verify the scheduled-run seam against the application persistence boundary: published posts with deliberately drifted database counts are repaired from Redis, while non-published posts are untouched.
- Verify a Redis count for an interaction whose relationship row is still pending projection is preserved and copied to the database count column.
- Verify pagination processes more than one configured batch without skipping or repeating posts.
- Verify the distributed lock prevents a second runner from scanning while another runner holds the lock.
- Verify the scan stops before the next post after watchdog ownership is lost.
- Verify an individual post failure is reported and later posts are still attempted.
- Follow the existing `InteractionCountReconcileIntegrationTest` and Testcontainers Redis patterns. Tests assert externally observable counts and lock behavior rather than implementation calls.

## Out of Scope

- Reconciliation of deleted posts.
- A public or administrator-facing endpoint to trigger reconciliation.
- Rebuilding interaction relationship rows from Redis or RocketMQ.
- Changes to the interaction projection protocol, count TTL, or feed fanout policy.
- Metrics dashboards, alert routing, and production capacity claims.

## Further Notes

This repair is eventually consistent: it corrects drift on a scheduled pass and does not make MySQL, Redis, and RocketMQ one atomic transaction. The daily interval is a recovery bound, not a latency or throughput guarantee.
