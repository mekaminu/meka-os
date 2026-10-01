import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.skie)
}

kotlin {
    jvmToolchain(21)
    jvm() // host target so the facade's tests run on Linux CI
    androidLibrary {
        namespace = "os.meka.core.facade"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    val xcf = XCFramework("MekaCore")
    macosArm64 {
        binaries.framework {
            baseName = "MekaCore"
            isStatic = true
            export(project(":core:domain"))
            export(project(":core:sync"))
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:domain"))
            api(project(":core:sync"))
            api(project(":core:policy"))
            implementation(project(":core:wire"))
            implementation(project(":core:data"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":core:testing"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        macosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
// SKIE defaults give: Flow → AsyncSequence, suspend → async throws, sealed classes → onEnum(of:) exhaustive switch.
