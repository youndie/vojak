package io.github.youndie.vojak.postgres

// Research §1.5: every call on a dead connection is a failed Result on the JVM.
internal actual val ROLLBACK_AFTER_CONNECTION_LOSS_IS_SAFE: Boolean = true
