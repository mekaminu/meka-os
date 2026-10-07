package os.meka.core.domain

/**
 * Self-updating phone app (build plan M1). The Mac publishes a build it made; the phone offers it once it is newer
 * than what is installed and Meka hasn't said "Later" to that very build. Pure: what to offer and how to say it.
 * Installing is always Meka's tap on Android's own confirmation; Android refuses a build not signed with the
 * installed app's key.
 */
object AppUpdateRules {
    /** A published build as the phone sees it. */
    data class Build(val versionCode: Long, val versionName: String, val sizeBytes: Long)

    /** The build to offer, or null: only newer than [installedCode], and not the one Meka put off ([laterCode]). */
    fun offer(latest: Build?, installedCode: Long, laterCode: Long?): Build? =
        latest?.takeIf { it.versionCode > installedCode && it.versionCode != laterCode }

    const val TITLE = "MEKA update ready"

    /**
     * The quiet "MEKA update ready" notification (hands-free phone updates): at most once per build. It is posted
     * only while MEKA isn't on screen (the Today card says it there); a build that was offered while Meka was looking
     * counts as told ([toldCode] is the last build he was told about either way). Never for a build put off with Later.
     */
    fun shouldNotify(offered: Build?, toldCode: Long?, onScreen: Boolean): Boolean =
        offered != null && !onScreen && (toldCode == null || offered.versionCode > toldCode)

    /** The build Meka has now been told about: [offered]'s number, else what he was told before. */
    fun told(offered: Build?, toldCode: Long?): Long? =
        if (offered != null && (toldCode == null || offered.versionCode > toldCode)) offered.versionCode else toldCode

    /** The notification's line: "Build 412 · 24.3 MB · open MEKA to install". */
    fun notificationLine(b: Build): String = "${line(b)} · open MEKA to install"

    /** "Build 412 · 24.3 MB". */
    fun line(b: Build): String = "Build ${b.versionCode} · ${sizeLabel(b.sizeBytes)}"

    /** "MEKA 0.1.412 · build 412 · 24.3 MB" (what the Mac shows before publishing). */
    fun summary(b: Build): String = "MEKA ${b.versionName} · build ${b.versionCode} · ${sizeLabel(b.sizeBytes)}"

    /** Decimal megabytes, as Android and macOS show file sizes: "850 KB", "9.9 MB", "24.3 MB", "120 MB". */
    fun sizeLabel(bytes: Long): String = when {
        bytes < 999_500 -> "${maxOf(1, (bytes + 500) / 1000)} KB"
        bytes < 99_950_000 -> {
            val tenths = (bytes + 50_000) / 100_000
            "${tenths / 10}.${tenths % 10} MB"
        }
        else -> "${(bytes + 500_000) / 1_000_000} MB"
    }

    /** Whole percent done, never 100 until the last chunk is in. */
    fun percent(done: Int, total: Int): Int = when {
        total <= 0 -> 0
        done >= total -> 100
        else -> minOf(99, done * 100 / total)
    }

    /** "Downloading · 40%". */
    fun progressLine(done: Int, total: Int): String = "Downloading · ${percent(done, total)}%"
}
