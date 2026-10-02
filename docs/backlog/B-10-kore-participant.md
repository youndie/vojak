---
id: B-10
title: "vojak-kore: hand leadership over during the drain instead of after the lease"
status: wip
priority: P2
size: S
stage: stage-3-adoption
blocked_by: [B-07]
---

# B-10 — vojak-kore: hand leadership over during the drain instead of after the lease

On a rolling update the old leader gets `SIGTERM`. If it simply exits, its session ends and the
follower takes over after the server notices; if it is kept alive by a slow drain, the follower
waits for the lease. Either way the handover is later than it needs to be.

- **A kore consumer participant** (research §1.4): `stop()` cancels the `whileLeader` block and ends
  the holding transaction. Registered with `consumer(...)`, so it runs before the pools close.
- Not published to Maven Central while kore is not there (research D8).

- AC: `SIGTERM` to the leading binary → the follower reports `Leader` within one poll interval, and
  the old leader exits inside its grace period.
- Anchors: `vojak-kore/src/commonMain/kotlin/io/github/youndie/vojak/kore/ElectionParticipant.kt`.
