package io.github.youndie.vojak.probe

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.cinterop.toKString
import platform.posix.system

actual val TARGET: String = "linuxX64"

actual fun <T> blocking(block: suspend CoroutineScope.() -> T): T = runBlocking(block = block)

@OptIn(ExperimentalForeignApi::class)
actual fun sh(command: String): Int = system(command)

@OptIn(ExperimentalForeignApi::class)
actual fun env(name: String): String? = platform.posix.getenv(name)?.toKString()
