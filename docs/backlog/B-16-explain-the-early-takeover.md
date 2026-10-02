---
id: B-16
title: "Explain the one partition run where the successor led ~1 s earlier than the lease allows"
status: done
priority: P2
size: S
stage: stage-2-election
epic: feature-leader-election
---

# B-16 — Explain the one partition run where the successor led ~1 s earlier than the lease allows

Research §1.7: in one `partition` run of twelve the old leader stepped down at 1.96 s and the
successor led at **4.64 s** after the fault. The floor is about 5.5 s: the server ends the
partitioned session `serverLease` (4 s) after its last statement — no earlier than ~3.5 s — and then
the successor waits `localLease` (2 s) because nobody released cleanly (research D12). Every other
run sat at 6.03–6.09 s.

There was no overlap in that run, so the promise held. But the number says one of two things
happened, and both matter:

- **the wait was skipped** — the successor read the predecessor as cleanly released. If that can
  happen after a partition it can happen after an ended session, where it *does* overlap (research
  §1.6, Consequence 16);
- **the session ended early** — Postgres ended it before `serverLease` of silence, or the last
  statement reached the server later than the client believes (`docker pause` takes effect some
  hundreds of milliseconds after the fault is recorded).

The second is harmless and likely; the first is not, and only evidence separates them. The logs of
that run were overwritten by the next one.

- AC: the anomaly reproduced with the server-side observer running (`<scenario>-server.log`: holder,
  idle time, epoch and `released`), or `RUNS=50 ONLY=partition` without a recurrence, recorded as such;
  either way research §1.7's open paragraph is replaced by what was found.
- Anchors: `samples/two-replicas/fault-suite.sh`, `samples/two-replicas/oracle.py`,
  `vojak-postgres/src/commonMain/kotlin/io/github/youndie/vojak/postgres/Epochs.kt`,
  `samples/two-replicas/lease-clock.py` (the clock check).

## Findings

- **Closed 2026-10-02 as not reproduced.** `RUNS=50 ONLY=partition` on the Linux box, with the
  server-side observer running. The oracle now refuses a takeover earlier than
  `SERVER - RENEW + LOCAL` (5.5 s). None was early: 49 runs fell between 5.98 and 6.30 s.
- **Clock jumps ruled out.** `lease-clock.py`: 40 of 40 leases ended at 4.00–4.01 s on
  `CLOCK_MONOTONIC`. In 90 s there was no realtime jump. The +2.93 s jumps measured on 2026-09-25
  stopped with the reboot on 2026-09-30.
- **One run took over late, at 10.86 s, while the box was shared with CI.** The observer's
  `docker exec` took ~10 s, and the follower's pool failed with `PoolTimedOut`. This is liveness under
  a starved host, not a safety question: there was no overlap. The observer's samples now carry a
  stamp both when the query starts and when the answer comes back.
- Research §1.7: the open paragraph is replaced by these results.

