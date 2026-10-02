import java.time.Duration

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

kotlin {
    // Research D7: every dependency publishes all three. The suites run on jvm and linuxX64;
    // linuxArm64 compiles here, and running its tests needs an arm64 host.
    jvm()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.coroutines.core)
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

// A TEST RUN HAS A DEADLINE. The core runs on virtual time, so a green run takes seconds; a broken
// step-down timer turns the wait into a loop that never suspends, which `runTest` cannot interrupt —
// in B-06 that mutant held the suite for the full 900 s of an outer timeout instead of failing.
tasks.withType<AbstractTestTask>().configureEach {
    timeout.set(Duration.ofMinutes(5))
}
