package io.github.youndie.vojak.postgres

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.youndie.vojak.Election
import io.github.youndie.vojak.ElectionTiming
import io.github.youndie.vojak.Leadership
import io.github.youndie.vojak.LockName
import io.github.youndie.vojak.Namespace
import io.github.youndie.vojak.Vojak
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** feature-run-while-leader against a real Postgres: two replicas, one block between them. */
class PostgresWhileLeaderTest {
    private val namespace = Namespace.of("t" + Random.nextInt(0, Int.MAX_VALUE).toString(36))
    private val sender = LockName.of("digest-sender")
    private val timing =
        ElectionTiming(
            serverLease = 4.seconds,
            localLease = 2.seconds,
            renewEvery = 500.milliseconds,
            pollEvery = 200.milliseconds,
        )
    private val drivers = mutableListOf<Driver>()
    private val elections = mutableListOf<Election>()
    private val started = TimeSource.Monotonic.markNow()
    private val logLock = Mutex()
    private val log = mutableListOf<String>()

    private suspend fun note(line: String) =
        logLock.withLock {
            log +=
                "${started.elapsedNow().inWholeMilliseconds} ms $line"
        }

    private fun replica(): Vojak = Vojak(PostgresLockStore(TestDatabase.driver().also { drivers += it }), namespace)

    // Bounded, from a scope of their own — see PostgresLockStoreTest.closeDrivers.
    @AfterTest
    fun closeEverything() =
        runTest {
            val closing =
                CoroutineScope(Dispatchers.Default).async {
                    elections.forEach { it.close() }
                    drivers.forEach { it.close() }
                }
            withContext(Dispatchers.Default) { withTimeoutOrNull(20.seconds) { closing.await() } }
        }

    private fun realTime(block: suspend CoroutineScope.() -> Unit) =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) { block() }
        }

    @Test
    fun aThrowingBlockHandsTheLeadershipToTheOtherReplica() =
        realTime {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val failures = mutableListOf<String>()
            var thrown = false
            val throwOnce = Mutex()

            suspend fun Vojak.campaign(who: String) =
                elect(sender, "$who-$namespace", scope, timing, onWorkFailure = { failures += "$who: ${it.message}" }) {
                    note("$who block starts")
                    val first = throwOnce.withLock { (!thrown).also { thrown = true } }
                    if (first) error("$who failed on its first run")
                    awaitCancellation()
                }.also { elections += it }
            val a = replica().campaign("a")
            val b = replica().campaign("b")

            val deadline = TimeSource.Monotonic.markNow()
            while (failures.isEmpty() && deadline.elapsedNow() < 10.seconds) delay(20.milliseconds)
            val thrower = if (failures.single().startsWith("a")) a else b
            val other = if (thrower === a) b else a
            while (other.leadership.value !is Leadership.Leader &&
                deadline.elapsedNow() < 15.seconds
            ) {
                delay(20.milliseconds)
            }

            assertIs<Leadership.Leader>(
                other.leadership.value,
                "the other replica did not take over:\n${log.joinToString("\n")}",
            )
            assertEquals(
                Leadership.Follower,
                thrower.leadership.value,
                "the thrower is campaigning again, as a follower",
            )
        }

    /**
     * A graceful handover: the old leader's block has ended — its `finally` included — before the new
     * leader's block starts. (A session the server ends is the overlap of research §1.6, B-15.)
     */
    @Test
    fun onAGracefulHandoverTheBlocksRunOneAfterTheOther() =
        realTime {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val spans = mutableMapOf<String, Pair<Long, Long?>>()
            val spansLock = Mutex()

            suspend fun Vojak.campaign(who: String) =
                elect(sender, "$who-$namespace", scope, timing) {
                    spansLock.withLock { spans[who] = started.elapsedNow().inWholeMilliseconds to null }
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(kotlinx.coroutines.NonCancellable) {
                            delay(150.milliseconds)
                            spansLock.withLock {
                                spans[who] =
                                    spans.getValue(who).first to started.elapsedNow().inWholeMilliseconds
                            }
                        }
                    }
                }.also { elections += it }
            val a = replica().campaign("a")
            val b = replica().campaign("b")
            val deadline = TimeSource.Monotonic.markNow()
            while (spans.isEmpty() && deadline.elapsedNow() < 10.seconds) delay(20.milliseconds)
            val first = spansLock.withLock { spans.keys.single() }
            val (leader, follower) = if (first == "a") a to b else b to a

            leader.close()
            while (follower.leadership.value !is Leadership.Leader &&
                deadline.elapsedNow() < 15.seconds
            ) {
                delay(20.milliseconds)
            }
            while (spansLock.withLock { spans.size } < 2 && deadline.elapsedNow() < 15.seconds) delay(20.milliseconds)

            val (oldStart, oldEnd) = spansLock.withLock { spans.getValue(first) }
            val (newStart, _) = spansLock.withLock { spans.getValue(if (first == "a") "b" else "a") }
            assertTrue(oldEnd != null && oldEnd <= newStart, "old block $oldStart..$oldEnd, new block from $newStart")
        }
}
