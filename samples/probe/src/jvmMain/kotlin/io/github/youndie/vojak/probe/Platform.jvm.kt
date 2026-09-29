package io.github.youndie.vojak.probe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

actual val TARGET: String = "jvm"

actual fun <T> blocking(block: suspend CoroutineScope.() -> T): T = runBlocking(block = block)

actual fun sh(command: String): Int =
    ProcessBuilder("sh", "-c", command).inheritIO().start().waitFor()

actual fun env(name: String): String? = System.getenv(name)
