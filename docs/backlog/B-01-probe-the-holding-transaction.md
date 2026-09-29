---
id: B-01
title: "Measure what a held leadership costs and how it dies, before any code depends on it"
status: done
priority: P1
size: S
stage: stage-0-evidence
---

# B-01 — Measure what a held leadership costs and how it dies, before any code depends on it

D1 and D2 in the [research](../research/research-architecture.md) — hold the lock in an open
transaction, let `idle_in_transaction_session_timeout` be the lease — are what every later item
compiles against, and both rest on hypotheses nobody has run: H1 (the idle transaction does not
hold back vacuum), H2 (`SET LOCAL` of the timeout applies to the same transaction), H3 (the native
driver reports a killed session as a failed `Result`, not a panic), H4 (how long a query takes to
come back under a partition), H5 (the JVM half agrees).

- **A throwaway probe, not the library.** A `samples/probe` binary on `linuxX64` and the JVM, one
  Postgres in Docker, a TCP proxy between them that can drop packets. Its findings go into research
  §1 as facts with addresses; its code may be deleted after.
- Rejected: reasoning from the Postgres source instead. H1 is answerable from `procarray.c`, but H3
  and H4 are about a Rust driver behind FFI and only a run answers them — and one harness answers
  all five.
- Not covered: PgBouncer. Recorded in D1 as a hypothesis; it is a separate probe if a consumer
  runs one.

- AC: research §1 gains a subsection with, for each of H1–H5, the measured value and a **positive
  control**: H1 shows `backend_xid`/`backend_xmin` empty for the holder *and* populated for a
  transaction that wrote; H2 shows the session terminated after the configured timeout *and* not
  terminated with the setting absent; H3 shows the process alive after `pg_terminate_backend` of
  the holder; H4 gives the elapsed time of a query started during a partition, measured, not only
  its outcome.
- AC: every hypothesis is either moved to §1 as a fact or rewritten as a refutation with what D1/D2
  become — not deleted.
- Anchors: `docs/research/research-architecture.md`, `samples/probe/`.

## Findings — 2026-09-29

Everything measured is in research §1.5, with the check that produced each row; this is what the
item itself needs to say.

- **H1 confirmed**, both targets, both controls green: the holder has no xid and no xmin, and
  vacuum removes what it should. Risk 1 closed; D1 stands.
- **H2 confirmed**, both targets, control green: `SET LOCAL idle_in_transaction_session_timeout`
  ends the holder's session and frees the lock; it does not leak into the pool (JVM; the native
  process had ended by then). The server lease also ends a **partitioned** holder on time
  (5029 / 5045 ms for a 5 s lease). D2 stands.
- **H3 refuted on linuxX64**, and wider than the hypothesis: the first call after the server ends
  a session fails cleanly; the second — and any I/O error at all — aborts the process from a Rust
  `panic!` in the driver. Became [B-13](B-13-native-driver-aborts-on-io-errors.md), a `question`
  for the owner, blocking [B-06](B-06-election.md) and [B-09](B-09-fault-suite.md).
- **H4 measured**: on linuxX64 a statement stuck in a partition cannot be cancelled (no `join()`
  within 20 s); on the JVM cancellation takes 3–4 ms. D6's timer-based step-down is load-bearing.
- **H5 refuted in part**: the targets agree on H1, H2 and the lease; they disagree on dead
  connections and on cancellation.
- **Beyond the hypotheses** (`N4`): a transaction abandoned without `rollback()` keeps its pool
  connection for good, on both targets — vojak must end every transaction it opens, and on native
  that end has to be the first call after a failure.
- **Not done**: PgBouncer (as planned); a minimal reproduction of the Kotlin/Native
  `Unexpected receiver type: kotlin.time.Duration` the probe hit (recorded in research §3, not the
  driver's and not vojak's).

The probe stays in `samples/probe/`: it is the reproduction B-13 hands upstream, and the check that
says whether a fixed driver is fixed. It is a separate Gradle build and is not part of the gate.
