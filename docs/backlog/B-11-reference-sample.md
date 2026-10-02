---
id: B-11
title: "Reference consumer: two replicas, one singleton, a store that rejects a stale epoch"
status: done
priority: P2
size: M
stage: stage-3-adoption
blocked_by: [B-09, B-10]
---

# B-11 — Reference consumer: two replicas, one singleton, a store that rejects a stale epoch

The shape a service author copies: a Ktor CIO binary with `sqlx4k-postgres`, a `whileLeader`
poller, its "last sent" state kept in the database rather than in memory, and a table whose writes
carry the epoch.

- The stale-write case is the point: a partitioned leader's delayed write with epoch 1 arrives
  after the new leader has written with epoch 2, and the store refuses it by a `WHERE`.
- The pool is sized with the held election counted (research Risk 5), and a comment says so.

- AC: in the two-replica stand, the delayed write with the stale epoch changes no row; the same
  write without the epoch guard does (the control).
- Anchors: `samples/digest-sender/` — `DigestStore.kt` (the guard), `Main.kt` (the poller, the pool,
  kore), `stale-write.sh` and `stale-oracle.py` (the stand). Its own module rather than inside
  `samples/two-replicas/`: the fault suite's replica runs a block that does nothing on purpose, and
  the shape a service author copies should not carry the suite's flags.

## Findings

- **Done 2026-10-02.** `samples/digest-sender/stale-write.sh` on the Linux box, 2 runs per mode. In
  every run the old leader's claim with epoch 1 ran on the server about 4 s after the successor's
  first claim with epoch 2. Guarded: it changed no row. Unguarded (the control): it changed the row,
  and the old leader sent a digest while it was not the leader. The oracle refuses a run where the
  late claim never reached the server, so a vacuous pass is not possible. It refused all four runs
  of the first version (below). Research §1.8.
- **The claim is one statement, on purpose.** As a read then a write, the read is what sits in the
  partition, and its coroutine is cancelled before the write is ever sent. That is not the case
  fencing exists for.
- **The first version had a 2 s acquire timeout, copied from the fault replica, and it never
  produced a stale write.** The pool's check of the connection was stuck in the paused proxy, so
  the acquire failed before the statement left. With sqlx's default timeout of 30 s the claim waits,
  then runs late (research Consequence 18).
- **On native, a statement does not observe cancellation** (research Consequence 17). In every run
  the old leader's block overran the lease margin, and vojak reported `LeaderWorkDidNotStopException`.
  This is the expected report under a partition, not a defect, and the reference consumer logs it
  through `onWorkFailure`.
- kore refused `gracePeriod = 10 s` because the sequence needs 11 s (drain 2 s + three release groups
  at 3 s). The sample takes Kubernetes' 30 s default.
