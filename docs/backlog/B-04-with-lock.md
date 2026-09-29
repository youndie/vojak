---
id: B-04
title: "A named critical section: withLock, tryWithLock, and no unbounded wait"
status: done
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

## Findings — 2026-09-29

- **Done, on both targets** (`make build` on the Linux box): `Vojak.withLock` / `tryWithLock` in
  `vojak-core`, `PostgresLockStore` in `vojak-postgres`. Seven scenarios against Postgres pass on
  `jvm` and `linuxX64` — including 2 × 500 contended runs whose in-database counter never exceeds 1,
  and a lock whose session the server ends mid-block (the native process survives it).
- **Mutations, each killed on both targets by named tests**: no lock taken (5 tests, among them
  `contendedRunsNeverOverlap`); no release when the block fails (`anExceptionInTheBlockReleasesTheLock`,
  `cancellingTheHolderReleasesTheLock`); re-entry check that never matches
  (`nestingTheSameNameIsRefusedInsteadOfDeadlocking`).
- **Two mutants first turned into hangs, not red tests** — fixed in the harness, then re-run clean:
  a `Vojak` on the real clock under virtual `delay` spun into an `OutOfMemoryError` on the JVM; and
  on `linuxX64` closing a driver whose connection a leaked transaction held never returned, so the
  test binary hung 29 minutes (`runTest`'s timeout cannot cancel a native driver call). Core tests
  now use the scheduler's clock; driver close is bounded; database test tasks have a 10-minute
  deadline.
- **Beyond the item, taken in**: `LockLostException` — the release failing after the block ran is
  the only sign that the session (and the lock) may have ended while the block ran; swallowing it
  would have reported a non-exclusive run as exclusive. Research Consequence 12 corrected: after a
  statement fails on a dead connection, native makes **no** further call (not "one rollback").
- **Names differ from the draft feature document** on `docs/v1-behaviour`: `LockTimeoutException`,
  `ReentrantLockException`, plus `LockLostException`; `Backoff`, `LockStore`, `Attempt` are new
  public types. That branch needs these, and `**Automated:**` lines for the scenarios above, when it
  is rebased.
- **Quirk worth a feature-document line**: the lock's transaction is idle for the length of the
  block. A database with a global `idle_in_transaction_session_timeout` shorter than the block ends
  the lock mid-block; vojak reports it afterwards (`LockLostException`), it cannot prevent it.
