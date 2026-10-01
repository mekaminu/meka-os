plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(21)
    jvm() // test target: runs ReplicaStoreContract against real SQLite via the JDBC driver
    macosArm64()
    // AGP 9 KMP Android library target. DSL name to confirm on first CI run (unverified: `androidLibrary` vs `android`).
    androidLibrary {
        namespace = "os.meka.core.data"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:sync"))
            implementation(libs.sqldelight.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":core:testing"))
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sqlcipher.android)
            implementation(libs.androidx.sqlite)
        }
        macosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
    }
}

sqldelight {
    databases {
        create("MekaDatabase") {
            packageName.set("os.meka.core.data")
            // verifyMigrations is enabled with the first .sqm migration (there are none before the first release).
        }
    }
    // Spike S6 (ADR-002): link SQLCipher instead of system sqlite on Apple. Until proven, linkSqlite stays true.
    linkSqlite.set(true)
}
