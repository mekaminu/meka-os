package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** About you (rule 13, slice 1): typed lines, children, the setup step and the one-off copy. Invented names only. */
class AboutYouTest {
    private var now = 1_791_795_600_000L // Mon 12 Oct 2026, 10:00 in London
    private var n = 0
    private fun store() = AboutYouStore(
        Replica("hh", "android", HlcClock("android", { now }), InMemoryReplicaStore(), MekaSchema) { "op" + n++ }, { now })

    @Test
    fun linesAreCleanedOrRefused() {
        assertEquals("Sam", AboutYouRules.clean(AboutField.PARTNER_NAME, "  Sam "))
        assertEquals("Mary-Jane O'Neil", AboutYouRules.clean(AboutField.PARTNER_NAME, "Mary-Jane   O'Neil"))
        assertNull(AboutYouRules.clean(AboutField.PARTNER_NAME, "Sam 2"))
        assertEquals("", AboutYouRules.clean(AboutField.HOME, "   "))
        assertEquals("07700 900123", AboutYouRules.clean(AboutField.PARTNER_PHONE, "+44 7700 900123"))
        assertNull(AboutYouRules.clean(AboutField.ASSISTANT_NUMBER, "call me"))
        assertEquals("St. Albans", AboutYouRules.clean(AboutField.HOME, "St. Albans"))
        assertNull(AboutYouRules.clean(AboutField.WORK, "<script>"))
    }

    @Test
    fun childrenAreReadByNameAgeGroupAndSchool() {
        assertEquals(AboutChild("Alex", 7, "Oakfield School"), AboutYouRules.parseChild("Alex, U7, Oakfield School"))
        assertEquals(AboutChild("Alex", 7), AboutYouRules.parseChild("Alex U7"))
        assertEquals(AboutChild("Mary Jane", 10), AboutYouRules.parseChild("Mary Jane under 10s"))
        assertEquals(AboutChild("Uma", null, "Oakfield School"), AboutYouRules.parseChild("Uma, Oakfield School"))
        assertEquals(AboutChild("Sam"), AboutYouRules.parseChild("Sam"))
        assertNull(AboutYouRules.parseChild(" , U7"))
        assertNull(AboutYouRules.ageGroupOf("Uma"))
        assertEquals(12, AboutYouRules.ageGroupOf("U12s"))
        val kids = listOf(AboutChild("Alex", 7, "Oakfield School"), AboutChild("Sam", 10))
        assertEquals(kids, AboutYouRules.decodeChildren(AboutYouRules.encodeChildren(kids)))
        assertEquals("Under 7s · Oakfield School", AboutYouRules.childLine(kids[0]))
        assertEquals("Alex, U7, Oakfield School", AboutYouRules.childText(kids[0]))
    }

    @Test
    fun anEmptyProfileAsksOnceAndPreFillsNothing() {
        val s = store()
        val v = s.view(LocalCalendar.UTC)
        assertTrue(v.isEmpty)
        assertEquals(AboutYouRules.WELCOME, v.summary)
        assertTrue(v.rows.all { it.value == null })
        assertEquals("Add your partner to get their requests flagged", v.rows.first { it.field == AboutField.PARTNER_NAME }.line)
        assertEquals(AboutYouRules.NO_CHILDREN, v.childrenEmpty)
        assertNull(v.watchLine)
        val step = AboutYouRules.setupStep(s.profile())
        assertEquals(SetupState.TODO, step.state)
        assertEquals(SetupFix.ABOUT_YOU, step.fix)
    }

    @Test
    fun savingClearingAndUndo() {
        val s = store()
        assertEquals(AboutSaved("Saved"), s.set(AboutField.PARTNER_NAME, "Sam"))
        assertTrue(s.set(AboutField.PARTNER_NAME, "Sam 2").refused)
        assertEquals("Sam", s.profile().partnerName)
        val cleared = s.set(AboutField.PARTNER_NAME, "")
        assertEquals("Sam", cleared.undo)
        assertNull(s.profile().partnerName)
        s.set(AboutField.PARTNER_NAME, cleared.undo!!)
        assertEquals("Sam", s.profile().partnerName)
        assertEquals(AboutYouRules.WATCH_ON, s.view(LocalCalendar.UTC).watchLine)
        assertTrue(s.setPartnerWatch(false))
        assertFalse(s.setPartnerWatch(false))
        assertEquals(AboutYouRules.WATCH_OFF, s.view(LocalCalendar.UTC).watchLine)
        assertEquals(AboutYouRules.TYPED, s.view(LocalCalendar.UTC).summary)

        assertEquals("Added Alex · Under 7s · Oakfield School", s.addChild("Alex, U7, Oakfield School").line)
        s.addChild("Sam U10")
        assertTrue(s.addChild("U7").refused)
        assertEquals("Saved", s.editChild(1, "Sam, U10, Oakfield School").line)
        val gone = s.removeChild(0)
        assertEquals("Alex, U7, Oakfield School", gone.undo)
        assertEquals(listOf("Sam"), s.profile().children.map { it.name })
        s.addChild(gone.undo!!)
        assertEquals("Partner and 2 children", AboutYouRules.held(s.profile()))
        assertEquals(SetupState.DONE, AboutYouRules.setupStep(s.profile()).state)
    }

    @Test
    fun theOneOffCopyWritesOnceAndNeverOverATypedProfile() {
        val values = AboutYou(partnerName = "Sam", children = listOf(AboutChild("Alex", 7)), home = "Oakfield", work = "Docklands",
            assistantNumber = "+44 7700 900111")
        val s = store()
        assertTrue(s.seedOnce(values))
        val p = s.profile()
        assertEquals("Sam", p.partnerName)
        assertEquals("07700 900111", p.assistantNumber)
        assertEquals(listOf(AboutChild("Alex", 7)), p.children)
        assertEquals(now, p.seededAtMs)
        assertTrue(s.view(LocalCalendar.UTC).summary.startsWith("MEKA filled this in today from what it already knew"))
        assertFalse(s.seedOnce(values.copy(partnerName = "Jo")))
        assertEquals("Sam", s.profile().partnerName)

        // Typed first (on either app): the copy never writes.
        val t = store()
        t.set(AboutField.HOME, "Hilltown")
        assertFalse(t.seedOnce(values))
        assertNull(t.profile().partnerName)
        // Nothing to copy: nothing written, so the profile stays empty and Setup still asks.
        assertTrue(AboutYouRules.seedFields(AboutYou.EMPTY, now).isEmpty())
        assertEquals(FieldValue.Int64(now), AboutYouRules.seedFields(values, now)[AboutYouFields.SEEDED_AT])
    }

    @Test
    fun setupPutsAboutYouFirst() {
        val base = SetupView(listOf(SetupSection("Calendars", listOf(SetupStep("c", "Calendars", "ok", SetupState.DONE)))), 1, 1, 0, "", null, 0L)
        val v = AboutYouRules.withSetupStep(base, AboutYou.EMPTY)
        assertEquals(listOf(AboutYouRules.TITLE, "Calendars"), v.sections.map { it.title })
        assertEquals(1, v.toDo)
    }
}
