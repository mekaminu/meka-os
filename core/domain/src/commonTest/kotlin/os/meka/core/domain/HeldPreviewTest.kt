package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeldPreviewTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_370L // a Friday
    private fun at(h: Int, m: Int, d: Long = day) = d * CivilDate.DAY_MS + (h * 60 + m) * 60_000L
    private val now = at(13, 45)

    private fun msg(who: String, text: String?, atMs: Long, group: String? = null, app: CaptureApp = CaptureApp.WHATSAPP) =
        CapturedItem(Capture.itemId(app, CaptureKind.MESSAGE, who, group, atMs, text), app, CaptureKind.MESSAGE, who, text, group, atMs)

    private fun call(who: String, atMs: Long) =
        CapturedItem(Capture.itemId(CaptureApp.PHONE, CaptureKind.MISSED_CALL, who, null, atMs, null), CaptureApp.PHONE, CaptureKind.MISSED_CALL, who, null, null, atMs)

    @Test
    fun oneHeldMessageShowsSenderFirstLineAndTime() {
        val summary = AfterWorkSummaries.build(listOf(msg("Ada", "Can you grab milk?\nAnd bread too", at(11, 5))), PeopleLists())
        val p = HeldPreviewRules.build(summary, "At work until 17:30", now, cal)
        assertEquals("At work until 17:30 · 1 held for later", p.label)
        assertEquals(1, p.rows.size)
        val r = p.rows.single()
        assertEquals("Ada", r.who)
        assertEquals("Can you grab milk?", r.line)
        assertEquals("11:05", r.time)
        assertEquals(0, p.more)
        assertNull(p.moreLine)
        assertTrue(p.caption.contains("nothing is marked read"))
    }

    @Test
    fun newestFirstAcrossPeopleAtMostFiveThenMore() {
        val items = listOf(
            msg("Ada", "one", at(9, 0)), msg("Tom", "two", at(9, 30)), call("Tom", at(10, 0)),
            msg("Ada", "four", at(10, 30)), msg("Sam", "five", at(11, 0)), msg("Ada", "six", at(12, 0)),
            msg("Tom", "seven", at(12, 30)),
        )
        val p = HeldPreviewRules.build(AfterWorkSummaries.build(items, PeopleLists()), "At work", now, cal)
        assertEquals("At work · 7 held for later", p.label)
        assertEquals(listOf("seven", "six", "five", "four", "Missed call"), p.rows.map { it.line })
        assertEquals(2, p.more)
        assertEquals("+2 more — all of them after work", p.moreLine)
    }

    @Test
    fun groupsVoiceMessagesUrgencyAndOlderDaysReadNaturally() {
        val voice = CapturedItem("v1", CaptureApp.PHONE, CaptureKind.VOICE_MESSAGE, "Dentist", "Please call back about Tuesday", null, at(18, 40, day - 1))
        val items = listOf(
            msg("Ada", "Who's bringing the cake?", at(12, 0), group = "Family chat"),
            msg("Tom", "URGENT the car won't start", at(12, 10)),
            voice,
            msg("Bea", "  ", at(8, 0, day - 3)),
        )
        val rows = HeldPreviewRules.build(AfterWorkSummaries.build(items, PeopleLists()), "At work", now, cal).rows
        assertEquals("Tom", rows[0].who)
        assertTrue(rows[0].urgent)
        assertEquals("Ada · Family chat", rows[1].who)
        assertEquals("Voice message · “Please call back about Tuesday”", rows[2].line)
        assertEquals("Yesterday 18:40", rows[2].time)
        assertEquals("Message", rows[3].line)
        assertEquals(CivilDate.shortLabel(day - 3) + " 08:00", rows[3].time)
    }

    @Test
    fun aCallerKnownByNumberShowsTheListedName() {
        val lists = PeopleLists(family = setOf("Mum")).withNumber("Mum", "07700 900123")
        val summary = AfterWorkSummaries.build(listOf(call("+44 7700 900123", at(10, 0))), lists)
        assertEquals("Mum", HeldPreviewRules.build(summary, "At work", now, cal).rows.single().who)
    }

    @Test
    fun longLinesAreCutAtAWord() {
        val long = "I was thinking we could all meet at the new place on the high street after school on Friday"
        val line = HeldPreviewRules.firstLine(long)
        assertTrue(line.endsWith("…"))
        assertTrue(line.length <= HeldPreviewRules.LINE_CHARS + 1)
        assertTrue(long.startsWith(line.removeSuffix("…")))
        assertEquals("short", HeldPreviewRules.firstLine("\n\n  short  \nnext"))
    }

    @Test
    fun nothingHeldIsEmpty() {
        val p = HeldPreviewRules.build(AfterWorkSummary(emptyList()), "At work", now, cal)
        assertTrue(p.isEmpty)
        assertEquals("At work · 0 held for later", p.label)
    }
}
