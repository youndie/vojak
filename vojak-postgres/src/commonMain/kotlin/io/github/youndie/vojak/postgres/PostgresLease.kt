package io.github.youndie.vojak.postgres

import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.youndie.vojak.Lease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * One held election lease: the transaction [PostgresLockStore.tryLease] opened, renewed with
 * `SELECT 1` inside it.
 *
 * **Never two calls on the transaction at once.** A renewal can hang for as long as a partition lasts
 * (research §1.5) while the election, on its timer, has already stepped down and asks for the release.
 * Issuing the rollback next to the hung statement would put two calls on one connection; instead the
 * release is recorded and performed by whichever call finishes last — the release itself if nothing
 * is in flight, or the renewal when it finally returns. Meanwhile Postgres ends the session on its own
 * after the server lease, so the lock does not wait for either.
 */
internal class PostgresLease(
    private val tx: Transaction,
    override val epoch: Long,
    override val previousReleasedCleanly: Boolean,
    private val markReleased: String,
) : Lease {
    private val inUse = Mutex()

    @Volatile private var failure: Throwable? = null

    @Volatile private var releaseRequested = false

    @Volatile private var ended = false

    override suspend fun renew() {
        check(!releaseRequested) { "the lease has been released" }
        inUse.withLock {
            try {
                tx.text("select 1::text")
            } catch (thrown: Throwable) {
                if (thrown !is CancellationException) failure = thrown
                throw thrown
            }
        }
        if (releaseRequested) endIfIdle()
    }

    override suspend fun release() {
        releaseRequested = true
        endIfIdle()
    }

    private suspend fun endIfIdle() {
        if (!inUse.tryLock()) return
        try {
            if (ended) return
            ended = true
            // After a failed renewal the connection may be gone, and on linuxX64 a rollback after a
            // failed statement on a dead connection aborts the process with the released driver
            // (research Consequence 12) — `endAfterFailure` knows when not to call it.
            val failed = failure
            if (failed != null) {
                tx.endAfterFailure(failed)
                return
            }
            // A release vojak makes is made after the state left Leader, so it may say so: the mark
            // commits with the end of the lock, and the successor skips the wait of research D12.
            // If either fails, the store has ended the lease already and the mark simply is not there
            // — the successor waits, which is the safe side.
            try {
                tx.run(markReleased)
                tx.commit().getOrThrow()
            } catch (thrown: Throwable) {
                if (thrown is CancellationException) throw thrown
                tx.endAfterFailure(thrown)
            }
        } finally {
            inUse.unlock()
        }
    }
}
