package io.github.youndie.vojak.kore

import io.github.youndie.kore.lifecycle.ShutdownParticipant
import io.github.youndie.kore.lifecycle.ShutdownPlanBuilder
import io.github.youndie.vojak.Election

/**
 * Closes [election] during kore's shutdown, so a rolling update hands leadership over within one
 * poll instead of after the lease: [Election.close] cancels the `whileLeader` block, waits for it,
 * and releases the lease **cleanly** — the successor skips the wait of research D12.
 *
 * A consumer, not a pool: kore releases consumers before pools, and releasing the lease needs a
 * connection from the very pool the pool group closes. Register it with [election] rather than by
 * hand so the group cannot be chosen wrong.
 */
public class ElectionParticipant(
    private val election: Election,
) : ShutdownParticipant {
    override val name: String = "vojak election '${election.name}'"

    override suspend fun stop() {
        election.close()
    }
}

/** Registers [election] in kore's consumer group — see [ElectionParticipant]. */
public fun ShutdownPlanBuilder.election(election: Election) {
    consumer(ElectionParticipant(election))
}
