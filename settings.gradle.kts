rootProject.name = "meka-os"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Downloads the JDK 21 toolchain on machines that only have a newer JDK (e.g. Android Studio's bundled JBR).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Correctness kernel: stdlib-only commonMain (ADR-011), verified offline by tools/core-verify.sh.
include(":core:sync", ":core:domain", ":core:policy", ":core:testing")
// Wire format: kotlinx-serialization JSON tree API only.
include(":core:wire")
// Platform-facing core: SQLDelight/SQLCipher store, Ktor transport, facade exported to Swift.
include(":core:data", ":core:facade")

include(":android:app")
// MEKA on the Galaxy Watch (build plan "Galaxy Watch", slice 2): its own device, its own replica.
include(":wear:app")
include(":backend")
