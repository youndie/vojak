rootProject.name = "vojak"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        // Written out by hand, and it has to be: `pluginManagement` is evaluated before any settings
        // plugin is applied — including the sborka one, which is fetched through it.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content {
                includeGroupByRegex("io\\.github\\.youndie.*")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Repositories with content filters, the shared `wip` catalog (which is where the compiler
    // version comes from), and the shared publishing contract.
    id("io.github.youndie.sborka.settings") version "0.4.0.93"
}

// The public API and everything that does not speak SQL: names and keys, the election and its
// local lease, the lock API. No database, no clock of its own (research D6, services/vojak-core).
include(":vojak-core")

// The only module that knows SQL. Depends on the plain sqlx4k artefact and takes the caller's
// Driver — never a driver artefact, because two in one native binary do not link (research D3).
include(":vojak-postgres")
include(":vojak-jdbc")

// One kore shutdown participant. Published only where kore is (research D8).
include(":vojak-kore")

// The fault suite's replica (B-09): a native binary the suite kills, partitions and freezes. An
// application, so it brings the one driver — the patched build, from the portfolio's reposilite.
include(":samples:two-replicas")

// The reference consumer (B-11): what a service author copies — Ktor CIO, kore, a `whileLeader`
// poller and a table whose writes carry the epoch. Also the stand of the stale-write check.
include(":samples:digest-sender")

dependencyResolutionManagement {
    repositories {
        // Only the fork's `-yio` builds of sqlx4k-postgres come from here (research §1.5, B-13).
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "sqlx4k-yio"
            content {
                includeVersionByRegex("io\\.github\\.smyrgeorge", "sqlx4k-postgres.*", ".*-yio")
            }
        }
    }
}

// NOT included: samples/probe. It is the B-01 probe — a separate build on purpose, since it has to
// carry a driver and this build's modules must not.
