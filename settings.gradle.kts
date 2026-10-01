rootProject.name = "meka-os"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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
include(":backend")
