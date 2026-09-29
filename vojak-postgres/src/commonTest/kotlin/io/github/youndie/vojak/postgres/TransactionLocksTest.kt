package io.github.youndie.vojak.postgres

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.youndie.vojak.Backoff
import io.github.youndie.vojak.LockName
import io.github.youndie.vojak.LockTimeoutException
import io.github.youndie.vojak.Namespace
import io.github.youndie.vojak.ReentrantLockException
import io.github.youndie.vojak.Vojak
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** feature-distributed-lock's "a lock inside the caller's transaction", against a real Postgres. */
class TransactionLocksTest {
    private val namespace = Namespace.of("t" + Random.nextInt(0, Int.MAX_VALUE).toString(36))
    private val ledger = LockName.of("ledger")
    private val drivers = mutableListOf<Driver>()

    private fun driver(): Driver = TestDatabase.driver().also { drivers += it }

    private fun replica(driver: Driver = driver()): Vojak =
        Vojak(PostgresLockStore(driver), namespace, Backoff(1.milliseconds, 5.milliseconds))

    // Bounded, from a scope of its own — see PostgresLockStoreTest.closeDrivers.
    @AfterTest
    fun closeDrivers() =
        runTest {
            val closing = CoroutineScope(Dispatchers.Default).async { drivers.forEach { it.close() } }
            withContext(Dispatchers.Default) { withTimeoutOrNull(10.seconds) { closing.await() } }
        }

    private fun realTime(block: suspend CoroutineScope.() -> Unit) =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) { block() }
        }

    private suspend fun rowsFor(db: Driver): String =
        db.text("select count(*)::text from vojak_test_ledger where ns = '$namespace'")

    private suspend fun prepare(db: Driver) {
        db.execute("create table if not exists vojak_test_ledger (ns text not null, v int not null)").getOrThrow()
    }

    @Test
    fun theCallersRollbackReleasesTheLockAndUndoesTheWrite() =
        realTime {
            val db = driver()
            prepare(db)
            val mine = replica(db)
            val other = replica()

            val tx = db.begin().getOrThrow()
            mine.lockForTransaction(tx, ledger)
            tx.execute("insert into vojak_test_ledger values ('$namespace', 1)").getOrThrow()
            assertNull(other.tryWithLock(ledger) { "ran" }, "held until the caller's transaction ends")

            tx.rollback().getOrThrow()

            assertEquals("ran", other.tryWithLock(ledger) { "ran" })
            assertEquals("0", rowsFor(db))
        }

    @Test
    fun theCallersCommitReleasesTheLockWithTheWriteVisible() =
        realTime {
            val db = driver()
            prepare(db)
            val mine = replica(db)
            val other = replica()

            val tx = db.begin().getOrThrow()
            mine.lockForTransaction(tx, ledger)
            tx.execute("insert into vojak_test_ledger values ('$namespace', 1)").getOrThrow()
            tx.commit().getOrThrow()

            assertEquals("1", other.tryWithLock(ledger) { rowsFor(db) })
        }

    @Test
    fun waitingForItIsBoundedAndLeavesTheTransactionUsable() =
        realTime {
            val db = driver()
            val mine = replica(db)
            val other = replica()
            val inside = CompletableDeferred<Unit>()
            val holder =
                launch {
                    other.withLock(ledger) {
                        inside.complete(Unit)
                        awaitCancellation()
                    }
                }
            inside.await()

            val tx = db.begin().getOrThrow()
            assertFailsWith<LockTimeoutException> { mine.lockForTransaction(tx, ledger, 300.milliseconds) }
            assertEquals("1", tx.text("select 1::text"))
            tx.rollback().getOrThrow()
            holder.cancelAndJoin()
        }

    @Test
    fun insideAWithLockBlockForTheSameNameItIsRefused() =
        realTime {
            val db = driver()
            val mine = replica(db)

            assertFailsWith<ReentrantLockException> {
                mine.withLock(ledger) {
                    val tx = db.begin().getOrThrow()
                    try {
                        mine.lockForTransaction(tx, ledger, 1.seconds)
                    } finally {
                        tx.rollback()
                    }
                }
            }
        }
}
