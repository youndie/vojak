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
will be checked. There is no library code yet: every fact in §1.1–§1.4 was read out of a
dependency's artefact or the official documentation of the version named next to it, and §1.5 was
measured by a throwaway probe against a real Postgres.

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
*Measured, 2026-09-29 (§1.5):* the failure class is ours to cite now, reproduced by the probe on a
different trigger — any I/O error ends the process.

### 1.4 The two libraries vojak plugs into

| Fact | Where verified |
|---|---|
| Two sqlx4k drivers in one Kotlin/Native binary do not link (both carry a Rust runtime and define the same symbols); a library that depends only on the plain `sqlx4k` artefact and takes a `Driver` from the caller avoids it | `youndie/chronik!/chronik-sqlx4k-sqlite/build.gradle.kts` — the same shape, shipped in chronik 0.2.0 on Maven Central with a `linuxX64` variant |
| A transaction-level advisory lock held through `Driver.begin()` on sqlx4k is already in production use, for serialising migrations | `youndie/shildik!/storage-sqlx4k/src/commonMain/kotlin/io/github/youndie/shildik/storage/sqlx4k/PostgresMigrations.kt` |
| kore's shutdown plan has a `consumer(participant)` group that releases **before** `pool(participant)`; a `ShutdownParticipant` is a `name` and a cancellable `suspend fun stop()` | `youndie/kore!/kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/ShutdownPlan.kt`, `…/ShutdownParticipant.kt` |
| kore is **not** on Maven Central: its metadata answers 404 on 2026-09-29. It is published to the portfolio's reposilite as `io.github.youndie:kore-core` 0.1.12, with `jvm`, `linuxX64`, `linuxArm64` and `macosArm64` variants | [repo1.maven.org/…/io/github/youndie/kore-core/maven-metadata.xml](https://repo1.maven.org/maven2/io/github/youndie/kore-core/maven-metadata.xml); the `.module` file of 0.1.12 in reposilite |
| booblik lists "no leader election" among its non-goals | `youndie/booblik!/README.md` |

*Correction found in B-02:* the first version of the row above checked
`io/github/youndie/kore/kore-core` — a coordinate kore never had — so its 404 proved nothing. The
right one, `io.github.youndie:kore-core`, answers 404 too; the conclusion stands on the right
evidence now.

*Found in B-10:* `kore-core`'s JVM variants (0.1.12 and 0.1.14 in the portfolio's reposilite) are
compiled for Java 25 — class file version 69 — and their `.module` metadata carries no
`org.gradle.jvm.version`. A consumer on Java 21 resolves them and fails at run time with
`UnsupportedClassVersionError` (`vojak-kore`'s JVM test did, on 21). Verified against
`io.github.youndie:kore-core:0.1.12!/kore-core-0.1.12.module` and the failing run. So `vojak-kore`
compiles for 25 and declares 25 itself (`vojak-kore/build.gradle.kts`); the rest of vojak keeps the
floor of 21.

*Fixed in kore 0.1.15* (2026-10-03, [youndie/kore#117](https://github.com/youndie/kore/pull/117),
kore's B-70): `kore-core` and `kore-ktor` are compiled for Java 21 and declare
`org.gradle.jvm.version` 21 on both JVM variants. Read back from
`io.github.youndie:kore-core:0.1.15!/kore-core-0.1.15.module` and the jar (class file 65 throughout).
`vojak-kore` is back on the repository's floor of 21 and no longer overrides the toolchain or the
attribute.

**Consequence 9.** `vojak-kore` is a consumer participant — release the lock after in-flight
work is cancelled and before the pool it needs is closed — and it is the whole of the kore
integration. It also cannot be published to Maven Central while kore is not there: a POM on
Central that names a coordinate Central does not have is a green upload and an unbuildable
consumer (D8).

### 1.5 What a held leadership costs and how it dies — measured (B-01)

Measured on 2026-09-29 by [`samples/probe/`](../../samples/probe/) — one program, run on
`linuxX64` and on the JVM against PostgreSQL 18.6 in Docker, with a socat proxy in front for the
partition (`docker pause` of the proxy: the connection stays open and nothing arrives). Every check
runs in its own process (`samples/probe/run.sh`), because on `linuxX64` some of them end it. The
check ids below are the ones the probe prints.

| Fact | linuxX64 | jvm | Check |
|---|---|---|---|
| A holder that ran only `pg_try_advisory_xact_lock`, `SET LOCAL` and `SELECT 1` shows `backend_xid` and `backend_xmin` **null**, state `idle in transaction`; 1000 tuples deleted while it is open are all removed by `VACUUM` (`pgstattuple`: 0 dead) | yes | yes | `H1` |
| Control: a transaction that wrote shows a `backend_xid`; a REPEATABLE READ transaction with a snapshot shows a `backend_xmin` and leaves all 1000 dead tuples in place | yes | yes | `H1-control-xid`, `H1-control-xmin` |
| `SET LOCAL idle_in_transaction_session_timeout = '2s'` on the holder: after 3.5 s idle the session is gone and the lock is free | yes | yes | `H2` |
| Control: the same holder without the setting is alive and still holds the lock after 3.5 s | yes | yes | `H2-control` |
| After the transaction commits, the same pooled connection reads `idle_in_transaction_session_timeout = 0` — `SET LOCAL` does not leak into the pool | — | yes | `H2-local` (on linuxX64 not reached: the process had ended in `H2`) |
| **Partitioned** holder with a 5 s lease: the server ends the session and frees the lock after | 5029 ms | 5045 ms | `H4-server-lease` |
| The **first** call on a transaction whose session the server ended (idle timeout or `pg_terminate_backend`) — statement, `rollback()` or `commit()` — returns a failed `Result` | yes, `[25P03]` / `[57P01]` with the server's text | yes, "Cannot exchange messages because the connection is closed" | `H2`, `H3`, `N1`, `N2`, `N3` |
| The **second** call on that transaction | **the process aborts** (SIGABRT, exit 134): `thread 'tokio-rt-worker' panicked at src/lib.rs:372:25: Io :: Unexpected error occurred.` | a failed `Result` | `N1`, and the `rollback()` after `H2`, `H3`, `H4` |
| A statement sent into the partition returns within 3 s | no | no | `H4` |
| Cancelling that statement's coroutine, then `join()` | **not within 20 s** | 3–4 ms | `H4` |
| A transaction abandoned without `rollback()`/`commit()` keeps its connection: a pool of one times out acquiring (10 s `acquireTimeout`) | yes (`PoolTimedOut`) | yes | `N4` |
| An idle pooled connection killed by the server, and a Postgres restart under an idle pool: the next plain query succeeds | yes | yes | `N5`, `N6` |

Where the abort comes from, read in the driver's Rust source:
`smyrgeorge/sqlx4k@1.13.1!/sqlx4k-postgres/src/rust/src/lib.rs` — `sqlx4k_postgres_error_result_of`
maps `Error::Database`, `PoolTimedOut`, `PoolClosed` and `WorkerCrashed` to error codes, and
**every other variant — `Io`, `Tls`, `Protocol`, `Configuration`, `Decode`, … — to `panic!`**
(lines 366–398). The first call after the server ends a session reads the server's `FATAL`
message, which is a `Database` error; every call after that meets a closed socket, which is `Io`.

**The patched driver, measured 2026-09-30 (B-13).** The fork `youndie/sqlx4k`, branch
`fix/postgres-io-errors-are-results` (commit `00bb4103`), reports every `sqlx::Error` variant it used
to panic on as a `Database` error naming the variant
(`youndie/sqlx4k@00bb4103!/sqlx4k-postgres/src/rust/src/lib.rs`). Built as
`io.github.smyrgeorge:sqlx4k-postgres:1.13.1.1-yio` for `jvm` and `linuxX64` into the Linux box's
local Maven repository, with the released core `sqlx4k:1.13.1` in its POM, and run through the same
probe (`SQLX4K=1.13.1.1-yio samples/probe/run.sh`):

| Fact | 1.13.1.1-yio | 1.13.1 (control, same probe code, same day) |
|---|---|---|
| Second call on a session the server ended (`N1`; the `rollback()` after `H2`, `H3`, `H4`) on linuxX64 | failed `Result`: `[Database] :: Io :: error communicating with database: expected to read 5 bytes, got 0 bytes at EOF` | exit 134, `panicked at src/lib.rs:372:25` |
| Every other check on both targets | as in the table above | — |
| Cancelling a statement stuck in a partition on linuxX64 | still not within 20 s | not within 20 s |
| vojak's own suite (`vojak-postgres` on `jvm` and `linuxX64`) with the driver swapped by an init script | 12 of 12 pass | 12 of 12 pass |

**Published 2026-09-30**: `io.github.smyrgeorge:sqlx4k-postgres:1.13.1.1-yio` with `jvm`, `linuxX64`
and `linuxArm64` variants, at `https://reposilite.kotlin.website/snapshots`, by the fork's
`publish-reposilite.yml` from tag `1.13.1.1-yio` (commit `19abb319`; run 36688036577 — the module's own
suite on `jvm` and `linuxX64` first, then every coordinate read back as 200). The probe then resolved
it from there — not from a local repository — and repeated the table above on both targets.

So the patch removes the abort and nothing else: Consequence 13 (native cancellation) is the
driver's coroutine bridge, not its error mapping, and stays true.

**Consequence 10.** D1 and D2 hold, on both targets, with numbers: the holding transaction costs
vacuum nothing (H1), and the server-side lease ends a silent — even a partitioned — leader on time
(H2, H4). Risk 1 is closed; the session-lock fallback is not needed.

**Consequence 11.** On `linuxX64` with `sqlx4k-postgres` 1.13.1, **any I/O error on a connection
ends the process**. That is not specific to vojak: a Postgres restart, a failover or a network
blip during a transaction aborts every native service on this driver whose code touches the
connection twice. For vojak it is unavoidable by construction — the leader's heartbeat is exactly
the call that meets a dead socket, and a statement stuck in a partition fails with `Io` the moment
the network returns, whether or not anyone is still waiting for it. On the JVM the same paths are
ordinary failed `Result`s.

**Consequence 12.** A dead transaction must still be ended, or its connection is lost to the pool
for good (`N4`); on `linuxX64` the only call that ends it safely is the *first* one. So the native
holder may issue at most one call after a failure, and it has to be `rollback()`. That rule is
necessary and not sufficient: it does nothing for the hung statement of Consequence 11.

*Correction found in B-04:* "at most one call after a failure, and it has to be `rollback()`" is
wrong, and the table above already says why. When the failure came from a **statement** on a dead
connection, that statement *was* the first call; the `rollback()` after it is the second, and it
aborts the process (`H2`, `H3`). What is safe on `linuxX64` is a `rollback()` that is itself the
first call since the session died — the release after a block, when nothing touched the
transaction meanwhile (`N2`). So: a statement that fails with a connection-gone SQLSTATE is followed
by **no** call on native — the transaction and its connection are abandoned, one connection lost
instead of the process — and by a `rollback()` on the JVM. That is what
`vojak-postgres/src/commonMain/kotlin/io/github/youndie/vojak/postgres/Sql.kt` does.

*Re-decided in B-13, kept:* with the patched driver the rollback after a connection-gone failure is
a failed `Result` on linuxX64 too, so the rule is no longer needed there — but vojak takes the
caller's `Driver` and cannot tell which build of it the caller linked. Skipping the rollback costs one
connection with either build; rolling back costs the process with the release. The rule goes when
the patched build (or an upstream release with the fix) is the documented minimum for native.

**Consequence 13.** A coroutine suspended in a native sqlx4k call is not cancellable (`H4`: no
`join()` within 20 s). D6's timer-based step-down is therefore required, not merely prudent; and
the cancelled call keeps its connection until the network returns — which, per Consequence 11, is
also when it aborts the process.

### 1.6 When the server ends the leader's session, the follower leads first — measured (2026-10-02)

Found as a flaky test in B-06 and then measured on purpose: two replicas campaign for one name
(`serverLease` 4 s, `localLease` 2 s, `renewEvery` 500 ms, `pollEvery` 200 ms), the leader's session
is ended with `pg_terminate_backend`, and both `leadership` flows are sampled every 2 ms. Three
rounds per target, `jvm` and `linuxX64`, Postgres 18.6 (a throwaway test, not kept):

| Round | Follower reports `Leader` after | Old leader reports `Follower` after | Both `Leader` at once |
|---|---|---|---|
| 1 | 171 ms / 112 ms | 467 ms / 263 ms | yes / yes |
| 2 | 110 ms / 189 ms | 309 ms / 480 ms | yes / yes |
| 3 | 103 ms / 187 ms | 252 ms / 479 ms | yes / yes |

**Consequence 14.** Ending the session frees the lock *at once*, and the follower's next poll takes
it; the old leader learns of it only at its next renewal. Every round overlapped, for up to
`renewEvery` plus a round trip. The same holds for anything that ends sessions from the server's
side — a restart, a failover, an administrator — while a partition (the server lease expires) and a
dead process (no believer left) do not overlap: there the old leader's local lease, shorter than the
server's, has already run out. The promise of D9 was written for the second kind only.

How it was found is worth keeping: the JVM and native suites run in parallel against one database,
and with the same candidate names in both, one suite's "end the leader's session" ended the other
suite's leader — `ofTwoReplicasExactlyOneLeads` then failed with "two leaders at once".

### 1.7 The fault suite — real binaries, real faults (B-09, 2026-10-02)

`samples/two-replicas/fault-suite.sh`: two native replicas (`linuxX64`, driver `1.13.1.1-yio`), one
Postgres 18, a socat proxy per replica; timing `serverLease` 4 s, `localLease` 2 s, `renewEvery`
500 ms, `pollEvery` 200 ms. `oracle.py` lays every `whileLeader` block on `CLOCK_MONOTONIC` and counts
overlaps. Times are from the fault, over the runs made on that day:

| Scenario | Runs | Overlaps | Old leader down after | Next leader after |
|---|---|---|---|---|
| `kill` — SIGKILL of the leader | 7 | 0 | at the kill | 2.17–2.22 s |
| `partition` — the leader's proxy paused | 12 | 0 | 1.94–1.96 s | 6.03–6.09 s, **once 4.64 s** |
| `restart` — Postgres restarted | 6 | 0 | 0.44–0.45 s | 3.2–3.4 s |
| `terminate` — only the leader's session ended | 3 | 0 | 0.44–0.45 s | 2.17–2.18 s |
| `freeze` — SIGSTOP past the server lease (control) | 7 | **1 each** | 8.0 s (on SIGCONT) | 4.6–6.1 s |

**Consequence 15.** Every number lands where D2, D6 and D12 put it: the partitioned leader steps down
at `localLease` after its last renewal, the server ends its session at `serverLease`, the successor
waits `localLease` more; after a kill or an ended session the successor polls once and waits. The
freeze control overlaps every time, which is the oracle shown able to see one — and the stated limit
of D9 shown real: a process paused past the margin wakes up still believing, and only the epoch
protects a store from it.

**Consequence 16.** The suite also catches the defect it was built for: with the D12 wait removed,
`terminate` overlaps in 3 runs of 3 (the successor led at ~175 ms, the old leader still held its
block until ~450 ms). `kill`, `partition` and `restart` would not have noticed — in each the old
leader is down before anybody can take the lock.

**The early takeover did not recur (B-16).** One `partition` run of twelve had the successor leading
at 4.64 s. The floor is ~5.5 s: the server lease minus one renewal interval, then `localLease`. The
run had no overlap (the old leader had stepped down at 1.96 s), and its logs were overwritten.
On 2026-10-02 the oracle was given that floor, so an early takeover now fails the suite with its logs
kept. **`RUNS=50 ONLY=partition`: no recurrence.** Takeovers ranged from 5.98 to 6.30 s, apart from
one run described below. Two of the explanations were checked, and both were refuted:

- **A wall-clock jump.** On 2026-09-25 this box's `CLOCK_REALTIME` jumped +2.93 s every ~32 s.
  Postgres schedules its timeouts by the wall clock, so a jump could end a lease early. Today there is
  no jump in 90 s, and `samples/two-replicas/lease-clock.py` timed the lease at 4.00–4.01 s on
  `CLOCK_MONOTONIC` in 40 of 40 trials. The box was rebooted on 2026-09-30, so the jumps were gone
  before the 4.64 s run.
- **The wait skipped.** The `released` mark is written only inside the holding transaction, just
  before it commits (D12). A partitioned leader cannot commit. The overlap test from research §1.6
  fails if the mark is missing or ignored.

The single number stays unexplained. Since it can no longer pass unnoticed, it is closed as such.

**A late takeover is a stand artefact.** One of the 50 runs took over at 10.86 s, above the 7.2 s
bound. At the same moment a CI job and another build were running on the box. One `docker exec` of
the observer took ~10 s, and the follower's pool failed with `PoolTimedOut`. The run had no overlap.
It shows that liveness depends on the host, while safety does not. The observer now stamps each
sample both when the query starts and when the answer comes back.

### 1.8 The stale write — the reference consumer under a partition (B-11, 2026-10-02)

`samples/digest-sender/stale-write.sh`: two replicas of the reference consumer (Ktor CIO, kore,
driver `1.13.1.1-yio`, pool of 4 with sqlx's default acquire timeout), the stand of §1.7 and its
timing. The leader claims a digest every 300 ms with one statement, `update digest_state set
last_sent = last_sent + 1, epoch = <e> where id = 1 and epoch <= <e>`. Its proxy is paused for 10 s
and resumed. The verdict is read from the server: Postgres logs every statement it ran
(`log_min_duration_statement = 0`), and a row trigger copies every changed row into an audit table.

| Run | Stale claim ran | Its distance after the successor's first claim | Changed a row |
|---|---|---|---|
| guarded, 2 runs | yes, 10.2 s after the leader's previous claim | 4.03–4.08 s | **no** |
| unguarded (the control), 2 runs | yes, 10.3 s after | 4.10–4.11 s | **yes** — and the old leader sent digest 19 |

**Consequence 17. On linuxX64 a statement does not stop when its coroutine is cancelled.** The old
leader stepped down on time, and its `whileLeader` block was cancelled; the claim it was parked in
did not return until the network healed, so the block overran the lease margin in all four runs
(`LeaderWorkDidNotStopException`, reported through `onWorkFailure`), then the statement ran on the
server ten seconds late. A guarded run's old leader even logged the refusal it got back. So
cancellation bounds what a block *starts*, never what it has already handed to the driver. A
partition is enough to produce the stale write that D5 exists for — no paused process is needed.

**Consequence 18. A short acquire timeout hides the case without closing it.** With
`acquireTimeout` at 2 s, the first version of the stand never produced a stale claim. The pool's
check of a connection before it hands it out was stuck in the paused proxy, and the acquire failed
(`PoolTimedOut`) before the statement was sent. A partition that begins after that check and before
the reply still delivers the statement late. The epoch in the `WHERE` is the defence. A timeout is
not.

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

*Correction found in B-06 (§1.6):* as built, the promise holds when the old leader is partitioned or
dead, and **not** when the server ends its session — restart, failover, `pg_terminate_backend` —
where the follower leads up to `renewEvery` before the old leader notices. Measured in every round.
The fix is D12; until it lands, the epoch (B-08) is the only protection against that window.
*Closed in B-15:* with D12 built, the §1.6 measurement is a test on both targets
(`whenTheServerEndsTheLeadersSessionNobodyElseLeadsBeforeItHasSteppedDown`), green, and red with the
wait removed. The promise above holds again — at the cost written in D12.

### D10. A process that lost the lease waits two polls before campaigning again *(found in B-06, amended in B-07)*

Without the pause the process that just lost the lease is the first to ask for it back, every time
— flapping, and the other replicas never get a turn after a failure that was this one's. Found on a
fake store, which releases instantly; Postgres usually hides it (the old session keeps the lock until
the server lease), but not after a clean failure such as a killed session.

*Amended in B-07:* two polls, not one. With one, a replica whose `whileLeader` block had just thrown
raced the others for the lease it gave up — its retry and their next poll fall in the same window —
and "the other replica takes over" held only most of the time. In two polls' time every other
replica polls at least once.

### D11. "One election per name per process" is enforced per `Vojak` instance *(deviation from the brief)*

The brief said per process. A process-wide registry would forbid the one legitimate case of two:
tests that stand up two replicas in one process — which is how every election test against Postgres
runs. A service builds one `Vojak`; the rule holds there, and `DuplicateElectionException` names it.

### D12. A new leader waits out the previous one unless that one let go cleanly *(built in B-15)*

Taking the lock is not the same as the previous leader having stopped (§1.6). After taking it, a new
leader declares itself only once the previous one *must* have noticed: its last successful renewal
plus `localLease` — which the new leader cannot see, so it waits a full `localLease` from taking the
lock. That is the worst case, and it is paid only when needed: a leader that gives the lease up
cleanly (`close()`, kore's drain) marks its epoch released in the same transaction that ends the
lease, and a successor that finds the mark skips the wait. A graceful handover stays within a poll;
a restart or a kill costs `localLease` (7.5 s by default) of nobody leading — the price of the
promise being true. Rejected: shortening the window by renewing faster (it narrows the overlap and
never closes it), and leaving it to the epoch alone (the epoch protects stores, not side effects
such as a bot polling its upstream).

*As built (B-15):* the mark is a `released` column of the epoch table. The acquiring upsert reads the
predecessor's mark through a CTE — every part of one statement sees the same snapshot, so it is the
row before this statement changed it — and the release writes the mark inside the holding
transaction just before its commit, so the mark and the free lock become visible together. Any
release vojak makes may set it, because it is always made after the state left `Leader`; only an
ended session leaves it unset. A partition therefore also costs the wait — the server lease ran out,
nobody released — which is the safe side and only `localLease` long.

---

## 3. Risks and open questions

**Risk 1 — closed by measurement (§1.5, `H1`).** Kept as written, with the answer after it.

**Risk 1. The holding transaction may hold back vacuum for the whole leadership.** Mechanism: an
open transaction with a snapshot pins the xmin horizon (§1.2). Hypothesis H1 is that a READ
COMMITTED transaction which only ran `pg_try_advisory_xact_lock`, `SET LOCAL` and `SELECT 1` has
no `backend_xid` and no `backend_xmin` between statements, and costs nothing. Mitigation: B-01
measures both columns of `pg_stat_activity` during a held leadership, with a positive control (a
transaction that did write must show a `backend_xid`). If H1 is false, D1 flips to a session lock
on a dedicated connection with `idle_session_timeout` as the lease (Postgres ≥ 14), and D5 is moot.
*Answer, 2026-09-29:* H1 holds on both targets, and the control shows the measurement can see the
opposite (§1.5). D1 stands; D5 still matters — the `H1-control-xid` row is what a write in the
holding transaction would cost.

**Risk 2. The native driver may kill the process when the server kills the session.** Mechanism:
Consequence 8. Mitigation: B-01 terminates the holder's session with `pg_terminate_backend` and
with the idle timeout, on `linuxX64`, and asserts on the *process* surviving, not only on the
`Result`; every statement vojak sends goes through one function, so a workaround has one home.
*Answer, 2026-09-29: realised, and wider than stated.* The first call after the server ends the
session is a clean failure; the second aborts the process, and so does any I/O error on any call
(§1.5, Consequence 11). This is no longer a risk to mitigate inside vojak but a property of the
driver version, and what to do about it is a decision for the owner:
[B-13](../backlog/B-13-native-driver-aborts-on-io-errors.md).

**Risk 3. A heartbeat call may not return while the network is partitioned (H4).** Mechanism:
cooperative cancellation does not interrupt a call that is blocked below the coroutine; a
`withTimeout` around a child waits for the child. Mitigation: D6 — the step-down is a timer, not the
call's return; B-01 measures how long the call takes to come back so that the cleanup cost is a
number. *Answer, 2026-09-29:* on the JVM cancellation completes in 3–4 ms; on `linuxX64` it does
not complete within 20 s (§1.5, Consequence 13). The timer is load-bearing on native.

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

**H5 — refuted in part (§1.5).** The JVM half agrees with the native one on H1, H2 and the server
lease; it disagrees on what happens after a connection dies (a failed `Result` against a process
abort) and on cancellation (milliseconds against not at all). A suite green on the JVM says nothing
about those two on `linuxX64`, which is why the fault suite ([B-09](../backlog/B-09-fault-suite.md))
runs native binaries.

**Quirk of the probe, not of the design.** On `linuxX64` (Kotlin 2.4.20), comparing a nullable
`Duration` in the probe threw `RuntimeException: Unexpected receiver type: kotlin.time.Duration`,
which the JVM did not; the probe measures in `Long` milliseconds instead
(`samples/probe/src/commonMain/kotlin/io/github/youndie/vojak/probe/Main.kt`). Not reduced to a
minimal reproduction; recorded so that the next person meeting it knows it is not the driver.

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

The order of work and the acceptance criteria live in the [backlog](../../backlog.md).
[B-01](../backlog/B-01-probe-the-holding-transaction.md) answered H1–H5 (§1.5): D1 and D2 stand.
What stands between the design and a native election is the driver's abort on I/O errors —
[B-13](../backlog/B-13-native-driver-aborts-on-io-errors.md), a decision for the owner. The lock
and the build ([B-02](../backlog/B-02-scaffold-and-gate.md) onwards) do not wait for it.
