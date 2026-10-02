package io.github.youndie.vojak.postgres

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.youndie.vojak.LockName
import io.github.youndie.vojak.Namespace
import io.github.youndie.vojak.advisoryKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * The epoch table: one row per lock key, `epoch` bumped by every new leadership of that key.
 *
 * **Bumped in a statement of its own, after the lock is held** — never inside the holding
 * transaction (research D5): a write there would give it a transaction id for the whole leadership and
 * hold back vacuum on every table in the database. The advisory lock already makes the leader the only
 * writer of its row, so a separate autocommit statement loses nothing.
 *
 * The table is created on first use rather than by a migration file: a Kotlin/Native library has no
 * resources to read one from. Two replicas starting together would race `create table if not exists`
 * — Postgres can fail one of them on the catalog's unique index — so the creation runs under an
 * advisory lock of its own.
 */
internal class Epochs(
    private val driver: Driver,
    private val table: String,
) {
    private val creating = Mutex()

    @Volatile private var created = false

    /** One leadership's number, and whether the leadership before it ended by its holder's release. */
    data class Next(
        val epoch: Long,
        val previousReleasedCleanly: Boolean,
    )

    /**
     * The next epoch for [key], durable when this returns, with the previous leadership's clean-release
     * mark (`true` for the first ever). The CTE reads the row as it was before this statement changed it
     * — every part of one statement sees the same snapshot — so the mark is the predecessor's.
     */
    suspend fun next(key: Long): Next {
        ensureTable()
        val (epoch, clean) =
            driver
                .text(
                    "with prev as (select released from $table where lock_key = $key) " +
                        "insert into $table (lock_key, epoch, released) values ($key, 1, false) " +
                        "on conflict (lock_key) do update set epoch = $table.epoch + 1, released = false " +
                        "returning epoch::text || ' ' || coalesce((select released from prev), true)::text",
                ).split(' ')
        return Next(epoch.toLong(), clean == "true")
    }

    /**
     * The statement that marks [epoch] of [key] released. Run inside the holding transaction, just
     * before it commits, so the mark and the end of the lock become visible together: a successor
     * cannot take the lock without seeing the mark.
     */
    fun markReleased(
        key: Long,
        epoch: Long,
    ): String = "update $table set released = true where lock_key = $key and epoch = $epoch"

    private suspend fun ensureTable() {
        if (created) return
        creating.withLock {
            if (created) return
            val tx = driver.begin().getOrThrow()
            try {
                tx.text("select pg_advisory_xact_lock($CREATION_KEY)::text")
                tx.run(
                    "create table if not exists $table " +
                        "(lock_key bigint primary key, epoch bigint not null, released boolean not null default false)",
                )
                // Tables created before the mark existed (B-08) get it here.
                tx.run("alter table $table add column if not exists released boolean not null default false")
            } catch (failure: Throwable) {
                tx.endAfterFailure(failure)
                throw failure
            }
            tx.commit().getOrThrow()
            created = true
        }
    }

    private companion object {
        /** Shared by every vojak, whatever its namespace: there is one epoch table per prefix. */
        val CREATION_KEY = LockName.of("create-epoch-table").advisoryKey(Namespace.of("vojak"))
    }
}
