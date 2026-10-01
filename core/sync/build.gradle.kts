plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Kernel module (ADR-011): targets the JVM (Android app + backend consume the JVM artifact) and macOS (Swift app).
kotlin {
    jvmToolchain(21)
    jvm()
    macosArm64()

    sourceSets {
        commonMain.dependencies {

        }
        commonTest.dependencies {
            implementation(kotlin("test"))

        }
    }
}
