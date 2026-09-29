---
id: B-13
title: "A patched sqlx4k-postgres that reports I/O errors instead of aborting, published from a fork"
status: wip
priority: P1
size: L
stage: stage-2-election
---

# B-13 — A patched sqlx4k-postgres that reports I/O errors instead of aborting, published from a fork

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

## Decision — 2026-09-30

The owner chose **2 now, 1 later**: a fork with the patch, published to the portfolio's reposilite;
the upstream report ([B-14](B-14-report-the-abort-upstream.md)) only once the fork has run in a
consumer and the owner says so. The item is no longer a question.

- **Fork `smyrgeorge/sqlx4k` as `youndie/sqlx4k`**, patch on a branch there; the upstream remote's
  push URL disabled, as with the portfolio's other forks. Nothing is pushed or filed upstream.
- **The patch**: `sqlx4k_postgres_error_result_of` maps `Io`, `Tls`, `Protocol` and the other
  panicking variants to an error code with the error's text, the way `Database` already is — no
  `panic!` left on a path a running connection can reach.
- **Coordinates unchanged, version suffixed**: `io.github.smyrgeorge:sqlx4k-postgres:1.13.1-yio.<n>`
  (and its per-target artefacts). A different group would put two copies of the same classes on a
  consumer's classpath next to the plain `io.github.smyrgeorge:sqlx4k` vojak depends on; a suffix
  keeps one set and makes the patched build impossible to mistake for the release.
- Rejected: patching by rebuilding only the Rust static library into a consumer's build — it would
  hide the change inside every consumer instead of naming it once.

- AC: the probe (`samples/probe`, pointed at the patched version) runs `N1`, `H2`, `H3` and `H4` on
  `linuxX64` **without exit 134**: the second call on a dead connection is a failed `Result`; the
  same checks with 1.13.1 still abort (the control).
- AC: vojak's own tests run against the patched driver on both targets, and `Sql.kt`'s native
  "no rollback after a connection-gone failure" rule is re-decided against it (research
  Consequence 12) — kept, or removed with the measurement that allows it.
- AC: research §1.5 and D-numbers record the patched version, where it is published and the fork's
  commit it was built from.
- Anchors: `samples/probe/build.gradle.kts`, `samples/probe/run.sh`,
  `vojak-postgres/src/commonMain/kotlin/io/github/youndie/vojak/postgres/Sql.kt`,
  `gradle/libs.versions.toml`.
