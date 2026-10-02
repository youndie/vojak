---
id: B-15
title: "A new leader waits until the previous one must have stepped down, unless it let go cleanly"
status: wip
priority: P1
size: M
stage: stage-2-election
epic: feature-leader-election
blocked_by: [B-08]
---

# B-15 — A new leader waits until the previous one must have stepped down, unless it let go cleanly

Measured in research §1.6: when the server ends the leader's session — a restart, a failover,
`pg_terminate_backend` — the lock is free at once, the follower takes it at its next poll and reports
`Leader`, and the old leader keeps reporting `Leader` until its next renewal fails. Every measured
round overlapped, by up to `renewEvery`. That breaks the one thing the library promises (research
D9), in exactly the situations a leader election is for.

- **The decision (research D12):** after taking the lock, a new leader does not report `Leader`
  until `localLease` has passed — the latest moment the previous leader can still believe it leads —
  **unless the previous leadership ended cleanly**: a leader that releases on purpose marks its
  epoch released in the transaction that ends its lease, and a successor that finds the mark
  declares at once. Graceful handover stays within a poll; a restart or a kill costs `localLease` of
  nobody leading.
- Rejected: renewing faster (narrows the window, never closes it); leaving it to the fencing epoch
  (protects a store, not side effects like a bot polling its upstream).
- Blocked by [B-08](B-08-fencing-epoch.md): the clean-release mark lives with the epoch.
- Not covered: Postgres failover to a replica that has not replicated the mark — the successor then
  waits `localLease`, which is the safe side.

- AC: the overlap measurement of research §1.6, as a test on both targets: the leader's session is
  ended, the two `leadership` flows are sampled, and **no instant has both `Leader`**; with the wait
  removed (the control) the same test reports the overlap.
- AC: a graceful `close()` of the leader still hands over within one poll interval.
- Anchors: `vojak-core/src/commonMain/kotlin/io/github/youndie/vojak/Election.kt`,
  `vojak-postgres/src/commonMain/kotlin/io/github/youndie/vojak/postgres/PostgresLease.kt`,
  `vojak-postgres/src/commonTest/kotlin/io/github/youndie/vojak/postgres/PostgresElectionTest.kt`.
