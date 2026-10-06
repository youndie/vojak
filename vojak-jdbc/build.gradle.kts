import java.time.Duration

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

// THE JVM ONLY, and that is the module's reason to exist (research D13): a JVM service that already
// talks to Postgres through JDBC — a `DataSource`, usually HikariCP under Exposed or jOOQ — gets the
// same lock and the same election without a second driver and a second pool beside its own.
kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":vojak-core"))
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                // The tests are an application, so they bring the driver and the pool a consumer has.
                implementation(libs.postgresql)
                implementation(libs.hikari)
                // The interop tests: the same key and the same epoch table, taken through sqlx4k.
                implementation(project(":vojak-postgres"))
                implementation(libs.sqlx4k.postgres)
            }
        }
    }
}

// The same two rules as vojak-postgres: the database is an input Gradle cannot see, so a test task
// here is never up to date nor cached; and a run has a deadline, because a JDBC call blocked on a
// dead socket does not answer cancellation either.
tasks.withType<AbstractTestTask>().configureEach {
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    timeout.set(Duration.ofMinutes(10))
}
