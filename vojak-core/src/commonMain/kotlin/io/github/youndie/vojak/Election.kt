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
import kotlin.time.Duration
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
    private val work: (suspend CoroutineScope.(epoch: Long) -> Unit)?,
    private val onWorkFailure: (Throwable) -> Unit,
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
     * Stops campaigning, cancels the `whileLeader` block and waits for it, gives the lease up if
     * held, and leaves [leadership] at [Leadership.Closed]. Returns within `serverLease` even if a
     * store call or the block is stuck: the campaign is waited for no longer than that, and the
     * release completes on its own.
     */
    public suspend fun close() {
        if (state.value == Leadership.Closed) return
        withTimeoutOrNull(timing.serverLease) { campaign.cancelAndJoin() } ?: campaign.cancel()
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
                holdUntilLost(lease)
            } finally {
                if (state.value is Leadership.Leader) state.value = Leadership.Follower
                withContext(NonCancellable) { lease.release() }
            }
            // Two polls' pause before campaigning again: without it a process that just lost the lease
            // is the first to ask for it back, every time — flapping, and the other replicas never get
            // a turn after a failure that was this one's. Two, not one: every other replica polls at
            // least once in that window, so the turn is theirs rather than a race (research D10).
            delay(timing.pollEvery * 2)
        }
    }

    /**
     * Holds the lease: waits out the previous leader if it did not let go cleanly, then reports
     * [Leadership.Leader] and runs the `whileLeader` block, and returns when the lease is lost — a
     * renewal failed, the block threw, or `localLease` passed since the last renewal that succeeded.
     *
     * **Taking the lock is not proof the previous leader has stopped** (research §1.6): when the
     * server ends a leader's session the lock is free at once, while that leader keeps believing until
     * its next renewal fails. So unless [Lease.previousReleasedCleanly], this process holds and renews
     * the lease for `localLease` — the latest the previous leader can still believe — before it calls
     * itself leader (research D12). A clean release by vojak is always made after its own state left
     * [Leadership.Leader], which is what makes skipping the wait safe then.
     *
     * Renewals and the block run **outside** this coroutine's structure. A native store call cannot be
     * cancelled (research §1.5), and a child stuck in one would keep this function from returning,
     * which is exactly the step-down that must not wait for it. The block is the one thing that *is*
     * waited for — its cancellation completes before [leadership] leaves [Leadership.Leader] — but
     * only within the lease margin: past `serverLease` the server has ended the lease and waiting
     * longer would only make the state lie.
     */
    private suspend fun holdUntilLost(lease: Lease) {
        val renewed = LastRenewal(timeSource.markNow())
        val lost = CompletableDeferred<Unit>()
        val detached = CoroutineScope(currentCoroutineContext() + Job())
        detached.launch {
            while (true) {
                delay(timing.renewEvery)
                try {
                    lease.renew()
                    renewed.at = timeSource.markNow()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    onStoreFailure(failure)
                    lost.complete(Unit)
                    return@launch
                }
            }
        }
        var running: Job? = null
        try {
            val leadsFrom =
                timeSource.markNow() + if (lease.previousReleasedCleanly) Duration.ZERO else timing.localLease
            while (true) {
                val left = timing.localLease - renewed.at.elapsedNow()
                if (!left.isPositive() || lost.isCompleted) return
                val untilLeading = -leadsFrom.elapsedNow()
                if (!untilLeading.isPositive()) break
                withTimeoutOrNull(minOf(left, untilLeading)) { lost.await() }
            }
            state.value = Leadership.Leader(lease.epoch)
            running =
                work?.let { block ->
                    detached.launch {
                        try {
                            block(lease.epoch)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            onWorkFailure(failure)
                            lost.complete(Unit)
                        }
                    }
                }
            while (true) {
                val left = timing.localLease - renewed.at.elapsedNow()
                if (!left.isPositive() || lost.isCompleted) return
                withTimeoutOrNull(left) { lost.await() }
            }
        } finally {
            detached.cancel()
            val block = running
            if (block != null) {
                withContext(NonCancellable) {
                    withTimeoutOrNull(timing.serverLease - timing.localLease) { block.join() }
                        ?: onWorkFailure(LeaderWorkDidNotStopException(name))
                }
            }
        }
    }

    private class LastRenewal(
        @Volatile var at: TimeMark,
    )
}
