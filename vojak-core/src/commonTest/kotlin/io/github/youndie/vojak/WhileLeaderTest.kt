package io.github.youndie.vojak

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** feature-run-while-leader on a fake store and the scheduler's clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class WhileLeaderTest {
    private val sender = LockName.of("digest-sender")
    private val digest = Namespace.of("digest")
    private val timing =
        ElectionTiming(
            serverLease = 3.seconds,
            localLease = 1500.milliseconds,
            renewEvery = 500.milliseconds,
            pollEvery = 200.milliseconds,
        )

    private fun TestScope.vojak(store: FakeLockStore) = Vojak(store, digest, timeSource = testScheduler.timeSource)

    @Test
    fun theBlockStartsOnlyOnceTheElectionReadsLeader() =
        runTest {
            val store = FakeLockStore { testScheduler.currentTime }
            lateinit var election: Election
            val seenAtStart = CompletableDeferred<Leadership>()
            election =
                vojak(store).elect(sender, "pod-a", backgroundScope, timing) {
                    seenAtStart.complete(election.leadership.value)
                    awaitCancellation()
                }
            testScheduler.runCurrent()

            assertIs<Leadership.Leader>(seenAtStart.await())
            election.close()
        }

    /**
     * The order the feature promises: on loss the block is cancelled and its `finally` has run to the
     * end before anybody can read `Follower` — here a `finally` that takes 100 ms of its own.
     */
    @Test
    fun aStepDownCancelsTheBlockAndFinishesItsFinallyBeforeFollowerIsVisible() =
        runTest {
            val store = FakeLockStore { testScheduler.currentTime }
            lateinit var election: Election
            var seenInFinally: Leadership? = null
            election =
                vojak(store).elect(sender, "pod-a", backgroundScope, timing) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            delay(100)
                            seenInFinally = election.leadership.value
                        }
                    }
                }
            testScheduler.runCurrent()
            store.renewal = { error("connection gone") }

            testScheduler.advanceTimeBy(timing.renewEvery.inWholeMilliseconds + 50)
            assertIs<Leadership.Leader>(election.leadership.value, "still in the block's finally")
            testScheduler.advanceTimeBy(60)

            assertIs<Leadership.Leader>(seenInFinally, "the finally ran while the state still read Leader")
            assertEquals(Leadership.Follower, election.leadership.value)
            election.close()
        }

    @Test
    fun aThrowingBlockStepsDownReachesTheHandlerAndRunsAgainOnceReelected() =
        runTest {
            val store = FakeLockStore { testScheduler.currentTime }
            val failures = mutableListOf<Throwable>()
            var runs = 0
            val election =
                vojak(store).elect(sender, "pod-a", backgroundScope, timing, onWorkFailure = { failures += it }) {
                    runs++
                    if (runs == 1) error("the singleton failed")
                    awaitCancellation()
                }
            testScheduler.runCurrent()

            assertEquals(1, failures.size)
            assertEquals("the singleton failed", failures.single().message)
            assertEquals(Leadership.Follower, election.leadership.value)
            testScheduler.advanceTimeBy(timing.pollEvery.inWholeMilliseconds * 2 + 1)
            assertIs<Leadership.Leader>(election.leadership.value)
            assertEquals(2, runs)
            election.close()
        }

    @Test
    fun aBlockThatReturnsKeepsTheLeadershipAndIsNotRestarted() =
        runTest {
            val store = FakeLockStore { testScheduler.currentTime }
            var runs = 0
            val election = vojak(store).elect(sender, "pod-a", backgroundScope, timing) { runs++ }
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(10_000)

            assertEquals(1, runs)
            assertIs<Leadership.Leader>(election.leadership.value)
            election.close()
        }

    /**
     * A block that ignores cancellation cannot be stopped by anybody. The state must not wait for it
     * past the lease margin — the server has ended the lease by then — and the handler is told.
     */
    @Test
    fun aBlockIgnoringCancellationIsReportedAndDoesNotHoldTheStateBeyondTheMargin() =
        runTest {
            val store = FakeLockStore { testScheduler.currentTime }
            val failures = mutableListOf<Throwable>()
            val election =
                vojak(store).elect(sender, "pod-a", backgroundScope, timing, onWorkFailure = { failures += it }) {
                    withContext(NonCancellable) { CompletableDeferred<Unit>().await() }
                }
            testScheduler.runCurrent()
            store.renewal = { error("connection gone") }

            val margin = (timing.serverLease - timing.localLease).inWholeMilliseconds
            testScheduler.advanceTimeBy(timing.renewEvery.inWholeMilliseconds + margin - 1)
            assertIs<Leadership.Leader>(election.leadership.value)
            testScheduler.advanceTimeBy(2)

            assertEquals(Leadership.Follower, election.leadership.value)
            assertIs<LeaderWorkDidNotStopException>(failures.single())
            assertTrue(store.leases.isEmpty(), "the lease was given up")
            election.close()
        }
}
