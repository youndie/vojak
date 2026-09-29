package io.github.youndie.vojak.postgres

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * THE ONE PLACE A STATEMENT LEAVES THIS MODULE (CLAUDE.md). On linuxX64 the driver ends the process
 * on some failures (research §1.5); a workaround, when there is one, has one home.
 *
 * Every value is cast to text in the SQL, so both drivers hand back the same string and nothing here
 * depends on how either decodes a type. Nothing is interpolated but numbers computed in this library.
 */
internal suspend fun QueryExecutor.text(sql: String): String =
    fetchAll(sql)
        .getOrThrow()
        .first()
        .get(0)
        .asString()

/**
 * Ends [tx] after [failure] was thrown by a statement on it — or leaves it alone, when ending it
 * would end the process.
 *
 * On linuxX64 (sqlx4k-postgres 1.13.1) the first call after the server closes a session reports the
 * server's message; **any later call on that connection aborts the process** (research §1.5, `N1`,
 * and the `rollback()` after `H2`/`H3`). So when [failure] says the connection is gone, a native
 * build does not roll back: the transaction and its pool connection are abandoned — one connection
 * lost (`N4`) instead of the service. The JVM rolls back, which fails harmlessly there. Tracked as
 * B-13.
 */
internal suspend fun Transaction.endAfterFailure(failure: Throwable) {
    if (!ROLLBACK_AFTER_CONNECTION_LOSS_IS_SAFE && failure.meansConnectionIsGone()) return
    withContext(NonCancellable) { rollback() }
}

/**
 * Ends [tx] with nothing to keep. Returns what went wrong instead of throwing, so the caller decides
 * what a failed release means for the work that already ran.
 */
internal suspend fun Transaction.release(): Throwable? = withContext(NonCancellable) { rollback().exceptionOrNull() }

/**
 * A failure after which the session no longer exists: connection exceptions (`08…`), the server's
 * own terminations (`57P01`–`57P05`), and the idle-in-transaction timeout (`25P03`). Read out of the
 * message because that is where the native driver puts the SQLSTATE (`[Database] :: [57P01] …`).
 */
internal fun Throwable.meansConnectionIsGone(): Boolean {
    if (this !is SQLError || code != SQLError.Code.Database) return false
    val state = SQLSTATE.find(message.orEmpty())?.groupValues?.get(1) ?: return false
    return state.startsWith("08") || state in SESSION_ENDED
}

private val SQLSTATE = Regex("""\[Database\] :: \[([0-9A-Z]{5})\]""")
private val SESSION_ENDED = setOf("57P01", "57P02", "57P03", "57P05", "25P03")

/** Whether a statement on a connection the server has closed fails, rather than aborting the process. */
internal expect val ROLLBACK_AFTER_CONNECTION_LOSS_IS_SAFE: Boolean
