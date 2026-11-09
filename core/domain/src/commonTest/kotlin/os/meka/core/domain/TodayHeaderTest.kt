package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TodayHeaderTest {
    private val now = 1_790_985_600_000L

    private fun task(id: String) = Task(id, "Task $id", null, Lifecycle.ACTIVE, null, null, null, 0, null, null, 0, null, false)

    private fun row(kind: TimelineKind, time: String, title: String, running: Boolean = false) =
        TimelineRow("r-$title", kind, time, title, null, null, null, running, now)

    private fun backlog(account: String, unread: Int?, tidy: Int = 0) = MailBacklog(
        id = "mb-$account", account = account, startedAtMs = now - 86_400_000L, scanned = 0, readyAtMs = null,
        counts = emptyMap(), top = null, approvedAtMs = null,
        inboxUnread = unread, tidyUnread = tidy, unreadCountedAtMs = now - 60_000L,
    )

    @Test
    fun theTasksTileCountsTodaysOwnTasks() {
        val t = TodayHeaderRules.tasksTile(listOf(task("a"), task("b")), listOf(task("c")))
        assertEquals("TASKS", t.key)
        assertEquals("1 of 3", t.value)
        assertEquals("done today", t.line)
        assertEquals("1 of 3 of today's tasks done.", t.spoken)
        assertEquals("3 of 3", TodayHeaderRules.tasksTile(emptyList(), List(3) { task("$it") }).value)
        assertEquals("all done ✓", TodayHeaderRules.tasksTile(emptyList(), listOf(task("a"))).line)
        val none = TodayHeaderRules.tasksTile(emptyList(), emptyList())
        assertEquals("0", none.value)
        assertEquals("nothing planned", none.line)
    }

    @Test
    fun theNextTileIsTheNextEventOrSessionNotATaskOrWork() {
        val tl = DayTimeline.EMPTY.copy(rows = listOf(
            row(TimelineKind.NOW, "14:32", "Now"),
            row(TimelineKind.WORK, "09:00–17:30", "Work"),
            row(TimelineKind.TASK, "15:00", "Pay the council"),
            row(TimelineKind.GAP, "15:30", "1 h free"),
            row(TimelineKind.EVENT, "16:55–18:00", "Training"),
        ))
        val t = TodayHeaderRules.nextTile(tl)
        assertEquals("16:55", t.value)
        assertEquals("Training", t.line)
        assertEquals("Next: Training at 16:55.", t.spoken)
        // The tile opens the row it names, on both apps.
        assertEquals("r-Training", TodayHeaderRules.nextRow(tl)?.id)
        assertNull(TodayHeaderRules.nextRow(DayTimeline.EMPTY.copy(rows = listOf(row(TimelineKind.TASK, "15:00", "Pay")))))
        val on = TodayHeaderRules.nextTile(DayTimeline.EMPTY.copy(rows = listOf(row(TimelineKind.SESSION, "Until 18:45", "Gym", running = true))))
        assertEquals("Now", on.value)
        assertEquals("Gym", on.line)
        val free = TodayHeaderRules.nextTile(DayTimeline.EMPTY.copy(rows = listOf(row(TimelineKind.NOW, "20:10", "Now"))))
        assertEquals("Free", free.value)
        assertEquals("nothing else today", free.line)
    }

    @Test
    fun theMailTileSaysTheUnreadAndWhatCanBeTidied() {
        val one = MailUnreadRules.todayLine(listOf(backlog("Personal", 1_048, 380)), now)!!
        assertEquals(1_048, one.unread)
        val t = TodayHeaderRules.mailTile(one)
        assertEquals("MAIL", t.key)
        assertEquals("1,048", t.value)
        assertEquals("unread · tidy 380", t.line)
        assertEquals(one.spoken, t.spoken)
        val several = MailUnreadRules.todayLine(listOf(backlog("Outlook", 82, 40), backlog("Business", 5)), now)!!
        assertEquals("87", TodayHeaderRules.mailTile(several).value)
        assertEquals("unread · tidy 40", TodayHeaderRules.mailTile(several).line)
        assertEquals("unread", TodayHeaderRules.mailTile(MailUnreadRules.todayLine(listOf(backlog("Personal", 5)), now)!!).line)
        val zero = TodayHeaderRules.mailTile(MailUnreadRules.todayLine(listOf(backlog("Personal", 0)), now)!!)
        assertEquals("0", zero.value)
        assertEquals("nothing unread", zero.line)
        val unknown = TodayHeaderRules.mailTile(MailUnreadLine("Mail · Personal Gmail not read yet · see Health", "Navilife can't read Personal Gmail yet.", false))
        assertEquals("–", unknown.value)
        assertEquals("not read yet", unknown.line)
    }

    @Test
    fun theTilesComeInOrderAndMailOnlyOnceCounted() {
        val tl = DayTimeline.EMPTY.copy(anytime = listOf(task("a")))
        assertEquals(listOf(HeaderTileKind.TASKS, HeaderTileKind.NEXT), TodayHeaderRules.tiles(tl, emptyList(), null).map { it.kind })
        val mail = MailUnreadLine("Personal · 3 unread", "3 unread emails in Personal.", false, unread = 3)
        assertEquals(listOf(HeaderTileKind.TASKS, HeaderTileKind.NEXT, HeaderTileKind.MAIL), TodayHeaderRules.tiles(tl, emptyList(), mail).map { it.kind })
    }

    @Test
    fun theWeatherChipIsTheTemperatureAndTheSky() {
        assertEquals("14° light rain", TodayHeaderRules.weatherChip("14° · light rain from 16:00"))
        assertEquals("12° light rain", TodayHeaderRules.weatherChip("12° · light rain until 15:00"))
        assertEquals("16° partly cloudy", TodayHeaderRules.weatherChip("16° · partly cloudy, dry today"))
        assertEquals("3° clear", TodayHeaderRules.weatherChip("3° · clear"))
        assertEquals("-2° light snow", TodayHeaderRules.weatherChip("-2° · light snow"))
        assertEquals("9° · work light rain", TodayHeaderRules.weatherChip("Oakfield 9° now · Riverside 14°, light rain from 15:00 — take a coat"))
        assertEquals("9° · work dry", TodayHeaderRules.weatherChip("Oakfield 9° now · Riverside 16°, dry"))
        assertNull(TodayHeaderRules.weatherChip(null))
        assertNull(TodayHeaderRules.weatherChip(" "))
    }
}
