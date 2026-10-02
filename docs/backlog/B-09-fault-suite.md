---
id: B-09
title: "Fault suite: kill, partition and restart, judged by an overlap oracle that is shown to fail"
status: done
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

## Findings — 2026-10-02

- **Done**: `samples/two-replicas/` — the replica (native, included in the build as
  `:samples:two-replicas`, driver `1.13.1.1-yio`), `fault-suite.sh` and `oracle.py`. Results in
  research §1.7.
- **AC, walked**: zero overlaps across every `kill`, `partition`, `restart` and `terminate` run (28);
  the control overlaps in each of its 7 runs.
- **AC changed by D12, on purpose**: after a SIGKILL the follower leads at one poll **plus
  `localLease`** (2.17–2.22 s here), not "within one poll" — the predecessor did not release cleanly,
  so the successor waits. After a partition the follower leads at `serverLease` plus `localLease`.
- **The control is a freeze, not `localLease = serverLease`**: that setting is refused by
  `ElectionTiming`, and with D12 it would not overlap anyway — every unclean end makes the successor
  wait. SIGSTOP past the server lease is the case research D9 excludes, so the oracle must see the
  overlap there; it did, every time.
- **A library mutant caught**: the D12 wait removed → `terminate` overlaps in 3 of 3 (research
  Consequence 16). The scenario was added for exactly that; the other three cannot see it.
- **Oracle fixed once**: "somebody leads again" first counted only the other replica, so `restart`
  failed whenever the old leader won the lease back.
- **Unexplained, now [B-16](B-16-explain-the-early-takeover.md)**: one early takeover in twelve
  partitions, its logs lost; the suite keeps every run's logs and a server-side observer since.
- **Not run in CI**: the suite needs Docker and minutes; it is a manual gate, named in CLAUDE.md.
