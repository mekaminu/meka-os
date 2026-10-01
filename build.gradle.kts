plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.skie) apply false
    alias(libs.plugins.ktor) apply false
}

// Guard for ADR-011: the kernel's commonMain must stay stdlib-only so tools/core-verify.sh keeps verifying it offline.
tasks.register("checkKernelPurity") {
    group = "verification"
    description = "Fails if core/{sync,domain,policy} commonMain imports anything outside kotlin.* and os.meka.*"
    val roots = listOf("core/sync", "core/domain", "core/policy").map { layout.projectDirectory.dir("$it/src/commonMain") }
    inputs.files(roots.map { it.asFileTree })
    doLast {
        val bad = roots.flatMap { it.asFileTree.files }.filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().filter { it.startsWith("import ") && !it.startsWith("import kotlin.") && !it.startsWith("import os.meka.") }
                .map { "${f.name}: $it" }
        }
        check(bad.isEmpty()) { "Kernel must be stdlib-only:\n" + bad.joinToString("\n") }
    }
}
