// The fault suite's replica (B-09). Not published, not part of the library: a binary for
// `fault-suite.sh` to kill, partition and freeze.
plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(21)

    linuxX64 {
        binaries.executable {
            entryPoint = "io.github.youndie.vojak.faults.main"
        }
    }

    sourceSets {
        linuxX64Main.dependencies {
            implementation(project(":vojak-postgres"))
            implementation(libs.sqlx4k.postgres.yio)
        }
    }
}
