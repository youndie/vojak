package io.github.youndie.vojak.postgres

// Research §1.5: sqlx4k-postgres 1.13.1 panics on the second call to a dead connection. B-13.
internal actual val ROLLBACK_AFTER_CONNECTION_LOSS_IS_SAFE: Boolean = false
