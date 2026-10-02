package io.github.youndie.vojak

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Research D12 on a fake store and the scheduler's clock: after a session the store ended, the new
 * holder waits `localLease` before it calls itself leader; after a clean release it does not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaitOutTest {
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

    /** When each election read `Leader` and when it stopped, on the scheduler's clock. */
    private fun TestScope.leading(election: Election): MutableList<LongRange> {
        val spans = mutableListOf<LongRange>()
        var from = -1L
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            election.leadership.collect { state ->
                val now = testScheduler.currentTime
                if (state is Leadership.Leader && from < 0) from = now
                if (state !is Leadership.Leader && from >= 0) {
                    spans += from..now
                    from = -1
                }
            }
        }
        return spans
    }

    @Test
    fun afterTheStoreEndsTheLeadersSessionTheSuccessorWaitsAndTheyNeverOverlap() =
        runTest {
            val store = FakeLockStore { testScheduler.currentTime }
            val a = vojak(store).elect(sender, "pod-a", backgroundScope, timing)
            testScheduler.runCurrent()
            val b = vojak(store).elect(sender, "pod-b", backgroundScope, timing)
            val aSpans = leading(a)
            val bSpans = leading(b)
            testScheduler.advanceTimeBy(1_000)

            val endedAt = testScheduler.currentTime
            store.endSessionOf(sender.advisoryKey(digest))
            testScheduler.advanceTimeBy(5_000)
            a.close()
            b.close()

            val aEnd = aSpans.single().last
            val bStart = bSpans.first().first
            assertTrue(aEnd <= bStart, "a led until $aEnd, b from $bStart")
            assertTrue(
                bStart >= endedAt + timing.localLease.inWholeMilliseconds,
                "b led at $bStart, the session ended at $endedAt",
            )
        }

    @Test
    fun afterACleanCloseTheSuccessorLeadsWithinAPoll() =
        runTest {
            val store = FakeLockStore { testScheduler.currentTime }
            val a = vojak(store).elect(sender, "pod-a", backgroundScope, timing)
            testScheduler.runCurrent()
            val b = vojak(store).elect(sender, "pod-b", backgroundScope, timing)
            val bSpans = leading(b)
            testScheduler.advanceTimeBy(1_000)

            val closedAt = testScheduler.currentTime
            a.close()
            testScheduler.advanceTimeBy(timing.pollEvery.inWholeMilliseconds + 1)

            assertTrue(bSpans.isEmpty() && b.leadership.value is Leadership.Leader, "b did not lead within a poll")
            assertTrue(testScheduler.currentTime - closedAt <= timing.pollEvery.inWholeMilliseconds + 1)
            b.close()
        }
}
