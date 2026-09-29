---
id: B-04
title: "A named critical section: withLock, tryWithLock, and no unbounded wait"
status: open
priority: P1
size: M
stage: stage-1-lock
blocked_by: [B-03]
---

# B-04 — A named critical section: withLock, tryWithLock, and no unbounded wait

The smaller half of the library and the first user of the holding transaction: a block runs while
no other process runs a block under the same name.

- **Transaction-scoped, always** (research D1): the block runs while a transaction holds
  `pg_advisory_xact_lock`; ending the block ends the transaction, whatever the pool does.
- **Re-entry is refused in process** (research Consequence 2): a nested `withLock` on the same name
  fails fast instead of blocking on a second pool connection.
- **`withLock` takes a `waitAtMost`** and fails with a timeout after it. Waiting is done with
  `pg_try_advisory_xact_lock` and a backoff rather than a blocking `pg_advisory_xact_lock`, because
  a blocked statement is not interrupted by coroutine cancellation (research Risk 3).
- Not covered: the caller's own transaction ([B-05](B-05-lock-in-callers-transaction.md)).

- AC: two processes contending for `migrate` never run their blocks concurrently (a shared
  counter in Postgres never exceeds 1 over 1 000 contended runs); `tryWithLock` returns `null`
  within one round trip while the other holds it; cancelling the holder frees the lock.
- Anchors: `vojak-core/src/commonMain/kotlin/io/github/youndie/vojak/Vojak.kt`,
  `vojak-postgres/src/commonMain/kotlin/io/github/youndie/vojak/postgres/PostgresLocks.kt`.
