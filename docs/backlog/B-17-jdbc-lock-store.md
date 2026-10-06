---
id: B-17
title: "A JDBC store: the same lock and election for a JVM service whose database is a DataSource"
status: done
priority: P1
size: M
stage: stage-3-adoption
---

# B-17 — A JDBC store: the same lock and election for a JVM service whose database is a DataSource

The first consumer outside this repository is a JVM service on Exposed and HikariCP. `vojak-postgres`
takes only an sqlx4k `Driver` (research D3), and on the JVM sqlx4k means r2dbc: the service would have
carried a second driver and a second pool next to its own, both pointed at the same database, for one
held connection. Its `lockForTransaction` takes an sqlx4k `Transaction`, which the service never has.

- **The decision (research D13): a JVM-only module, `vojak-jdbc`, that implements `LockStore` over a
  `javax.sql.DataSource`** and adds `lockForTransaction(java.sql.Connection, …)`. It depends on
  `vojak-core` and the JDK only — no driver, no pool — which is D3's rule restated for the JVM.
- **The same SQL as `vojak-postgres`, not its own.** The same advisory keys, the same `<prefix>_epoch`
  table, the same clean-release mark: a JDBC replica and an sqlx4k replica of one service are one
  election. Held to it by a test, since a shared string would have meant a JDBC module depending on
  sqlx4k after all.
- **One addition over `vojak-postgres`: the lease's connection gets a network timeout of twice the
  server lease.** A renewal sent into a partition otherwise holds a pool thread and a pool connection
  until the kernel gives up on the socket — a quarter of an hour on a Linux default — while Postgres
  ended the session after the lease. A pool lends a fixed number of connections; that is a cost the
  sqlx4k store does not have the knob to avoid.
- Rejected: a second sqlx4k pool in the consumer (two drivers for one database, and the leadership's
  connection outside the pool the service sizes); an Exposed module (ties vojak to one ORM, when every
  JVM database library ends in a `DataSource`).
- Not covered: Kotlin/Native (JDBC does not exist there), Maven Central (B-12 still governs the release;
  the module ships to reposilite with the others).

- AC: `vojak-postgres`'s lock and election scenarios pass over JDBC and HikariCP — contention never
  overlaps, `tryWithLock` gives up at once, cancellation and an exception release, a session ended
  during the block is `LockLostException`, one leader of two, a clean close hands over within a poll,
  and research §1.6 (the server ends the leader's session) shows no instant with two leaders.
- AC: a JDBC replica and an sqlx4k replica exclude each other, continue one epoch sequence and honour
  each other's clean-release mark.
- AC: no path keeps a pool connection: every `withLock` outcome and a closed leadership leave the pool
  with nothing lent out.
- AC: `lockForTransaction` on an auto-commit connection is refused — there the lock would end with the
  statement that takes it.
- Anchors: `vojak-jdbc/src/jvmMain/kotlin/io/github/youndie/vojak/jdbc/JdbcLockStore.kt`,
  `vojak-jdbc/src/jvmMain/kotlin/io/github/youndie/vojak/jdbc/JdbcLease.kt`,
  `vojak-jdbc/src/jvmMain/kotlin/io/github/youndie/vojak/jdbc/TransactionLocks.kt`,
  `vojak-jdbc/src/jvmTest/kotlin/io/github/youndie/vojak/jdbc/JdbcElectionTest.kt`.

## Findings — 2026-10-06

- **Done.** 14 tests in `JdbcLockStoreTest` and `JdbcElectionTest`, against `vojak-test-pg` through
  HikariCP 7.1.0 and pgjdbc 42.7.13 — the versions of the first consumer.
- **The oracles were shown to fail.** With the release not giving the connection back,
  `noPathKeepsAConnection` is red. With the clean-release mark replaced by `select 1`, the handover
  tests are red on both kinds of replica: the successor waited the full `localLease` (2.14 s and
  2.17 s against a 2 s lease) — the mark is what the interop test sees, not a coincidence of timing.
- **SET LOCAL through `set_config(…, true)`.** It takes a bound parameter, so the holder's name reaches
  `application_name` without being spliced into SQL; `vojak-postgres` interpolates it.
