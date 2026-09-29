package io.github.youndie.vojak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Literal vectors, checked in, and the same file runs on every target: a replica built for the JVM
 * and one built for Kotlin/Native must take the same key, or both lead and nothing says so.
 *
 * The first three are the published FNV-1a 64 reference values; the rest were computed outside
 * Kotlin, so a mistake shared by the implementation and its test cannot hide here.
 */
class LockNameTest {
    @Test
    fun fnv1a64MatchesTheReferenceVectors() {
        assertEquals(0xcbf29ce484222325uL.toLong(), fnv1a64(""))
        assertEquals(0xaf63dc4c8601ec8cuL.toLong(), fnv1a64("a"))
        assertEquals(0x85944171f73967e8uL.toLong(), fnv1a64("foobar"))
    }

    @Test
    fun theKeyIsTheHashOfNamespaceColonName() {
        val digest = Namespace.of("digest")
        assertEquals(5998082547337431625L, LockName.of("digest-sender").advisoryKey(digest))
        assertEquals(1076973899422214654L, LockName.of("migrate").advisoryKey(digest))
        assertEquals(-4738480849198095764L, LockName.of("ledger").advisoryKey(digest))
    }

    @Test
    fun theNamespaceSeparatesServicesThatChoseTheSameName() {
        val name = LockName.of("migrate")
        assertNotEquals(name.advisoryKey(Namespace.of("digest")), name.advisoryKey(Namespace.of("billing")))
    }

    @Test
    fun acceptsTheWholeAlphabetAtBothLengthBounds() {
        LockName.of("a")
        LockName.of("0-9-a-z")
        LockName.of("x".repeat(63))
        Namespace.of("x".repeat(63))
    }

    @Test
    fun refusesWhatWouldBreakTheKeyOrALogLine() {
        for (bad in listOf("", "x".repeat(64), "Upper", "under_score", "a:b", "sp ace", "ünïcode")) {
            assertFailsWith<IllegalArgumentException>(bad) { LockName.of(bad) }
            assertFailsWith<IllegalArgumentException>(bad) { Namespace.of(bad) }
        }
    }
}
