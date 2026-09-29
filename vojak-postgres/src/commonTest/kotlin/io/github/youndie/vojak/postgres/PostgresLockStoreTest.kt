package io.github.youndie.vojak.postgres

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.youndie.vojak.Backoff
import io.github.youndie.vojak.LockLostException
import io.github.youndie.vojak.LockName
import io.github.youndie.vojak.LockTimeoutException
import io.github.youndie.vojak.Namespace
import io.github.youndie.vojak.Vojak
import io.github.youndie.vojak.advisoryKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Against a real Postgres (`make postgres`), on every target the suite runs on. Each "process" is a
 * [Vojak] over its own driver — its own pool, so its own sessions — which is what Postgres sees of
 * two replicas. The scenarios are feature-distributed-lock's.
 */
class PostgresLockStoreTest {
    // A fresh namespace per test instance, so no key survives from one test into the next.
    private val namespace = Namespace.of("t" + Random.nextInt(0, Int.MAX_VALUE).toString(36))
    private val migrate = LockName.of("migrate")
    private val drivers = mutableListOf<Driver>()

    private fun replica(): Vojak {
        val driver = TestDatabase.driver()
        drivers += driver
        return Vojak(PostgresLockStore(driver), namespace, Backoff(1.milliseconds, 5.milliseconds))
    }

    private fun observer(): Driver = TestDatabase.driver().also { drivers += it }

    @AfterTest
    fun closeDrivers() =
        runTest {
            drivers.forEach { it.close() }
        }

    // Real time: waiting is what several of these measure, and the database does not run on the
    // test scheduler's clock.
    private fun realTime(block: suspend CoroutineScope.() -> Unit) =
        runTest(timeout = 120.seconds) { withContext(Dispatchers.Default) { block() } }

    @Test
    fun theSecondCallerWaitsForTheFirst() =
        realTime {
            val a = replica()
            val b = replica()
            val inside = CompletableDeferred<Unit>()
            val leave = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()

            val first =
                launch {
                    a.withLock(migrate) {
                        inside.complete(Unit)
                        leave.await()
                        events += "a ends"
                    }
                }
            inside.await()
            val second = launch { b.withLock(migrate, 10.seconds) { events += "b starts" } }
            kotlinx.coroutines.delay(300.milliseconds)
            assertTrue(second.isActive, "b must still be waiting while a holds the lock")
            leave.complete(Unit)

            first.join()
            second.join()
            assertEquals(listOf("a ends", "b starts"), events)
        }

    @Test
    fun contendedRunsNeverOverlap() =
        realTime {
            val db = observer()
            db
                .execute(
                    "create table if not exists vojak_test_counter (ns text primary key, n int not null)",
                ).getOrThrow()
            db.execute("insert into vojak_test_counter values ('$namespace', 0)").getOrThrow()
            var widest = 0
            val runsPerReplica = 500

            coroutineScope {
                repeat(2) {
                    val replica = replica()
                    launch {
                        repeat(runsPerReplica) {
                            replica.withLock(migrate, 30.seconds) {
                                val inside =
                                    db
                                        .text(
                                            "update vojak_test_counter set n = n + 1 where ns = '$namespace' returning n::text",
                                        ).toInt()
                                widest = maxOf(widest, inside)
                                db.text(
                                    "update vojak_test_counter set n = n - 1 where ns = '$namespace' returning n::text",
                                )
                            }
                        }
                    }
                }
            }

            // The counter is in the database, so this counts sessions inside the section — which is
            // what a lock between processes has to keep at one.
            assertEquals(1, widest, "two blocks ran at once")
        }

    @Test
    fun tryGivesUpAtOnceWhileAnotherHolds() =
        realTime {
            val a = replica()
            val b = replica()
            val inside = CompletableDeferred<Unit>()
            val holder =
                launch {
                    a.withLock(migrate) {
                        inside.complete(Unit)
                        awaitCancellation()
                    }
                }
            inside.await()

            val started = TimeSource.Monotonic.markNow()
            assertNull(b.tryWithLock(migrate) { "ran" })
            assertTrue(started.elapsedNow() < 2.seconds, "one round trip, not a wait: ${started.elapsedNow()}")
            holder.cancelAndJoin()
        }

    @Test
    fun waitingIsBounded() =
        realTime {
            val a = replica()
            val b = replica()
            val inside = CompletableDeferred<Unit>()
            val holder =
                launch {
                    a.withLock(migrate) {
                        inside.complete(Unit)
                        awaitCancellation()
                    }
                }
            inside.await()

            val started = TimeSource.Monotonic.markNow()
            assertFailsWith<LockTimeoutException> { b.withLock(migrate, 500.milliseconds) { error("ran") } }
            val waited = started.elapsedNow()
            assertTrue(waited >= 500.milliseconds && waited < 5.seconds, "waited $waited")
            holder.cancelAndJoin()
        }

    @Test
    fun cancellingTheHolderReleasesTheLock() =
        realTime {
            val a = replica()
            val b = replica()
            val inside = CompletableDeferred<Unit>()
            val holder =
                launch {
                    a.withLock(migrate) {
                        inside.complete(Unit)
                        awaitCancellation()
                    }
                }
            inside.await()
            assertNull(b.tryWithLock(migrate) { "ran" })

            holder.cancelAndJoin()

            assertEquals("ran", b.tryWithLock(migrate) { "ran" })
        }

    @Test
    fun anExceptionInTheBlockReleasesTheLock() =
        realTime {
            val a = replica()
            val b = replica()
            assertFailsWith<IllegalStateException> { a.withLock(migrate) { error("the block failed") } }

            assertEquals("ran", b.tryWithLock(migrate) { "ran" })
        }

    /**
     * The one release that can fail: the server ended the lock's session while the block ran. The
     * release is then the first call on a dead connection, which fails cleanly even on linuxX64
     * (research §1.5, `N2`) — so this also proves the process survives it.
     */
    @Test
    fun aSessionEndedWhileTheBlockRanIsReportedAsLost() =
        realTime {
            val a = replica()
            val b = replica()
            val db = observer()
            val key = migrate.advisoryKey(namespace)

            assertFailsWith<LockLostException> {
                a.withLock(migrate) {
                    val pid =
                        db.text(
                            "select pid::text from pg_locks where locktype = 'advisory' and objsubid = 1" +
                                " and classid = ${key ushr 32}::oid and objid = ${key and 0xffffffffL}::oid",
                        )
                    db.text("select pg_terminate_backend($pid)::text")
                }
            }

            assertNotNull(b.tryWithLock(migrate) { "ran" })
        }
}
