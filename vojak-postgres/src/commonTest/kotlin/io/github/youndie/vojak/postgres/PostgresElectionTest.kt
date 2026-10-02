package io.github.youndie.vojak.postgres

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.youndie.vojak.DuplicateElectionException
import io.github.youndie.vojak.Election
import io.github.youndie.vojak.ElectionTiming
import io.github.youndie.vojak.Leadership
import io.github.youndie.vojak.LockName
import io.github.youndie.vojak.Namespace
import io.github.youndie.vojak.Vojak
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * feature-leader-election against a real Postgres. Each replica is its own driver — its own pool, so
 * its own sessions — and its own [Vojak], which is what two processes look like to the database.
 * Kill, partition and restart of real processes are the fault suite's (B-09).
 */
class PostgresElectionTest {
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

    private fun replica(): Vojak = Vojak(PostgresLockStore(TestDatabase.driver().also { drivers += it }), namespace)

    private fun observer(): Driver = TestDatabase.driver().also { drivers += it }

    private suspend fun Vojak.campaign(
        candidate: String,
        scope: CoroutineScope,
    ): Election = elect(sender, candidate, scope, timing).also { elections += it }

    // Bounded, from a scope of their own — see PostgresLockStoreTest.closeDrivers.
    @AfterTest
    fun closeEverything() =
        runTest {
            val closing =
                CoroutineScope(Dispatchers.Default).async {
                    elections.forEach { it.close() }
                    drivers.forEach { it.close() }
                }
            withContext(Dispatchers.Default) { withTimeoutOrNull(15.seconds) { closing.await() } }
        }

    private fun realTime(block: suspend CoroutineScope.() -> Unit) =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) { block() }
        }

    private suspend fun awaitLeader(
        of: List<Election>,
        within: Duration,
    ): Election? {
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow() < within) {
            of.firstOrNull { it.leadership.value == Leadership.Leader }?.let { return it }
            delay(50.milliseconds)
        }
        return null
    }

    @Test
    fun ofTwoReplicasExactlyOneLeads() =
        realTime {
            val scope = CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob())
            val a = replica().campaign("pod-a", scope)
            val b = replica().campaign("pod-b", scope)

            assertNotNull(awaitLeader(listOf(a, b), 10.seconds), "nobody led within 10 s")
            // Watched for several renewals: never both, and after it, still exactly one.
            val watch = TimeSource.Monotonic.markNow()
            while (watch.elapsedNow() < 3.seconds) {
                val leaders = listOf(a, b).count { it.leadership.value == Leadership.Leader }
                assertTrue(leaders <= 1, "two leaders at once")
                delay(20.milliseconds)
            }
            assertEquals(1, listOf(a, b).count { it.leadership.value == Leadership.Leader })
        }

    @Test
    fun whenTheLeaderClosesTheOtherTakesOverWithinAPoll() =
        realTime {
            val scope = CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob())
            val a = replica().campaign("pod-a", scope)
            val b = replica().campaign("pod-b", scope)
            val leader = assertNotNull(awaitLeader(listOf(a, b), 10.seconds))
            val other = if (leader === a) b else a

            val closed = TimeSource.Monotonic.markNow()
            leader.close()

            assertNotNull(awaitLeader(listOf(other), 3.seconds), "no handover within 3 s")
            assertTrue(closed.elapsedNow() < 3.seconds)
        }

    /**
     * The server ends the leader's session — what a Postgres restart, a failover or an administrator
     * does to it. The leader must stop claiming at its next renewal, and somebody must lead again.
     * The session is found by `application_name`, which is also what an operator would look for.
     */
    @Test
    fun whenTheServerEndsTheLeadersSessionItStepsDownAndLeadershipComesBack() =
        realTime {
            val scope = CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob())
            val a = replica().campaign("pod-a", scope)
            val b = replica().campaign("pod-b", scope)
            val db = observer()
            val leader = assertNotNull(awaitLeader(listOf(a, b), 10.seconds))
            val candidate = if (leader === a) "pod-a" else "pod-b"

            val pid = db.text("select pid::text from pg_stat_activity where application_name = 'vojak $candidate'")
            val killed = TimeSource.Monotonic.markNow()
            db.text("select pg_terminate_backend($pid)::text")

            while (leader.leadership.value == Leadership.Leader &&
                killed.elapsedNow() < 5.seconds
            ) {
                delay(20.milliseconds)
            }
            assertEquals(Leadership.Follower, leader.leadership.value)
            assertTrue(killed.elapsedNow() < timing.renewEvery + 1.seconds, "stepped down after ${killed.elapsedNow()}")
            assertNotNull(awaitLeader(listOf(a, b), 10.seconds), "nobody led again")
        }

    @Test
    fun aSecondElectionForTheSameNameInOneProcessIsRefused() =
        realTime {
            val scope = CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob())
            val vojak = replica()
            vojak.campaign("pod-a", scope)

            assertFailsWith<DuplicateElectionException> { vojak.elect(sender, "pod-a", scope, timing) }
        }
}
