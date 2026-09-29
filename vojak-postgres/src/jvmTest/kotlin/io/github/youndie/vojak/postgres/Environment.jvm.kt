package io.github.youndie.vojak.postgres

internal actual fun environment(name: String): String? = System.getenv(name)
