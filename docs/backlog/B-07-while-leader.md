---
id: B-07
title: "whileLeader runs the singleton's loop and cancels it before leadership is observably lost"
status: wip
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
