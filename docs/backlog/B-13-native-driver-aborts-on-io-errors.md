---
id: B-13
title: "On linuxX64 sqlx4k-postgres 1.13.1 aborts the process on any I/O error — choose the way out"
status: question
priority: P1
size: M
stage: stage-2-election
---

# B-13 — On linuxX64 sqlx4k-postgres 1.13.1 aborts the process on any I/O error — choose the way out

[B-01](B-01-probe-the-holding-transaction.md) measured it (research §1.5, Consequences 11–13):
the native driver maps every `sqlx::Error` other than `Database`, `PoolTimedOut`, `PoolClosed` and
`WorkerCrashed` to a Rust `panic!`, and the panic aborts the process
(`smyrgeorge/sqlx4k@1.13.1!/sqlx4k-postgres/src/rust/src/lib.rs`, `sqlx4k_postgres_error_result_of`).
The second call on a connection the server has closed is `Io`; so is a statement stuck in a
partition once the network returns. A leader's heartbeat is exactly that call, so on `linuxX64`
every lost connection under a held leadership would end the whole service, not step it down.

This is not vojak's to fix inside vojak, and it is not only vojak's problem: any native service on
this driver that touches a connection twice after a restart or a blip goes down the same way.

## Question

What is the way out? The choices, with what each costs:

1. **Upstream first.** Report it to `smyrgeorge/sqlx4k` with the probe as the reproduction, and
   offer the fix — map `Io`/`Tls`/`Protocol` to an error code as `Database` already is. Cheapest if
   accepted; the wait is theirs. Needs the owner's agreement to file in someone else's repository.
2. **A patched build in reposilite**, as done for other dependencies of the portfolio: the same
   one-function change, published under the portfolio's group, pinned by vojak's sample and by
   services that need it. Costs a Rust toolchain in a release pipeline and a fork to keep rebased.
3. **Accept fail-stop on native and say so.** A process that aborts stops believing it leads, and
   the server frees the lock; safety holds, availability does not — every blip restarts the
   service. vojak would document it and the native fault suite would assert the restart, not the
   step-down.
4. **Drop linuxX64 from the first version** and ship JVM-only until 1 or 2 lands — against the
   reason the library exists (research, the opening paragraph).

Not a choice: catching it in Kotlin. The abort happens on a Rust worker thread before control
returns across FFI.

Recommendation, for the owner to accept or not: 1 and 2 together — file upstream with the fix,
and publish the patched build so work does not wait on the answer.

- Blocks: [B-06](B-06-election.md) and [B-09](B-09-fault-suite.md) on `linuxX64`; the lock
  ([B-04](B-04-with-lock.md)) and the build ([B-02](B-02-scaffold-and-gate.md)) do not wait.
- AC: the owner's choice recorded here and in research D-numbers; if 1 or 2, `N1` of the probe
  (`samples/probe/run.sh` with `CHECKS=N1`) returns a failed `Result` for the second statement on
  `linuxX64` instead of exit 134.
- Anchors: `samples/probe/src/commonMain/kotlin/io/github/youndie/vojak/probe/Main.kt`,
  `samples/probe/run.sh`.

## Owner's answer so far — 2026-09-29

Choice 1 is **on hold**: nothing is filed upstream until the owner has looked at it. Choices 2–4
are still open; the item stays a `question`, and B-06 / B-09 stay blocked on it.
