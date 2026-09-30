// A throwaway build, deliberately outside the library's (B-02 does not exist yet, and this probe is
// what decides its shape). Deleted or kept as evidence once B-01 closes — see its findings.
rootProject.name = "probe"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // B-13: the patched driver, published from the fork `youndie/sqlx4k` to the portfolio's
        // reposilite under upstream's coordinates. Only `-yio` versions come from here, so a release
        // never does.
        maven("https://reposilite.kotlin.website/snapshots") {
            content {
                includeVersionByRegex("io\\.github\\.smyrgeorge", "sqlx4k-postgres.*", ".*-yio")
            }
        }
    }
}
