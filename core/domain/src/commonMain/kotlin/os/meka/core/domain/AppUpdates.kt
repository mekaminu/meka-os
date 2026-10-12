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

    /**
     * Appearance's build line (Meka, 2026-10-08, so we can tell whether the phone has the motion builds):
     * "MEKA 0.1.412 · build 412 · up to date", "… · update ready (build 415)", or just the build while the newest
     * published build isn't known yet ([latestCode] null: offline, or not checked).
     */
    fun versionLine(versionName: String, versionCode: Long, latestCode: Long?): String {
        val head = "MEKA $versionName · build $versionCode"
        return when {
            latestCode == null -> head
            latestCode > versionCode -> "$head · update ready (build $latestCode)"
            else -> "$head · up to date"
        }
    }

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

    /**
     * Where a kept download resumes: the whole chunks already held ([heldBytes] of a [sizeBytes] build in [chunkBytes]
     * chunks). A file longer than the build, or a build already fully held, starts over at 0 and is checked by hash
     * ([isWhole]) instead.
     */
    fun resumeFrom(heldBytes: Long, sizeBytes: Long, chunkBytes: Int): Int {
        if (heldBytes <= 0 || chunkBytes <= 0 || heldBytes >= sizeBytes) return 0
        return (heldBytes / chunkBytes).toInt()
    }

    /** A kept download is the whole build (worth hashing to reuse) when it is exactly [sizeBytes] long. */
    fun isWhole(heldBytes: Long, sizeBytes: Long): Boolean = sizeBytes > 0 && heldBytes == sizeBytes

    /** "Downloading · 40%". */
    fun progressLine(done: Int, total: Int): String = "Downloading · ${percent(done, total)}%"
}

/**
 * Why a phone update didn't go in (build plan: phone updates sometimes fail to install, Meka 2026-10-10 and
 * 2026-10-12). Kept on the Fold, shown under the card's line and in Activity and Health, and reported to MEKA's
 * server so a build run can read it. Nothing personal: the stage, Android's status and message, the two build numbers,
 * a free-storage band and the network type.
 */
data class UpdateFailure(
    /** [UpdateFailureRules.STAGE_DOWNLOAD], [UpdateFailureRules.STAGE_CHECK] or [UpdateFailureRules.STAGE_INSTALL]. */
    val stage: String,
    /** Android's install status (PackageInstaller.STATUS_*), install stage only. */
    val status: Int? = null,
    /** Android's own words (EXTRA_STATUS_MESSAGE) or the download's error, cleaned ([UpdateFailureRules.cleanMessage]). */
    val message: String? = null,
    val fromCode: Long,
    val toCode: Long,
    val atMs: Long,
    /** [UpdateFailureRules.storageBand]. */
    val storage: String? = null,
    /** "wifi", "mobile", "other" or "none". */
    val network: String? = null,
    /** Download attempts made (1 when it failed after the download). */
    val attempts: Int = 1,
)

object UpdateFailureRules {
    const val STAGE_DOWNLOAD = "download"
    const val STAGE_CHECK = "check"
    const val STAGE_INSTALL = "install"
    val STAGES = setOf(STAGE_DOWNLOAD, STAGE_CHECK, STAGE_INSTALL)

    // Android's PackageInstaller statuses (the Fold's test checks these match the platform's).
    const val STATUS_PENDING_USER_ACTION = -1
    const val STATUS_SUCCESS = 0
    const val STATUS_FAILURE = 1
    const val STATUS_FAILURE_BLOCKED = 2
    const val STATUS_FAILURE_ABORTED = 3
    const val STATUS_FAILURE_INVALID = 4
    const val STATUS_FAILURE_CONFLICT = 5
    const val STATUS_FAILURE_STORAGE = 6
    const val STATUS_FAILURE_INCOMPATIBLE = 7
    const val STATUS_FAILURE_TIMEOUT = 8

    /** A broken download is tried this many times in all before the card says it failed. */
    const val DOWNLOAD_ATTEMPTS = 3
    /** Longest message kept or sent. */
    const val MESSAGE_MAX = 300

    /** The wait before download attempt [nextAttempt] (2, 3…): 2 s, then 10 s, then 30 s. */
    fun retryDelayMs(nextAttempt: Int): Long = when {
        nextAttempt <= 2 -> 2_000L
        nextAttempt == 3 -> 10_000L
        else -> 30_000L
    }

    /** Whether a download that failed on attempt [attempt] (1-based) is tried again. */
    fun retries(attempt: Int): Boolean = attempt < DOWNLOAD_ATTEMPTS

    /**
     * An install status that isn't a failure for Meka to read about: success, the prompt (Android's own step), and
     * ABORTED (he dismissed Android's prompt, or it was closed under him): the card goes back to Install.
     */
    fun isFailure(status: Int): Boolean =
        status != STATUS_SUCCESS && status != STATUS_PENDING_USER_ACTION && status != STATUS_FAILURE_ABORTED

    /** "STATUS_FAILURE_CONFLICT" for status 5; "status 42" for one this build doesn't know. */
    fun statusName(status: Int): String = when (status) {
        STATUS_PENDING_USER_ACTION -> "STATUS_PENDING_USER_ACTION"
        STATUS_SUCCESS -> "STATUS_SUCCESS"
        STATUS_FAILURE -> "STATUS_FAILURE"
        STATUS_FAILURE_BLOCKED -> "STATUS_FAILURE_BLOCKED"
        STATUS_FAILURE_ABORTED -> "STATUS_FAILURE_ABORTED"
        STATUS_FAILURE_INVALID -> "STATUS_FAILURE_INVALID"
        STATUS_FAILURE_CONFLICT -> "STATUS_FAILURE_CONFLICT"
        STATUS_FAILURE_STORAGE -> "STATUS_FAILURE_STORAGE"
        STATUS_FAILURE_INCOMPATIBLE -> "STATUS_FAILURE_INCOMPATIBLE"
        STATUS_FAILURE_TIMEOUT -> "STATUS_FAILURE_TIMEOUT"
        else -> "status $status"
    }

    /** One line, no control characters, at most [MESSAGE_MAX] characters; null when there's nothing to say. */
    fun cleanMessage(raw: String?): String? {
        if (raw == null) return null
        val flat = buildString {
            var space = false
            for (c in raw) {
                if (c.isWhitespace() || c.isISOControl()) { space = true; continue }
                if (space && isNotEmpty()) append(' ')
                space = false
                append(c)
            }
        }
        if (flat.isEmpty()) return null
        return if (flat.length > MESSAGE_MAX) flat.take(MESSAGE_MAX - 1).trimEnd() + "…" else flat
    }

    /** The card's line: what went wrong in Meka's words. */
    fun line(f: UpdateFailure): String = when (f.stage) {
        STAGE_DOWNLOAD -> if (f.attempts > 1) "Couldn't download the update (tried ${f.attempts} times). Check the connection and try again."
            else "Couldn't download the update. Check the connection and try again."
        STAGE_CHECK -> "The download didn't match the published build. Try again."
        else -> when (f.status) {
            STATUS_FAILURE_CONFLICT, STATUS_FAILURE_INCOMPATIBLE ->
                if (f.message?.contains("signature", ignoreCase = true) == true || f.status == STATUS_FAILURE_INCOMPATIBLE)
                    "Android refused it: it isn't signed like the MEKA you have (it must carry the debug key of the Mac that installed MEKA)."
                else "Android refused it: it conflicts with the MEKA you have."
            STATUS_FAILURE_STORAGE -> "Not enough space on the phone for the update."
            STATUS_FAILURE_BLOCKED -> "Android blocked the install."
            STATUS_FAILURE_INVALID -> "Android said the downloaded file isn't a valid app. Try again."
            STATUS_FAILURE_TIMEOUT -> "Android timed out installing it. Try again."
            else -> "Android couldn't install it. Try again."
        }
    }

    /** The smaller line under it: Android's own words ("Android: INSTALL_FAILED_… · STATUS_FAILURE_CONFLICT"). */
    fun detail(f: UpdateFailure): String? {
        val code = f.status?.let(::statusName)
        val msg = f.message
        return when {
            f.stage == STAGE_INSTALL && msg != null && code != null -> "Android: $msg · $code"
            f.stage == STAGE_INSTALL && code != null -> "Android: $code"
            msg != null -> msg
            else -> null
        }
    }

    /** Free space as a band, never the exact figure: "under 100 MB", "100 MB–1 GB", "1–5 GB", "over 5 GB". */
    fun storageBand(freeBytes: Long?): String? = when {
        freeBytes == null || freeBytes < 0 -> null
        freeBytes < 100_000_000L -> "under 100 MB"
        freeBytes < 1_000_000_000L -> "100 MB–1 GB"
        freeBytes < 5_000_000_000L -> "1–5 GB"
        else -> "over 5 GB"
    }

    /** Activity's summary: "Build 430 didn't install". */
    fun activitySummary(f: UpdateFailure): String = when (f.stage) {
        STAGE_DOWNLOAD -> "Build ${f.toCode} didn't download"
        else -> "Build ${f.toCode} didn't install"
    }

    /** Activity's why: the line, then Android's words. */
    fun activityWhy(f: UpdateFailure): String = listOfNotNull(line(f), detail(f)).joinToString(" · ")

    /** A stable Activity id per failure (one entry per failure, however often it is written). */
    fun activityId(f: UpdateFailure): String = "u" + ActivityRules.fnv64("update-failure:${f.toCode}:${f.atMs}")

    /**
     * One line for a build run reading the server's reports (the publish job prints them): "build 425 → 430 · install ·
     * STATUS_FAILURE_CONFLICT · INSTALL_FAILED_… · storage over 5 GB · wifi · 1 attempt".
     */
    fun reportLine(f: UpdateFailure): String = listOfNotNull(
        "build ${f.fromCode} → ${f.toCode}",
        f.stage,
        f.status?.let(::statusName),
        f.message,
        f.storage?.let { "storage $it" },
        f.network,
        if (f.attempts == 1) "1 attempt" else "${f.attempts} attempts",
    ).joinToString(" · ")

    /**
     * Health's Updates row while the failed build is still not installed ([installedCode] below it): "Build 430 didn't
     * install 06:15 · Android: … ". Null once that build (or a newer one) is in.
     */
    fun healthLine(f: UpdateFailure?, installedCode: Long, at: String): String? {
        if (f == null || f.toCode <= installedCode) return null
        val what = if (f.stage == STAGE_DOWNLOAD) "didn't download" else "didn't install"
        return listOfNotNull("Build ${f.toCode} $what $at", detail(f) ?: line(f)).joinToString(" · ")
    }
}
