package io.github.youndie.vojak.probe

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.postgres.postgreSQL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

expect val TARGET: String

expect fun <T> blocking(block: suspend CoroutineScope.() -> T): T

/** Runs a shell command and returns its exit code; used only to pause the proxy and restart Postgres. */
expect fun sh(command: String): Int

expect fun env(name: String): String?

// Ports and names are fixed by run.sh, which owns the containers.
private const val DIRECT = "postgresql://127.0.0.1:55432/postgres"
private const val PROXIED = "postgresql://127.0.0.1:55433/postgres"
private const val PROXY_CONTAINER = "vojak-probe-proxy"
private const val PG_CONTAINER = "vojak-probe-pg"

private var failures = 0

private fun fact(check: String, key: String, value: Any?) = println("PROBE $TARGET $check $key=$value")

private fun verdict(check: String, ok: Boolean, detail: String) {
    if (!ok) failures++
    println("CHECK $TARGET $check ${if (ok) "PASS" else "FAIL"} $detail")
}

private fun driver(url: String, max: Int): Driver =
    postgreSQL(
        url = url,
        username = "postgres",
        password = "vojak",
        options = ConnectionPool.Options.builder().maxConnections(max).acquireTimeout(10.seconds).build(),
    )

// Every value is cast to text in SQL, so both drivers hand back the same string and nothing here
// depends on how either of them decodes a type.
private suspend fun QueryExecutor.text(sql: String): String =
    fetchAll(sql).getOrThrow().first().get(0).asString()

private suspend fun QueryExecutor.row(sql: String): List<String> {
    val row = fetchAll(sql).getOrThrow().first()
    return (0 until row.size).map { row.get(it).asStringOrNull() ?: "null" }
}

private fun Result<*>.describe(): String = exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" } ?: "ok"

private class Holder(val tx: Transaction, val pid: String)

/** The shape research D1/D2 proposes: a transaction kept open around one transaction-level lock. */
private suspend fun hold(driver: Driver, key: Long, leaseSeconds: Int?): Holder {
    val tx = driver.begin().getOrThrow()
    val taken = tx.text("select pg_try_advisory_xact_lock($key)::text")
    check(taken == "true") { "lock $key was not taken: $taken" }
    if (leaseSeconds != null) {
        tx.execute("set local idle_in_transaction_session_timeout = '${leaseSeconds}s'").getOrThrow()
    }
    tx.text("select 1::text")
    return Holder(tx, tx.text("select pg_backend_pid()::text"))
}

private suspend fun lockFree(driver: Driver, key: Long): Boolean {
    val tx = driver.begin().getOrThrow()
    return try {
        tx.text("select pg_try_advisory_xact_lock($key)::text") == "true"
    } finally {
        tx.rollback()
    }
}

private suspend fun sessionAlive(observer: Driver, pid: String): Boolean =
    observer.text("select count(*)::text from pg_stat_activity where pid = $pid") == "1"

/** Makes 1000 dead tuples, vacuums, and returns how many dead tuples vacuum could not remove. */
private suspend fun churnAndVacuum(observer: Driver): String {
    observer.execute("insert into probe_t select generate_series(1, 1000)").getOrThrow()
    observer.execute("delete from probe_t").getOrThrow()
    observer.execute("vacuum probe_t").getOrThrow()
    return observer.text("select dead_tuple_count::text from pgstattuple('probe_t')")
}

/** H1: an idle holder carries no xid and no xmin, and vacuum is not held back by it. */
private suspend fun h1(observer: Driver, holders: Driver) {
    observer.execute("create extension if not exists pgstattuple").getOrThrow()
    observer.execute("create table if not exists probe_t (x int)").getOrThrow()

    val holder = hold(holders, 4201, 60)
    val (xid, xmin, state) =
        observer.row(
            "select backend_xid::text, backend_xmin::text, state from pg_stat_activity where pid = ${holder.pid}",
        )
    fact("H1", "holder_backend_xid", xid)
    fact("H1", "holder_backend_xmin", xmin)
    fact("H1", "holder_state", state)
    val dead = churnAndVacuum(observer)
    fact("H1", "dead_tuples_after_vacuum_with_holder_open", dead)
    verdict("H1", xid == "null" && xmin == "null" && dead == "0", "xid=$xid xmin=$xmin dead=$dead")
    holder.tx.rollback().getOrThrow()

    // Positive control 1: a transaction that wrote does carry an xid, and the query above sees it.
    val writer = holders.begin().getOrThrow()
    writer.execute("insert into probe_t values (1)").getOrThrow()
    val writerPid = writer.text("select pg_backend_pid()::text")
    val writerXid = observer.row("select backend_xid::text from pg_stat_activity where pid = $writerPid")[0]
    writer.rollback().getOrThrow()
    verdict("H1-control-xid", writerXid != "null", "a writing transaction shows xid=$writerXid")

    // Positive control 2: a snapshot-holding transaction shows an xmin AND keeps vacuum from
    // removing what was deleted after it started — so a zero above is not a vacuum that never ran.
    val snapshot = holders.begin().getOrThrow()
    snapshot.execute("set transaction isolation level repeatable read").getOrThrow()
    snapshot.text("select 1::text")
    val snapshotPid = snapshot.text("select pg_backend_pid()::text")
    val snapshotXmin = observer.row("select backend_xmin::text from pg_stat_activity where pid = $snapshotPid")[0]
    val deadHeld = churnAndVacuum(observer)
    snapshot.rollback().getOrThrow()
    verdict(
        "H1-control-xmin",
        snapshotXmin != "null" && deadHeld != "0",
        "a repeatable-read snapshot shows xmin=$snapshotXmin and leaves dead=$deadHeld",
    )
}

/** H2: SET LOCAL idle_in_transaction_session_timeout ends the holder's own session, and the lock. */
private suspend fun h2(observer: Driver, holders: Driver) {
    val leased = hold(holders, 4202, 2)
    delay(3500)
    val gone = !sessionAlive(observer, leased.pid)
    val free = lockFree(observer, 4202)
    val after = leased.tx.fetchAll("select 1::text")
    fact("H2", "holder_statement_after_timeout", after.describe())
    verdict("H2", gone && free && after.isFailure, "session gone=$gone, lock free=$free after 3.5 s with a 2 s lease")
    // Last, because on linuxX64 this is the call that ends the process (see the N checks).
    fact("H2", "holder_rollback_after_timeout", "calling")
    fact("H2", "holder_rollback_after_timeout", leased.tx.rollback().describe())

    // Positive control: the same holder without the setting survives the same wait, lock held.
    val unleased = hold(holders, 4203, null)
    delay(3500)
    val alive = sessionAlive(observer, unleased.pid)
    val held = !lockFree(observer, 4203)
    unleased.tx.rollback().getOrThrow()
    verdict("H2-control", alive && held, "without the setting: session alive=$alive, lock held=$held after 3.5 s")

    // SET LOCAL must not outlive its transaction on a pooled connection: one connection, reused.
    val single = driver(DIRECT, 1)
    val tx = single.begin().getOrThrow()
    tx.execute("set local idle_in_transaction_session_timeout = '2s'").getOrThrow()
    tx.commit().getOrThrow()
    val afterCommit = single.text("show idle_in_transaction_session_timeout")
    single.close()
    verdict("H2-local", afterCommit == "0", "the same pooled connection after commit reads $afterCommit")
}

/** H3: a session killed by the server is a failed Result on the next statement; the process lives. */
private suspend fun h3(observer: Driver) {
    val single = driver(DIRECT, 1)
    val holder = hold(single, 4204, null)
    fact("H3", "terminate", observer.text("select pg_terminate_backend(${holder.pid})::text"))
    delay(200.milliseconds)
    val next = holder.tx.fetchAll("select 1::text")
    fact("H3", "holder_statement_after_terminate", next.describe())
    verdict("H3", next.isFailure, "the process is still running and the statement failed")
    fact("H3", "holder_rollback_after_terminate", "calling")
    fact("H3", "holder_rollback_after_terminate", holder.tx.rollback().describe())

    // Informative, not a verdict: does a pool of one hand the dead connection out again?
    fact("H3", "pool_of_one_first_query_after", single.fetchAll("select 1::text").describe())
    fact("H3", "pool_of_one_second_query_after", single.fetchAll("select 1::text").describe())
    single.close()
}

/** H4: a statement sent into a partition, the server's lease under that partition, cancellation. */
private suspend fun h4(observer: Driver) {
    val proxied = driver(PROXIED, 1)
    val rtt = TimeSource.Monotonic.markNow()
    proxied.text("select 1::text")
    fact("H4", "control_roundtrip_through_proxy", rtt.elapsedNow())

    val holder = hold(proxied, 4205, 5)
    check(sh("docker pause $PROXY_CONTAINER >/dev/null") == 0) { "could not pause the proxy" }
    try {
        partition(observer, holder)
    } finally {
        // In a finally: a first version threw before this line and left the proxy frozen for every
        // run after it, which then read as a hang of the next target.
        sh("docker unpause $PROXY_CONTAINER >/dev/null 2>&1")
    }
    fact("H4", "holder_rollback_after_partition", "calling")
    fact("H4", "holder_rollback_after_partition", withTimeoutOrNull(10.seconds) { holder.tx.rollback() }?.describe())
    withTimeoutOrNull(10.seconds) { proxied.close() }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun partition(observer: Driver, holder: Holder) {
    val partitioned = TimeSource.Monotonic.markNow()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val call = scope.async { runCatching { holder.tx.text("select 1::text") } }
    delay(3.seconds)
    fact("H4", "statement_returned_within_3s", call.isCompleted)

    // The server's side of D2: with no packet reaching the client, does it end the session anyway?
    // Milliseconds in a Long, not a `Duration?`: comparing a nullable Duration here threw
    // "Unexpected receiver type: kotlin.time.Duration" on linuxX64 (Kotlin 2.4.20) and passed on
    // the JVM — a compiler matter, not the driver's, and not what this probe is about.
    var goneAtMs = -1L
    while (partitioned.elapsedNow() < 15.seconds) {
        if (!sessionAlive(observer, holder.pid)) {
            goneAtMs = partitioned.elapsedNow().inWholeMilliseconds
            break
        }
        delay(250.milliseconds)
    }
    val free = lockFree(observer, 4205)
    fact("H4", "server_ended_session_after_ms", goneAtMs)
    verdict(
        "H4-server-lease",
        goneAtMs in 4_000..9_000 && free,
        "partitioned holder with a 5 s lease: session ended after $goneAtMs ms, lock free=$free",
    )

    fact("H4", "statement_returned_before_cancel", call.isCompleted)
    val cancelled = TimeSource.Monotonic.markNow()
    call.cancel()
    val joined = withTimeoutOrNull(20.seconds) { call.join() } != null
    fact("H4", "cancel_to_join", if (joined) cancelled.elapsedNow() else "not within 20s")

    fact("H4", "call_result_after_cancel", if (call.isCompleted && !call.isCancelled) call.getCompleted().describe() else "cancelled")
    fact("H4", "total", partitioned.elapsedNow())
}

/**
 * The N checks isolate which call on a connection the server has closed ends the process. Each runs
 * in its own process (run.sh), because on linuxX64 the answer is "the process ends", and a check
 * that ends the process hides every check after it.
 */
private suspend fun killed(observer: Driver, key: Long): Pair<Driver, Holder> {
    val single = driver(DIRECT, 1)
    val holder = hold(single, key, null)
    observer.text("select pg_terminate_backend(${holder.pid})::text")
    delay(200.milliseconds)
    return single to holder
}

private suspend fun n1(observer: Driver) {
    val (_, holder) = killed(observer, 4301)
    fact("N1", "first_statement", "calling")
    fact("N1", "first_statement", holder.tx.fetchAll("select 1::text").describe())
    fact("N1", "second_statement", "calling")
    fact("N1", "second_statement", holder.tx.fetchAll("select 1::text").describe())
}

private suspend fun n2(observer: Driver) {
    val (_, holder) = killed(observer, 4302)
    fact("N2", "rollback_without_statement", "calling")
    fact("N2", "rollback_without_statement", holder.tx.rollback().describe())
}

private suspend fun n3(observer: Driver) {
    val (_, holder) = killed(observer, 4303)
    fact("N3", "commit_without_statement", "calling")
    fact("N3", "commit_without_statement", holder.tx.commit().describe())
}

/** Abandon the dead transaction without touching it: is its connection lost to the pool for good? */
private suspend fun n4(observer: Driver) {
    val (single, _) = killed(observer, 4304)
    fact("N4", "pool_of_one_query_with_dead_tx_abandoned", "calling")
    val mark = TimeSource.Monotonic.markNow()
    fact("N4", "pool_of_one_query_with_dead_tx_abandoned", single.fetchAll("select 1::text").describe())
    fact("N4", "took", mark.elapsedNow())
}

/** An idle pooled connection killed by the server: does the next plain query survive? */
private suspend fun n5(observer: Driver) {
    val single = driver(DIRECT, 1)
    val pid = single.text("select pg_backend_pid()::text")
    observer.text("select pg_terminate_backend($pid)::text")
    delay(200.milliseconds)
    fact("N5", "query_after_idle_connection_killed", "calling")
    fact("N5", "query_after_idle_connection_killed", single.fetchAll("select 1::text").describe())
    fact("N5", "second_query", single.fetchAll("select 1::text").describe())
}

/** Postgres restarts under a pool with an idle connection. */
private suspend fun n6() {
    val single = driver(DIRECT, 1)
    single.text("select 1::text")
    check(sh("docker restart $PG_CONTAINER >/dev/null") == 0) { "could not restart Postgres" }
    for (attempt in 1..30) {
        if (sh("docker exec $PG_CONTAINER pg_isready -U postgres -h 127.0.0.1 >/dev/null 2>&1") == 0) break
        delay(1.seconds)
    }
    fact("N6", "query_after_restart", "calling")
    fact("N6", "query_after_restart", single.fetchAll("select 1::text").describe())
    fact("N6", "second_query", single.fetchAll("select 1::text").describe())
}

fun main() {
    blocking {
        val started = TimeSource.Monotonic.markNow()
        val observer = driver(DIRECT, 4)
        val holders = driver(DIRECT, 4)
        fact("setup", "server_version", observer.text("show server_version"))

        val all: List<Pair<String, suspend () -> Unit>> =
            listOf(
                "H1" to { h1(observer, holders) },
                "H2" to { h2(observer, holders) },
                "H3" to { h3(observer) },
                "H4" to { h4(observer) },
                "N1" to { n1(observer) },
                "N2" to { n2(observer) },
                "N3" to { n3(observer) },
                "N4" to { n4(observer) },
                "N5" to { n5(observer) },
                "N6" to { n6() },
            )
        // PROBE_CHECKS=H1,N2 runs those; unset runs everything in one process.
        val wanted = env("PROBE_CHECKS")?.split(',')?.map { it.trim() }?.toSet()
        val checks = all.filter { wanted == null || it.first in wanted }
        for ((name, check) in checks) {
            runCatching { check() }.onFailure {
                failures++
                println("CHECK $TARGET $name ERROR ${it::class.simpleName}: ${it.message}")
            }
        }
        println("DONE $TARGET failures=$failures elapsed=${started.elapsedNow()}")
    }
    // The JVM driver leaves non-daemon threads behind; the verdict is already printed.
    exitProcess(if (failures == 0) 0 else 1)
}
