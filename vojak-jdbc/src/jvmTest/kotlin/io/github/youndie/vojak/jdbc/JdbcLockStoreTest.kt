package io.github.youndie.vojak.jdbc

import com.zaxxer.hikari.HikariDataSource
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
import java.sql.Connection
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

/**
 * vojak-postgres's lock scenarios, through JDBC and HikariCP — the shape a JVM service on Exposed
 * has. Each replica is its own pool, so its own sessions.
 */
class JdbcLockStoreTest {
    private val namespace = Namespace.of("j" + Random.nextInt(0, Int.MAX_VALUE).toString(36))
    private val migrate = LockName.of("migrate")
    private val pools = mutableListOf<HikariDataSource>()

    private fun pool(): HikariDataSource = TestDatabase.pool().also { pools += it }

    private fun replica(dataSource: HikariDataSource = pool()): Vojak =
        Vojak(JdbcLockStore(dataSource), namespace, Backoff(1.milliseconds, 5.milliseconds))

    @AfterTest
    fun closePools() = pools.forEach { it.close() }

    private fun realTime(block: suspend CoroutineScope.() -> Unit) =
        runTest(timeout = 120.seconds) { withContext(Dispatchers.Default) { block() } }

    private fun HikariDataSource.text(sql: String): String = connection.use { it.text(sql) }

    @Test
    fun contendedRunsNeverOverlap() =
        realTime {
            val db = pool()
            db.text("select 1::text")
            db.connection.use {
                it.run("create table if not exists vojak_test_counter (ns text primary key, n int not null)")
                it.run("insert into vojak_test_counter values ('$namespace', 0)")
            }
            var widest = 0
            coroutineScope {
                repeat(2) {
                    val replica = replica()
                    launch {
                        repeat(300) {
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
            assertEquals(1, widest, "two blocks ran at once")
        }

    @Test
    fun tryGivesUpWhileAnotherHoldsAndCancellingTheHolderReleasesIt() =
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
            assertFailsWith<LockTimeoutException> { b.withLock(migrate, 300.milliseconds) { error("ran") } }

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

    @Test
    fun aSessionEndedWhileTheBlockRanIsReportedAsLost() =
        realTime {
            val a = replica()
            val b = replica()
            val db = pool()
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

    /**
     * A pool lends a fixed number of connections, so a store that forgets to give one back stops a
     * service some hours later rather than in a test. Every path — taken, busy, thrown — is walked
     * many times over a pool of two, and the pool must end with nothing lent out.
     */
    @Test
    fun noPathKeepsAConnection() =
        realTime {
            val shared = TestDatabase.pool(size = 2).also { pools += it }
            val a = replica(shared)
            val other = replica()
            repeat(50) {
                a.withLock(migrate) { }
                runCatching { a.withLock(migrate) { error("thrown") } }
            }
            val inside = CompletableDeferred<Unit>()
            val holder =
                launch {
                    other.withLock(migrate) {
                        inside.complete(Unit)
                        awaitCancellation()
                    }
                }
            inside.await()
            repeat(50) { assertNull(a.tryWithLock(migrate) { "ran" }) }
            holder.cancelAndJoin()

            assertEquals(0, shared.hikariPoolMXBean.activeConnections, "connections still lent out")
        }

    @Test
    fun theCallersCommitReleasesTheLockWithTheWriteVisible() =
        realTime {
            val a = replica()
            val b = replica()
            val db = pool()
            db.connection.use { it.run("create table if not exists vojak_test_tx (ns text primary key)") }

            val connection: Connection = pool().connection
            connection.autoCommit = false
            a.lockForTransaction(connection, migrate)
            assertNull(b.tryWithLock(migrate) { "ran" }, "held by the caller's transaction")
            connection.run("insert into vojak_test_tx values ('$namespace')")
            connection.commit()
            connection.close()

            assertEquals("ran", b.tryWithLock(migrate) { "ran" })
            assertEquals("1", db.text("select count(*)::text from vojak_test_tx where ns = '$namespace'"))
        }

    @Test
    fun theCallersRollbackReleasesTheLock() =
        realTime {
            val a = replica()
            val b = replica()
            val connection: Connection = pool().connection
            connection.autoCommit = false
            a.lockForTransaction(connection, migrate)
            connection.rollback()
            connection.close()
            assertEquals("ran", b.tryWithLock(migrate) { "ran" })
        }

    /** In auto-commit the lock would end with the statement that takes it: refused, not silently empty. */
    @Test
    fun anAutoCommitConnectionIsRefused() =
        realTime {
            val a = replica()
            pool().connection.use { connection ->
                assertTrue(connection.autoCommit)
                assertFailsWith<IllegalStateException> { a.lockForTransaction(connection, migrate) }
            }
        }

    @Test
    fun aTablePrefixThatIsNotAPlainIdentifierIsRefused() {
        assertFailsWith<IllegalArgumentException> { JdbcLockStore(pool(), tablePrefix = "vojak; drop table x") }
    }
}
