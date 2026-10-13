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
    fun filesInUseIsTriedOnceMoreByItself() {
        assertTrue(r.retriesItself(UpdateFailureRules.STATUS_FAILURE, msg, retriesSoFar = 0))
        assertTrue(r.retriesItself(null, "java.lang.IllegalStateException: session files in use", 0))
        assertFalse(r.retriesItself(UpdateFailureRules.STATUS_FAILURE, msg, retriesSoFar = 1))
        assertFalse(r.retriesItself(UpdateFailureRules.STATUS_FAILURE_CONFLICT, msg, 0))
        assertFalse(r.retriesItself(UpdateFailureRules.STATUS_FAILURE, "INSTALL_FAILED_INSUFFICIENT_STORAGE", 0))
        assertFalse(r.retriesItself(UpdateFailureRules.STATUS_FAILURE, null, 0))
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
}
