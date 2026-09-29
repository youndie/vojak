---
id: B-05
title: "Take a lock inside the caller's transaction and release it with that transaction"
status: done
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

## Findings — 2026-09-30

- **Done, on both targets** (`make build`): `Vojak.lockForTransaction(tx, name, waitAtMost)` in
  `vojak-postgres/src/commonMain/kotlin/io/github/youndie/vojak/postgres/TransactionLocks.kt`; four
  scenarios in `TransactionLocksTest` — the caller's rollback releases the lock and undoes the write,
  the commit releases it with the write visible, waiting is bounded and leaves the transaction usable,
  and inside a `withLock` block for the same name it is refused.
- **Named differently from the draft** (`withLock(tx, name)`): it runs no block — it takes the lock
  and returns, and the caller's commit is the release — so a `with…` name would have promised a scope
  it does not have. The draft feature document on `docs/v1-behaviour` needs the new name.
- **The waiting loop moved into the core** as the public `Vojak.acquireWithin(name, waitAtMost,
  tryOnce)`: one set of waiting and re-entry rules for `withLock` and for this, instead of a second
  loop in the Postgres module.
- **Mutation**: the attempt reporting "taken" whatever Postgres answers is killed on both targets by
  `waitingForItIsBoundedAndLeavesTheTransactionUsable`.
- **Not decided by vojak, on purpose**: after a connection-gone failure inside the caller's
  transaction, rolling it back aborts a linuxX64 process (research Consequence 12). The KDoc says so;
  the transaction is the caller's.
