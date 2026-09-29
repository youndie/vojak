package io.github.youndie.vojak

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Every [Vojak] here runs on the test scheduler's clock. One that did not — the real clock under
 * virtual `delay` — turned a broken re-entry check into 30 real seconds of attempts and an
 * OutOfMemoryError instead of a red test (B-04, mutation M2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VojakTest {
    private val migrate = LockName.of("migrate")
    private val ledger = LockName.of("ledger")
    private val digest = Namespace.of("digest")

    @Test
    fun withLockReturnsTheBlocksValue() =
        runTest {
            val vojak =
                Vojak(FakeLockStore { testScheduler.currentTime }, digest, timeSource = testScheduler.timeSource)
            assertEquals(42, vojak.withLock(migrate) { 42 })
        }

    @Test
    fun withLockGivesUpAfterWaitAtMostOnTheInjectedClock() =
        runTest {
            val store = FakeLockStore { testScheduler.currentTime }
            store.heldElsewhere += migrate.advisoryKey(digest)
            val vojak = Vojak(store, digest, Backoff(100.milliseconds, 400.milliseconds), testScheduler.timeSource)

            val failure = assertFailsWith<LockTimeoutException> { vojak.withLock(migrate, 1.seconds) { error("ran") } }

            assertEquals(migrate, failure.name)
            assertEquals(1000L, testScheduler.currentTime)
            // 0, then +100, +200, +400, +400 (capped), and the last pause is cut to what is left.
            assertEquals(listOf(0L, 100L, 300L, 700L, 1000L), store.attempts)
        }

    @Test
    fun withLockTakesTheLockOnceTheOtherHolderLetsGo() =
        runTest {
            val store = FakeLockStore { testScheduler.currentTime }
            val key = migrate.advisoryKey(digest)
            store.heldElsewhere += key
            val vojak = Vojak(store, digest, Backoff(100.milliseconds, 100.milliseconds), testScheduler.timeSource)

            val result = async { vojak.withLock(migrate, 5.seconds) { testScheduler.currentTime } }
            testScheduler.advanceTimeBy(250)
            store.heldElsewhere -= key

            assertEquals(300L, result.await())
        }

    @Test
    fun tryWithLockReturnsNullWithoutRunningTheBlockWhenBusy() =
        runTest {
            val store = FakeLockStore { testScheduler.currentTime }
            store.heldElsewhere += migrate.advisoryKey(digest)
            var ran = false

            assertNull(Vojak(store, digest, timeSource = testScheduler.timeSource).tryWithLock(migrate) { ran = true })
            assertEquals(false, ran)
            assertEquals(1, store.attempts.size)
        }

    @Test
    fun nestingTheSameNameIsRefusedInsteadOfDeadlocking() =
        runTest {
            val vojak =
                Vojak(FakeLockStore { testScheduler.currentTime }, digest, timeSource = testScheduler.timeSource)

            assertFailsWith<ReentrantLockException> { vojak.withLock(migrate) { vojak.withLock(migrate) { } } }
            assertFailsWith<ReentrantLockException> { vojak.withLock(migrate) { vojak.tryWithLock(migrate) { 1 } } }
            // ...including from a child coroutine, which inherits the holder's context.
            assertFailsWith<ReentrantLockException> {
                vojak.withLock(migrate) { coroutineScope { async { vojak.withLock(migrate) { } }.await() } }
            }
        }

    @Test
    fun nestingAnotherNameIsAllowed() =
        runTest {
            val vojak =
                Vojak(FakeLockStore { testScheduler.currentTime }, digest, timeSource = testScheduler.timeSource)
            assertEquals(2, vojak.withLock(migrate) { vojak.withLock(ledger) { 2 } })
        }

    @Test
    fun aSiblingCoroutineWaitsRatherThanFailing() =
        runTest {
            val vojak =
                Vojak(
                    FakeLockStore {
                        testScheduler.currentTime
                    },
                    digest,
                    Backoff(10.milliseconds, 10.milliseconds),
                    testScheduler.timeSource,
                )
            val inside = CompletableDeferred<Unit>()
            val leave = CompletableDeferred<Unit>()
            val first =
                launch {
                    vojak.withLock(migrate) {
                        inside.complete(Unit)
                        leave.await()
                    }
                }
            inside.await()

            val second = async { vojak.withLock(migrate, 5.seconds) { "second" } }
            testScheduler.advanceTimeBy(50)
            assertTrue(second.isActive)
            leave.complete(Unit)

            assertEquals("second", second.await())
            first.join()
        }

    @Test
    fun acquireWithinRetriesTheAttemptUntilItSucceeds() =
        runTest {
            val vojak =
                Vojak(
                    FakeLockStore {
                        testScheduler.currentTime
                    },
                    digest,
                    Backoff(100.milliseconds, 100.milliseconds),
                    testScheduler.timeSource,
                )
            val tries = mutableListOf<Long>()

            vojak.acquireWithin(ledger, 5.seconds) { key ->
                tries += testScheduler.currentTime
                assertEquals(ledger.advisoryKey(digest), key)
                tries.size == 3
            }

            assertEquals(listOf(0L, 100L, 200L), tries)
        }

    @Test
    fun acquireWithinGivesUpAfterWaitAtMost() =
        runTest {
            val vojak =
                Vojak(FakeLockStore { testScheduler.currentTime }, digest, timeSource = testScheduler.timeSource)

            assertFailsWith<LockTimeoutException> { vojak.acquireWithin(ledger, 1.seconds) { false } }
            assertEquals(1000L, testScheduler.currentTime)
        }

    @Test
    fun acquireWithinIsRefusedInsideABlockHoldingTheSameName() =
        runTest {
            val vojak =
                Vojak(FakeLockStore { testScheduler.currentTime }, digest, timeSource = testScheduler.timeSource)

            assertFailsWith<ReentrantLockException> {
                vojak.withLock(
                    ledger,
                ) { vojak.acquireWithin(ledger, 1.seconds) { true } }
            }
        }
}
