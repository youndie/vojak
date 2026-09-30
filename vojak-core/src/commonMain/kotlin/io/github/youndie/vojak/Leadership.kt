package io.github.youndie.vojak

/** Where this process stands in one [Election]. A closed set: nothing else is ever emitted. */
public sealed interface Leadership {
    /** Another process leads, or nobody does and this one has not taken it yet. */
    public data object Follower : Leadership

    /**
     * This process holds the lease and has renewed it within `localLease`. It stops being true on
     * a timer, not when a renewal returns (research D6).
     */
    public data object Leader : Leadership

    /** [Election.close] has run. Final. */
    public data object Closed : Leadership
}
