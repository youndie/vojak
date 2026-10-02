---
id: B-10
title: "vojak-kore: hand leadership over during the drain instead of after the lease"
status: done
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

## Findings — 2026-10-02

- **Done**: `ElectionParticipant` and `ShutdownPlanBuilder.election(...)` in `vojak-kore`; a unit
  test through kore's real `shutdownSequence` on both targets; the fault suite's replica now shuts
  down through `runUntilSignal` with the election as its only participant.
- **AC, walked on real binaries** (`fault-suite.sh`, scenario `term`, 4 runs): SIGTERM to the leader
  → the other replica leads after 198–285 ms, within a poll and without the wait of research D12,
  because the close released cleanly; the old leader exits well inside 10 s.
- **Mutation**: the participant not closing the election → `term` fails twice over — the successor
  never leads within the bound, and the process does not exit at all (the campaign keeps the
  caller's `runBlocking` alive). Without the participant a service would hang until it is killed.
- **Found in kore, not fixed here**: `kore-core`'s JVM variants are built for Java 25 and do not
  declare it (research §1.4). `vojak-kore` compiles for 25 and declares 25; the other modules stay
  at 21. A draft for kore, **not filed** — that is the owner's call:

  > **kore-core's JVM variant needs Java 25 but its metadata does not say so.** `kore-core`
  > 0.1.12/0.1.14 is compiled to class file 69, and the `jvm*Elements-published` variants in the
  > `.module` have no `org.gradle.jvm.version`. A consumer on a lower JDK resolves it and fails at
  > run time with `UnsupportedClassVersionError` (seen in youndie/vojak's `vojak-kore` JVM test on
  > Java 21). Either stamp `org.gradle.jvm.version=25` on the JVM variants, so Gradle refuses the
  > resolution, or compile the JVM target lower.

- **Not on Maven Central while kore is not** (research D8) — B-12's concern.
