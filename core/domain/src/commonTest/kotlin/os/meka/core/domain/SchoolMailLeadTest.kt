package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * School emails first, slice 4d: Family → School emails' four switches (task on the day, reminder 3 days before, task the
 * day before, its reminder) shape what each school event added for him makes. Invented school (rule 13).
 */
class SchoolMailLeadTest {
    private val cal = LocalCalendar.UTC
    /** Tue 24 Nov 2026. */
    private val today = CivilDate.toEpochDay(2026, 11, 24)
    private val now = cal.toEpochMs(today, 18 * 60)
    private val sat = today + 4

    private fun find(id: String, what: String, day: Long, kind: String = SchoolKind.DAY.wire) = MailFind(
        id = id, kind = SchoolMailRules.KIND_SCHOOL, account = "Personal", merchant = "Oakfield Primary", renewDay = day,
        pence = 100, obligationKind = ObligationKind.SUBSCRIPTION, repeat = RenewalRepeat.NONE, receivedDay = today - 1,
        subject = "Christmas fair this Saturday", state = MailFindState.OPEN, decidedAtMs = 0L, what = what,
        endDay = day, schoolKind = kind, startMinute = 12 * 60, endMinute = 16 * 60,
    )

    private val fair = find("f1", "School fair", sat)

    @Test
    fun allOnIsWhatItAlwaysMade() {
        assertEquals(SchoolLead(), SchoolLeadRules.read(null))
        assertEquals(SchoolAutoRules.plan(fair, now, cal), SchoolAutoRules.plan(fair, now, cal, SchoolLead()))
        val p = SchoolAutoRules.plan(fair, now, cal)
        assertEquals(2, p.tasks.size)
        assertTrue(p.tasks.all { it.remindAtMs != null })
        assertTrue(SchoolCalendarRules.wanted(fair, p))
    }

    @Test
    fun eachSwitchTakesOutItsPart() {
        val noEarly = SchoolAutoRules.plan(fair, now, cal, SchoolLead(early = false))
        assertNull(noEarly.tasks.single { it.id == SchoolAutoRules.taskId("f1", "day") }.remindAtMs)
        assertTrue(noEarly.tasks.single { it.id == SchoolAutoRules.taskId("f1", "prep") }.remindAtMs != null)

        val noDayBefore = SchoolAutoRules.plan(fair, now, cal, SchoolLead(dayBefore = false))
        assertNull(noDayBefore.tasks.single { it.id == SchoolAutoRules.taskId("f1", "prep") }.remindAtMs)

        val noPrep = SchoolAutoRules.plan(fair, now, cal, SchoolLead(prepTask = false))
        assertEquals(listOf(SchoolAutoRules.taskId("f1", "day")), noPrep.tasks.map { it.id })

        // With no task on the day the event still goes into School and his calendar.
        val noDay = SchoolAutoRules.plan(fair, now, cal, SchoolLead(dayTask = false))
        assertEquals(listOf(SchoolAutoRules.taskId("f1", "prep")), noDay.tasks.map { it.id })
        assertTrue(noDay.schoolLine)
        assertTrue(SchoolCalendarRules.wanted(fair, noDay))

        val none = SchoolAutoRules.plan(fair, now, cal, SchoolLead(dayTask = false, prepTask = false))
        assertTrue(none.tasks.isEmpty())
        assertEquals("From the school: School fair · Sat 28 Nov, 12:00–16:00 · in School", SchoolAutoRules.summary(fair, none, now, cal))
        assertEquals(
            "From the school: School fair · Sat 28 Nov, 12:00–16:00 · in School, reminder Fri, task Fri",
            SchoolAutoRules.summary(fair, SchoolAutoRules.plan(fair, now, cal, SchoolLead(dayTask = false)), now, cal),
        )
    }

    @Test
    fun deadlinesAsksAndDaysOffKeepTheirOwn() {
        val off = SchoolLead(dayTask = false, early = false, prepTask = false, dayBefore = false)
        val pay = find("p1", "Trip payment due", sat)
        assertEquals(SchoolAutoRules.plan(pay, now, cal), SchoolAutoRules.plan(pay, now, cal, off))
        val inset = find("i1", "INSET day", sat, kind = SchoolKind.OFF.wire)
        assertEquals(SchoolAutoRules.plan(inset, now, cal), SchoolAutoRules.plan(inset, now, cal, off))
        assertFalse(SchoolCalendarRules.wanted(inset, SchoolAutoRules.plan(inset, now, cal, off)))
    }

    @Test
    fun theRowsSayWhatIsOnAndAReminderNeedsItsTask() {
        val v = SchoolLeadRules.view(SchoolLead(), autoOn = true)
        assertEquals("School emails", v.title)
        assertEquals(SchoolLeadRules.KEYS, v.rows.map { it.key })
        assertEquals("On · 09:00", v.rows[1].line)
        assertTrue(v.rows.all { it.on && it.enabled })

        val noPrep = SchoolLeadRules.view(SchoolLead(prepTask = false), autoOn = true).rows.associateBy { it.key }
        val r = noPrep.getValue(SchoolLeadRules.DAY_BEFORE)
        assertFalse(r.enabled); assertFalse(r.on)
        assertEquals("Needs the task the day before (it rides on that task)", r.line)
        assertTrue(noPrep.getValue(SchoolLeadRules.EARLY).enabled)

        val off = SchoolLeadRules.view(SchoolLead(), autoOn = false)
        assertEquals(SchoolLeadRules.OFF_LINE, off.line)
        assertTrue(off.rows.none { it.enabled })
    }

    @Test
    fun aSwitchWritesOneFieldAndReadsBack() {
        val f = SchoolLeadRules.fields(SchoolLeadRules.EARLY, false, 5L)!!
        assertEquals(FieldValue.Bool(false), f[SchoolLeadRules.EARLY])
        assertEquals(SchoolLead(early = false), SchoolLeadRules.read(f))
        assertNull(SchoolLeadRules.fields("other", false, 5L))
        // The on/off switch shares the entity and is not touched.
        assertTrue(SchoolAutoRules.read(f))
        assertEquals("The next school events get a reminder 3 days before", SchoolLeadRules.switchedLine(SchoolLeadRules.EARLY, true))
        assertEquals("The next school events get no reminder the day before", SchoolLeadRules.switchedLine(SchoolLeadRules.DAY_BEFORE, false))
    }
}
