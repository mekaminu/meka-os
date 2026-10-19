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

    const val TITLE = "Navilife update ready"

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
    fun notificationLine(b: Build): String = "${line(b)} · open Navilife to install"

    /** "Build 412 · 24.3 MB". */
    fun line(b: Build): String = "Build ${b.versionCode} · ${sizeLabel(b.sizeBytes)}"

    /** "MEKA 0.1.412 · build 412 · 24.3 MB" (what the Mac shows before publishing). */
    fun summary(b: Build): String = "Navilife ${b.versionName} · build ${b.versionCode} · ${sizeLabel(b.sizeBytes)}"

    /**
     * Appearance's build line (Meka, 2026-10-08, so we can tell whether the phone has the motion builds):
     * "MEKA 0.1.412 · build 412 · up to date", "… · update ready (build 415)", or just the build while the newest
     * published build isn't known yet ([latestCode] null: offline, or not checked).
     */
    fun versionLine(versionName: String, versionCode: Long, latestCode: Long?): String {
        val head = "Navilife $versionName · build $versionCode"
        return when {
            latestCode == null -> head
            latestCode > versionCode -> "$head · update ready (build $latestCode)"
            else -> "$head · up to date"
        }
    }

    /**
     * Activity's line the first time a build runs (build plan: smaller phone updates): "Updated to build 471 · 18.2 MB",
     * the installed app's own size, so Meka sees what each update costs on mobile data. [previousCode] is the build that
     * ran before (null on a fresh install or the first build that reports it): "Installed build 471 · 18.2 MB".
     */
    fun installedSummary(versionCode: Long, sizeBytes: Long, previousCode: Long?): String {
        val what = if (previousCode != null && previousCode < versionCode) "Updated to build" else "Installed build"
        return "$what $versionCode · ${sizeLabel(sizeBytes)}"
    }

    /** Activity's why for [installedSummary]: "From build 470 (24.9 MB) · 6.7 MB smaller", else the plain fact. */
    fun installedWhy(sizeBytes: Long, previousCode: Long?, previousSize: Long?): String {
        if (previousCode == null) return "Navilife takes ${sizeLabel(sizeBytes)} on this phone"
        val from = "From build $previousCode" + (previousSize?.takeIf { it > 0 }?.let { " (${sizeLabel(it)})" } ?: "")
        val change = previousSize?.takeIf { it > 0 }?.let { prev ->
            val d = sizeBytes - prev
            when {
                d <= -50_000 -> "${sizeLabel(-d)} smaller"
                d >= 50_000 -> "${sizeLabel(d)} bigger"
                else -> "same size"
            }
        }
        return listOfNotNull(from, change).joinToString(" · ")
    }

    /**
     * Health's Updates row (build plan: smaller phone updates): the installed app's size, and the build before's when
     * it differs by 50 KB or more: "10.3 MB · was 55.1 MB". Null when the size isn't known.
     */
    fun healthSize(sizeBytes: Long?, previousSize: Long?): String? {
        val size = sizeBytes?.takeIf { it > 0 } ?: return null
        val prev = previousSize?.takeIf { it > 0 && kotlin.math.abs(it - size) >= 50_000 }
        return sizeLabel(size) + (prev?.let { " · was ${sizeLabel(it)}" } ?: "")
    }

    /** True when [versionCode] hasn't been written to Activity yet ([recordedCode] is the last one that was). */
    fun shouldRecordInstall(versionCode: Long, recordedCode: Long?): Boolean = recordedCode == null || versionCode > recordedCode

    /**
     * The app icon MEKA currently ships (the rename to Navilife, Meka 2026-10-13: logo N3·2, the compass dial; was
     * "hands-m"). When it changes, the first run of an updated build says so once in Activity, since launchers often
     * keep the old icon until they restart.
     */
    const val ICON = "navilife-n3-2"

    /** The app's name as a person sees it (the rename, 2026-10-13); the code name stays `meka`. */
    const val APP_NAME = "Navilife"

    /** The tagline, for About and the splash. */
    const val TAGLINE = "Navilife — calm in the chaos."

    /**
     * True when Activity should say the icon changed: an update (the phone had recorded an earlier build, so not a
     * fresh install) and [notedIcon] — the icon last announced — isn't [ICON].
     */
    fun shouldNoteIcon(notedIcon: String?, hadEarlierBuild: Boolean): Boolean = hadEarlierBuild && notedIcon != ICON

    fun iconActivityId(icon: String = ICON): String = "u" + ActivityRules.fnv64("app-icon:$icon")

    const val ICON_SUMMARY = "Now called Navilife"
    const val ICON_WHY = "Calm in the chaos: a new name and a new icon, the compass dial. Your data and settings are " +
        "as they were, and \"MEKA\" still works when you talk to it. If the home screen still shows the old icon, " +
        "restart the phone (or the launcher) and it will catch up."

    /** One Activity entry per build. */
    fun installedActivityId(versionCode: Long): String = "u" + ActivityRules.fnv64("update-installed:$versionCode")

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
                    "Android refused it: it isn't signed like the Navilife you have (it must carry the debug key of the Mac that installed Navilife)."
                else "Android refused it: it conflicts with the Navilife you have."
            STATUS_FAILURE_STORAGE -> "Not enough space on the phone for the update."
            STATUS_FAILURE_BLOCKED -> "Android blocked the install."
            STATUS_FAILURE_INVALID -> "Android said the downloaded file isn't a valid app. Try again."
            STATUS_FAILURE_TIMEOUT -> "Android timed out installing it. Try again."
            else -> if (InstallSessionRules.isFilesInUse(f.message))
                "Android was still holding an earlier install. Try again (restarting the phone clears it if it keeps happening)."
                else "Android couldn't install it. Try again."
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

/**
 * Android's install sessions (build plan: phone updates fail with "Session files in use", Meka's Fold, 2026-10-13).
 * A stale session from an earlier attempt (or two installs at once: Today's Install and Health's Try again, or a retry
 * while the first was committing) held its file. So: one install at a time across the app, every own session but the
 * one with Android's prompt up is abandoned before a new one is made, each session writes under its own name, and a
 * "Session files in use" failure abandons them all and is tried again with a fresh session, by itself.
 *
 * Slice 3 (the 11:12 report: build 942, which had all of the above, still failed once): Android checks that every file
 * written into a session has been let go when the session is committed, and the write stream's close reaches it
 * asynchronously, so committing straight after closing races it. Each commit now waits [settleMs] after the write is
 * closed (longer each fresh session), up to [AUTO_RETRIES] fresh sessions are tried, and if Android still says the files
 * are in use the kept download is handed to Android's own installer ([handsToAndroidsInstaller]), which makes its own
 * session: the same one tap, and nothing Meka has to do. The report says how many sessions were tried.
 */
object InstallSessionRules {
    /** Android's words for the clash (INSTALL_FAILED_INTERNAL_ERROR: Session files in use). */
    const val FILES_IN_USE = "Session files in use"
    /** A committed session whose outcome hasn't come back is still Android's for this long (its prompt may be up). */
    const val OUTSTANDING_MS = 10 * 60_000L
    /** Fresh sessions tried by themselves after a "files in use" failure. */
    const val AUTO_RETRIES = 3
    /** The wait between closing the write and committing, per fresh session (the first tap is index 0). */
    val SETTLE_MS = listOf(1_000L, 2_500L, 5_000L, 8_000L)

    /** How long to let Android let go of the written file before committing, after [retriesSoFar] fresh sessions. */
    fun settleMs(retriesSoFar: Int): Long = SETTLE_MS[retriesSoFar.coerceIn(0, SETTLE_MS.lastIndex)]

    /**
     * After the fresh sessions are used up and Android still says the files are in use: hand the kept download to
     * Android's own installer instead of failing.
     */
    fun handsToAndroidsInstaller(status: Int?, message: String?, retriesSoFar: Int): Boolean =
        (status == null || status == UpdateFailureRules.STATUS_FAILURE) && isFilesInUse(message) && retriesSoFar >= AUTO_RETRIES

    /**
     * Android's words for the report, with the sessions tried ("… · 4 sessions tried · handed to Android's installer").
     * (Letting the session read the kept file itself isn't open to apps: `Session.write` with a file descriptor is a
     * system API, so run 915 couldn't compile it.)
     */
    fun reportMessage(message: String?, sessionsTried: Int, handedOver: Boolean): String? {
        if (message == null) return null
        val tried = if (sessionsTried > 1) " · $sessionsTried sessions tried" else ""
        return message + tried + (if (handedOver) " · $HANDED_OVER" else "")
    }

    /**
     * Whether a status Android sends belongs to the install in hand: only the session committed now counts. A late answer
     * from a session already abandoned ("Session destroyed"), or one arriving after the build went to Android's own
     * installer, used to turn the card to a failure and was reported as one (19 Oct 03:25). [statusSession] null: Android
     * didn't say which, so it counts while a session is committed.
     */
    fun isCurrent(statusSession: Int?, committedSession: Int?): Boolean =
        committedSession != null && (statusSession == null || statusSession == committedSession)

    /**
     * A build already handed to Android's installer goes straight back there on the next tap (its own sessions kept
     * failing): no new round of sessions beside Android's, which is when "Failed to override installation location" came.
     */
    fun straightToAndroidsInstaller(handedOverCode: Long?, releaseCode: Long, fileWhole: Boolean): Boolean =
        fileWhole && handedOverCode == releaseCode

    /** What the report ends with when Android's own installer took over. */
    const val HANDED_OVER = "handed to Android's installer"

    /** One of MEKA's own sessions as Android lists them (PackageInstaller.mySessions). */
    data class Session(val id: Int, val committed: Boolean)

    /** The install MEKA handed to Android and hasn't heard back about. */
    data class Outstanding(val sessionId: Int, val committedAtMs: Long, val hasPrompt: Boolean)

    enum class OnTap { START, SHOW_PROMPT, WAIT }

    fun isFilesInUse(message: String?): Boolean = message?.contains(FILES_IN_USE, ignoreCase = true) == true

    /** The name a session writes under: never the same twice ("meka-512-1760331000000.apk"). */
    fun apkName(versionCode: Long, nowMs: Long): String = "meka-$versionCode-$nowMs.apk"

    /** Whether [outstanding] still counts: committed under [OUTSTANDING_MS] ago and Android still lists its session. */
    fun isLive(outstanding: Outstanding?, sessions: List<Session>, nowMs: Long): Boolean =
        outstanding != null && nowMs - outstanding.committedAtMs in 0 until OUTSTANDING_MS &&
            sessions.any { it.id == outstanding.sessionId }

    /** Install tapped: show Android's prompt again (or wait for it) while an install is with Android, else start one. */
    fun onTap(outstanding: Outstanding?, sessions: List<Session>, nowMs: Long): OnTap = when {
        !isLive(outstanding, sessions, nowMs) -> OnTap.START
        outstanding!!.hasPrompt -> OnTap.SHOW_PROMPT
        else -> OnTap.WAIT
    }

    /** Sessions to abandon before making a new one: every own session except a live outstanding one. */
    fun toAbandon(sessions: List<Session>, outstanding: Outstanding?, nowMs: Long): List<Int> {
        val keep = if (isLive(outstanding, sessions, nowMs)) outstanding!!.sessionId else null
        return sessions.map { it.id }.filter { it != keep }
    }

    /** A failure tried again by itself: only "files in use", and only [AUTO_RETRIES] times per tap. */
    fun retriesItself(status: Int?, message: String?, retriesSoFar: Int): Boolean =
        (status == null || status == UpdateFailureRules.STATUS_FAILURE) && isFilesInUse(message) && retriesSoFar < AUTO_RETRIES
}
