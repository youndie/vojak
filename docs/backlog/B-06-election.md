---
id: B-06
title: "Election: hold the lock, keep the lease, publish Leadership as a StateFlow"
status: done
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

## Findings — 2026-10-02

- **Done, on both targets** (`make build` on the Linux box): `Vojak.elect` → `Election` with
  `leadership: StateFlow<Leadership>` (`Follower`, `Leader`, `Closed`) in `vojak-core`; the lease
  port (`LockStore.tryLease`, `Lease`) and `PostgresLease` in `vojak-postgres`. 8 core tests on the
  scheduler's clock, 4 against Postgres on `jvm` and `linuxX64`.
- **AC, walked**: of two replicas exactly one leads, watched over several renewals; the leader
  closing hands over within a poll; a second election for one name on one `Vojak` throws. **The
  Postgres-restart AC is exercised as "the server ends the leader's session"** —
  `pg_terminate_backend` of the session found by `application_name = 'vojak <candidate>'` (which is
  also the operator's view the item asked for): the leader steps down at its next renewal and
  leadership comes back. A real restart of a real process's database is the fault suite's
  ([B-09](B-09-fault-suite.md)). On linuxX64 this runs on the **released** driver 1.13.1 and the
  process survives: the renewal is the first call after the kill (a clean `57P01`), and the lease's
  release then skips the rollback (research Consequence 12).
- **Driver decision (left from B-13)**: vojak's suite stays on the released 1.13.1 — the worst case,
  and what a consumer gets without opting in. The patched `1.13.1.1-yio` is what a native consumer
  should run; it removes the abort that a partition plus a returning network would still cause
  (research §1.5). Written down for the service documents when the docs branch lands.
- **Mutations, killed on both targets**: the step-down timer removed — `aHungRenewalStepsDownOnTheTimerNotOnItsReturn`
  on linuxX64 and the JVM suite's 5-minute deadline (the mutant turns the wait into a loop that never
  suspends, so it hangs rather than asserts; the deadline is what turns that into red —
  `vojak-core/build.gradle.kts`); the duplicate registry disabled —
  `aSecondElectionForTheSameNameIsRefusedUntilTheFirstCloses`.
- **Decisions on the way**: research D10 (a poll's pause after losing the lease — found because the
  campaign flapped on the fake store) and D11 (the duplicate rule per `Vojak`, not per process).
  `onStoreFailure` added to `elect` because the portfolio's lint refuses a swallowed `Throwable`, and
  rightly: a store failure now reaches the service's log. `Leader` carries no epoch and no
  timestamp yet — the epoch is [B-08](B-08-fencing-epoch.md).
- **Renewals run outside the campaign's structure** and a lease never takes two calls at once: a
  release asked for while a renewal is stuck is performed when that renewal returns
  (`PostgresLease`). Not exercised against a real partition here — that is B-09.
