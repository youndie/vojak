package io.github.youndie.vojak

/**
 * Where a lock is actually taken — the port `vojak-postgres` implements. The core decides names,
 * keys, waiting and re-entry; a store only tries a key once.
 */
public interface LockStore {
    /**
     * Tries to take [key] once, without waiting. When it is taken, runs [block] while holding it
     * and releases it when [block] ends — normally, by exception or by cancellation.
     *
     * @return [Attempt.Held] with [block]'s value, or [Attempt.Busy] without running [block] when
     *   another session holds [key].
     * @throws LockLostException when [block] completed but the store could not confirm the lock was
     *   still held at the end — exclusivity for that run is not guaranteed.
     */
    public suspend fun <T> tryHolding(
        key: Long,
        block: suspend () -> T,
    ): Attempt<T>
}

/** The outcome of one [LockStore.tryHolding]. */
public sealed interface Attempt<out T> {
    /** The key was taken and the block ran. */
    public data class Held<out T>(
        val value: T,
    ) : Attempt<T>

    /** Another session holds the key; the block did not run. */
    public data object Busy : Attempt<Nothing>
}
