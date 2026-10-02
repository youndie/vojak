package io.github.youndie.vojak.postgres

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.youndie.vojak.Attempt
import io.github.youndie.vojak.Lease
import io.github.youndie.vojak.LockLostException
import io.github.youndie.vojak.LockStore
import kotlin.time.Duration

/**
 * Advisory locks on the caller's Postgres. Each attempt is its own transaction holding
 * `pg_try_advisory_xact_lock` — never a session lock, so ending the transaction is the only release
 * path and nothing can stay behind in the pool (research D1).
 *
 * **Every held lock pins one connection of [driver]'s pool** for the length of the block, in addition
 * to whatever the block itself uses. Size the pool for it; vojak cannot, because a `Driver` does not
 * tell anybody its maximum (research Consequence 7).
 *
 * @param driver built by the caller with `sqlx4k-postgres`; vojak depends on no driver (research D3).
 */
public class PostgresLockStore(
    private val driver: Driver,
) : LockStore {
    override suspend fun <T> tryHolding(
        key: Long,
        block: suspend () -> T,
    ): Attempt<T> {
        val tx = driver.begin().getOrThrow()
        val taken =
            try {
                tx.text("select pg_try_advisory_xact_lock($key)::text") == "true"
            } catch (failure: Throwable) {
                tx.endAfterFailure(failure)
                throw failure
            }
        if (!taken) {
            tx.release()?.let { throw it }
            return Attempt.Busy
        }

        val value =
            try {
                block()
            } catch (failure: Throwable) {
                tx.release()?.let { failure.addSuppressed(it) }
                throw failure
            }
        // The release is the first call on this transaction since the lock was taken, so on a dead
        // connection it fails cleanly on both targets (research §1.5, `N2`) — and a failure here is
        // the only sign that the session, and the lock, may have been gone while the block ran.
        tx.release()?.let {
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
     * that stops renewing — partitioned, frozen or gone (research D2, measured in §1.5). The holder
     * shows in `pg_stat_activity.application_name` as `vojak <holder>`.
     */
    override suspend fun tryLease(
        key: Long,
        lease: Duration,
        holder: String,
    ): Lease? {
        val tx = driver.begin().getOrThrow()
        val taken =
            try {
                tx.text("select pg_try_advisory_xact_lock($key)::text") == "true"
            } catch (failure: Throwable) {
                tx.endAfterFailure(failure)
                throw failure
            }
        if (!taken) {
            tx.release()?.let { throw it }
            return null
        }
        try {
            tx.run("set local idle_in_transaction_session_timeout = '${lease.inWholeMilliseconds}ms'")
            tx.run("set local application_name = 'vojak $holder'")
        } catch (failure: Throwable) {
            tx.endAfterFailure(failure)
            throw failure
        }
        return PostgresLease(tx)
    }
}
