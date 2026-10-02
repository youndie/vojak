---
id: B-09
title: "Fault suite: kill, partition and restart, judged by an overlap oracle that is shown to fail"
status: open
priority: P1
size: L
stage: stage-2-election
blocked_by: [B-07, B-08, B-13, B-15]
---

# B-09 — Fault suite: kill, partition and restart, judged by an overlap oracle that is shown to fail

The promise of research D9 is about instants, and only real processes on a real network test it.

- **Real binaries on `linuxX64`**, one Postgres, a TCP proxy per replica that can drop every packet
  (a partition, not a closed socket — research Consequence 4 is why the difference matters).
- **The oracle** records, for every `whileLeader` block, its start and end on one shared clock and
  counts overlapping intervals; the verdict is "zero overlaps".
- **Positive control**: the same suite with `localLease = serverLease` must report an overlap. An
  oracle that has never gone red has not been shown able to see the thing it is for.
- Not covered: Postgres failover to a replica — research Risk 6 argues it reduces to the restart
  case; a failover stand is a separate item if a consumer runs one.

- AC: SIGKILL of the leader → the follower leads within one poll interval of the server noticing;
  a partition of the leader → it reports `Follower` within `localLease` of its last successful
  heartbeat, and the follower leads no earlier than that and no later than `serverLease` plus one
  poll interval; zero overlaps across all runs; the control run reports at least one.
- Anchors: `samples/two-replicas/`, `vojak-postgres/src/jvmTest/`, `vojak-postgres/src/linuxX64Test/`.
