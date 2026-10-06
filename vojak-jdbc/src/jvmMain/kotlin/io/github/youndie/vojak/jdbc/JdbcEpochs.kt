package io.github.youndie.vojak.jdbc

import io.github.youndie.vojak.LockName
import io.github.youndie.vojak.Namespace
import io.github.youndie.vojak.advisoryKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.sql.DataSource
import kotlin.concurrent.Volatile

/**
 * vojak-postgres's `Epochs` over JDBC: the same table, the same statements, so a JDBC replica and an
 * sqlx4k replica of one service share epochs and the clean-release mark (`InteropTest` holds them to
 * it). The reasoning — bumped outside the holding transaction (research D5), created under an
 * advisory lock of its own — is written there and not repeated here.
 */
internal class JdbcEpochs(
    private val dataSource: DataSource,
    private val table: String,
) {
    private val creating = Mutex()

    @Volatile private var created = false

    data class Next(
        val epoch: Long,
        val previousReleasedCleanly: Boolean,
    )

    suspend fun next(key: Long): Next {
        ensureTable()
        val (epoch, clean) =
            blocking {
                dataSource.connection.use { connection ->
                    connection.autoCommit = true
                    connection.text(
                        "with prev as (select released from $table where lock_key = $key) " +
                            "insert into $table (lock_key, epoch, released) values ($key, 1, false) " +
                            "on conflict (lock_key) do update set epoch = $table.epoch + 1, released = false " +
                            "returning epoch::text || ' ' || coalesce((select released from prev), true)::text",
                    )
                }
            }.split(' ')
        return Next(epoch.toLong(), clean == "true")
    }

    fun markReleased(
        key: Long,
        epoch: Long,
    ): String = "update $table set released = true where lock_key = $key and epoch = $epoch"

    private suspend fun ensureTable() {
        if (created) return
        creating.withLock {
            if (created) return
            val connection = dataSource.openTransaction()
            try {
                blocking {
                    connection.text("select pg_advisory_xact_lock($CREATION_KEY)::text")
                    connection.run(
                        "create table if not exists $table " +
                            "(lock_key bigint primary key, epoch bigint not null, released boolean not null default false)",
                    )
                    connection.run(
                        "alter table $table add column if not exists released boolean not null default false",
                    )
                    connection.commit()
                }
            } catch (failure: Throwable) {
                connection.release()?.let(failure::addSuppressed)
                throw failure
            }
            blocking { connection.giveBack() }
            created = true
        }
    }

    private companion object {
        /** The same key as vojak-postgres: one creation lock per database, whichever store asks. */
        val CREATION_KEY = LockName.of("create-epoch-table").advisoryKey(Namespace.of("vojak"))
    }
}
