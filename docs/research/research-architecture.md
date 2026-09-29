---
id: research-architecture
title: vojak — architecture research
type: research
status: active
date: 2026-09-29
---

# Research: the architecture of vojak

vojak is leader election and a distributed lock for Kotlin services — Kotlin/Native first, the JVM
second — built on the one coordinator such a service already has: its Postgres. A service that runs
as one replica keeps its singletons in process (a long-polling bot client that its upstream allows
only once, a scheduler that sends the morning digest, a sweeper). Run the same binary twice — for a
rolling update with `maxSurge`, for availability — and every one of them runs twice. On the JVM
this is solved by ShedLock or Spring Integration's `LockRegistry`; on Kotlin/Native nothing exists,
and the alternatives (etcd, ZooKeeper, a Kubernetes `Lease` with the RBAC it needs) are a second
piece of infrastructure bought to answer one question.

This document records **verified facts** (what was actually read in code and artefacts),
**decisions taken**, and **risks**. Anything unverified is marked as a hypothesis and says where it
will be checked. There is no code yet: every fact below was read out of a dependency's artefact or
the official documentation of the version named next to it.

This is the entry point of the documentation: the layer documents say what the system does, and
this one says why it is built that way. It lives here permanently and is amended as the
implementation teaches us things.

---

## 1. Verified facts

### 1.1 What a Postgres advisory lock is, and what it is not

Verified against the PostgreSQL 18 documentation.

| Fact | Where verified |
|---|---|
| A **session-level** advisory lock is held until explicitly released or the session ends; it does not honour transaction semantics — taken in a transaction that later rolls back, it is still held | [PG 18 §13.3.5 Advisory Locks](https://www.postgresql.org/docs/18/explicit-locking.html#ADVISORY-LOCKS) |
| A **transaction-level** advisory lock (`pg_advisory_xact_lock`, `pg_try_advisory_xact_lock`) is released automatically at the end of the transaction; there is no explicit unlock | same |
| Locks stack: "for each completed lock request there must be a corresponding unlock request" | same |
| "If a session already holds a given advisory lock, additional requests by it will always succeed, even if other sessions are awaiting the lock" — at either level | same |
| A locking function inside a query with `ORDER BY`/`LIMIT` may be evaluated for rows the `LIMIT` later discards | same |

**Consequence 1.** An advisory lock says "this *session* holds it", never "this *process* holds it".
Anything that lets a session outlive the process's belief — a pool that keeps the connection, a
network that stops delivering packets — separates the two, and the lock alone cannot tell.

**Consequence 2.** Re-entrancy is per session, not per process. Two elections for the same name in
one process land on two pool connections — two sessions — and the second blocks on the first,
or, if they happen to share a connection, silently both "win". The library has to arbitrate in
process before it asks Postgres (feature rule `DuplicateElection` / `ReentrantLock`).

**Consequence 3.** Every lock statement vojak issues takes a single key computed in Kotlin, never a
key derived from rows inside the same query, so the `LIMIT` hazard cannot arise.

### 1.2 When Postgres notices that a client is gone

Verified against the PostgreSQL documentation of the versions named.

| Fact | Where verified |
|---|---|
| `idle_in_transaction_session_timeout` terminates a session idle *inside an open transaction* for longer than the value; default `0` (off); any user may set it | [PG 18 §19.11](https://www.postgresql.org/docs/18/runtime-config-client.html); present in the 9.6 docs, absent in 9.5 |
| The same page: "an open transaction prevents vacuuming away recently-dead tuples" | same |
| `idle_session_timeout` terminates a session idle *outside* a transaction; default `0`; the docs advise against it for pooled connections | [PG 18 §19.11](https://www.postgresql.org/docs/18/runtime-config-client.html); present in the 14 docs, absent in 13 |
| `client_connection_check_interval` polls the socket **only while a query is running**; "without connection checks, the server will detect the loss of the connection only at the next interaction with the socket" | [PG 18 §19.3](https://www.postgresql.org/docs/18/runtime-config-connection.html); present in 14, absent in 13 |
| `tcp_keepalives_idle = 0` selects the operating system default | same |

**Consequence 4.** A leader whose process dies releases its lock quickly: the kernel closes the
socket, the server's next read fails, the session ends. A leader that is **partitioned** — alive,
but no packet arrives — holds the lock for as long as the server does not touch its idle socket,
which with default settings is the OS keepalive horizon (hours on a stock Linux). That is the case
that matters, and the one the default configuration handles worst.

**Consequence 5.** `idle_in_transaction_session_timeout` turns an idle transaction into a lease
enforced by the server: stay silent for longer than the value and the server ends the session, and
with it every transaction-level lock. That is the mechanism of D2, and it needs no superuser, no
server configuration and no keepalive tuning. The vacuum warning on the same page is the price to
check (Risk 1).

### 1.3 How sqlx4k gives out connections — per target, not per library

Verified against the sources jars of sqlx4k **1.13.1** (the current release on Maven Central on
2026-09-29).

| Fact | Where verified |
|---|---|
| `Driver` is `ConnectionPool` + `QueryExecutor` + `QueryExecutor.Transactional`; `begin()` returns a `Transaction` that holds one connection until `commit()`/`rollback()` | `io.github.smyrgeorge:sqlx4k:1.13.1!/commonMain/io/github/smyrgeorge/sqlx4k/QueryExecutor.kt`, `…/Driver.kt`, `…/Transaction.kt` |
| `transaction { }` rolls back when the block throws or returns a failed `Result` | `io.github.smyrgeorge:sqlx4k:1.13.1!/commonMain/io/github/smyrgeorge/sqlx4k/QueryExecutor.kt` |
| `ConnectionPool` exposes `poolSize()`, `poolIdleSize()`, `acquire()`, `close()` — **not** the `Options` it was built with, so a library handed a `Driver` cannot learn its maximum size | `io.github.smyrgeorge:sqlx4k:1.13.1!/commonMain/io/github/smyrgeorge/sqlx4k/ConnectionPool.kt` |
| The **Kotlin** pool in the core artefact returns a connection to the idle queue on `close()` without resetting the session | `io.github.smyrgeorge:sqlx4k:1.13.1!/commonMain/io/github/smyrgeorge/sqlx4k/impl/pool/PooledConnection.kt` |
| The **native** Postgres driver does not use that pool: `acquire()` and `begin()` go through FFI into the Rust runtime (`sqlx4k_postgresql_cn_acquire`, `sqlx4k_postgresql_tx_begin`), which owns the pool | `io.github.smyrgeorge:sqlx4k-postgres:1.13.1!/nativeMain/io/github/smyrgeorge/sqlx4k/postgres/PostgreSQL.kt` |
| The **JVM** Postgres driver does not use it either: it builds an `io.r2dbc.pool.ConnectionPool` with size, timeouts and lifetime only — no allocation or release hooks | `io.github.smyrgeorge:sqlx4k-postgres-jvm:1.13.1!/jvmMain/io/github/smyrgeorge/sqlx4k/postgres/PostgreSQL.kt`; `r2dbc-postgresql 1.1.2`, `r2dbc-pool 1.0.2` in its POM |
| The native driver renders a `LocalDateTime` parameter as `toString().replace('T', ' ')` and hands it to Rust as text | `io.github.smyrgeorge:sqlx4k-postgres:1.13.1!/nativeMain/io/github/smyrgeorge/sqlx4k/postgres/params.kt` |
| `sqlx4k`, `sqlx4k-postgres`, `sqlx4k-postgres-linuxx64` and `sqlx4k-postgres-linuxarm64` are all published at 1.13.1 | Maven Central `maven-metadata.xml` of each, 2026-09-29 |

**Correction found while researching.** The brief this document started from said "a pooled sqlx4k
connection's `close()` returns it to the idle queue with no session reset", verified against
`PooledConnection.kt`. That file is real, and it is **not the pool either Postgres driver uses**:
native pools in Rust, the JVM pools in r2dbc-pool. Whether either of those resets a session on
release is therefore *not verified* (H0). The decision it supported (D1) stands, and now for a
stronger reason — it does not depend on the answer.

**Consequence 6.** A session-level lock taken on a connection from the caller's pool and not
explicitly unlocked on *that same connection* may outlive its owner inside the pool, held by an
idle connection nobody can name. Whether it does depends on three pool implementations, two of
them in other languages. A transaction-level lock cannot outlive its transaction, whichever pool
is underneath.

**Consequence 7.** vojak cannot refuse a pool that is too small, because it cannot see the size.
Each held leadership pins one connection for its whole duration; that is a documented cost the
caller sizes for (Risk 5), not a check.

**Consequence 8.** kotlinx-datetime omits the seconds of a whole minute (`2030-01-07T11:00`), so the
text above reaches Rust without them. A consumer of this driver observed the Rust side answer that
with a `panic!` that ended the process rather than a failed `Result` — *reported, not re-read
here*: the Rust source is not in the sources jar, and the observation is not ours to cite by
address. What it establishes is the failure class, not this bug: a Rust driver behind FFI may
end the process where a JVM driver would throw. A heartbeat is the one
statement vojak runs forever; how the native driver reports a connection the *server* has killed
is the most important unknown in the project (H3).

### 1.4 The two libraries vojak plugs into

| Fact | Where verified |
|---|---|
| Two sqlx4k drivers in one Kotlin/Native binary do not link (both carry a Rust runtime and define the same symbols); a library that depends only on the plain `sqlx4k` artefact and takes a `Driver` from the caller avoids it | `youndie/chronik!/chronik-sqlx4k-sqlite/build.gradle.kts` — the same shape, shipped in chronik 0.2.0 on Maven Central with a `linuxX64` variant |
| A transaction-level advisory lock held through `Driver.begin()` on sqlx4k is already in production use, for serialising migrations | `youndie/shildik!/storage-sqlx4k/src/commonMain/kotlin/io/github/youndie/shildik/storage/sqlx4k/PostgresMigrations.kt` |
| kore's shutdown plan has a `consumer(participant)` group that releases **before** `pool(participant)`; a `ShutdownParticipant` is a `name` and a cancellable `suspend fun stop()` | `youndie/kore!/kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/ShutdownPlan.kt`, `…/ShutdownParticipant.kt` |
| kore is **not** on Maven Central: its metadata answers 404 on 2026-09-29 | [repo1.maven.org/…/kore-core/maven-metadata.xml](https://repo1.maven.org/maven2/io/github/youndie/kore/kore-core/maven-metadata.xml) |
| booblik lists "no leader election" among its non-goals | `youndie/booblik!/README.md` |

**Consequence 9.** `vojak-kore` is a consumer participant — release the lock after in-flight
work is cancelled and before the pool it needs is closed — and it is the whole of the kore
integration. It also cannot be published to Maven Central while kore is not there: a POM on
Central that names a coordinate Central does not have is a green upload and an unbuildable
consumer (D8).

---

## 2. Decisions

### D1. Hold the election lock with `pg_try_advisory_xact_lock` inside a transaction kept open *(deviation from the brief's wording)*

Brief / first idea: "leader election on advisory locks" — the textbook recipe is
`pg_try_advisory_lock` (session level) on a long-lived connection.
Decision: the leader opens a transaction with `Driver.begin()`, takes
`pg_try_advisory_xact_lock(key)` in it, and keeps that transaction open for as long as it leads.
Losing or giving up leadership is ending that transaction — commit, rollback, or the server ending
the session.

Why:

- there is exactly one release path, and it is the one Postgres guarantees; a session lock needs an
  explicit unlock on the same connection, and every path where that unlock does not happen leaves
  the lock in a pool whose reset behaviour is unverified (Consequence 6);
- it works behind PgBouncer in transaction pooling, where a session lock does not (the lock would be
  held by whichever server connection the pooler happened to use) — *hypothesis* for PgBouncer's
  exact behaviour, not read here;
- it has a precedent on the same driver (§1.4).

Rejected:

- **session lock on a connection from `acquire()`**: correct only if the unlock always runs on that
  connection; kept as the fallback for Risk 1;
- **a lease row** (`UPDATE leases SET holder = …, until = now() + …`): a write every heartbeat, and
  correctness rests on comparing clocks across processes, which is exactly what the advisory lock
  saves us from;
- **a Kubernetes `Lease`**: a second coordinator, RBAC, and a client that does not exist for
  Kotlin/Native.

The price: an open transaction for the whole of a leadership — Risk 1.

### D2. The server-side lease is `idle_in_transaction_session_timeout` on the holding transaction

Decision: after taking the lock, the holder runs `SET LOCAL idle_in_transaction_session_timeout =
<serverLease>`, and heartbeats with `SELECT 1` inside the same transaction.

Why: Consequence 4 — a partitioned leader is otherwise protected for hours. With this setting the
server ends a silent leader's session after `serverLease` of silence, its lock goes with it, and a
follower can take over. No server configuration, no superuser, no keepalive tuning on either side.

Rejected: TCP keepalives (per host, per OS, and invisible to the library); `client_connection_check_interval`
(only checks while a query runs — the holder is idle between heartbeats); `idle_session_timeout`
(needs the lock outside a transaction, i.e. the session lock D1 rejects).

Open: that `SET LOCAL` of this setting governs the idle periods of the *same* transaction (H2).

### D3. Take a `Driver` from the caller; depend on plain `sqlx4k`

Two drivers in one native binary do not link (§1.4). The caller has already chosen
`sqlx4k-postgres`; vojak's SQL is Postgres dialect, its dependency is not.

### D4. A lock name becomes a key in Kotlin: 64-bit FNV-1a of `"<namespace>:<name>"`

Why: identical on every target by construction, with pinned test vectors; no reliance on a server
hash function whose stability across major versions is not documented. At the scale of "a few
dozen names per database" the collision probability is not a practical concern. Rejected: the
two-`int` form with a constant class id — a 32-bit hash space for the names.

### D5. The fencing epoch is bumped in its own short transaction, after the lock is held

A write in the holding transaction would give it a transaction id for the whole leadership and hold
back vacuum on every table in the database — the worst form of Risk 1. The advisory lock already
makes the leader the only writer of its epoch row, so a separate transaction loses nothing.

### D6. `localLease = serverLease / 2`, heartbeat every `serverLease / 3`, both on the monotonic clock

The leader steps down on its own when `localLease` has passed since its last *successful*
heartbeat, and it decides that with a timer, not by waiting for the heartbeat call to return — so a
driver call that hangs under a partition (H4) delays cleanup, never the step-down. The margin
between the two leases absorbs one lost heartbeat and clock drift; virtualised hosts are known to
run their monotonic clock several percent off, so a thin margin is not safe even in a test stand.
The drift bound is *assumed*, and the feature says so.

### D7. Targets: `jvm`, `linuxX64`, `linuxArm64`

Every dependency publishes all three (§1.3; kore builds `linuxArm64` too). The suites run on `jvm`
and `linuxX64`; `linuxArm64` is compiled, and running its tests needs an arm64 runner — a backlog
item, not an assumption that compiling is testing.

### D8. `vojak-kore` is not published to Maven Central until kore is

Consequence 9. `vojak-core` and `vojak-postgres` go to Central; `vojak-kore` goes where kore is, and
follows it to Central.

### D9. vojak promises less than "one leader" *(deviation from the brief)*

The request was leader election. An advisory lock alone gives "one session holds the lock", which
is not "one process acts as leader" (Consequence 1). vojak promises: **at most one process
*believes* it leads at any instant, provided clock drift stays inside the lease margin**, and hands
out a monotonically increasing epoch per leadership so that a store can reject a stale leader's
writes. Making a stale leader harmless is the consumer's store's job; vojak makes it possible and
says so, rather than implying it.

---

## 3. Risks and open questions

**Risk 1. The holding transaction may hold back vacuum for the whole leadership.** Mechanism: an
open transaction with a snapshot pins the xmin horizon (§1.2). Hypothesis H1 is that a READ
COMMITTED transaction which only ran `pg_try_advisory_xact_lock`, `SET LOCAL` and `SELECT 1` has
no `backend_xid` and no `backend_xmin` between statements, and costs nothing. Mitigation: B-01
measures both columns of `pg_stat_activity` during a held leadership, with a positive control (a
transaction that did write must show a `backend_xid`). If H1 is false, D1 flips to a session lock
on a dedicated connection with `idle_session_timeout` as the lease (Postgres ≥ 14), and D5 is moot.

**Risk 2. The native driver may kill the process when the server kills the session.** Mechanism:
Consequence 8. Mitigation: B-01 terminates the holder's session with `pg_terminate_backend` and
with the idle timeout, on `linuxX64`, and asserts on the *process* surviving, not only on the
`Result`; every statement vojak sends goes through one function, so a workaround has one home.

**Risk 3. A heartbeat call may not return while the network is partitioned (H4).** Mechanism:
cooperative cancellation does not interrupt a call that is blocked below the coroutine; a
`withTimeout` around a child waits for the child. Mitigation: D6 — the step-down is a timer, not the
call's return; B-01 measures how long the call takes to come back so that the cleanup cost is a
number.

**Risk 4. Clock drift larger than the lease margin.** Mechanism: the leader measures `localLease`
on its clock, the server measures `serverLease` on its own. Mitigation: a margin of half the lease;
the fault suite's overlap oracle is run once with the margin removed (`localLease = serverLease`)
and must go red, which is what proves it can see an overlap at all.

**Risk 5. Leadership pins a pool connection.** Mechanism: Consequence 7 — one connection per held
election, for hours, invisible to the library's arithmetic. Mitigation: KDoc on `Election` states
it; the reference sample sizes its pool with the election counted.

**Risk 6. Postgres fails over.** Advisory locks live in the primary's memory and are not replicated.
Mitigation: the old leader's connection dies with the old primary, its next heartbeat fails, and
it steps down (feature scenario *Postgres restarts*); the new primary starts with no locks and the
epoch table continues from the replicated value.

**H0.** Neither the Rust pool (native) nor r2dbc-pool (JVM) resets session state on release.
Not needed by D1; recorded so that nobody reintroduces a session lock on the assumption either way.

**H5.** The JVM half (r2dbc) behaves like the native half for H2–H4. Checked in B-01 on both
targets.

**Open question 1 — the first consumer.** Which service moves to two replicas first, and which of
its singletons keep state in process that a handover loses. That decides the reference sample.

**Open question 2 — chronik and petich.** chronik's Postgres backend is JVM-only; a native service
that wants both vojak and durable timers has no native Postgres timer store. Leader election does
not change that, and whether a `chronik-sqlx4k-postgres` belongs in this effort is open.

**Open question 3 — leadership on kore's admin route.** Default: no; a follower is a healthy
replica and must not look like a degraded one.

**Open question 4 — `LISTEN/NOTIFY` for a faster handover.** The native driver has `listen`
(`io.github.smyrgeorge:sqlx4k-postgres:1.13.1!/nativeMain/io/github/smyrgeorge/sqlx4k/postgres/PostgreSQL.kt`).
Out of the first version: polling first, and only a measured handover time would justify more.

---

## 4. What happens next

The order of work and the acceptance criteria live in the [backlog](../../backlog.md). The first
step is [B-01](../backlog/B-01-probe-the-holding-transaction.md): H1–H5 answered by measurement
against a real Postgres on both targets, because D1 and D2 — the two decisions everything else
compiles against — rest on them.
