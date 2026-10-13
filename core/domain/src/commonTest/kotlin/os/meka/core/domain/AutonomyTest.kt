package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "What MEKA may do" (policy engine UI, slice 1): the page's rows and the kill switch. */
class AutonomyTest {
    private val cal = LocalCalendar.fixedOffset(60 * 60_000L) // BST
    private val now = 1_791_476_100_000L // Thu 8 Oct 2026, 17:15 BST

    @Test
    fun eachKindSaysWhereItStands() {
        assertEquals("Asks first · offers after 5 yeses in a row", AutonomyRules.trustLine(TrustState(), paused = false))
        assertEquals("Asks first · 3 of 5 yeses in a row", AutonomyRules.trustLine(TrustState(streak = 3), paused = false))
        assertEquals(AutonomyRules.TRUSTED_LINE, AutonomyRules.trustLine(TrustRules.answered(yes = true), paused = false))
        assertEquals(AutonomyRules.PAUSED_LINE, AutonomyRules.trustLine(TrustRules.answered(yes = true), paused = true))
        assertEquals(AutonomyRules.DECLINED_LINE, AutonomyRules.trustLine(TrustRules.answered(yes = false), paused = false))

        val trusted = AutonomyRules.trustRow(TrustKind.ADD_TASK, TrustRules.answered(yes = true), paused = false)
        assertEquals("Add a task", trusted.title)
        assertEquals(AutonomyRules.ASK_FIRST, trusted.action)
        assertTrue(trusted.onItsOwn)
        assertFalse(AutonomyRules.trustRow(TrustKind.ADD_TASK, TrustRules.answered(yes = true), paused = true).onItsOwn)
        assertEquals(AutonomyRules.OFFER_AGAIN, AutonomyRules.trustRow(TrustKind.ADD_WIN, TrustRules.answered(yes = false), false).action)
        assertNull(AutonomyRules.trustRow(TrustKind.ADD_WIN, TrustState(streak = 4), false).action)
    }

    @Test
    fun thePageListsEveryKindThenTheLockedRows() {
        val v = AutonomyRules.view(mapOf(TrustKind.ADD_SHOPPING to TrustRules.answered(true)), AutonomySetting(false, null), cal, now)
        assertEquals(listOf(AutonomyRules.LISTS_LABEL, AutonomyRules.LOCKED_LABEL), v.sections.map { it.label })
        assertEquals(TrustKind.entries.map { it.key }, v.sections[0].rows.map { it.key })
        assertTrue(v.sections[1].rows.all { it.locked && it.action == null })
        assertEquals("Navilife does 1 thing on its own · each with Undo and in Activity", v.summary)
        assertEquals(AutonomyRules.PAUSE, v.pauseButton)
        val two = mapOf(TrustKind.ADD_SHOPPING to TrustRules.answered(true), TrustKind.ADD_TASK to TrustRules.answered(true))
        assertEquals("Navilife does 2 things on its own · each with Undo and in Activity", AutonomyRules.summary(two, AutonomySetting(false, null), cal, now))
        assertEquals(AutonomyRules.NONE_LINE, AutonomyRules.summary(emptyMap(), AutonomySetting(false, null), cal, now))
        assertEquals(AutonomyView.EMPTY.sections, AutonomyRules.sections(emptyMap(), false))
        // Locked rows never mention a level by number and fit a row.
        assertTrue(AutonomyRules.LOCKED.all { it.line.length <= 100 }, AutonomyRules.LOCKED.joinToString { it.line })
    }

    @Test
    fun pausedSaysSinceWhen() {
        assertEquals("Paused since 17:15 · Navilife asks before everything", AutonomyRules.summary(emptyMap(), AutonomySetting(true, now), cal, now))
        val yesterday = now - 24 * 60 * 60_000L
        assertEquals("Paused since Wed 7 Oct · Navilife asks before everything", AutonomyRules.summary(emptyMap(), AutonomySetting(true, yesterday), cal, now))
        assertEquals("Paused · Navilife asks before everything", AutonomyRules.summary(emptyMap(), AutonomySetting(true, null), cal, now))
        assertEquals(AutonomyRules.RESUME, AutonomyRules.view(emptyMap(), AutonomySetting(true, now), cal, now).pauseButton)
    }

    @Test
    fun theKillSwitchSyncsAndResetTakesTrustBackToTheStart() {
        val world = SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        val fold = Autonomy(a.replica) { world.clock.nowMs }
        assertFalse(fold.paused())
        assertTrue(fold.setPaused(true))
        assertFalse(fold.setPaused(true))
        a.sync(); m.sync()
        assertTrue(Autonomy(m.replica) { world.clock.nowMs }.paused())
        assertTrue(fold.setPaused(false))
        assertNull(fold.setting().pausedAtMs)

        val trust = EarnedTrust(a.replica)
        assertNull(trust.reset(TrustKind.ADD_TASK))
        trust.answer(TrustKind.ADD_TASK, yes = true)
        assertTrue(trust.reset(TrustKind.ADD_TASK)!!.trusted)
        assertEquals(TrustState(), trust.state(TrustKind.ADD_TASK))
        assertEquals("Navilife will ask before it adds tasks again.", AutonomyRules.resetLine(TrustKind.ADD_TASK, TrustRules.answered(true)))
        assertEquals("Navilife will ask before it adds to the shopping list again.", AutonomyRules.resetLine(TrustKind.ADD_SHOPPING, TrustRules.answered(true)))
        assertEquals("Navilife may offer again after 5 yeses in a row.", AutonomyRules.resetLine(TrustKind.ADD_WIN, TrustRules.answered(false)))
        assertNull(AutonomyRules.resetLine(TrustKind.ADD_WIN, TrustState(streak = 2)))
    }
}
