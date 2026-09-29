---
id: B-01
title: "Measure what a held leadership costs and how it dies, before any code depends on it"
status: open
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
