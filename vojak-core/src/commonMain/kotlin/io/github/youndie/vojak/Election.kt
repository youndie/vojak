package io.github.youndie.vojak

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * One process's part in electing a leader for [name]: it campaigns until [close], and [leadership]
 * says where it stands. Made by [Vojak.elect].
 *
 * **At most one process believes it leads at any instant, provided clock drift stays inside the
 * margin between `localLease` and `serverLease`** — the one assumption stated rather than enforced
 * (research D9). Making a stale leader's late writes harmless is the job of a fencing token, not of
 * this class.
 *
 * **A held leadership pins one connection of the store's pool** for as long as it lasts.
 */
public class Election internal constructor(
    public val name: LockName,
    private val key: Long,
    private val candidate: String,
    private val store: LockStore,
    private val timing: ElectionTiming,
    private val timeSource: TimeSource,
    private val onStoreFailure: (Throwable) -> Unit,
    private val onClosed: suspend () -> Unit,
) {
    private val state = MutableStateFlow<Leadership>(Leadership.Follower)

    /** Where this process stands. Starts [Leadership.Follower]; ends [Leadership.Closed]. */
    public val leadership: StateFlow<Leadership> = state.asStateFlow()

    private lateinit var campaign: Job

    internal fun start(scope: CoroutineScope) {
        campaign = scope.launch { campaign() }
    }

    /**
     * Stops campaigning, gives the lease up if held, and leaves [leadership] at
     * [Leadership.Closed]. Returns within `localLease` even if a store call is stuck: the campaign
     * is cancelled and waited for no longer than that, and the release completes on its own.
     */
    public suspend fun close() {
        if (state.value == Leadership.Closed) return
        withTimeoutOrNull(timing.localLease) { campaign.cancelAndJoin() } ?: campaign.cancel()
        state.value = Leadership.Closed
        onClosed()
    }

    private suspend fun campaign() {
        while (currentCoroutineContext().isActive) {
            val lease =
                try {
                    store.tryLease(key, timing.serverLease, candidate)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    // The store is unreachable or refused: not a leader, try again later.
                    onStoreFailure(failure)
                    null
                }
            if (lease == null) {
                delay(timing.pollEvery)
                continue
            }
            try {
                state.value = Leadership.Leader
                holdUntilLost(lease)
            } finally {
                if (state.value == Leadership.Leader) state.value = Leadership.Follower
                withContext(NonCancellable) { lease.release() }
            }
            // A poll's pause before campaigning again: without it a process that just lost the lease
            // is the first to ask for it back, every time — flapping, and the other replicas never
            // get a turn after a failure that was this one's.
            delay(timing.pollEvery)
        }
    }

    /**
     * Returns when the lease is lost: a renewal failed, or `localLease` passed since the last one
     * that succeeded. The renewals run **outside** this coroutine's structure — a native store call
     * cannot be cancelled (research §1.5), and a child stuck in one would keep this function from
     * returning, which is exactly the step-down that must not wait for it.
     */
    private suspend fun holdUntilLost(lease: Lease) {
        val renewed = LastRenewal(timeSource.markNow())
        val failed = CompletableDeferred<Unit>()
        val renewals = CoroutineScope(currentCoroutineContext() + Job())
        renewals.launch {
            while (true) {
                delay(timing.renewEvery)
                try {
                    lease.renew()
                    renewed.at = timeSource.markNow()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    onStoreFailure(failure)
                    failed.complete(Unit)
                    return@launch
                }
            }
        }
        try {
            while (true) {
                val left = timing.localLease - renewed.at.elapsedNow()
                if (!left.isPositive() || failed.isCompleted) return
                withTimeoutOrNull(left) { failed.await() }
            }
        } finally {
            renewals.cancel()
        }
    }

    private class LastRenewal(
        @Volatile var at: TimeMark,
    )
}
