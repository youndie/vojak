---
id: B-05
title: "Take a lock inside the caller's transaction and release it with that transaction"
status: open
priority: P2
size: S
stage: stage-1-lock
blocked_by: [B-04]
---

# B-05 — Take a lock inside the caller's transaction and release it with that transaction

A caller that already has a transaction — a ledger write, a migration step — wants the lock to
cover exactly that transaction, so that commit and release are one event.

- `withLock(tx, name)` issues `pg_try_advisory_xact_lock` on the caller's `Transaction` and returns;
  the release is the caller's commit or rollback. vojak never commits a transaction it did not open.
- Rejected: taking the lock in a separate transaction around the caller's — the lock would be
  released at a different moment from the commit it is meant to cover.

- AC: after `withLock(tx, "ledger")` and a rollback of `tx`, another process takes `ledger` at once
  and sees none of the block's writes.
- Anchors: `vojak-postgres/src/commonMain/kotlin/io/github/youndie/vojak/postgres/PostgresLocks.kt`.
