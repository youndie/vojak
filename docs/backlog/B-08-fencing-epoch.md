---
id: B-08
title: "Each leadership gets the next epoch, so a store can reject a stale leader"
status: done
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

## Findings — 2026-10-02

- **Done, on both targets** (`make build` on the Linux box, twice on the head): `Leadership.Leader`
  carries `epoch`, the `whileLeader` block receives it, `Lease.epoch` in the port; in
  `vojak-postgres`, `Epochs` bumps `<prefix>_epoch` with an autocommit upsert after the lock is held,
  and `PostgresLockStore(driver, tablePrefix = "vojak")` names the table.
- **AC, walked**: 100 forced handovers between two replicas — epochs strictly increasing, no repeats
  (`overAHundredHandoversTheEpochsStrictlyIncreaseWithNoRepeats`); the holding session's
  `backend_xid` sampled ten times during a leadership — always null, and the control (a transaction
  that takes an xid) shows one through the same query (`theHoldingSessionNeverGetsATransactionId`).
- **Mutations, killed on both targets**: the holding transaction taking an xid — the xid test; an
  upsert that does not increment — the handover test.
- **Different from the anchors on purpose**: no migration file under `resources/` — a Kotlin/Native
  library cannot read one — so the table is created on first election, under an advisory lock of its
  own because two replicas starting together race `create table if not exists`
  (`vojak-postgres/src/commonMain/kotlin/io/github/youndie/vojak/postgres/Epochs.kt`).
- **Left to B-15**: the clean-release mark (research D12) goes in this table as a column; the table
  is created without it today, so B-15 adds it with `alter table … add column if not exists`.
- **Not exercised**: the reference store that refuses a stale epoch — that is the sample's
  ([B-11](B-11-reference-sample.md)).
