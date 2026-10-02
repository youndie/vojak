package io.github.youndie.vojak.digest

import io.github.smyrgeorge.sqlx4k.Driver

/**
 * Where the sender keeps its progress: one row, `last_sent` and the epoch of the leader that wrote it.
 * In the database rather than in memory, so a new leader carries on from the old one's last digest
 * instead of from zero — and so the row can refuse a leader that is no longer one.
 *
 * **The guard is the `WHERE`.** Leadership ends in the old leader's process later than in the
 * database: a partitioned leader's statement can sit in a socket buffer, arrive after the new leader
 * has written, and run. Nothing in the election can recall bytes that already left the process, so
 * the row checks the epoch it is written with against the one it holds and changes nothing for an
 * older one (research D5).
 *
 * @param guarded false only for the stand's control run, which shows what the guard prevents. A real
 *   service has no such switch.
 */
class DigestStore(
    private val driver: Driver,
    private val guarded: Boolean = true,
) {
    suspend fun create() {
        driver.execute(CREATE).getOrThrow()
        driver.execute("insert into digest_state (id, last_sent, epoch) values (1, 0, 0) on conflict do nothing").getOrThrow()
    }

    /**
     * Claims the next digest for the leader of [epoch]: the number to send, or null when the row holds
     * a newer epoch — somebody else leads, and this replica must send nothing.
     *
     * One statement, claim before send. A read followed by a write would leave the read stuck in a
     * partition and the write never sent; the stale write this guard exists for is the one already on
     * the wire. The price is at-most-once: a process that dies between this and the send skips one.
     */
    suspend fun claimNext(epoch: Long): Long? {
        val guard = if (guarded) " and epoch <= $epoch" else ""
        return driver
            .fetchAll("update digest_state set last_sent = last_sent + 1, epoch = $epoch where id = 1$guard returning last_sent::text")
            .getOrThrow()
            .firstOrNull()
            ?.get(0)
            ?.asString()
            ?.toLong()
    }

    /** `last_sent epoch`, for `/status`. */
    suspend fun state(): String =
        driver
            .fetchAll("select last_sent::text || ' ' || epoch::text from digest_state where id = 1")
            .getOrThrow()
            .first()
            .get(0)
            .asString()

    companion object {
        /** `stale-write.sh` creates the same table first, to put its audit trigger on it. */
        const val CREATE =
            "create table if not exists digest_state (id int primary key, last_sent bigint not null, epoch bigint not null)"
    }
}
