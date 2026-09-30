package io.github.youndie.vojak

import kotlin.time.Duration

/** `withLock` waited [waited] for [name] and did not get it; the block did not run. */
public class LockTimeoutException(
    public val name: LockName,
    public val waited: Duration,
) : RuntimeException("lock '$name' was not acquired within $waited")

/**
 * `withLock` or `tryWithLock` on [name] from inside a block already holding [name].
 *
 * Refused rather than passed to Postgres: a nested call lands on a second pool connection — a
 * second session — and would wait for the lock its own caller holds (research Consequence 2).
 */
public class ReentrantLockException(
    public val name: LockName,
) : IllegalStateException("lock '$name' is already held by this coroutine; nested acquisition would deadlock")

/**
 * The block ran to completion, but when it ended the lock's session could not be confirmed — the
 * server may have ended it (a restart, `idle_in_transaction_session_timeout`, an administrator) while
 * the block ran. The work is done; that it was exclusive is not guaranteed.
 */
public class LockLostException(
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause)

/**
 * A second [Election] for [name] from the same [Vojak] while the first is open.
 *
 * Refused rather than passed to Postgres: the second would campaign on its own session and either
 * wait for the first for ever or, sharing a connection, silently "win" by re-entrancy — in both
 * cases the process would hold two views of one leadership (research Consequence 2).
 */
public class DuplicateElectionException(
    public val name: LockName,
) : IllegalStateException("an election for '$name' is already open on this Vojak; close it before opening another")
