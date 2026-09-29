package io.github.youndie.vojak.postgres

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The gate's proof that the database is really there on every target the suite runs on. Stopping
 * the `make postgres` container turns this red — that is the point of it (B-02).
 */
class TestDatabaseTest {
    @Test
    fun theTestDatabaseAnswers() =
        runTest {
            val driver = TestDatabase.driver()
            try {
                val answer =
                    driver
                        .fetchAll("select 42::text")
                        .getOrThrow()
                        .first()
                        .get(0)
                        .asString()
                assertEquals("42", answer)
            } finally {
                driver.close()
            }
        }
}
