package io.github.youndie.vojak

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Named critical sections for one service: every name is taken under [namespace], so two services
 * sharing a database cannot take each other's locks by choosing the same word.
 *
 * @param timeSource what `waitAtMost` is measured on; a parameter so the waiting is testable on a
 *   virtual clock.
 */
public class Vojak(
    private val store: LockStore,
    public val namespace: Namespace,
    private val backoff: Backoff = Backoff(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /**
     * Waits for [name] — at most [waitAtMost] — and runs [block] while holding it.
     *
     * Waiting is retrying a non-blocking attempt with [backoff], never a statement blocked inside
     * the database: such a statement is not interrupted by cancellation (research §1.5), so it would
     * make [waitAtMost] a wish.
     *
     * @throws LockTimeoutException when [name] was not taken within [waitAtMost]; [block] did not run.
     * @throws ReentrantLockException when called from inside a block already holding [name].
     * @throws LockLostException see [LockStore.tryHolding].
     */
    public suspend fun <T> withLock(
        name: LockName,
        waitAtMost: Duration = DEFAULT_WAIT,
        block: suspend () -> T,
    ): T = retrying(name, waitAtMost) { key -> store.tryHolding(key) { holding(key, block) } }

    /**
     * Retries [tryOnce] for [name]'s key — with [backoff], for at most [waitAtMost] — until it
     * reports the key taken. The waiting and re-entry rules of [withLock], for a store whose attempt
     * does not run a block: `vojak-postgres`'s lock inside the caller's own transaction is one.
     *
     * @param tryOnce one non-blocking attempt on the key; `true` when it was taken.
     * @throws LockTimeoutException when [tryOnce] did not succeed within [waitAtMost].
     * @throws ReentrantLockException when called from inside a block already holding [name].
     */
    public suspend fun acquireWithin(
        name: LockName,
        waitAtMost: Duration,
        tryOnce: suspend (key: Long) -> Boolean,
    ): Unit = retrying(name, waitAtMost) { key -> if (tryOnce(key)) Attempt.Held(Unit) else Attempt.Busy }

    private suspend fun <T> retrying(
        name: LockName,
        waitAtMost: Duration,
        attempt: suspend (key: Long) -> Attempt<T>,
    ): T {
        val key = keyOf(name)
        val deadline = timeSource.markNow() + waitAtMost
        var pause = backoff.initial
        while (true) {
            when (val outcome = attempt(key)) {
                is Attempt.Held -> return outcome.value
                Attempt.Busy -> Unit
            }
            val left = -deadline.elapsedNow()
            if (!left.isPositive()) throw LockTimeoutException(name, waitAtMost)
            delay(minOf(pause, left))
            pause = minOf(pause * 2, backoff.max)
        }
    }

    /**
     * Runs [block] while holding [name] if it is free right now; otherwise returns `null` after one
     * round trip, without running [block].
     *
     * @throws ReentrantLockException when called from inside a block already holding [name].
     * @throws LockLostException see [LockStore.tryHolding].
     */
    public suspend fun <T : Any> tryWithLock(
        name: LockName,
        block: suspend () -> T,
    ): T? {
        val key = keyOf(name)
        return when (val attempt = store.tryHolding(key) { holding(key, block) }) {
            is Attempt.Held -> attempt.value
            Attempt.Busy -> null
        }
    }

    private val electionsLock = Mutex()
    private val openElections = mutableSetOf<Long>()

    /**
     * Starts campaigning for [name] in [scope] and returns the [Election]; [Election.leadership]
     * says when this process leads. The campaign runs until [Election.close] or until [scope] ends.
     *
     * @param candidate how this process is shown to operators — a pod name; `[A-Za-z0-9._-]{1,63}`.
     *   Never used to decide anything.
     * @param onStoreFailure every failure of the store while campaigning or renewing — for the
     *   service's log. [Election.leadership] already reflects each one (a failed attempt leaves this
     *   process a follower, a failed renewal steps it down); this is where it gets a name.
     * @throws DuplicateElectionException when an election for [name] is already open on this [Vojak].
     */
    public suspend fun elect(
        name: LockName,
        candidate: String,
        scope: CoroutineScope,
        timing: ElectionTiming = ElectionTiming(),
        onStoreFailure: (Throwable) -> Unit = {},
    ): Election {
        require(CANDIDATE.matches(candidate)) { "a candidate must match [A-Za-z0-9._-]{1,63}, got '$candidate'" }
        val key = name.advisoryKey(namespace)
        electionsLock.withLock { if (!openElections.add(key)) throw DuplicateElectionException(name) }
        val election =
            Election(name, key, candidate, store, timing, timeSource, onStoreFailure) {
                electionsLock.withLock { openElections.remove(key) }
            }
        election.start(scope)
        return election
    }

    private suspend fun keyOf(name: LockName): Long {
        val key = name.advisoryKey(namespace)
        if (currentCoroutineContext()[HeldKeys]?.keys?.contains(key) == true) throw ReentrantLockException(name)
        return key
    }

    private suspend fun <T> holding(
        key: Long,
        block: suspend () -> T,
    ): T {
        val held = currentCoroutineContext()[HeldKeys]?.keys.orEmpty()
        return withContext(HeldKeys(held + key)) { block() }
    }

    public companion object {
        /** What `withLock` waits when the caller does not say; bounded, like every wait here. */
        public val DEFAULT_WAIT: Duration = 30.seconds

        private val CANDIDATE = Regex("[A-Za-z0-9._-]{1,63}")
    }
}

/** How `withLock` spaces its attempts: [initial], doubling, never more than [max]. */
public data class Backoff(
    val initial: Duration = 50.milliseconds,
    val max: Duration = 1.seconds,
) {
    init {
        require(initial.isPositive() && max >= initial) { "backoff needs 0 < initial <= max, got $initial..$max" }
    }
}

/**
 * The keys held by the current coroutine and its children — what makes re-entry detectable. A
 * context element rather than a process-wide set: two sibling coroutines asking for one name are
 * two callers and must queue through the database, not fail.
 */
private class HeldKeys(
    val keys: Set<Long>,
) : AbstractCoroutineContextElement(HeldKeys) {
    companion object Key : CoroutineContext.Key<HeldKeys>
}
