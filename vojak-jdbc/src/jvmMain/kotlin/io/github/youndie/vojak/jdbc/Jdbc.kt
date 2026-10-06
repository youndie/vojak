package io.github.youndie.vojak.jdbc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Executor
import javax.sql.DataSource

/**
 * THE ONE PLACE A STATEMENT LEAVES THIS MODULE, as `Sql.kt` is for vojak-postgres. JDBC blocks, so
 * every call goes to [Dispatchers.IO]; a blocked call does not answer cancellation, which is why the
 * waiting in vojak is a retried non-blocking attempt and never a statement left to wait in the
 * database.
 *
 * Every value is cast to text in the SQL, the same SQL as vojak-postgres, so the two stores read and
 * write the same rows the same way. Nothing is interpolated but numbers computed in this library.
 */
internal suspend fun <T> blocking(block: () -> T): T = withContext(Dispatchers.IO) { block() }

internal fun Connection.text(sql: String): String =
    createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            check(rows.next()) { "no row from: $sql" }
            rows.getString(1)
        }
    }

/** A statement whose result is not read. */
internal fun Connection.run(sql: String) {
    createStatement().use { it.execute(sql) }
}

/**
 * `SET LOCAL` with a value that is not a number vojak computed — the holder's name — through
 * `set_config(…, true)`, which is `SET LOCAL` and takes a bound parameter.
 */
internal fun Connection.setLocal(
    setting: String,
    value: String,
) {
    prepareStatement("select set_config(?, ?, true)").use { statement ->
        statement.setString(1, setting)
        statement.setString(2, value)
        statement.executeQuery().close()
    }
}

/** A connection with a transaction open on it: auto-commit off, or closed again if that failed. */
internal suspend fun DataSource.openTransaction(): Connection =
    blocking {
        val connection = connection
        try {
            connection.autoCommit = false
            connection
        } catch (failure: SQLException) {
            try {
                connection.close()
            } catch (closing: SQLException) {
                failure.addSuppressed(closing)
            }
            throw failure
        }
    }

/**
 * Rolls the transaction back and gives the connection back to whoever lent it. Returns what went
 * wrong with the rollback instead of throwing, so the caller decides what a failed release means for
 * the work that already ran.
 *
 * On the JVM a rollback on a connection the server has closed fails harmlessly — the native rule of
 * vojak-postgres (never a second call on a dead connection) has no counterpart here.
 */
internal suspend fun Connection.release(): Throwable? =
    withContext(NonCancellable) {
        blocking {
            val failed =
                try {
                    rollback()
                    null
                } catch (failure: SQLException) {
                    failure
                }
            giveBack()
            failed
        }
    }

/**
 * Back to the pool the way it came out: auto-commit on, the network timeout a lease may have set
 * restored. A pool resets both anyway — HikariCP does — but a plain `DataSource` hands out a physical
 * connection, and either way none of this may throw past a release.
 */
internal fun Connection.giveBack(networkTimeout: Int? = null) {
    if (networkTimeout != null) ignoringSqlFailure { setNetworkTimeout(DIRECT, networkTimeout) }
    ignoringSqlFailure { if (!isClosed) autoCommit = true }
    ignoringSqlFailure { close() }
}

/**
 * For the steps of giving a connection back, and only those: each runs after the outcome is already
 * decided — the lock released or known lost — and a connection that refuses them is a dead one, which
 * the pool discards on its own. Narrowed to [SQLException], so anything else still surfaces.
 */
internal inline fun ignoringSqlFailure(step: () -> Unit) {
    try {
        step()
    } catch (_: SQLException) {
        // See above: the outcome is decided, and the pool discards a broken connection itself.
    }
}

/** `setNetworkTimeout` wants an executor; the Postgres driver sets a socket timeout and never uses it. */
internal val DIRECT = Executor { it.run() }
