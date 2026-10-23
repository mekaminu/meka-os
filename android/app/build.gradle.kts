plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // AGP 9 provides built-in Kotlin support for Android modules; no separate kotlin-android plugin.
}

// Self-updating phone app: every build gets a higher versionCode (the number of commits on the branch it was built
// from), so the Fold can tell a newer build the Mac published from the one it has, and Android accepts it as an update.
// -Pmeka.versionCode=N overrides it. CI's shallow clone counts 1, which is fine for checks.
val mekaVersionCode: Int = providers.gradleProperty("meka.versionCode").map { it.toInt() }.orNull
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().toInt()
    }.getOrDefault(1)

android {
    namespace = "os.meka.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "os.meka.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = mekaVersionCode
        versionName = "0.1.$mekaVersionCode"
        // Sync endpoint is configured per build; no secrets in the binary (ADR-005).
        buildConfigField("String", "SYNC_URL", "\"${providers.gradleProperty("meka.syncUrl").getOrElse("")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release signing config is supplied from outside the repo (ADR-010).
        }
        // The build GitHub publishes to the Fold (build plan: smaller phone updates; the debug build was ~55 MB).
        // Signed with the debug key like before, so it installs over the current app (same signature, higher
        // versionCode); R8 shrinks the code and resources, names are kept (-dontobfuscate in proguard-rules.pro) so
        // crash and failure reports stay readable, and only the Fold's ABI ships (-Pmeka.abi=x86_64 for the emulator
        // smoke test in fold-smoke.yml). No debug-only tooling (Compose preview, test manifest) goes in.
        create("fold") {
            initWith(getByName("debug"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk { abiFilters += providers.gradleProperty("meka.abi").getOrElse("arm64-v8a").split(',') }
            matchingFallbacks += listOf("release", "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // The quality gate's screenshots (Roborazzi on Robolectric, native graphics): the tests need the app's resources and
    // write the images (record mode) under build/outputs/roborazzi, which CI uploads as `fold-screens`.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                // The quality gate's shots (16 classes, ~150 screens on native graphics) outgrew the 512 MB default (CI run 1103).
                it.maxHeapSize = "3g"
                // A failed shot names what it found (the cut or nonsense lines) in the CI log, not only in the report.
                it.testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
            }
        }
    }

    lint {
        // Store-readiness guardrail (ADR-010): fail on USE_EXACT_ALARM or other policy-restricted permissions.
        error += listOf("ProtectedPermissions")
        warningsAsErrors = false
    }
}

dependencies {
    implementation(project(":core:facade"))
    implementation(project(":core:data"))
    implementation(project(":core:wire")) // Ada's Live mode: the Live session and protocol types

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.material3.adaptive)
    implementation(libs.androidx.window)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.health.connect.client)
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.okhttp)
    // Push via Firebase (build plan M1): FCM only, configured from res/values/firebase.xml (no google-services plugin).
    implementation(libs.firebase.messaging)
    // Reading the bay's sign off its photo (car and parking slice 4), on the phone: the bundled model, nothing downloaded
    // or sent (Needs Meka #23: yes to ML Kit on the Fold).
    implementation(libs.mlkit.text.recognition)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(kotlin("test-junit")) // JUnit4 binding for kotlin.test under AGP unit tests
    // Quality gate: screenshots of each screen on the Fold's two sizes, and the "no nonsense on screen" read.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.datetime)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
