---
id: B-07
title: "whileLeader runs the singleton's loop and cancels it before leadership is observably lost"
status: done
priority: P1
size: M
stage: stage-2-election
blocked_by: [B-06]
---

# B-07 — whileLeader runs the singleton's loop and cancels it before leadership is observably lost

The API a singleton's author actually writes against: a loop that runs only on the leader.

- **Order is the contract**: the block starts after the state is `Leader` and is cancelled — and
  its cancellation completed — before the state reads `Follower`.
- **A block that throws steps down**, releases the lock and campaigns again after a backoff; the
  exception goes to a handler the caller passes, never to an unhandled-exception crash of the scope.
- In-process state kept by the block is lost on handover; the KDoc says so in the first sentence.

- AC: a block that throws on its first run is observed by the handler, the other replica becomes
  leader, and the thrower campaigns again; a block cancelled by step-down has finished its
  `finally` before `Follower` is emitted.
- Anchors: `vojak-core/src/commonMain/kotlin/io/github/youndie/vojak/Election.kt`.

## Findings — 2026-10-02

- **Done, on both targets** (`make build` on the Linux box, twice on the head): `whileLeader` is a
  trailing lambda of `Vojak.elect`, with `onWorkFailure` as its handler; it runs inside the campaign
  (`Election.holdUntilLost`). 5 core tests on the scheduler's clock, 2 against Postgres.
- **AC, walked**: a block that throws on its first run reaches the handler, the other replica takes
  over (`aThrowingBlockHandsTheLeadershipToTheOtherReplica`), and the thrower campaigns again; a
  block cancelled by a step-down finishes a 100 ms `finally` while the state still reads `Leader`,
  and only then `Follower` (`aStepDownCancelsTheBlockAndFinishesItsFinallyBeforeFollowerIsVisible`);
  on a graceful handover the old block has ended before the new one starts.
- **Mutations, killed on both targets and both layers**: not waiting for the block before `Follower`
  — the step-down test, the ignoring-cancellation test and the graceful-handover test; a throwing
  block not stepping down — the throwing-block tests in core and against Postgres.
- **Decided on the way**: a block that ignores cancellation is waited for at most the lease margin
  (`serverLease − localLease`) and reported as `LeaderWorkDidNotStopException` — past that the
  server has ended the lease, so waiting longer would only make the state lie. `close()` now waits up
  to `serverLease` for the same reason. Research D10 amended: the pause after losing the lease is two
  polls, because with one the thrower raced the other replica for the lease it had just given up.
- **Not in this item**: the block gets no epoch argument yet ([B-08](B-08-fencing-epoch.md)); the
  "handover cancels the loser's work first" scenario under a *partition* is the fault suite's
  ([B-09](B-09-fault-suite.md)); under a server-ended session it overlaps (research §1.6,
  [B-15](B-15-wait-out-the-previous-leader.md)).
