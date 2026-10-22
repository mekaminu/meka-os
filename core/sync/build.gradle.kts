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

// The nightly Mac build: each native test is logged as it starts, so a test that hangs is named in the watched step's
// annotation (tools/ci-run.sh CI_RUN_WATCH_MIN; run 1087 ran past the job's limit with nothing to read).
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    testLogging { events("started", "failed") }
}
