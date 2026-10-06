package io.github.youndie.vojak.jdbc

import io.github.youndie.vojak.Lease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import kotlin.concurrent.Volatile

/**
 * One held election lease: the transaction [JdbcLockStore.tryLease] opened, renewed with `SELECT 1`
 * inside it. The rules are vojak-postgres's `PostgresLease`, for the same reason: **never two calls on
 * the connection at once** — a renewal can hang while the election, on its own timer, has already
 * stepped down and asks for the release, so the release is recorded and performed by whichever call
 * finishes last.
 */
internal class JdbcLease(
    private val connection: Connection,
    override val epoch: Long,
    override val previousReleasedCleanly: Boolean,
    private val markReleased: String,
    private val restoreNetworkTimeout: Int,
) : Lease {
    private val inUse = Mutex()

    @Volatile private var failure: Throwable? = null

    @Volatile private var releaseRequested = false

    @Volatile private var ended = false

    override suspend fun renew() {
        check(!releaseRequested) { "the lease has been released" }
        inUse.withLock {
            try {
                blocking { connection.text("select 1::text") }
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
            withContext(NonCancellable) {
                blocking {
                    // After a failed renewal there is nothing to mark: the store may have ended the
                    // lease already. Otherwise the mark commits with the end of the lock, and the
                    // successor skips the wait of research D12; if either fails, the mark is simply
                    // not there and the successor waits — the safe side.
                    if (failure == null) {
                        try {
                            connection.run(markReleased)
                            connection.commit()
                        } catch (_: SQLException) {
                            ignoringSqlFailure { connection.rollback() }
                        }
                    } else {
                        ignoringSqlFailure { connection.rollback() }
                    }
                    connection.giveBack(restoreNetworkTimeout)
                }
            }
        } finally {
            inUse.unlock()
        }
    }
}
