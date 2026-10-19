package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InstallSessionTest {
    private val r = InstallSessionRules
    private fun sess(id: Int, committed: Boolean) = InstallSessionRules.Session(id, committed)
    private val now = 1_000_000_000L
    private val msg = "INSTALL_FAILED_INTERNAL_ERROR: Session files in use"

    @Test
    fun everyStaleSessionIsAbandonedBeforeANewOne() {
        val sessions = listOf(sess(11, committed = true), sess(12, committed = false), sess(13, committed = false))
        assertEquals(listOf(11, 12, 13), r.toAbandon(sessions, outstanding = null, nowMs = now))
    }

    @Test
    fun theSessionWithAndroidsPromptUpIsKept() {
        val sessions = listOf(sess(11, true), sess(12, false))
        val out = InstallSessionRules.Outstanding(11, committedAtMs = now - 60_000, hasPrompt = true)
        assertEquals(listOf(12), r.toAbandon(sessions, out, now))
    }

    @Test
    fun anOldOrVanishedOutstandingSessionIsAbandonedToo() {
        val sessions = listOf(sess(11, true))
        val old = InstallSessionRules.Outstanding(11, committedAtMs = now - r.OUTSTANDING_MS, hasPrompt = true)
        assertEquals(listOf(11), r.toAbandon(sessions, old, now))
        val gone = InstallSessionRules.Outstanding(9, committedAtMs = now - 1_000, hasPrompt = true)
        assertEquals(listOf(11), r.toAbandon(sessions, gone, now))
        assertEquals(InstallSessionRules.OnTap.START, r.onTap(gone, sessions, now))
    }

    @Test
    fun aSecondTapWhileAndroidHasTheInstallDoesntStartAnother() {
        val sessions = listOf(sess(11, true))
        val prompt = InstallSessionRules.Outstanding(11, now - 5_000, hasPrompt = true)
        assertEquals(InstallSessionRules.OnTap.SHOW_PROMPT, r.onTap(prompt, sessions, now))
        assertEquals(InstallSessionRules.OnTap.WAIT, r.onTap(prompt.copy(hasPrompt = false), sessions, now))
        assertEquals(InstallSessionRules.OnTap.START, r.onTap(null, sessions, now))
    }

    @Test
    fun filesInUseIsTriedWithFreshSessionsByItself() {
        assertTrue(r.retriesItself(UpdateFailureRules.STATUS_FAILURE, msg, retriesSoFar = 0))
        assertTrue(r.retriesItself(null, "java.lang.IllegalStateException: session files in use", 0))
        assertTrue(r.retriesItself(UpdateFailureRules.STATUS_FAILURE, msg, retriesSoFar = r.AUTO_RETRIES - 1))
        assertFalse(r.retriesItself(UpdateFailureRules.STATUS_FAILURE, msg, retriesSoFar = r.AUTO_RETRIES))
        assertFalse(r.retriesItself(UpdateFailureRules.STATUS_FAILURE_CONFLICT, msg, 0))
        assertFalse(r.retriesItself(UpdateFailureRules.STATUS_FAILURE, "INSTALL_FAILED_INSUFFICIENT_STORAGE", 0))
        assertFalse(r.retriesItself(UpdateFailureRules.STATUS_FAILURE, null, 0))
    }

    @Test
    fun eachCommitWaitsLongerForAndroidToLetGoOfTheFile() {
        // Build 942 (11:12) committed straight after closing the write and Android still saw the file open.
        assertTrue(r.settleMs(0) >= 1_000)
        for (i in 1..r.AUTO_RETRIES) assertTrue(r.settleMs(i) > r.settleMs(i - 1))
        assertEquals(r.settleMs(r.AUTO_RETRIES), r.settleMs(r.AUTO_RETRIES + 5))
    }

    @Test
    fun afterTheFreshSessionsAndroidsOwnInstallerTakesOver() {
        val s = UpdateFailureRules.STATUS_FAILURE
        assertFalse(r.handsToAndroidsInstaller(s, msg, retriesSoFar = r.AUTO_RETRIES - 1))
        assertTrue(r.handsToAndroidsInstaller(s, msg, retriesSoFar = r.AUTO_RETRIES))
        assertTrue(r.handsToAndroidsInstaller(null, msg, retriesSoFar = r.AUTO_RETRIES))
        // Any other failure is said as it is: Android's installer would refuse a conflict or a full phone the same way.
        assertFalse(r.handsToAndroidsInstaller(UpdateFailureRules.STATUS_FAILURE_CONFLICT, msg, r.AUTO_RETRIES))
        assertFalse(r.handsToAndroidsInstaller(s, "INSTALL_FAILED_INSUFFICIENT_STORAGE", r.AUTO_RETRIES))
        // Exactly one of retry or hand over, never both, for every count.
        for (n in 0..r.AUTO_RETRIES + 1) assertNotEquals(r.retriesItself(s, msg, n), r.handsToAndroidsInstaller(s, msg, n))
    }

    @Test
    fun theReportSaysHowManySessionsWereTried() {
        assertEquals(msg, r.reportMessage(msg, sessionsTried = 1, handedOver = false))
        assertEquals("$msg · 4 sessions tried", r.reportMessage(msg, 4, false))
        assertEquals("$msg · 4 sessions tried · handed to Android's installer", r.reportMessage(msg, 4, true))
        assertEquals(null, r.reportMessage(null, 4, true))
        val f = UpdateFailure(UpdateFailureRules.STAGE_INSTALL, UpdateFailureRules.STATUS_FAILURE, r.reportMessage(msg, 4, false), 942, 973, 1)
        assertTrue(UpdateFailureRules.reportLine(f).contains("Session files in use · 4 sessions tried"))
        assertTrue(UpdateFailureRules.line(f).startsWith("Android was still holding an earlier install"))
    }

    @Test
    fun eachSessionWritesUnderItsOwnName() {
        assertEquals("meka-512-1000.apk", r.apkName(512, 1_000))
        assertNotEquals(r.apkName(512, 1_000), r.apkName(512, 1_001))
    }

    @Test
    fun theFailureLineSaysAndroidWasStillHoldingAnInstall() {
        val f = UpdateFailure(
            stage = UpdateFailureRules.STAGE_INSTALL, status = UpdateFailureRules.STATUS_FAILURE, message = msg,
            fromCode = 500, toCode = 505, atMs = 1, storage = null, network = "wifi", attempts = 1,
        )
        assertTrue(UpdateFailureRules.line(f).startsWith("Android was still holding an earlier install"))
        assertEquals("Android couldn't install it. Try again.", UpdateFailureRules.line(f.copy(message = "INSTALL_FAILED_INTERNAL_ERROR")))
    }

    @Test
    fun fromAndroid14TheSessionReadsTheKeptFileItself() {
        assertFalse(r.writesFromFile(31))
        assertFalse(r.writesFromFile(33))
        assertTrue(r.writesFromFile(34))
        assertTrue(r.writesFromFile(36))
        assertEquals("$msg · 4 sessions tried · written from the file", r.reportMessage(msg, 4, handedOver = false, fromFile = true))
        assertEquals("$msg · 4 sessions tried · written from the file · handed to Android's installer", r.reportMessage(msg, 4, true, fromFile = true))
    }

    @Test
    fun aLateAnswerFromAnAbandonedSessionIsDropped() {
        // 19 Oct 03:25: after the hand-over, "Session destroyed" from an abandoned session turned the card to a failure.
        assertTrue(r.isCurrent(statusSession = 42, committedSession = 42))
        assertFalse(r.isCurrent(statusSession = 41, committedSession = 42))
        assertFalse(r.isCurrent(statusSession = 42, committedSession = null))
        assertFalse(r.isCurrent(statusSession = null, committedSession = null))
        assertTrue(r.isCurrent(statusSession = null, committedSession = 42))
    }

    @Test
    fun aBuildAlreadyHandedOverGoesStraightBackToAndroidsInstaller() {
        assertTrue(r.straightToAndroidsInstaller(handedOverCode = 1589, releaseCode = 1589, fileWhole = true))
        assertFalse(r.straightToAndroidsInstaller(1589, 1589, fileWhole = false))
        // A newer build tries its own sessions first (now written from the file).
        assertFalse(r.straightToAndroidsInstaller(1589, 1600, true))
        assertFalse(r.straightToAndroidsInstaller(null, 1589, true))
    }
}
