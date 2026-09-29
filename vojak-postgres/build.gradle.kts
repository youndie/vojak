import java.time.Duration

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

kotlin {
    jvm()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":vojak-core"))
                // The plain artefact: the caller brings the driver (research D3).
                api(libs.sqlx4k.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                // The tests are an application, so they bring the one driver — and only the tests.
                implementation(libs.sqlx4k.postgres)
            }
        }
    }
}

// THESE TESTS HAVE AN INPUT GRADLE CANNOT SEE: the database. Left to the defaults, a second
// `make build` with Postgres stopped reported the JVM suite UP-TO-DATE — green over a database that
// was not there (found in B-02 by stopping it on purpose). So a test task here is never up to date
// and never taken from the build cache.
//
// AND A TEST RUN HAS A DEADLINE. On linuxX64 a coroutine suspended in the driver cannot be
// cancelled (research §1.5), so `runTest`'s own timeout does not end a stuck test there: a mutant in
// B-04 left the native test binary hanging for 29 minutes. Ten minutes is several times a green run.
tasks.withType<AbstractTestTask>().configureEach {
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    timeout.set(Duration.ofMinutes(10))
}
