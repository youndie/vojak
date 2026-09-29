import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("multiplatform") version "2.4.20"
}

kotlin {
    jvmToolchain(25)

    jvm {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        mainRun {
            mainClass.set("io.github.youndie.vojak.probe.MainKt")
        }
    }

    linuxX64 {
        binaries.executable {
            entryPoint = "io.github.youndie.vojak.probe.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            // The driver, not the plain artefact: a probe is an application, and it is the driver's
            // behaviour on each target that is being measured.
            // -Psqlx4k=<version> swaps the driver: 1.13.1 is the release the probe first measured,
            // `1.13.1.1-yio` the fork's patch (B-13). The control is always the release.
            implementation("io.github.smyrgeorge:sqlx4k-postgres:${providers.gradleProperty("sqlx4k").getOrElse("1.13.1")}")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
    }
}
