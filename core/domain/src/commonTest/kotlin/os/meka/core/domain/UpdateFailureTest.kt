package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateFailureTest {
    private val r = UpdateFailureRules
    private val conflict = UpdateFailure(
        stage = r.STAGE_INSTALL, status = r.STATUS_FAILURE_CONFLICT,
        message = "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package os.meka.android signatures do not match newer version; ignoring!",
        fromCode = 425, toCode = 430, atMs = 1_000, storage = "over 5 GB", network = "wifi", attempts = 1,
    )

    @Test
    fun androidsOwnWordsAreKeptUnderTheLine() {
        assertEquals(
            "Android refused it: it isn't signed like the MEKA you have (it must carry the debug key of the Mac that installed MEKA).",
            r.line(conflict),
        )
        assertEquals("Android: ${conflict.message} · STATUS_FAILURE_CONFLICT", r.detail(conflict))
        // A conflict that isn't about the signature says so plainly.
        val other = conflict.copy(message = "INSTALL_FAILED_DUPLICATE_PERMISSION")
        assertEquals("Android refused it: it conflicts with the MEKA you have.", r.line(other))
        // No message: the status alone.
        assertEquals("Android: STATUS_FAILURE_BLOCKED", r.detail(conflict.copy(status = r.STATUS_FAILURE_BLOCKED, message = null)))
        assertEquals("Android blocked the install.", r.line(conflict.copy(status = r.STATUS_FAILURE_BLOCKED, message = null)))
        assertEquals("Not enough space on the phone for the update.", r.line(conflict.copy(status = r.STATUS_FAILURE_STORAGE)))
        assertEquals("Android said the downloaded file isn't a valid app. Try again.", r.line(conflict.copy(status = r.STATUS_FAILURE_INVALID)))
        assertEquals("Android timed out installing it. Try again.", r.line(conflict.copy(status = r.STATUS_FAILURE_TIMEOUT)))
        assertEquals("Android couldn't install it. Try again.", r.line(conflict.copy(status = r.STATUS_FAILURE)))
        assertEquals("status 42", r.statusName(42))
    }

    @Test
    fun downloadAndCheckFailuresSayWhatHappened() {
        val dl = conflict.copy(stage = r.STAGE_DOWNLOAD, status = null, message = "TransportException: chunk 3 has the wrong size", attempts = 3)
        assertEquals("Couldn't download the update (tried 3 times). Check the connection and try again.", r.line(dl))
        assertEquals("TransportException: chunk 3 has the wrong size", r.detail(dl))
        assertEquals("Couldn't download the update. Check the connection and try again.", r.line(dl.copy(attempts = 1)))
        assertEquals("The download didn't match the published build. Try again.", r.line(dl.copy(stage = r.STAGE_CHECK)))
        assertEquals("Build 430 didn't download", r.activitySummary(dl))
        assertEquals("Build 430 didn't install", r.activitySummary(conflict))
    }

    @Test
    fun abortedAndThePromptArentFailures() {
        assertFalse(r.isFailure(r.STATUS_FAILURE_ABORTED))
        assertFalse(r.isFailure(r.STATUS_SUCCESS))
        assertFalse(r.isFailure(r.STATUS_PENDING_USER_ACTION))
        assertTrue(r.isFailure(r.STATUS_FAILURE))
        assertTrue(r.isFailure(r.STATUS_FAILURE_CONFLICT))
        assertTrue(r.isFailure(99))
    }

    @Test
    fun aBrokenDownloadIsTriedThreeTimesWithGrowingPauses() {
        assertTrue(r.retries(1))
        assertTrue(r.retries(2))
        assertFalse(r.retries(3))
        assertEquals(2_000L, r.retryDelayMs(2))
        assertEquals(10_000L, r.retryDelayMs(3))
        assertEquals(30_000L, r.retryDelayMs(4))
    }

    @Test
    fun messagesAreOneShortLine() {
        assertNull(r.cleanMessage(null))
        assertNull(r.cleanMessage("  \n\t "))
        assertEquals("Failed to commit install session 12 with command package", r.cleanMessage("  Failed to commit\ninstall session 12\twith command   package \u0000"))
        val long = r.cleanMessage("x".repeat(400))!!
        assertEquals(r.MESSAGE_MAX, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun freeSpaceIsABandNeverTheFigure() {
        assertNull(r.storageBand(null))
        assertEquals("under 100 MB", r.storageBand(50_000_000))
        assertEquals("100 MB–1 GB", r.storageBand(100_000_000))
        assertEquals("1–5 GB", r.storageBand(4_999_999_999))
        assertEquals("over 5 GB", r.storageBand(64_000_000_000))
    }

    @Test
    fun theReportLineAndHealthLine() {
        assertEquals(
            "build 425 → 430 · install · STATUS_FAILURE_CONFLICT · ${conflict.message} · storage over 5 GB · wifi · 1 attempt",
            r.reportLine(conflict),
        )
        assertEquals("Build 430 didn't install 06:15 · Android: ${conflict.message} · STATUS_FAILURE_CONFLICT", r.healthLine(conflict, 425, "06:15"))
        // Once that build (or a newer one) is installed, Health stops showing it.
        assertNull(r.healthLine(conflict, 430, "06:15"))
        assertNull(r.healthLine(null, 425, "06:15"))
        // One failure, one Activity entry.
        assertEquals(r.activityId(conflict), r.activityId(conflict.copy(message = "other")))
        assertTrue(r.activityId(conflict) != r.activityId(conflict.copy(atMs = 2_000)))
    }

    @Test
    fun aKeptDownloadResumesFromItsWholeChunks() {
        val mib = 1024 * 1024
        assertEquals(0, AppUpdateRules.resumeFrom(0, 30L * mib, mib))
        assertEquals(2, AppUpdateRules.resumeFrom(2L * mib + 500, 30L * mib, mib))
        // A whole file (or a longer one) starts over and is checked by hash instead.
        assertEquals(0, AppUpdateRules.resumeFrom(30L * mib, 30L * mib, mib))
        assertEquals(0, AppUpdateRules.resumeFrom(31L * mib, 30L * mib, mib))
        assertTrue(AppUpdateRules.isWhole(30L * mib, 30L * mib))
        assertFalse(AppUpdateRules.isWhole(29L * mib, 30L * mib))
    }
}
