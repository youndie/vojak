---
id: B-11
title: "Reference consumer: two replicas, one singleton, a store that rejects a stale epoch"
status: open
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
- Anchors: `samples/two-replicas/`.
