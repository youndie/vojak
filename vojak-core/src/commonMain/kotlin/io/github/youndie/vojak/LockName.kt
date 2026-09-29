package io.github.youndie.vojak

import kotlin.jvm.JvmInline

/**
 * The name of a lock or of an elected role — `digest-sender`, `migrate`.
 *
 * `[a-z0-9-]{1,63}`, checked when the name is made: short enough for `application_name` and a log
 * line, and **without `:`**, which is what keeps `"<namespace>:<name>"` unambiguous — with a colon
 * allowed, `a` + `b:c` and `a:b` + `c` would be one key.
 */
@JvmInline
public value class LockName private constructor(
    public val value: String,
) {
    override fun toString(): String = value

    public companion object {
        /** @throws IllegalArgumentException when [value] is not `[a-z0-9-]{1,63}`. */
        public fun of(value: String): LockName = LockName(requireName(value, "lock name"))
    }
}

/**
 * What a service puts in front of every name it uses, so that two services sharing one database
 * cannot take each other's locks by choosing the same word. Same rules as [LockName].
 */
@JvmInline
public value class Namespace private constructor(
    public val value: String,
) {
    override fun toString(): String = value

    public companion object {
        /** @throws IllegalArgumentException when [value] is not `[a-z0-9-]{1,63}`. */
        public fun of(value: String): Namespace = Namespace(requireName(value, "namespace"))
    }
}

/**
 * The 64-bit Postgres advisory-lock key for this name under [namespace]: FNV-1a 64 of the UTF-8
 * bytes of `"<namespace>:<name>"`, as a signed `Long` (research D4).
 *
 * Computed in Kotlin, not by the server, so that a JVM replica and a native one of the same service
 * take the same key by construction; the vectors in the tests are what hold that down. In
 * `pg_locks` the key shows as `classid` (high 32 bits) and `objid` (low 32 bits) with `objsubid = 1`.
 *
 * Changing this function changes every key and silently splits a running fleet into two that do not
 * see each other's locks — it is part of the wire contract, not an implementation detail.
 */
public fun LockName.advisoryKey(namespace: Namespace): Long = fnv1a64("${namespace.value}:$value")

internal fun fnv1a64(text: String): Long {
    var hash = FNV_OFFSET_BASIS
    for (byte in text.encodeToByteArray()) {
        hash = (hash xor byte.toUByte().toULong()) * FNV_PRIME
    }
    return hash.toLong()
}

private const val FNV_OFFSET_BASIS: ULong = 0xcbf29ce484222325uL
private const val FNV_PRIME: ULong = 0x100000001b3uL

private val NAME = Regex("[a-z0-9-]{1,63}")

private fun requireName(
    value: String,
    what: String,
): String {
    require(NAME.matches(value)) { "a $what must match [a-z0-9-]{1,63}, got '$value'" }
    return value
}
