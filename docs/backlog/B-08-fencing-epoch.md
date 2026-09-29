---
id: B-08
title: "Each leadership gets the next epoch, so a store can reject a stale leader"
status: open
priority: P1
size: S
stage: stage-2-election
blocked_by: [B-06]
---

# B-08 — Each leadership gets the next epoch, so a store can reject a stale leader

Research D9: vojak bounds how long a stale leader *believes* it leads; only a token the store checks
makes its late writes harmless.

- **`vojak_epoch(name, epoch)`**, bumped with an upsert in its **own** short transaction right after
  the lock is taken (research D5) — never in the holding transaction, which must not get a
  transaction id.
- The table is created by vojak's own migration, under a name the caller can prefix; vojak never
  touches the caller's tables.

- AC: over 100 forced handovers between two processes the observed epochs are strictly increasing
  with no repeats; `pg_stat_activity.backend_xid` of the holding session stays empty throughout.
- Anchors: `vojak-postgres/src/commonMain/kotlin/io/github/youndie/vojak/postgres/Epochs.kt`,
  `vojak-postgres/src/commonMain/resources/vojak/migrations/`.
