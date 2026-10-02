package io.github.youndie.vojak.digest

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.postgres.postgreSQL
import io.github.youndie.kore.ktor.EngineDrain
import io.github.youndie.kore.ktor.installShutdownRefusal
import io.github.youndie.kore.ktor.startForKore
import io.github.youndie.kore.lifecycle.DrainGate
import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.runUntilSignal
import io.github.youndie.vojak.ElectionTiming
import io.github.youndie.vojak.Leadership
import io.github.youndie.vojak.LockName
import io.github.youndie.vojak.Namespace
import io.github.youndie.vojak.Vojak
import io.github.youndie.vojak.kore.election
import io.github.youndie.vojak.postgres.PostgresLockStore
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.posix.fflush
import platform.posix.stdout
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * THE REFERENCE CONSUMER: a service run in two copies with one thing only one of them may do — send
 * the digest. Both copies serve HTTP; one sends.
 *
 *   digest-sender <url> <candidate> <port> [guarded|unguarded]
 *
 * `unguarded` drops the epoch from the store's `WHERE` and exists for the stand's control run only
 * (`stale-write.sh`).
 */
fun main(args: Array<String>) {
    require(args.size in 3..4) { "usage: digest-sender <url> <candidate> <port> [guarded|unguarded]" }
    val (url, candidate, port) = args
    val guarded = args.getOrElse(3) { "guarded" } == "guarded"
    val timing = ElectionTiming(serverLease = 4.seconds, localLease = 2.seconds, renewEvery = 500.milliseconds, pollEvery = 200.milliseconds)

    // FOUR CONNECTIONS, AND THE ELECTION IS ONE OF THEM FOR AS LONG AS THIS REPLICA LEADS (research
    // Risk 5): the lease is an open transaction, so its connection never goes back to the pool. The
    // other three: the epoch bump while leadership is taken (research D5, a separate autocommit), the
    // sender's claim, and `/status`. A pool sized for the work alone runs out the moment it leads.
    val driver =
        postgreSQL(
            url = url,
            username = "postgres",
            password = "vojak",
            options = ConnectionPool.Options.builder().maxConnections(4).build(),
        )
    val store = DigestStore(driver, guarded)
    val vojak = Vojak(PostgresLockStore(driver), Namespace.of("digest"))

    runBlocking {
        store.create()
        val election =
            vojak.elect(
                LockName.of("digest-sender"),
                candidate,
                this,
                timing,
                onStoreFailure = { log(candidate, "STORE_FAILURE ${it.message?.take(120)}") },
                onWorkFailure = { log(candidate, "WORK_FAILURE ${it.message?.take(120)}") },
            ) { epoch ->
                // Cancelled when leadership is lost. A claim already on the wire is not — that is the
                // store's guard's job, not this loop's.
                while (true) {
                    val next = store.claimNext(epoch)
                    if (next == null) {
                        log(candidate, "REFUSED epoch=$epoch")
                    } else {
                        log(candidate, "SEND digest=$next epoch=$epoch")
                    }
                    delay(300.milliseconds)
                }
            }

        val draining = DrainGate()
        val server =
            embeddedServer(CIO, port = port.toInt()) {
                installShutdownRefusal(draining)
                routing {
                    get("/status") {
                        val role = (election.leadership.value as? Leadership.Leader)?.let { "leader epoch=${it.epoch}" } ?: "follower"
                        call.respondText("$candidate $role state=${store.state()}\n")
                    }
                }
            }.startForKore()
        log(candidate, "STARTED guarded=$guarded port=$port")

        // HTTP first, the election after it: requests in flight finish while this replica still
        // leads, and the lease is released last — so the other copy takes over without the wait of
        // research D12.
        // gracePeriod is the pod's terminationGracePeriodSeconds (Kubernetes' default); kore refuses a sequence that
        // does not fit in it.
        runUntilSignal(ShutdownDeadlines(preDrainWait = 0.seconds, drain = 2.seconds, gracePeriod = 30.seconds)) {
            drain(EngineDrain(server, grace = 2.seconds, timeout = 3.seconds, gate = draining))
            election(election)
        }
        log(candidate, "EXIT")
    }
    exitProcess(0)
}

@OptIn(ExperimentalForeignApi::class)
private fun log(
    candidate: String,
    line: String,
) {
    println("$candidate $line")
    fflush(stdout)
}
