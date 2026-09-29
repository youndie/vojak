package io.github.youndie.vojak.postgres

import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.youndie.vojak.LockName
import io.github.youndie.vojak.Vojak
import kotlin.time.Duration

/**
 * Takes [name] inside [tx] — the caller's own transaction — and returns. The lock is released when
 * [tx] commits or rolls back, so commit and release are one event: nobody can take the lock between
 * the caller's write becoming visible and the lock going away.
 *
 * vojak never commits or rolls back [tx]; it issues `pg_try_advisory_xact_lock` on it, retried with
 * the [Vojak]'s backoff for at most [waitAtMost].
 *
 * If the attempt fails because the connection is gone, **on linuxX64 the caller must not roll [tx]
 * back**: that would be the second call on a dead connection, and sqlx4k-postgres 1.13.1 aborts the
 * process on it (research §1.5, Consequence 12). vojak cannot make that choice for a transaction it
 * does not own.
 *
 * @throws io.github.youndie.vojak.LockTimeoutException when [name] was not taken within [waitAtMost];
 *   [tx] is untouched apart from the failed attempts, and the caller decides what to do with it.
 * @throws io.github.youndie.vojak.ReentrantLockException when called from inside a [Vojak.withLock]
 *   block holding [name] — that block's lock is another session, and [tx] would wait for it forever.
 */
public suspend fun Vojak.lockForTransaction(
    tx: Transaction,
    name: LockName,
    waitAtMost: Duration = Vojak.DEFAULT_WAIT,
): Unit =
    acquireWithin(name, waitAtMost) { key ->
        tx.text("select pg_try_advisory_xact_lock($key)::text") == "true"
    }
