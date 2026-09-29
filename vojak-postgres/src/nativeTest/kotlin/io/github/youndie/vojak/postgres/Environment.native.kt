package io.github.youndie.vojak.postgres

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun environment(name: String): String? = getenv(name)?.toKString()
