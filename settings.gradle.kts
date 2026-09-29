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

// One kore shutdown participant. Published only where kore is (research D8).
include(":vojak-kore")

// NOT included: samples/probe. It is the B-01 probe — a separate build on purpose, since it has to
// carry a driver and this build's modules must not.
