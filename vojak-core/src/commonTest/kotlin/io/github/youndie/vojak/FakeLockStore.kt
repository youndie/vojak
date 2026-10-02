package io.github.youndie.vojak

import kotlin.time.Duration

/**
 * An in-memory store: a key is busy while a block holds it or while a test marks it held by
 * "another process". Kinder than Postgres in one way that matters — it cannot lose a lock — so the
 * Postgres suite, not this one, is where release and loss are tested.
 */
class FakeLockStore(
    private val now: () -> Long,
) : LockStore {
    val heldElsewhere = mutableSetOf<Long>()
    private val held = mutableSetOf<Long>()
    val attempts = mutableListOf<Long>()

    override suspend fun <T> tryHolding(
        key: Long,
        block: suspend () -> T,
    ): Attempt<T> {
        attempts += now()
        if (key in heldElsewhere || key in held) return Attempt.Busy
        held += key
        try {
            return Attempt.Held(block())
        } finally {
            held -= key
        }
    }

    val leases = mutableMapOf<Long, FakeLease>()
    private val epochs = mutableMapOf<Long, Long>()
    private val cleanlyReleased = mutableMapOf<Long, Boolean>()

    /**
     * What a server-ended session looks like: the key is free at once, the holder's next renewal
     * fails, and nothing records a clean release.
     */
    fun endSessionOf(key: Long) {
        leases.remove(key)?.ended = true
    }

    /** What every renewal does; replace it to make renewals fail or hang. */
    var renewal: suspend () -> Unit = {}

    override suspend fun tryLease(
        key: Long,
        lease: Duration,
        holder: String,
    ): Lease? {
        if (key in heldElsewhere || key in held || key in leases) return null
        val epoch = (epochs[key] ?: 0L) + 1
        epochs[key] = epoch
        return FakeLease(key, epoch, cleanlyReleased[key] ?: true).also {
            leases[key] = it
            cleanlyReleased[key] = false
        }
    }

    inner class FakeLease(
        private val key: Long,
        override val epoch: Long,
        override val previousReleasedCleanly: Boolean,
    ) : Lease {
        var renewals = 0
        var released = false
        var ended = false

        override suspend fun renew() {
            check(!ended) { "the session was ended" }
            renewal()
            renewals++
        }

        override suspend fun release() {
            released = true
            if (ended) return
            leases.remove(key)
            cleanlyReleased[key] = true
        }
    }
}
