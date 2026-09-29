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
                api(libs.kore.core)
            }
        }
    }
}
