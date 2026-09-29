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
tasks.withType<AbstractTestTask>().configureEach {
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
