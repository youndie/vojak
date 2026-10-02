// The reference consumer (B-11). Not published, not part of the library: the shape a service author
// copies, and the binary `stale-write.sh` partitions.
plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(21)

    linuxX64 {
        binaries.executable {
            entryPoint = "io.github.youndie.vojak.digest.main"
        }
    }

    sourceSets {
        linuxX64Main.dependencies {
            implementation(project(":vojak-postgres"))
            implementation(project(":vojak-kore"))
            implementation(libs.sqlx4k.postgres.yio)
            implementation(libs.kore.ktor)
            implementation(libs.ktor.server.cio)
        }
    }
}
