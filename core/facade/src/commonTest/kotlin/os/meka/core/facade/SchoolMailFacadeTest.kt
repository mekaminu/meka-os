package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.EventEditFields
import os.meka.core.domain.EventFields
import os.meka.core.domain.SchoolAutoRules
import os.meka.core.domain.SchoolCalendarRules
import os.meka.core.domain.SchoolDoneRules
import os.meka.core.domain.SchoolBodyRules
import os.meka.core.domain.SchoolMailRules
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * School and family logistics from email, slice 1: a school email's find reaches Needs you on both apps; Add puts its
 * line into School (once, on both, marked as from the school's email) and Undo takes it out; a line he typed himself
 * for the same thing hides the find. Invented school and children (rule 13).
 */
class SchoolMailFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val serverOps = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(serverOps))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvsm${k++}", "hh", EntityTypes.MAIL_FIND, id, f, v, clock.now(), emptyList(), "server"))
    }

    private val received = CivilDate.toEpochDay(2026, 10, 16)

    @Test
    fun addPutsItInSchoolOnBothAppsAndUndoTakesItOut() = runTest {
        val trip = SchoolMailRules.read("Trip payment", "Please pay £12 for the trip to Oakfield Farm by 20 Nov.", received)!!
        val inset = SchoolMailRules.read("INSET day Monday 2 November", null, received)!!
        val idt = SchoolMailRules.findId("acc1", trip)
        val idi = SchoolMailRules.findId("acc1", inset)
        serverWrite(idt, SchoolMailRules.serverFields(trip, "Oakfield Primary", "Personal", "Trip payment", received))
        serverWrite(idi, SchoolMailRules.serverFields(inset, "Oakfield Primary", "Personal", null, received))
        val fold = core("android")
        val mac = core("mac")
        // Slice 3a: dated finds are done for him unless he turns that off; this test is his own Add.
        assertEquals(SchoolAutoRules.switchedLine(false), fold.resetTrust(SchoolAutoRules.ENTITY_ID))
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        val card = fold.mailFinds.value!!
        assertEquals(SchoolMailRules.TITLE, card.title)
        // Soonest first: the INSET day (Mon 2 Nov) before the payment (Fri 20 Nov).
        assertEquals(listOf(idi, idt), card.rows.map { it.id })
        assertTrue(card.rows.first { it.id == idt }.line.startsWith("Trip payment · £12 · due Fri 20 Nov"), card.rows.first { it.id == idt }.line)
        assertEquals(2, mac.mailFinds.value!!.rows.size)

        val added = fold.addMailFind(idt)
        assertTrue(added.startsWith("In School: Trip payment · due Fri 20 Nov"), added)
        assertEquals(listOf(idi), fold.mailFinds.value!!.rows.map { it.id })
        val row = fold.schoolView.value.dates.single()
        assertEquals("Trip payment · £12", row.title)
        assertEquals(SchoolMailRules.SOURCE_LINE, row.source)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(listOf(idi), mac.mailFinds.value!!.rows.map { it.id })
        assertEquals("Trip payment · £12", mac.schoolView.value.dates.single().title)

        // Undo: out of School, back in the card.
        fold.undoMailFind()
        assertTrue(fold.schoolView.value.dates.isEmpty())
        assertEquals(listOf(idi, idt), fold.mailFinds.value!!.rows.map { it.id })

        // A day off he typed himself for that day hides the INSET find; Add on the Mac then lands the trip once.
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        mac.addSchool("INSET 2 Nov")
        assertEquals(listOf(idt), mac.mailFinds.value!!.rows.map { it.id })
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        mac.addMailFind(idt)
        fold.addMailFind(idt)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        assertEquals(1, fold.schoolView.value.dates.size)
        assertNull(fold.mailFinds.value)
    }

    /** The task's fields as the server holds them after a sync (deleted ones included). */
    private fun serverTask(id: String): Map<String, FieldValue> =
        serverOps.after("hh", 0, 100_000).map { it.op }.filter { it.entityType == EntityTypes.TASK && it.entityId == id }
            .associate { it.field to it.value }

    @Test
    fun anAskBecomesATaskOnBothAppsAndUndoTakesItOff() = runTest {
        // Slice 2: "Please send in a named coat" names no day: Add makes a task for the next school morning.
        val ask = SchoolMailRules.read("Cold weather", "Please send in a named coat for Alex as the weather turns.", received, listOf("Alex", "Sam"))!!
        val id = SchoolMailRules.findId("acc1", ask)
        serverWrite(id, SchoolMailRules.serverFields(ask, "Oakfield Primary", "Personal", "Cold weather", received))
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        val card = fold.mailFinds.value!!
        assertEquals("1 from the school · nothing is added until you tap Add", card.headline)
        assertEquals("Send in a named coat for Alex · no date given", card.rows.single().line)

        // Saturday 09:30: due Monday 08:00, and nothing goes into School.
        assertEquals("Task added: Send in a named coat for Alex · due Mon 19 Oct, 08:00", mac.addMailFind(id))
        assertNull(mac.mailFinds.value)
        assertTrue(mac.schoolView.value.dates.isEmpty())
        assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        assertNull(fold.mailFinds.value)
        val task = serverTask(SchoolMailRules.taskId(id))
        assertEquals(FieldValue.Text("Send in a named coat for Alex"), task[os.meka.core.domain.ActionableFields.TITLE])
        val due = (task[os.meka.core.domain.ActionableFields.DUE_AT] as FieldValue.Int64).value
        assertEquals(1_792_393_200_000L, due) // Mon 19 Oct 2026, 08:00 in London (BST)

        // Add on the Fold too lands on the same task; Undo there takes it off and brings the find back on both.
        fold.addMailFind(id)
        fold.undoMailFind()
        assertEquals(listOf(id), fold.mailFinds.value!!.rows.map { it.id })
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(FieldValue.Bool(true), serverTask(SchoolMailRules.taskId(id))[os.meka.core.domain.ActionableFields.DELETED])
        assertEquals(listOf(id), mac.mailFinds.value!!.rows.map { it.id })
    }

    @Test
    fun aWeeklyPeDayGoesIntoSchoolOnBothApps() = runTest {
        // Slice 3: "PE on Tuesdays" from the school's email: Add makes School's weekly day, marked as from the email.
        val pe = SchoolMailRules.readAll("PE this term", "Alex's class will have PE on Tuesdays this term.", received, listOf("Alex", "Sam")).single()
        val id = SchoolMailRules.findId("acc1", pe)
        serverWrite(id, SchoolMailRules.serverFields(pe, "Oakfield Primary", "Personal", "PE this term", received))
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals("PE · every Tuesday · Alex", fold.mailFinds.value!!.rows.single().line)

        assertEquals("In School: PE · every Tuesday · Kids shows “Alex: PE kit” the day before", fold.addMailFind(id))
        assertNull(fold.mailFinds.value)
        val row = fold.schoolView.value.weekly.single()
        assertEquals("PE", row.title)
        assertEquals("Every Tuesday · Alex", row.line)
        assertEquals(SchoolMailRules.SOURCE_LINE, row.source)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertNull(mac.mailFinds.value)
        assertEquals("PE", mac.schoolView.value.weekly.single().title)

        // Undo: out of School, back in the card.
        fold.undoMailFind()
        assertTrue(fold.schoolView.value.weekly.isEmpty())
        assertEquals(listOf(id), fold.mailFinds.value!!.rows.map { it.id })
    }

    @Test
    fun aDatedSchoolEventIsDoneForHimOnBothAppsAndUndoTakesItAllAway() = runTest {
        // Slice 3a: the fair email (Sat 24 Oct, 12pm-4pm) read whole: no tap, its School line, a task the day before
        // and on the day, reminders Wed and Fri at 09:00, and one Activity entry whose Undo removes all of it.
        val body = "Dear parents,\n\nOur Christmas fair is on Saturday 24 October, 12pm-4pm in the school hall. " +
            "Entry is £1 per adult. Please note there is no parking at school.\n\nThank you"
        val b = SchoolBodyRules.read("Christmas fair", body, received).finds.single()
        val id = SchoolMailRules.findId("acc1", b.find)
        serverWrite(id, SchoolMailRules.serverFields(b.find, "Oakfield Primary", "Personal", "Christmas fair", received) + SchoolBodyRules.findFields(b))
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow())
        assertNull(fold.mailFinds.value)
        assertEquals("School fair", fold.schoolView.value.dates.single().title)
        val entry = SchoolAutoRules.activityId(id)
        val row = fold.activityView.value.days.flatMap { it.rows }.single { it.id == entry }
        assertTrue(row.summary.startsWith("From the school: School fair · Sat 24 Oct, 12:00–16:00 · in School"), row.summary)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        val day = serverTask(SchoolAutoRules.taskId(id, "day"))
        assertEquals(FieldValue.Text("School fair"), day[os.meka.core.domain.ActionableFields.TITLE])
        assertEquals(FieldValue.Int64(1_792_569_600_000L), day[os.meka.core.domain.TaskFields.REMIND_AT]) // Wed 21 Oct, 09:00
        val prep = serverTask(SchoolAutoRules.taskId(id, "prep"))
        assertTrue((prep[os.meka.core.domain.ActionableFields.TITLE] as FieldValue.Text).value.startsWith("Get ready for School fair"))
        assertEquals(FieldValue.Int64(1_792_742_400_000L), prep[os.meka.core.domain.TaskFields.REMIND_AT]) // Fri 23 Oct, 09:00
        // The Mac shows the same, made once.
        assertNull(mac.mailFinds.value)
        assertEquals(1, mac.schoolView.value.dates.size)
        assertEquals(1, mac.activityView.value.days.flatMap { it.rows }.count { it.id == entry })

        // Undo: out of School, the tasks gone, the card back with Add on both, and it never adds itself again.
        fold.undoActivity(entry)
        assertTrue(fold.schoolView.value.dates.isEmpty())
        assertEquals(listOf(id), fold.mailFinds.value!!.rows.map { it.id })
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(FieldValue.Bool(true), serverTask(SchoolAutoRules.taskId(id, "day"))[os.meka.core.domain.ActionableFields.DELETED])
        assertEquals(listOf(id), mac.mailFinds.value!!.rows.map { it.id })
        assertTrue(mac.schoolView.value.dates.isEmpty())
    }

    private inner class EditingTransport(
        private val list: List<ConnectedAccount>,
        private val inner: os.meka.core.sync.SyncTransport = transport,
    ) : os.meka.core.sync.SyncTransport by inner, AccountsApi {
        override suspend fun startConnect(provider: String, editing: Boolean): ConnectStart = ConnectStart.NotSetUp
        override suspend fun accounts(): List<ConnectedAccount> = list
        override suspend fun stopEditing(provider: String, email: String): List<ConnectedAccount> = list
    }

    private fun serverEdit(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvse${k++}", "hh", EntityTypes.EVENT_EDIT, id, f, v, clock.now(), emptyList(), "server"))
    }

    private fun serverEvent(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvev${k++}", "hh", EntityTypes.EVENT, id, f, v, clock.now(), emptyList(), "server"))
    }

    private fun serverEdits(): Map<String, Map<String, FieldValue>> =
        serverOps.after("hh", 0, 100_000).map { it.op }.filter { it.entityType == EntityTypes.EVENT_EDIT }
            .groupBy { it.entityId }.mapValues { (_, ops) -> ops.associate { it.field to it.value } }

    @Test
    fun aSchoolEventGoesIntoHisGoogleCalendarOnceAndUndoTakesItOut() = runTest {
        // Slice 3b: the fair (Sat 24 Oct, 12pm-4pm, school hall) is added to Google once though both apps run, it isn't
        // shown twice in Navilife (the task on the day stands for it), and Undo deletes it from Google once it's there.
        val body = "Dear parents,\n\nOur Christmas fair is on Saturday 24 October, 12pm-4pm in the school hall. " +
            "Entry is £1 per adult. Please note there is no parking at school.\n\nThank you"
        val b = SchoolBodyRules.read("Christmas fair", body, received).finds.single()
        val id = SchoolMailRules.findId("acc1", b.find)
        serverWrite(id, SchoolMailRules.serverFields(b.find, "Oakfield Primary", "Personal", "Christmas fair", received) + SchoolBodyRules.findFields(b))
        val google = listOf(ConnectedAccount("google", "alex@example.com", "ok", null, canEdit = true))
        fun core(device: String) = MekaCore(
            householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = EditingTransport(google),
            secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        val fold = core("android")
        val mac = core("mac")
        fold.refreshCalendarAccounts(); mac.refreshCalendarAccounts()
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        val editId = SchoolCalendarRules.editId(id)
        val adds = serverEdits().filterValues { it[EventEditFields.KIND] == FieldValue.Text("ADD") }
        assertEquals(setOf(editId), adds.keys, "one add, made the same on both apps")
        val add = adds.getValue(editId)
        assertEquals(FieldValue.Text("School fair"), add[EventEditFields.TITLE])
        assertEquals(FieldValue.Text("alex@example.com"), add[EventEditFields.ACCOUNT])
        assertEquals(FieldValue.Int64(1_792_839_600_000L), add[EventEditFields.START_AT]) // Sat 24 Oct, 12:00 in London
        assertEquals(FieldValue.Int64(1_792_854_000_000L), add[EventEditFields.END_AT]) // 16:00
        assertEquals(FieldValue.Bool(false), add[EventEditFields.ALL_DAY])
        assertTrue((add[EventEditFields.NOTES] as FieldValue.Text).value.contains("No parking at school", ignoreCase = true))
        val entry = SchoolAutoRules.activityId(id)
        val row = mac.activityView.value.days.flatMap { it.rows }.single { it.id == entry }
        assertTrue(row.summary.contains("· in Google and School, reminders"), row.summary)

        // Google has it: the server says done and the mirror holds it; Navilife shows it once, as the task.
        now += 60_000
        serverEdit(editId, mapOf(EventEditFields.STATUS to FieldValue.Text("DONE"), EventEditFields.STATUS_AT to FieldValue.Int64(now)))
        serverEvent("g1", mapOf(
            EventFields.TITLE to FieldValue.Text("School fair"), EventFields.START_AT to FieldValue.Int64(1_792_839_600_000L),
            EventFields.END_AT to FieldValue.Int64(1_792_854_000_000L), EventFields.ALL_DAY to FieldValue.Bool(false),
            EventFields.PROVIDER to FieldValue.Text("google"), EventFields.ACCOUNT to FieldValue.Text("alex@example.com"),
        ))
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        val sat = fold.calendarView.value.sections.first { it.title == "Sat 24 Oct" }
        assertEquals(1, sat.rows.count { it.title == "School fair" }, "the task stands for it: ${sat.rows.map { it.title }}")

        // Undo: the tasks and the School line go, and the event is deleted from Google (once, though both apps run).
        fold.undoActivity(entry)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        val deletes = serverEdits().filterValues { it[EventEditFields.KIND] == FieldValue.Text("DELETE") }
        assertEquals(setOf(SchoolCalendarRules.deleteId(id)), deletes.keys)
        assertEquals(FieldValue.Text("g1"), deletes.values.single()[EventEditFields.EVENT_ID])
    }

    @Test
    fun aSchoolDateDoneForHimIsADoneForYouRowAndOneHeadsUpOnBothAppsWithUndoAndClear() = runTest {
        // Slice 3c: the fair added on its own is said: a Done for you row "From the school" with Undo, Change and Clear
        // on both apps, and one heads-up "Done for you · from the school". Undo there takes it all away.
        val body = "Dear parents,\n\nOur Christmas fair is on Saturday 24 October, 12pm-4pm in the school hall. " +
            "Entry is £1 per adult.\n\nThank you"
        val b = SchoolBodyRules.read("Christmas fair", body, received).finds.single()
        val id = SchoolMailRules.findId("acc1", b.find)
        serverWrite(id, SchoolMailRules.serverFields(b.find, "Oakfield Primary", "Personal", "Christmas fair", received) + SchoolBodyRules.findFields(b))
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        val rowId = SchoolDoneRules.rowId(id)
        val row = fold.doneForYou.value.single { it.id == rowId }
        assertTrue(row.from.startsWith("From the school · "), row.from)
        assertTrue(row.line.startsWith("School fair · Sat 24 Oct, 12:00–16:00 · in School"), row.line)
        assertEquals(SchoolAutoRules.taskId(id, "day"), row.changeTaskId)
        assertEquals("School fair", row.changeTitle)
        assertEquals(listOf(rowId), mac.doneForYou.value.map { it.id })
        val notice = fold.noticesNow().single { it.key == "handled:$rowId" }
        assertEquals("Done for you · from the school", notice.title)
        assertEquals(row.line, notice.text)

        // Clear on the Mac: the row goes on both; what it made stays.
        assertTrue(mac.clearDoneForYou(rowId))
        assertTrue(mac.doneForYou.value.isEmpty())
        assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        assertTrue(fold.doneForYou.value.isEmpty())
        assertEquals(1, fold.schoolView.value.dates.size)
        assertTrue(fold.noticesNow().none { it.key == "handled:$rowId" })
        assertFalse(fold.undoDoneForYou(rowId), "a cleared row has no Undo here (Activity keeps it)")
    }

    @Test
    fun undoOnTheSchoolRowTakesEverythingAway() = runTest {
        val body = "Dear parents,\n\nOur Christmas fair is on Saturday 24 October, 12pm-4pm in the school hall.\n\nThank you"
        val b = SchoolBodyRules.read("Christmas fair", body, received).finds.single()
        val id = SchoolMailRules.findId("acc1", b.find)
        serverWrite(id, SchoolMailRules.serverFields(b.find, "Oakfield Primary", "Personal", "Christmas fair", received) + SchoolBodyRules.findFields(b))
        val fold = core("android")
        assertTrue(fold.syncNow())
        val rowId = SchoolDoneRules.rowId(id)
        assertTrue(fold.undoDoneForYou(rowId))
        assertTrue(fold.doneForYou.value.isEmpty())
        assertTrue(fold.schoolView.value.dates.isEmpty())
        assertEquals(listOf(id), fold.mailFinds.value!!.rows.map { it.id })
        assertFalse(fold.undoDoneForYou(rowId))
    }

    private fun fairDoneForHim(): String {
        val fair = SchoolBodyRules.read(
            "Christmas fair", "Dear parents,\n\nOur Christmas fair is on Saturday 24 October, 12pm-4pm in the school hall.\n\nThank you", received,
        ).finds.single()
        val id = SchoolMailRules.bodyFindId("acc1", fair)
        serverWrite(id, SchoolMailRules.serverFields(fair.find, "Oakfield Primary", "Personal", "Christmas fair", received) + SchoolBodyRules.findFields(fair))
        return id
    }

    private fun schoolEmail(subject: String, body: String, day: Long): String {
        val b = SchoolBodyRules.read(subject, body, day).finds.single()
        val id = SchoolMailRules.bodyFindId("acc1", b)
        serverWrite(id, SchoolMailRules.serverFields(b.find, "Oakfield Primary", "Personal", subject, day) + SchoolBodyRules.findFields(b))
        return id
    }

    @Test
    fun aCancelledSchoolDateTakesOutWhatWasMadeOnBothAppsAndSaysSo() = runTest {
        // Slice 4c: the fair (Sat 24 Oct) was done for him; the school's next email calls it off.
        val ida = fairDoneForHim()
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(1, fold.schoolView.value.dates.size)

        val idb = schoolEmail(
            "Christmas fair",
            "Dear parents,\n\nUnfortunately the Christmas fair on Saturday 24 October has been cancelled.\n\nThank you",
            received + 1,
        )
        assertTrue(ida != idb)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        // Out of School and its tasks gone, on both; nothing offered with Add.
        assertTrue(fold.schoolView.value.dates.isEmpty())
        assertTrue(mac.schoolView.value.dates.isEmpty())
        val deleted = os.meka.core.domain.ActionableFields.DELETED
        assertEquals(FieldValue.Bool(true), serverTask(SchoolAutoRules.taskId(ida, "day"))[deleted])
        assertEquals(FieldValue.Bool(true), serverTask(SchoolAutoRules.taskId(ida, "prep"))[deleted])
        assertNull(fold.mailFinds.value)
        // Said once: the change's row stands for the fair's, with a heads-up.
        val row = fold.doneForYou.value.single()
        assertEquals(SchoolDoneRules.rowId(idb), row.id)
        assertEquals("School fair cancelled · was Sat 24 Oct · taken out of School and 2 tasks", row.line)
        assertEquals(listOf(row.id), mac.doneForYou.value.map { it.id })
        assertTrue(fold.noticesNow().any { it.key == "handled:${row.id}" })

        // Undo: the fair is back in School with its own row.
        assertTrue(fold.undoDoneForYou(row.id))
        assertEquals(1, fold.schoolView.value.dates.size)
        assertEquals(listOf(SchoolDoneRules.rowId(ida)), fold.doneForYou.value.map { it.id })
    }

    @Test
    fun aMovedSchoolDateMovesWhatWasMadeAndSaysSo() = runTest {
        // Slice 4c: the fair moves from Sat 24 Oct to Sun 25 Oct: the old date's line and tasks go, the new date's come.
        val ida = fairDoneForHim()
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        val idc = schoolEmail(
            "Christmas fair update",
            "Dear parents,\n\nThe Christmas fair has been moved to Sunday 25 October, 12pm-4pm.\n\nThank you",
            received + 1,
        )
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        val deleted = os.meka.core.domain.ActionableFields.DELETED
        assertEquals(FieldValue.Bool(true), serverTask(SchoolAutoRules.taskId(ida, "day"))[deleted])
        val moved = serverTask(SchoolAutoRules.taskId(idc, "day"))
        assertEquals(FieldValue.Text("School fair"), moved[os.meka.core.domain.ActionableFields.TITLE])
        assertTrue(moved[deleted] != FieldValue.Bool(true))
        assertEquals(1, fold.schoolView.value.dates.size)
        assertEquals(1, mac.schoolView.value.dates.size)
        val row = mac.doneForYou.value.single()
        assertEquals(SchoolDoneRules.rowId(idc), row.id)
        assertTrue(row.line.startsWith("School fair moved to Sun 25 Oct, 12:00–16:00 (was Sat 24 Oct) · in School"), row.line)
    }

    @Test
    fun theSchoolEmailSwitchesSetOnTheMacShapeWhatTheFoldMakes() = runTest {
        // Slice 4d: on the Mac he turns off the task the day before and the reminder 3 days before; the Fold follows.
        val fold = core("android")
        val mac = core("mac")
        assertTrue(mac.syncNow())
        assertTrue(mac.schoolLeadView.value.rows.all { it.on && it.enabled })
        assertEquals("The next school events get no task the day before", mac.setSchoolLead(os.meka.core.domain.SchoolLeadRules.PREP_TASK, false))
        mac.setSchoolLead(os.meka.core.domain.SchoolLeadRules.EARLY, false)
        assertNull(mac.setSchoolLead("nope", false))
        val rows = mac.schoolLeadView.value.rows.associateBy { it.key }
        assertFalse(rows.getValue(os.meka.core.domain.SchoolLeadRules.DAY_BEFORE).enabled)
        assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        val seen = fold.schoolLeadView.value.rows.associateBy { it.key }
        assertFalse(seen.getValue(os.meka.core.domain.SchoolLeadRules.PREP_TASK).on)
        assertFalse(seen.getValue(os.meka.core.domain.SchoolLeadRules.EARLY).on)
        assertTrue(seen.getValue(os.meka.core.domain.SchoolLeadRules.DAY_TASK).on)

        val id = fairDoneForHim()
        assertTrue(fold.syncNow()); assertTrue(fold.syncNow())
        assertEquals("School fair", fold.schoolView.value.dates.single().title)
        val day = serverTask(SchoolAutoRules.taskId(id, "day"))
        assertEquals(FieldValue.Text("School fair"), day[os.meka.core.domain.ActionableFields.TITLE])
        assertFalse(day[os.meka.core.domain.TaskFields.REMIND_AT] is FieldValue.Int64, "no reminder 3 days before")
        assertTrue(serverTask(SchoolAutoRules.taskId(id, "prep")).isEmpty())
        val row = fold.activityView.value.days.flatMap { it.rows }.single { it.id == SchoolAutoRules.activityId(id) }
        assertTrue(row.summary.contains("task Sat") && !row.summary.contains("reminder"), row.summary)
    }

    @Test
    fun anUndatedCancellationWaitsForHisTakeItOut() = runTest {
        // Slice 4e: the school says the fair is cancelled but gives no date: nothing is taken out until he says so.
        val ida = fairDoneForHim()
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        val idb = schoolEmail(
            "Christmas fair",
            "Dear parents,\n\nUnfortunately the Christmas fair has been cancelled.\n\nThank you",
            received + 1,
        )
        assertTrue(ida != idb)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow()); assertTrue(fold.syncNow())
        // Still in School, its tasks still there, and a row to check on both apps.
        assertEquals(1, fold.schoolView.value.dates.size)
        val deleted = os.meka.core.domain.ActionableFields.DELETED
        assertTrue(serverTask(SchoolAutoRules.taskId(ida, "day"))[deleted] != FieldValue.Bool(true))
        val row = fold.mailFinds.value!!.rows.single()
        assertEquals(idb, row.id)
        assertEquals("Take it out", row.addLabel)
        assertEquals("Keep it", row.notNeededLabel)
        assertTrue(row.line.startsWith("School fair · Sat 24 Oct · the school says it's cancelled"), row.line)
        assertEquals(listOf(idb), mac.mailFinds.value!!.rows.map { it.id })

        // Take it out: gone from School and its tasks, with the change's Done for you row.
        assertTrue(fold.addMailFind(idb).startsWith("Taken out: School fair"))
        assertTrue(fold.schoolView.value.dates.isEmpty())
        assertNull(fold.mailFinds.value)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(FieldValue.Bool(true), serverTask(SchoolAutoRules.taskId(ida, "day"))[deleted])
        assertTrue(mac.schoolView.value.dates.isEmpty())
        assertEquals(listOf(SchoolDoneRules.rowId(idb)), fold.doneForYou.value.map { it.id })

        // Undo on the bar: the fair is back and the row to check with it.
        fold.undoMailFind()
        assertEquals(1, fold.schoolView.value.dates.size)
        assertEquals(listOf(idb), fold.mailFinds.value!!.rows.map { it.id })
    }
}
