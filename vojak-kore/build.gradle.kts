import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

kotlin {
    // kore-core's JVM variant is built for Java 25 (class file 69) without saying so in its metadata
    // (no `org.gradle.jvm.version`) — found in B-10, where a JVM 21 test resolved it and failed with
    // UnsupportedClassVersionError. This module cannot run below 25 either, so it compiles for 25 and
    // says so below; the repository's floor (21) stays for the modules that do not depend on kore.
    jvmToolchain(25)
    jvm()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":vojak-core"))
                api(libs.kore.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// The floor this module really has, in the published metadata: sborka stamps `sborka.jvmFloor` (21)
// on every module, and a per-module gradle.properties is not read, so it is set here after it.
afterEvaluate {
    listOf("jvmApiElements", "jvmRuntimeElements").forEach { name ->
        configurations.named(name) { attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25) }
    }
}
