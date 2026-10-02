package io.github.youndie.vojak

import kotlin.time.Duration

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

    /**
     * Tries to take [key] once, without waiting, and keeps it until the returned [Lease] is released
     * — or until [lease] passes without a successful [Lease.renew], when the store itself must let
     * it go. That second half is what makes a silent holder safe: the store, not the holder, ends it
     * (research D2).
     *
     * @param holder shown to operators wherever the store can show it; never used to arbitrate.
     * @return the lease, or `null` when another holder has [key].
     */
    public suspend fun tryLease(
        key: Long,
        lease: Duration,
        holder: String,
    ): Lease?
}

/** A key held until [release], kept alive by [renew]. */
public interface Lease {
    /**
     * This lease's number among every lease of its key: strictly greater than any earlier one's,
     * assigned and made durable before the lease is returned.
     */
    public val epoch: Long

    /**
     * Whether the previous lease of this key was given up by its holder — through [release] — rather
     * than ended by the store (a restart, a failover, a terminated session, an expired lease). `true`
     * for the first lease of a key. When `false`, the previous holder may still believe it holds the
     * key until its next renewal, and the new holder has to wait that out (research D12).
     */
    public val previousReleasedCleanly: Boolean

    /**
     * Proves the lease is still held and restarts the store's countdown.
     *
     * @throws Throwable when it is not held any more — the caller must stop acting as the holder.
     */
    public suspend fun renew()

    /**
     * Gives the lease up, and records that it was given up cleanly when it can — the next holder then
     * skips the wait of D12. Called only after the holder has stopped calling itself leader. Never
     * throws: a release that fails means the store has already ended the lease, which is the outcome
     * asked for. May return before the store has let go when a [renew]
     * is still in flight; it then completes when that call returns.
     */
    public suspend fun release()
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
