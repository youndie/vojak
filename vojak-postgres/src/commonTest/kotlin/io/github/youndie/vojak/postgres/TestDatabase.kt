package io.github.youndie.vojak.postgres

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.postgres.postgreSQL

/**
 * The Postgres every test in this module talks to: `make postgres` starts it, CI runs the same
 * target. `VOJAK_TEST_POSTGRES_URL` overrides the address.
 *
 * There is deliberately no "skip when absent": a suite that skips its database is green over
 * nothing, and the default address is one that fails loudly when `make postgres` was not run.
 */
internal object TestDatabase {
    val url: String = environment("VOJAK_TEST_POSTGRES_URL") ?: "postgresql://127.0.0.1:55440/postgres"

    fun driver(): Driver = postgreSQL(url = url, username = "postgres", password = "vojak")
}

internal expect fun environment(name: String): String?
