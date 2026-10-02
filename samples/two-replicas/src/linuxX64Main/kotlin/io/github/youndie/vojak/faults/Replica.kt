package io.github.youndie.vojak.faults

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.postgres.postgreSQL
import io.github.youndie.vojak.ElectionTiming
import io.github.youndie.vojak.LockName
import io.github.youndie.vojak.Namespace
import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.runUntilSignal
import io.github.youndie.vojak.Vojak
import io.github.youndie.vojak.kore.election
import io.github.youndie.vojak.postgres.PostgresLockStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.fflush
import platform.posix.stdout
import platform.posix.timespec
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One replica of the fault suite: campaigns for one name, runs a `whileLeader` block that does
 * nothing but exist, and logs every transition and the block's start and end against
 * `CLOCK_MONOTONIC` — one clock for every process on the host, so the oracle can lay the replicas'
 * lines side by side.
 *
 *   replica now                                   print the clock and exit (for the harness)
 *   replica <url> <candidate> [serverLeaseMs localLeaseMs renewEveryMs pollEveryMs]
 *
 * SIGTERM goes through kore: its shutdown sequence closes the election in the consumer stage
 * (`vojak-kore`), which releases the lease cleanly before the process exits. SIGKILL and SIGSTOP are
 * the suite's.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "now") {
        println(nowNs())
        return
    }
    require(args.size >= 2) { "usage: replica <url> <candidate> [serverLeaseMs localLeaseMs renewEveryMs pollEveryMs]" }
    val (url, candidate) = args
    val ms = args.drop(2).map { it.toLong().milliseconds }
    val timing =
        if (ms.size == 4) {
            ElectionTiming(serverLease = ms[0], localLease = ms[1], renewEvery = ms[2], pollEvery = ms[3])
        } else {
            ElectionTiming(serverLease = 4.seconds, localLease = 2.seconds, renewEvery = 500.milliseconds, pollEvery = 200.milliseconds)
        }
    runBlocking {
        val driver =
            postgreSQL(
                url = url,
                username = "postgres",
                password = "vojak",
                options = ConnectionPool.Options.builder().maxConnections(4).acquireTimeout(2.seconds).build(),
            )
        val vojak = Vojak(PostgresLockStore(driver), Namespace.of("fault"))
        val election =
            vojak.elect(
                LockName.of("singleton"),
                candidate,
                this,
                timing,
                onStoreFailure = { log(candidate, "STORE_FAILURE ${it.message?.take(120)}") },
                onWorkFailure = { log(candidate, "WORK_FAILURE ${it.message?.take(120)}") },
            ) { epoch ->
                log(candidate, "BLOCK start epoch=$epoch")
                try {
                    awaitCancellation()
                } finally {
                    log(candidate, "BLOCK end epoch=$epoch")
                }
            }
        val states = launch { election.leadership.collect { log(candidate, "STATE $it") } }
        log(candidate, "STARTED")
        // No HTTP to stop accepting and nothing to drain: the only participant is the election.
        runUntilSignal(
            ShutdownDeadlines(preDrainWait = Duration.ZERO, drain = Duration.ZERO, gracePeriod = 10.seconds),
            onFinished = { run -> log(candidate, "SHUTDOWN signal=${run.signal}") },
        ) {
            log(candidate, "TERM")
            election(election)
        }
        states.cancel()
        log(candidate, "EXIT")
    }
    exitProcess(0)
}

@OptIn(ExperimentalForeignApi::class)
private fun nowNs(): Long =
    memScoped {
        val ts = alloc<timespec>()
        clock_gettime(CLOCK_MONOTONIC, ts.ptr)
        ts.tv_sec * 1_000_000_000L + ts.tv_nsec
    }

@OptIn(ExperimentalForeignApi::class)
private fun log(
    candidate: String,
    line: String,
) {
    println("${nowNs()} $candidate $line")
    fflush(stdout)
}
