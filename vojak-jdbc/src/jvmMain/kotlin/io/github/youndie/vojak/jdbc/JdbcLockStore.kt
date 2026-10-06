package io.github.youndie.vojak.jdbc

import io.github.youndie.vojak.Attempt
import io.github.youndie.vojak.Lease
import io.github.youndie.vojak.LockLostException
import io.github.youndie.vojak.LockStore
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Duration

/**
 * Advisory locks on the caller's Postgres through JDBC — vojak-postgres's `PostgresLockStore` for a
 * service whose database access is already a [DataSource] (research D13). The same statements, the
 * same epoch table, the same keys: a JDBC replica and an sqlx4k replica of one service exclude each
 * other and share one sequence of epochs.
 *
 * Each attempt is its own transaction holding `pg_try_advisory_xact_lock` — never a session lock, so
 * ending the transaction is the only release path and nothing stays behind in the pool (research D1).
 *
 * **Every held lock borrows one connection of [dataSource] for the length of the block, and a held
 * leadership one for as long as it lasts**, in addition to whatever the block itself uses. Size the
 * pool for it: a leadership that cannot get its connection back after the block took the last one is
 * a deadlock the pool's own timeout ends.
 *
 * @param dataSource the service's own, pooled or not; vojak brings no driver.
 * @param tablePrefix the one table vojak owns is `<tablePrefix>_epoch`, created on first election.
 */
public class JdbcLockStore(
    private val dataSource: DataSource,
    tablePrefix: String = "vojak",
) : LockStore {
    init {
        require(
            IDENTIFIER.matches(tablePrefix),
        ) { "a table prefix must match [a-z_][a-z0-9_]{0,40}, got '$tablePrefix'" }
    }

    private val epochs = JdbcEpochs(dataSource, "${tablePrefix}_epoch")

    override suspend fun <T> tryHolding(
        key: Long,
        block: suspend () -> T,
    ): Attempt<T> {
        val connection = dataSource.openTransaction()
        if (!connection.tryLock(key)) {
            connection.release()?.let { throw it }
            return Attempt.Busy
        }

        val value =
            try {
                block()
            } catch (failure: Throwable) {
                connection.release()?.let { failure.addSuppressed(it) }
                throw failure
            }
        // The rollback is the first call on this transaction since the lock was taken, so a failure
        // here is the only sign that the session — and the lock — may have been gone while the block ran.
        connection.release()?.let {
            throw LockLostException(
                "advisory lock $key could not be released; its session may have ended while the block ran",
                it,
            )
        }
        return Attempt.Held(value)
    }

    /**
     * The lease is a transaction kept open around `pg_try_advisory_xact_lock` (research D1), with
     * `idle_in_transaction_session_timeout` set on it to [lease] so that Postgres itself ends a holder
     * that stops renewing (research D2). The holder shows in `pg_stat_activity.application_name` as
     * `vojak <holder>`.
     *
     * One thing this store adds over vojak-postgres: **the connection's network timeout is twice
     * [lease]**. A renewal sent into a partition otherwise blocks a pool thread and a pool connection
     * until the kernel gives up on the socket — a quarter of an hour on a Linux default — while the
     * server ended the session after [lease]. A pool lends a fixed number of connections; vojak-postgres
     * has no such knob to turn.
     */
    override suspend fun tryLease(
        key: Long,
        lease: Duration,
        holder: String,
    ): Lease? {
        val connection = dataSource.openTransaction()
        if (!connection.tryLock(key)) {
            connection.release()?.let { throw it }
            return null
        }
        val (restoreTimeout, next) =
            try {
                val previousTimeout =
                    blocking {
                        connection.setLocal("idle_in_transaction_session_timeout", "${lease.inWholeMilliseconds}ms")
                        connection.setLocal("application_name", "vojak $holder")
                        val previous = connection.networkTimeout
                        val twiceTheLease = (lease.inWholeMilliseconds * 2).coerceAtMost(Int.MAX_VALUE.toLong())
                        connection.setNetworkTimeout(DIRECT, twiceTheLease.toInt())
                        previous
                    }
                // Its own statement on another connection, the lock already held (research D5).
                previousTimeout to epochs.next(key)
            } catch (failure: Throwable) {
                connection.release()?.let(failure::addSuppressed)
                throw failure
            }
        return JdbcLease(
            connection,
            next.epoch,
            next.previousReleasedCleanly,
            epochs.markReleased(key, next.epoch),
            restoreTimeout,
        )
    }

    /** One non-blocking attempt; a failure ends the transaction and gives the connection back. */
    private suspend fun Connection.tryLock(key: Long): Boolean =
        try {
            blocking { text("select pg_try_advisory_xact_lock($key)::text") == "true" }
        } catch (failure: Throwable) {
            release()?.let(failure::addSuppressed)
            throw failure
        }

    private companion object {
        val IDENTIFIER = Regex("[a-z_][a-z0-9_]{0,40}")
    }
}
