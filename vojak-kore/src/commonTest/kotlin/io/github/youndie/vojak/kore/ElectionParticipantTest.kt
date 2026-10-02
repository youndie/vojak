package io.github.youndie.vojak.kore

import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.shutdownSequence
import io.github.youndie.vojak.Attempt
import io.github.youndie.vojak.ElectionTiming
import io.github.youndie.vojak.Leadership
import io.github.youndie.vojak.Lease
import io.github.youndie.vojak.LockName
import io.github.youndie.vojak.LockStore
import io.github.youndie.vojak.Namespace
import io.github.youndie.vojak.Vojak
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** kore's real shutdown sequence, with the election registered through [election]. */
class ElectionParticipantTest {
    /** One key, one lease; remembers whether the lease was released. */
    private class OneLeaseStore : LockStore {
        var released = false

        override suspend fun <T> tryHolding(
            key: Long,
            block: suspend () -> T,
        ): Attempt<T> = Attempt.Held(block())

        override suspend fun tryLease(
            key: Long,
            lease: Duration,
            holder: String,
        ): Lease =
            object : Lease {
                override val epoch = 1L
                override val previousReleasedCleanly = true

                override suspend fun renew() = Unit

                override suspend fun release() {
                    released = true
                }
            }
    }

    @Test
    fun theShutdownSequenceClosesTheElectionAndReleasesTheLease() =
        runTest {
            val store = OneLeaseStore()
            val timing =
                ElectionTiming(
                    serverLease = 3.seconds,
                    localLease = 1500.milliseconds,
                    renewEvery = 500.milliseconds,
                    pollEvery = 200.milliseconds,
                )
            val vojak = Vojak(store, Namespace.of("digest"), timeSource = testScheduler.timeSource)
            val election = vojak.elect(LockName.of("digest-sender"), "pod-a", backgroundScope, timing)
            testScheduler.runCurrent()
            assertIs<Leadership.Leader>(election.leadership.value)

            shutdownSequence(
                ShutdownDeadlines(preDrainWait = Duration.ZERO, drain = Duration.ZERO),
                testScheduler.timeSource,
            ) {
                election(election)
            }.run()

            assertEquals(Leadership.Closed, election.leadership.value)
            assertTrue(store.released, "the lease was given up")
        }
}
