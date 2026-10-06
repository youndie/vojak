package io.github.youndie.vojak.jdbc

import io.github.youndie.vojak.LockName
import io.github.youndie.vojak.Vojak
import java.sql.Connection
import kotlin.time.Duration

/**
 * Takes [name] inside the caller's own transaction on [connection] and returns. The lock is released
 * when that transaction commits or rolls back, so commit and release are one event — vojak-postgres's
 * `lockForTransaction`, for a JDBC connection (under Exposed: the transaction's `connection.connection`).
 *
 * vojak never commits, rolls back or closes [connection]; it issues `pg_try_advisory_xact_lock` on it,
 * retried with the [Vojak]'s backoff for at most [waitAtMost].
 *
 * @throws IllegalStateException when [connection] is in auto-commit mode. A transaction-level lock
 *   taken there is released by the same statement that takes it — a lock that holds nothing, with no
 *   error anywhere — so it is refused instead.
 * @throws io.github.youndie.vojak.LockTimeoutException when [name] was not taken within [waitAtMost].
 * @throws io.github.youndie.vojak.ReentrantLockException when called from inside a [Vojak.withLock]
 *   block holding [name].
 */
public suspend fun Vojak.lockForTransaction(
    connection: Connection,
    name: LockName,
    waitAtMost: Duration = Vojak.DEFAULT_WAIT,
) {
    check(!blocking { connection.autoCommit }) {
        "lockForTransaction needs an open transaction; the connection is in auto-commit mode, where the lock would end with the statement that takes it"
    }
    acquireWithin(name, waitAtMost) { key ->
        blocking { connection.text("select pg_try_advisory_xact_lock($key)::text") == "true" }
    }
}
