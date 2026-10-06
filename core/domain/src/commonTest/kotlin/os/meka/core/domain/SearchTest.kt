package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private val hour = 3_600_000L
    private var n = 0
    private fun ids(): String = "S${n++}"

    private val d = world.device("android")
    private val lists = Lists(d.replica, ::ids, { world.clock.nowMs })
    private val renewals = Renewals(d.replica, ::ids, { world.clock.nowMs })
    private val goals = Goals(d.replica, ::ids, { world.clock.nowMs })

    init {
        world.clock.nowMs = world.clock.nowMs.floorDiv(dayMs) * dayMs + 10 * hour
    }

    private fun today() = world.clock.nowMs.floorDiv(dayMs)

    private fun sources(dev: Device = d, events: List<CalendarEvent> = emptyList()): SearchSources {
        val l = Lists(dev.replica, ::ids, { world.clock.nowMs })
        val g = Goals(dev.replica, ::ids, { world.clock.nowMs }).view(dev.tasks.all())
        return SearchSources(
            tasks = dev.tasks.all(),
            events = events,
            waiting = l.waitingItems(),
            decisions = l.decisionItems(includeSuperseded = true),
            renewals = Renewals(dev.replica, ::ids, { world.clock.nowMs }).view().all,
            habits = g.habits,
            goals = g.goals,
        )
    }

    private fun search(q: String, dev: Device = d, events: List<CalendarEvent> = emptyList()) =
        Search.run(q, sources(dev, events), world.clock.nowMs, LocalCalendar.UTC)

    @Test
    fun normalisingIgnoresCaseAccentsAndPunctuation() {
        assertEquals("cafe creme brulee", SearchRules.normalize("  Café — Crème-Brûlée! "))
        assertEquals("adas birthday", SearchRules.normalize("Ada’s birthday"))
        assertEquals(listOf("boil", "serv"), SearchRules.tokens("Boil  serv boil"))
        assertTrue(SearchRules.tokens("a").isEmpty(), "one letter is too little to search")
        assertTrue(SearchRules.tokens(" - ").isEmpty())
        assertEquals(listOf("a", "b"), SearchRules.tokens("a b"))
    }

    @Test
    fun everyWordMustStartAWordInTheItem() {
        d.tasks.create(NewTask("Book dentist appointment"))
        d.tasks.create(NewTask("Dentures for Gran"))
        d.tasks.create(NewTask("Call the bank"))

        assertEquals(listOf("Book dentist appointment", "Dentures for Gran"), search("dent").hits.map { it.title }.sorted())
        assertEquals(listOf("Book dentist appointment"), search("dent book").hits.map { it.title })
        assertTrue(search("entist").hits.isEmpty(), "the middle of a word doesn't match")
        assertEquals("No matches for “zebra”", search(" zebra ").summary)
        assertEquals("", search("").summary)
        assertFalse(search("x").active)
        assertEquals("2 matches", search("DENT").summary)
    }

    @Test
    fun titlesOutrankNotesAndANoteMatchShowsASnippet() {
        d.tasks.create(NewTask("Ring surgery", notes = "Ask about the crown on the lower left, and whether the dentist can see me before Friday at the latest please"))
        d.tasks.create(NewTask("Dentist: pay invoice"))
        val hits = search("dentist").groups.single { it.kind == SearchKind.TASK }.hits
        assertEquals(listOf("Dentist: pay invoice", "Ring surgery"), hits.map { it.title })
        assertNull(hits[0].snippet, "the title already shows the match")
        val snippet = hits[1].snippet!!
        assertTrue("dentist" in snippet, snippet)
        assertTrue(snippet.length <= SearchRules.SNIPPET_CHARS + 2, snippet)
        assertTrue(snippet.startsWith("…") || snippet.startsWith("Ask"), snippet)
    }

    @Test
    fun stepsAreSearchedToo() {
        val id = d.tasks.create(NewTask("Sunday reset"))
        d.tasks.addChecklistItem(id, "Water the plants")
        d.tasks.addChecklistItem(id, "Bins out")
        val hit = search("plants").hits.single()
        assertEquals(id, hit.id)
        assertEquals("Sunday reset", hit.task?.title)
        assertEquals(SearchTarget.TASK, hit.target)
        assertEquals("Water the plants · Bins out", hit.snippet)
        assertEquals("0 of 2 steps", hit.detail)
    }

    @Test
    fun tasksSayWhenTheyArePlannedDueOrSnoozed() {
        val t = today()
        d.tasks.create(NewTask("Gym planned", scheduledAtMs = t * dayMs + 18 * hour))
        d.tasks.create(NewTask("Gym due", dueAtMs = (t + 1) * dayMs + 9 * hour))
        d.tasks.create(NewTask("Gym late", dueAtMs = (t - 2) * dayMs + 9 * hour))
        d.tasks.create(NewTask("Gym bag"))
        val hits = search("gym").groups.single().hits
        assertEquals(listOf("Gym late", "Gym planned", "Gym due", "Gym bag"), hits.map { it.title }, "soonest first, undated last")
        assertEquals(listOf("Overdue · due ${CivilDate.shortLabel(t - 2)}", "Planned today 18:00", "Due tomorrow", null), hits.map { it.detail })

        val snoozed = d.tasks.create(NewTask("Gym shoes"))
        d.tasks.snoozeOccurrence(snoozed, 2)
        assertEquals("Snoozed until ${CivilDate.shortLabel(t + 2)}", search("shoes").hits.single().detail)
    }

    @Test
    fun doneSomedayAndOpenTasksAreGroupedApartAndCancelledOnesAreLeftOut() {
        val done = d.tasks.create(NewTask("Paint the fence"))
        d.tasks.complete(done)
        lists.addSomeday("Paint the shed", SomedayKind.HOME_IMPROVEMENT)
        d.tasks.create(NewTask("Paint samples"))
        val gone = d.tasks.create(NewTask("Paint the gate"))
        d.tasks.delete(gone)

        val v = search("paint")
        assertEquals(listOf(SearchKind.TASK, SearchKind.SOMEDAY, SearchKind.DONE), v.groups.map { it.kind })
        assertEquals("Home", v.groups[1].hits.single().detail)
        assertEquals(SearchTarget.LISTS_SOMEDAY, v.groups[1].hits.single().target)
        assertEquals(SearchTarget.TASK, v.groups[2].hits.single().target)
        assertEquals("Done today", v.groups[2].hits.single().detail)
        assertEquals(3, v.total)
    }

    @Test
    fun aRoutinesDoneOccurrencesFoldIntoTheLatest() {
        val id = d.tasks.create(NewTask("Stretch"))
        d.tasks.setRepeatRule(id, Recurrence.Daily().encode())
        repeat(3) {
            val open = d.tasks.all().single { it.title == "Stretch" && !it.isDone }
            d.tasks.complete(open.id)
            world.clock.advance(dayMs)
        }
        val v = search("stretch")
        val done = v.groups.single { it.kind == SearchKind.DONE }.hits
        assertEquals(1, done.size)
        assertEquals("Done yesterday · ↻ Every day", done.single().detail)
        assertEquals(1, v.groups.single { it.kind == SearchKind.TASK }.hits.size, "the next occurrence is open")
    }

    @Test
    fun listsRenewalsHabitsAndGoalsAreFoundWithWhereTheyLive() {
        lists.addWaiting("Refund for boots", who = "Sports Direct")
        val old = lists.recordDecision("Keep the Golf", rationale = "Cheap to run")
        lists.replaceDecision(old, "Sell the Golf", rationale = "Barely used since moving")
        renewals.add("Golf MOT", ObligationKind.MOT, today() + 40, RenewalRepeat.YEARLY, cost = "54.85")
        val goal = goals.addGoal("Golf handicap under 20", target = "Play 10 rounds")
        goals.addHabit("Golf practice", perWeek = 2, goalId = goal)

        val v = search("golf")
        assertEquals(listOf(SearchKind.DECISION, SearchKind.RENEWAL, SearchKind.GOAL, SearchKind.HABIT), v.groups.map { it.kind })
        assertEquals(listOf("Sell the Golf", "Keep the Golf"), v.groups[0].hits.map { it.title }, "a replaced decision comes after the one that stands")
        assertEquals("Replaced", v.groups[0].hits[1].detail)
        assertEquals(SearchTarget.LISTS_DECISIONS, v.groups[0].hits[0].target)
        assertEquals(SearchTarget.LISTS_RENEWALS, v.groups[1].hits.single().target)
        assertEquals(SearchTarget.GOALS, v.groups[2].hits.single().target)
        assertEquals(SearchTarget.GOALS, v.groups[3].hits.single().target)

        val w = search("sports").hits.single()
        assertEquals(SearchKind.WAITING, w.kind)
        assertEquals(SearchTarget.LISTS_WAITING, w.target)
        assertEquals("Refund for boots", w.title)
        assertEquals(1, search("mot").groups.single { it.kind == SearchKind.RENEWAL }.hits.size, "the kind label counts")
        assertEquals("Barely used since moving", search("barely").hits.single().snippet)
    }

    @Test
    fun eventsComeUpcomingFirstThenMostRecentWithTimesAndPlaces() {
        val t = today()
        val e = { id: String, start: Long, allDay: Boolean ->
            CalendarEvent(id, "Barça match", start, start + if (allDay) dayMs else 2 * hour, allDay, if (allDay) null else "Camp Nou", "fixtures", null, "FC Barcelona")
        }
        val events = listOf(
            e("past", (t - 7) * dayMs + 20 * hour, false),
            e("later", (t + 10) * dayMs + 20 * hour, false),
            e("soon", (t + 1) * dayMs + 20 * hour, false),
            e("older", (t - 30) * dayMs + 20 * hour, false),
            e("allday", t * dayMs, true),
        )
        val hits = search("barca", events = events).hits
        assertEquals(listOf("allday", "soon", "later", "past", "older"), hits.map { it.id })
        assertEquals("Tomorrow · 20:00–22:00 · Camp Nou", hits[1].detail)
        assertEquals("Today · all day", hits[0].detail)
        assertTrue(hits.all { it.target == SearchTarget.INFO && it.task == null })
        assertEquals(4, search("camp nou", events = events).total, "places count (the all-day one has none)")
    }

    @Test
    fun bigGroupsShowTwentyAndCountTheRest() {
        repeat(25) { d.tasks.create(NewTask("Invoice ${it + 1}")) }
        val g = search("invoice").groups.single()
        assertEquals(SearchRules.PER_GROUP, g.hits.size)
        assertEquals(5, g.more)
        assertEquals("and 5 more · keep typing to narrow", g.moreLine)
        assertEquals(25, search("invoice").total)
        assertEquals(listOf("Invoice 12"), search("invoice 12").hits.map { it.title }.filter { it.endsWith("12") })
    }

    @Test
    fun aPhraseInTheTitleComesFirst() {
        d.tasks.create(NewTask("Car insurance renewal"))
        d.tasks.create(NewTask("Renewal of the car park permit"))
        d.tasks.create(NewTask("Insurance for the car"))
        val titles = search("car insurance").hits.map { it.title }
        assertEquals("Car insurance renewal", titles.first())
        assertEquals(2, titles.size)
    }

    @Test
    fun whatTheOtherDeviceAddedIsFoundOnceSynced() {
        val mac = world.device("mac")
        mac.tasks.create(NewTask("Passport photos"))
        Lists(mac.replica, ::ids, { world.clock.nowMs }).addWaiting("Passport back", who = "HM Passport Office")
        assertTrue(search("passport").hits.isEmpty())
        mac.sync(); d.sync()
        val v = search("passport")
        assertEquals(listOf(SearchKind.TASK, SearchKind.WAITING), v.groups.map { it.kind })
        assertEquals(v.hits.map { it.id }, search("passport", dev = mac).hits.map { it.id }, "both devices find the same")
    }
}
