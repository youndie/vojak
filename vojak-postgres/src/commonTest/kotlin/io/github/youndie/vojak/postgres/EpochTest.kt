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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** feature-fencing-token against a real Postgres. */
class EpochTest {
    private val namespace = Namespace.of("t" + Random.nextInt(0, Int.MAX_VALUE).toString(36))
    private val sender = LockName.of("digest-sender")
    private val timing =
        ElectionTiming(
            serverLease = 1600.milliseconds,
            localLease = 800.milliseconds,
            renewEvery = 200.milliseconds,
            pollEvery = 50.milliseconds,
        )
    private val drivers = mutableListOf<Driver>()
    private val elections = mutableListOf<Election>()

    private fun driver(): Driver = TestDatabase.driver().also { drivers += it }

    private fun replica(): Vojak = Vojak(PostgresLockStore(driver()), namespace)

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
        runTest(timeout = 180.seconds) {
            withContext(Dispatchers.Default) { block() }
        }

    @Test
    fun overAHundredHandoversTheEpochsStrictlyIncreaseWithNoRepeats() =
        realTime {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val replicas = listOf(replica(), replica())
            val current =
                replicas
                    .mapIndexed {
                        i,
                        r,
                        ->
                        r.elect(sender, "pod-$i-$namespace", scope, timing)
                    }.toMutableList()
            elections += current
            val seen = mutableListOf<Long>()

            repeat(100) {
                val deadline = TimeSource.Monotonic.markNow()
                var index = -1
                while (index < 0 && deadline.elapsedNow() < 10.seconds) {
                    index = current.indexOfFirst { it.leadership.value is Leadership.Leader }
                    if (index < 0) delay(5.milliseconds)
                }
                assertTrue(index >= 0, "nobody led after ${seen.size} handovers")
                seen += (current[index].leadership.value as Leadership.Leader).epoch
                // Force the handover: the leader leaves, and comes back as a follower.
                current[index].close()
                current[index] =
                    replicas[index].elect(sender, "pod-$index-$namespace", scope, timing).also { elections += it }
            }

            assertEquals(seen.distinct(), seen, "an epoch repeated")
            assertEquals(seen.sorted(), seen, "an epoch went backwards")
            assertEquals(100, seen.size)
        }

    /**
     * Research D5: the bump is its own statement, so the holding transaction never gets an xid. The
     * control is a transaction that does take one, read through the same query, so a `null` here is
     * not a column that is always null.
     */
    @Test
    fun theHoldingSessionNeverGetsATransactionId() =
        realTime {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val candidate = "pod-$namespace"
            val election = replica().elect(sender, candidate, scope, timing).also { elections += it }
            val db = driver()
            val deadline = TimeSource.Monotonic.markNow()
            while (election.leadership.value !is Leadership.Leader &&
                deadline.elapsedNow() < 10.seconds
            ) {
                delay(20.milliseconds)
            }

            repeat(10) {
                val xid =
                    db.text(
                        "select coalesce(backend_xid::text, 'null') from pg_stat_activity where application_name = 'vojak $candidate'",
                    )
                assertEquals("null", xid, "the holding session got an xid")
                delay(100.milliseconds)
            }

            val control = db.begin().getOrThrow()
            try {
                control.text("select txid_current()::text")
                val own =
                    control.text(
                        "select coalesce(backend_xid::text, 'null') from pg_stat_activity where pid = pg_backend_pid()",
                    )
                assertNotEquals("null", own, "the control's xid did not show — the query cannot see an xid at all")
            } finally {
                control.rollback()
            }
        }

    @Test
    fun aTablePrefixThatIsNotAPlainIdentifierIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            PostgresLockStore(TestDatabase.driver().also { drivers += it }, "bad-prefix; drop")
        }
    }
}
