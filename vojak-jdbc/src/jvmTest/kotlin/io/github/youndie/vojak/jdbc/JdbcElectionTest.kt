package io.github.youndie.vojak.jdbc

import com.zaxxer.hikari.HikariDataSource
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.youndie.vojak.Election
import io.github.youndie.vojak.ElectionTiming
import io.github.youndie.vojak.Leadership
import io.github.youndie.vojak.LockName
import io.github.youndie.vojak.Namespace
import io.github.youndie.vojak.Vojak
import io.github.youndie.vojak.postgres.PostgresLockStore
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The election over JDBC, and the reason the store copies vojak-postgres's SQL rather than inventing
 * its own: a JDBC replica and an sqlx4k replica of one service are one election — they exclude each
 * other, share the epoch sequence and honour each other's clean-release mark.
 */
class JdbcElectionTest {
    private val namespace = Namespace.of("j" + Random.nextInt(0, Int.MAX_VALUE).toString(36))
    private val sender = LockName.of("digest-sender")
    private val timing =
        ElectionTiming(
            serverLease = 4.seconds,
            localLease = 2.seconds,
            renewEvery = 500.milliseconds,
            pollEvery = 200.milliseconds,
        )
    private val podA = "pod-a-${namespace.value}"
    private val podB = "pod-b-${namespace.value}"
    private val pools = mutableListOf<HikariDataSource>()
    private val drivers = mutableListOf<Driver>()
    private val elections = mutableListOf<Election>()

    private fun pool(): HikariDataSource = TestDatabase.pool().also { pools += it }

    private fun jdbcReplica(dataSource: HikariDataSource = pool()): Vojak = Vojak(JdbcLockStore(dataSource), namespace)

    private fun sqlx4kReplica(): Vojak =
        Vojak(PostgresLockStore(TestDatabase.sqlx4k().also { drivers += it }), namespace)

    private suspend fun Vojak.campaign(
        candidate: String,
        scope: CoroutineScope,
    ): Election = elect(sender, candidate, scope, timing).also { elections += it }

    @AfterTest
    fun closeEverything() =
        runTest {
            val closing =
                CoroutineScope(Dispatchers.Default).async {
                    elections.forEach { it.close() }
                    drivers.forEach { it.close() }
                    pools.forEach { it.close() }
                }
            withContext(Dispatchers.Default) { withTimeoutOrNull(15.seconds) { closing.await() } }
        }

    private fun realTime(block: suspend CoroutineScope.() -> Unit) =
        runTest(timeout = 120.seconds) { withContext(Dispatchers.Default) { block() } }

    private suspend fun awaitLeader(
        of: List<Election>,
        within: Duration,
    ): Election? {
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow() < within) {
            of.firstOrNull { it.leadership.value is Leadership.Leader }?.let { return it }
            delay(50.milliseconds)
        }
        return null
    }

    private fun epochOf(election: Election): Long = (election.leadership.value as Leadership.Leader).epoch

    @Test
    fun ofTwoReplicasExactlyOneLeads() =
        realTime {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val a = jdbcReplica().campaign(podA, scope)
            val b = jdbcReplica().campaign(podB, scope)
            assertNotNull(awaitLeader(listOf(a, b), 10.seconds), "nobody led within 10 s")
            val watch = TimeSource.Monotonic.markNow()
            while (watch.elapsedNow() < 3.seconds) {
                assertTrue(listOf(a, b).count { it.leadership.value is Leadership.Leader } <= 1, "two leaders at once")
                delay(20.milliseconds)
            }
            assertEquals(1, listOf(a, b).count { it.leadership.value is Leadership.Leader })
        }

    /** The close marks the epoch released, so the successor skips the wait of research D12. */
    @Test
    fun whenTheLeaderClosesTheOtherTakesOverWithinAPoll() =
        realTime {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val a = jdbcReplica().campaign(podA, scope)
            val b = jdbcReplica().campaign(podB, scope)
            val leader = assertNotNull(awaitLeader(listOf(a, b), 10.seconds))
            val other = if (leader === a) b else a
            val first = epochOf(leader)

            val closed = TimeSource.Monotonic.markNow()
            leader.close()
            assertNotNull(awaitLeader(listOf(other), 3.seconds), "no handover within 3 s")
            assertTrue(closed.elapsedNow() < timing.localLease, "handover took ${closed.elapsedNow()}")
            assertEquals(first + 1, epochOf(other))
        }

    /** Research §1.6 over JDBC: the server ends the leader's session, and no instant has two leaders. */
    @Test
    fun whenTheServerEndsTheLeadersSessionNobodyElseLeadsBeforeItHasSteppedDown() =
        realTime {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val a = jdbcReplica().campaign(podA, scope)
            val b = jdbcReplica().campaign(podB, scope)
            val db = pool()
            val leader = assertNotNull(awaitLeader(listOf(a, b), 10.seconds))
            val other = if (leader === a) b else a
            val candidate = if (leader === a) podA else podB
            val pid =
                db.connection.use {
                    it.text("select pid::text from pg_stat_activity where application_name = 'vojak $candidate'")
                }

            db.connection.use { it.text("select pg_terminate_backend($pid)::text") }
            val watch = TimeSource.Monotonic.markNow()
            while (watch.elapsedNow() < timing.localLease + 2.seconds) {
                val both = leader.leadership.value is Leadership.Leader && other.leadership.value is Leadership.Leader
                assertTrue(!both, "two leaders at once")
                delay(2.milliseconds)
            }
            assertEquals(Leadership.Follower, leader.leadership.value)
            assertNotNull(awaitLeader(listOf(a, b), 5.seconds), "nobody led again")
        }

    /**
     * One election, two kinds of replica: a JDBC leader hands over to an sqlx4k follower and back, the
     * epochs continue one sequence, and each clean close lets the other skip the D12 wait.
     */
    @Test
    fun aJdbcReplicaAndAnSqlx4kReplicaAreOneElection() =
        realTime {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val jdbc = jdbcReplica().campaign(podA, scope)
            val first = assertNotNull(awaitLeader(listOf(jdbc), 10.seconds))
            val sqlx4k = sqlx4kReplica().campaign(podB, scope)
            delay(1.seconds)
            assertTrue(sqlx4k.leadership.value !is Leadership.Leader, "the sqlx4k replica led beside the JDBC one")
            val firstEpoch = epochOf(first)

            val closed = TimeSource.Monotonic.markNow()
            jdbc.close()
            assertNotNull(awaitLeader(listOf(sqlx4k), 3.seconds), "the sqlx4k replica did not take over")
            assertTrue(
                closed.elapsedNow() < timing.localLease,
                "the JDBC release mark was not seen: ${closed.elapsedNow()}",
            )
            assertEquals(firstEpoch + 1, epochOf(sqlx4k))

            val again = jdbcReplica().campaign("$podA-again", scope)
            delay(1.seconds)
            assertTrue(again.leadership.value !is Leadership.Leader, "the JDBC replica led beside the sqlx4k one")
            sqlx4k.close()
            assertNotNull(awaitLeader(listOf(again), 3.seconds), "the JDBC replica did not take back over")
            assertEquals(firstEpoch + 2, epochOf(again))
        }

    /** The lease's connection goes back to the pool when the leadership ends. */
    @Test
    fun aClosedLeadershipGivesItsConnectionBack() =
        realTime {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val shared = pool()
            val a = jdbcReplica(shared).campaign(podA, scope)
            assertNotNull(awaitLeader(listOf(a), 10.seconds))
            assertEquals(1, shared.hikariPoolMXBean.activeConnections, "a leadership holds exactly one connection")
            a.close()
            assertEquals(0, shared.hikariPoolMXBean.activeConnections, "the lease's connection was kept")
        }
}
