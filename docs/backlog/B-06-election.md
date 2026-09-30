---
id: B-06
title: "Election: hold the lock, keep the lease, publish Leadership as a StateFlow"
status: wip
priority: P1
size: L
stage: stage-2-election
blocked_by: [B-01, B-04, B-13]
---

# B-06 — Election: hold the lock, keep the lease, publish Leadership as a StateFlow

The centre of the library. Every replica campaigns for a name; one holds the transaction of
research D1 with the lease of D2 and heartbeats inside it; everyone else polls.

- **Step-down is a timer, not a call's return** (research D6): the leader goes `Follower` when
  `localLease` has passed since its last *successful* heartbeat, even if the heartbeat call is
  still hanging.
- **A failed heartbeat steps down at once**; no retry while still claiming to lead.
- **A second `Election` for the same name in one process fails** (research Consequence 2).
- `Candidate` goes into `application_name`, so an operator can see the holder in `pg_stat_activity`.

- AC: with two processes, exactly one reports `Leader`; restarting Postgres makes the leader report
  `Follower` on its next heartbeat and, once it is back, exactly one leads again; a second
  `Election` for the same name in one process throws.
- Anchors: `vojak-core/src/commonMain/kotlin/io/github/youndie/vojak/Election.kt`,
  `vojak-core/src/commonMain/kotlin/io/github/youndie/vojak/Leadership.kt`,
  `vojak-postgres/src/commonMain/kotlin/io/github/youndie/vojak/postgres/PostgresElection.kt`.
