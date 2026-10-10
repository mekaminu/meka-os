plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // AGP 9 provides built-in Kotlin support for Android modules; no separate kotlin-android plugin.
}

// MEKA on the Galaxy Watch (build plan "Galaxy Watch", slice 2; ADR-005 amendment 2026-10-10): a device of its own
// with its own Keystore key and SQLCipher replica, synced with MEKA's server directly (Wi-Fi/LTE, or the phone's
// Bluetooth link as the watch's network). Same application id as the phone's app, so Wear OS pairs them as one app;
// debug builds from the same Mac share its debug signing key. Version codes follow the phone's scheme.
val mekaVersionCode: Int = providers.gradleProperty("meka.versionCode").map { it.toInt() }.orNull
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().toInt()
    }.getOrDefault(1)

android {
    namespace = "os.meka.wear"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "os.meka.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = mekaVersionCode
        versionName = "0.1.$mekaVersionCode"
        // MEKA's server, as for the phone (tools/install-watch.sh passes it); no secrets in the binary (ADR-005).
        buildConfigField("String", "SYNC_URL", "\"${providers.gradleProperty("meka.syncUrl").getOrElse("")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release signing config is supplied from outside the repo (ADR-010), the same key as the phone's.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        error += listOf("ProtectedPermissions")
        warningsAsErrors = false
    }
}

dependencies {
    implementation(project(":core:facade"))
    implementation(project(":core:data"))
    // The replica's driver is closed when the watch is unlinked (core:data keeps SQLDelight to itself).
    implementation(libs.sqldelight.runtime)

    // Plain Compose (the BOM the phone uses): the watch's one round screen needs no Material.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.okhttp)

    // Galaxy Watch, slice 3: the tile, the complication and a quarter-hourly background sync that keeps them current.
    implementation(libs.androidx.work.runtime)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.complications.data.source)
    implementation(libs.androidx.concurrent.futures)

}
