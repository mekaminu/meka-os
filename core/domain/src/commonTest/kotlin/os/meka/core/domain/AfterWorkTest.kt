package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AfterWorkTest {
    private fun msg(who: String, text: String?, at: Long, app: CaptureApp = CaptureApp.WHATSAPP, group: String? = null) =
        CapturedItem(Capture.itemId(app, CaptureKind.MESSAGE, who, group, at, text), app, CaptureKind.MESSAGE, who, text, group, at)

    private fun call(who: String, at: Long) =
        CapturedItem(Capture.itemId(CaptureApp.PHONE, CaptureKind.MISSED_CALL, who, null, at, null), CaptureApp.PHONE, CaptureKind.MISSED_CALL, who, null, null, at)

    @Test
    fun urgentAndEmergencyBreakThroughAsWholeWords() {
        assertTrue(Urgency.isUrgent("Call me, URGENT"))
        assertTrue(Urgency.isUrgent("It's an emergency!"))
        assertTrue(Urgency.isUrgent("need you urgently"))
        assertFalse(Urgency.isUrgent("insurgent movie tonight?"))
        assertFalse(Urgency.isUrgent(null))
        val lists = PeopleLists(alwaysNotify = setOf("Ada Okafor"))
        assertEquals(BreakThrough.URGENT, Capture.breakThrough(msg("Tom", "urgent: car", 1), lists))
        assertEquals(BreakThrough.ALWAYS_NOTIFY, Capture.breakThrough(msg("  ada  OKAFOR ", "hi", 1), lists))
        assertNull(Capture.breakThrough(msg("Tom", "hi", 1), lists))
    }

    @Test
    fun repostedNotificationsDoNotDuplicate() {
        val first = listOf(msg("Tom", "hi", 1), msg("Tom", "you there?", 2))
        val (all, fresh) = Capture.merge(emptyList(), first)
        assertEquals(2, fresh.size)
        // WhatsApp re-posts both unread messages plus the new one.
        val (all2, fresh2) = Capture.merge(all, first + msg("Tom", "ok call later", 3))
        assertEquals(listOf("ok call later"), fresh2.map { it.text })
        assertEquals(3, all2.size)
    }

    @Test
    fun storeStaysBounded() {
        val many = (1..(Capture.MAX_ITEMS + 20)).map { msg("P$it", "m", it.toLong()) }
        val (all, _) = Capture.merge(emptyList(), many)
        assertEquals(Capture.MAX_ITEMS, all.size)
        assertEquals(21L, all.first().atMs) // oldest dropped
    }

    @Test
    fun phoneNumbersInDifferentFormatsAreOnePerson() {
        assertEquals(People.key("+44 7700 900123"), People.key("07700 900123"))
        assertEquals(People.key("Ada  Okafor"), People.key("ada okafor"))
    }

    @Test
    fun summaryGroupsByPersonUrgentFirstThenFamily() {
        val lists = PeopleLists(family = setOf("Wife"))
        val items = listOf(
            msg("Colleague", "lunch?", 10),
            msg("Wife", "pick up milk", 20),
            msg("Wife", "and bread", 30, app = CaptureApp.SMS),
            call("Wife", 35),
            msg("School", "Please call, it's urgent", 15, app = CaptureApp.SMS),
            msg("Friend", "match tonight?", 40, group = "Five-a-side"),
        )
        val s = AfterWorkSummaries.build(items, lists)
        assertEquals(listOf("School", "Wife", "Friend", "Colleague"), s.people.map { it.personName })
        val wife = s.people[1]
        assertTrue(wife.isFamily)
        assertEquals("2 messages · 1 missed call", wife.line)
        assertEquals(listOf(CaptureApp.WHATSAPP, CaptureApp.SMS, CaptureApp.PHONE), wife.apps)
        assertEquals("and bread", wife.latestText)
        assertEquals("4 people · 5 messages · 1 missed call", s.headline)
        assertEquals(1, s.urgentPeople)
    }

    @Test
    fun emptySummarySaysSo() {
        val s = AfterWorkSummaries.build(emptyList(), PeopleLists())
        assertTrue(s.isEmpty)
        assertEquals("Nothing came in.", s.headline)
        assertEquals("1 missed call", AfterWorkSummaries.build(listOf(call("Mum", 1)), PeopleLists()).people.single().line)
    }

    @Test
    fun idsAreStableAndContentSensitive() {
        assertEquals(msg("Tom", "hi", 1).id, msg("tom", "hi", 1).id)
        assertFalse(msg("Tom", "hi", 1).id == msg("Tom", "hi!", 1).id)
        assertEquals("af63dc4c8601ec8c", Capture.stableHash("a")) // FNV-1a 64 reference value
    }
}
