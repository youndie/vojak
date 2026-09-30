package io.github.youndie.vojak

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The four durations an [Election] runs on (research D6).
 *
 * @param serverLease how long the store keeps a lease its holder has stopped renewing — enforced by
 *   Postgres itself, so it holds when the holder is partitioned or frozen.
 * @param localLease how long the holder keeps calling itself leader without a successful renewal.
 *   Shorter than [serverLease], so the holder steps down before anybody else can take over; the
 *   margin is what absorbs clock drift between the two, and it is assumed rather than enforced.
 * @param renewEvery how often the leader renews; shorter than [localLease], so one lost renewal does
 *   not cost the leadership.
 * @param pollEvery how often a follower tries to take the lease.
 */
public data class ElectionTiming(
    val serverLease: Duration = 15.seconds,
    val localLease: Duration = serverLease / 2,
    val renewEvery: Duration = serverLease / 3,
    val pollEvery: Duration = 2.seconds,
) {
    init {
        require(pollEvery.isPositive() && renewEvery.isPositive()) { "intervals must be positive: $this" }
        require(renewEvery < localLease) { "renewEvery must be shorter than localLease: $this" }
        require(localLease < serverLease) { "localLease must be shorter than serverLease: $this" }
    }
}
