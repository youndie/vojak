package io.github.youndie.vojak

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The campaign on a fake store and the scheduler's clock: every duration here is exact. */
@OptIn(ExperimentalCoroutinesApi::class)
class ElectionTest {
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
    fun aLoneCandidateLeadsAndKeepsLeadingWhileRenewalsSucceed() =
        runTest {
            val store = FakeLockStore { testScheduler.currentTime }
            val election = vojak(store).elect(sender, "pod-a", backgroundScope, timing)

            testScheduler.runCurrent()
            assertEquals(Leadership.Leader, election.leadership.value)
            testScheduler.advanceTimeBy(10_000)
            assertEquals(Leadership.Leader, election.leadership.value)
            assertTrue(
                store.leases.values
                    .single()
                    .renewals >= 19,
                "renewed every 500 ms",
            )
            election.close()
        }

    @Test
    fun ofTwoCandidatesOneLeadsAndTheOtherTakesOverWhenItCloses() =
        runTest {
            val store = FakeLockStore { testScheduler.currentTime }
            val a = vojak(store).elect(sender, "pod-a", backgroundScope, timing)
            val b = vojak(store).elect(sender, "pod-b", backgroundScope, timing)
            testScheduler.runCurrent()
            assertEquals(Leadership.Leader, a.leadership.value)
            assertEquals(Leadership.Follower, b.leadership.value)

            a.close()
            assertEquals(Leadership.Closed, a.leadership.value)
            testScheduler.advanceTimeBy(timing.pollEvery.inWholeMilliseconds + 1)

            assertEquals(Leadership.Leader, b.leadership.value)
            b.close()
        }

    @Test
    fun aFailedRenewalStepsDownAtOnceAndReleasesTheLease() =
        runTest {
            val store = FakeLockStore { testScheduler.currentTime }
            val election = vojak(store).elect(sender, "pod-a", backgroundScope, timing)
            testScheduler.runCurrent()
            val lease = store.leases.values.single()
            store.renewal = { error("connection gone") }

            testScheduler.advanceTimeBy(timing.renewEvery.inWholeMilliseconds + 1)

            assertEquals(Leadership.Follower, election.leadership.value, "down at the failed renewal, not later")
            assertTrue(lease.released)
            election.close()
        }

    @Test
    fun afterLosingTheLeaseItWaitsTwoPollsBeforeCampaigningAgain() =
        runTest {
            val store = FakeLockStore { testScheduler.currentTime }
            val election = vojak(store).elect(sender, "pod-a", backgroundScope, timing)
            testScheduler.runCurrent()
            store.renewal = { error("connection gone") }
            testScheduler.advanceTimeBy(timing.renewEvery.inWholeMilliseconds + 1)
            store.renewal = {}

            testScheduler.advanceTimeBy(timing.pollEvery.inWholeMilliseconds * 2 - 2)
            assertEquals(Leadership.Follower, election.leadership.value)
            testScheduler.advanceTimeBy(2)

            assertEquals(Leadership.Leader, election.leadership.value)
            election.close()
        }

    /**
     * The renewal never returns and cannot be cancelled — a native driver call in a partition. The
     * leader must still step down, on the timer, exactly `localLease` after its last success.
     */
    @Test
    fun aHungRenewalStepsDownOnTheTimerNotOnItsReturn() =
        runTest {
            val store = FakeLockStore { testScheduler.currentTime }
            store.renewal = { withContext(NonCancellable) { CompletableDeferred<Unit>().await() } }
            val election = vojak(store).elect(sender, "pod-a", backgroundScope, timing)
            testScheduler.runCurrent()
            assertEquals(Leadership.Leader, election.leadership.value)

            testScheduler.advanceTimeBy(timing.localLease.inWholeMilliseconds - 1)
            assertEquals(Leadership.Leader, election.leadership.value)
            testScheduler.advanceTimeBy(2)

            assertEquals(Leadership.Follower, election.leadership.value)
            election.close()
        }

    @Test
    fun aSecondElectionForTheSameNameIsRefusedUntilTheFirstCloses() =
        runTest {
            val vojak = vojak(FakeLockStore { testScheduler.currentTime })
            val first = vojak.elect(sender, "pod-a", backgroundScope, timing)

            assertFailsWith<DuplicateElectionException> { vojak.elect(sender, "pod-a", backgroundScope, timing) }

            first.close()
            vojak.elect(sender, "pod-a", backgroundScope, timing).close()
        }

    @Test
    fun closeLeavesClosedAndGivesTheLeaseUp() =
        runTest {
            val store = FakeLockStore { testScheduler.currentTime }
            val election = vojak(store).elect(sender, "pod-a", backgroundScope, timing)
            testScheduler.runCurrent()
            val lease = store.leases.values.single()

            election.close()

            assertEquals(Leadership.Closed, election.leadership.value)
            assertTrue(lease.released)
        }

    @Test
    fun aCandidateThatCannotBeShownSafelyIsRefused() =
        runTest {
            val vojak = vojak(FakeLockStore { testScheduler.currentTime })
            assertFailsWith<IllegalArgumentException> { vojak.elect(sender, "pod a'; drop", backgroundScope, timing) }
        }
}
