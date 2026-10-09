package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reliability first, item 2 (Meka 2026-10-09 22:29): Google's 7-day sign-ins warned a day ahead; expired ones said. */
class SignInsTest {
    // Friday 9 October 2026, British Summer Time (UTC+1).
    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private val day = SignInRules.DAY_MS

    @Test
    fun aGoogleSignInEndsSevenDaysOnAndIsWarnedADayAhead() {
        val granted = at(fri - 6, 14, 5) // Saturday 14:05
        val ends = granted + 7 * day
        // Before its last day: fine.
        assertEquals(SignInState.OK to null, SignInRules.state("google", "ok", granted, ends - day - 60_000))
        // The last day: ending, with when.
        assertEquals(SignInState.ENDING to ends, SignInRules.state("google", "ok", granted, ends - day))
        assertEquals(SignInState.ENDING to ends, SignInRules.state("google", "ok", granted, ends - 1))
        // Past day 7 and still working: Google's app is in Production, nothing runs out.
        assertEquals(SignInState.OK to null, SignInRules.state("google", "ok", granted, ends + 1))
        // Microsoft doesn't run out this way, nor does an account signed in before the time was recorded.
        assertEquals(SignInState.OK to null, SignInRules.state("microsoft", "ok", granted, ends - 1))
        assertEquals(SignInState.OK to null, SignInRules.state("google", "ok", null, ends - 1))
        // A refused sign-in is expired, from when the server first saw it.
        assertEquals(SignInState.EXPIRED to 42L, SignInRules.state("google", "needs_reconnect", granted, ends + day, seenExpiredMs = 42L))
        assertEquals(SignInState.EXPIRED to ends + day, SignInRules.state("microsoft", "needs_reconnect", null, ends + day))
    }

    @Test
    fun todaysLineSaysTheMostUrgentAndHowToReconnect() {
        val now = at(fri, 21, 0)
        val ending = SignIn("google", "meka@gmail.com", SignInState.ENDING, at(fri + 1, 14, 5), canEdit = true)
        val line = SignInRules.line(listOf(ending), now, cal)!!
        assertEquals("Google sign-in ends tomorrow at 14:05 · Reconnect", line.text)
        assertFalse(line.critical)
        assertEquals("google", line.provider)
        assertEquals("meka@gmail.com", line.account)
        assertTrue(line.editing)
        assertTrue("7 days" in line.detail && "Needs Meka #17" in line.detail)
        assertEquals("Google sign-in ends today at 14:05 · Reconnect", SignInRules.line(listOf(ending), at(fri + 1, 9), cal)!!.text)
        // Once its time has gone the server says expired; until then an ended one says nothing.
        assertNull(SignInRules.line(listOf(ending), at(fri + 1, 14, 5), cal))

        val expired = SignIn("microsoft", "akunebuni@hotmail.co.uk", SignInState.EXPIRED, at(fri, 8))
        val both = SignInRules.line(listOf(ending, expired), now, cal)!!
        assertEquals("Outlook sign-in expired · calendars aren't updating · Reconnect", both.text)
        assertTrue(both.critical)
        assertEquals("microsoft", both.provider)
        assertFalse(both.editing)
        assertNull(SignInRules.line(listOf(SignIn("google", "a@gmail.com", SignInState.OK, null)), now, cal))
        assertNull(SignInRules.line(emptyList(), now, cal))
    }

    @Test
    fun aHeadsUpGoesOnceForTheLastDayAndOnceWhenExpired() {
        val until = at(fri + 1, 14, 5)
        val ending = SignIn("google", "meka@gmail.com", SignInState.ENDING, until)
        val n = SignInRules.notices(listOf(ending), at(fri, 21), cal).single()
        assertEquals(NoticeSource.SIGN_IN, n.source)
        assertEquals(NoticeTier.HEADS_UP, n.tier)
        assertEquals("Google sign-in ends tomorrow at 14:05", n.title)
        assertEquals(until - day, n.atMs)
        assertEquals(until, n.expiresAtMs)
        assertEquals(NoticeTarget.TODAY, n.target)
        val expired = SignIn("google", "meka@gmail.com", SignInState.EXPIRED, until + 60_000)
        val e = SignInRules.notices(listOf(expired), until + 120_000, cal).single()
        assertEquals("Reconnect Google", e.title)
        assertTrue(e.key != n.key)
        assertTrue(SignInRules.notices(listOf(SignIn("google", "x@gmail.com", SignInState.OK, null)), until, cal).isEmpty())
    }

    @Test
    fun theServersEntityReachesEveryDevice() {
        val world = SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        // What the server writes; written on a device replica here to stand in for it.
        a.replica.commitLocal(
            EntityTypes.CONTEXT_MODE, SignInRules.entityId("accAbc123"),
            mapOf(
                SignInFields.PROVIDER to "google".fv(), SignInFields.ACCOUNT to "meka@gmail.com".fv(),
                SignInFields.STATE to "ENDING".fv(), SignInFields.UNTIL to 1_000L.fv(), SignInFields.EDIT to true.fv(),
            ),
        )
        // Something else in context_mode is never read as a sign-in.
        a.replica.commitLocal(EntityTypes.CONTEXT_MODE, "line_status", mapOf(SignInFields.STATE to "EXPIRED".fv()))
        a.syncWithRetry(); m.syncWithRetry()
        assertEquals("sign_in.accabc123", SignInRules.entityId("accAbc123"))
        assertEquals(listOf(SignIn("google", "meka@gmail.com", SignInState.ENDING, 1_000L, true)), SignInStore(m.replica).all())
    }
}
