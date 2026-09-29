package io.github.youndie.vojak

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
}
