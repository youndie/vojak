package io.github.youndie.vojak.jdbc

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.postgres.postgreSQL

/**
 * The same Postgres as vojak-postgres's suite (`make postgres`), reached the way a JDBC service
 * reaches it: a HikariCP pool. No "skip when absent" — see vojak-postgres's `TestDatabase`.
 */
internal object TestDatabase {
    private val hostAndDatabase: String =
        System.getenv("VOJAK_TEST_POSTGRES_URL")?.removePrefix("postgresql://") ?: "127.0.0.1:55440/postgres"

    /** One replica's pool: its own connections, so its own sessions — what Postgres sees of a pod. */
    fun pool(size: Int = 4): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:postgresql://$hostAndDatabase"
                username = "postgres"
                password = "vojak"
                maximumPoolSize = size
                // A test that leaks a connection should fail on the leak, not wait half a minute.
                connectionTimeout = 5_000
            },
        )

    /** An sqlx4k driver on the same database — the other kind of replica, for the interop tests. */
    fun sqlx4k(): Driver = postgreSQL(url = "postgresql://$hostAndDatabase", username = "postgres", password = "vojak")
}
