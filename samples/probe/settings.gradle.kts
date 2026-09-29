// A throwaway build, deliberately outside the library's (B-02 does not exist yet, and this probe is
// what decides its shape). Deleted or kept as evidence once B-01 closes — see its findings.
rootProject.name = "probe"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // B-13: the patched driver, built from the fork and published to the local repository of the
        // box that runs the probe. Only `-yio` versions come from here, so a release never does.
        mavenLocal {
            content {
                includeVersionByRegex("io\\.github\\.smyrgeorge", "sqlx4k-postgres.*", ".*-yio")
            }
        }
    }
}
