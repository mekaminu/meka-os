plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    application
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("os.meka.backend.ApplicationKt")
}

ktor {
    docker {
        jreVersion.set(JavaVersion.VERSION_21)
        localImageName.set("meka-os-sync")
    }
}

dependencies {
    implementation(project(":core:sync"))
    implementation(project(":core:wire"))
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.awssdk.bom))
    implementation(libs.awssdk.kms)
    implementation(libs.awssdk.secretsmanager)
    implementation(libs.awssdk.polly)
    implementation(libs.awssdk.s3)
    implementation(libs.awssdk.transcribe)
    implementation(libs.awssdk.urlconnection)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.postgres)
    implementation(libs.hikari)
    implementation(libs.logback)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

tasks.test {
    // CI provides a Postgres service container; locally the Postgres test self-skips when unset.
    environment("MEKA_TEST_DB_URL", System.getenv("MEKA_TEST_DB_URL") ?: "")
}
