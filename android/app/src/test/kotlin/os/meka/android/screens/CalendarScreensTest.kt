package os.meka.android.screens

import androidx.compose.foundation.background
import android.os.Looper
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import kotlin.test.fail
import org.robolectric.Shadows
import os.meka.core.domain.WeekBoardRules
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.calendar.CalendarRoute
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.EventFields
import os.meka.core.domain.MekaSchema
import os.meka.core.facade.MekaCore
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import os.meka.core.sync.ReplicaStore
import os.meka.core.sync.fv
import kotlin.random.Random

/**
 * The quality gate, slice 8 (build plan: "the same shots for every other screen, Calendar's Agenda next"): the Calendar
 * tab's Agenda on the Fold's cover and inner screens, with nothing on, with one event and with a full week (an event
 * running now, one already over, a long title, a place, an all-day day off, a planned task, two calendars), Dark and
 * Light. Saved under `calendar/` (the `fold-screens` artifact, then the `screens` branch's docs/screens/fold/calendar/)
 * and read for nonsense on screen, cut lines and words broken in two. Events are written into the replica as the
 * server's calendar mirror would write them. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class CalendarScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val london = TimeZone.of("Europe/London")
    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime(2026, 10, day, h, m).toInstant(london).toEpochMilliseconds()

    /** UTC midnight of 2026-10-[day], as the mirror writes an all-day event's bounds. */
    private fun utcMidnight(day: Int) = LocalDateTime(2026, 10, day, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private val longTitle = "Quarterly planning with the design and engineering leads"

    private fun titles(fill: Fill): List<String> = when (fill) {
        Fill.EMPTY -> emptyList()
        Fill.ONE -> listOf("Swimming lesson")
        Fill.MANY -> listOf("Call with Sam", "Swimming lesson", longTitle)
    }

    /** Writes the events into [store] as the server's calendar mirror does, before the core opens it. */
    private fun seed(store: ReplicaStore, fill: Fill, hand: Boolean = false) {
        if (fill == Fill.EMPTY) return
        var n = 0
        val mirror = Replica("hh", "server", HlcClock("server", { now }), store, MekaSchema) { "seed${n++}" }
        fun event(id: String, title: String, start: Long, end: Long, calendar: String, place: String? = null, allDay: Boolean = false) =
            mirror.commitLocal(EntityTypes.EVENT, id, buildMap {
                put(EventFields.TITLE, title.fv()); put(EventFields.START_AT, start.fv()); put(EventFields.END_AT, end.fv())
                put(EventFields.PROVIDER, "google".fv()); put(EventFields.ACCOUNT, "alex@example.com".fv())
                put(EventFields.CALENDAR, calendar.fv())
                if (allDay) put(EventFields.ALL_DAY, true.fv())
                place?.let { put(EventFields.LOCATION, it.fv()) }
            })
        event("ev1", "Swimming lesson", at(21, 18, 30), at(21, 19, 15), "Kids", place = "Oakfield Leisure Centre")
        if (fill != Fill.MANY) return
        event("ev2", "Dentist", at(21, 9, 30), at(21, 10, 0), "Personal")
        event("ev3", "Call with Sam", at(21, 17, 0), at(21, 17, 45), "Personal")
        event("ev4", longTitle, at(22, 10, 0), at(22, 11, 30), "Personal")
        event("ev5", "Inset day", utcMidnight(23), utcMidnight(24), "Kids", allDay = true)
        event("ev6", "Football training", at(24, 10, 0), at(24, 11, 0), "Kids", place = "Oakfield Rec")
        if (!hand) return
        // Works by hand: four all-day things on Friday, so "+1 more" shows under the first three.
        event("ev7", "Book fair", utcMidnight(23), utcMidnight(24), "Kids", allDay = true)
        event("ev8", "Mufti day", utcMidnight(23), utcMidnight(24), "Kids", allDay = true)
        event("ev9", "Bin day", utcMidnight(23), utcMidnight(24), "Kids", allDay = true)
    }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK, hand: Boolean = false): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val store = InMemoryReplicaStore()
        seed(store, fill, hand)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = store, transport = null,
            secureRandom = Random(13), timeZone = { london }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            if (fill == Fill.MANY) core.schedule(core.addTask("Pay the window cleaner"), at(22, 12, 0))
            core.tick()
            // Works by hand: one event hidden from my day, so the day's foot has a Show to tap.
            if (hand) {
                val long = core.calendarView.value.sections.flatMap { it.rows }.firstOrNull { it.title == longTitle }?.event
                    ?: fail("$name: no \"$longTitle\" to hide")
                core.hideEvent(long.id)
                core.tick()
            }
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    CalendarRoute(core)
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("calendar", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertReadable(compose, name, titles(fill), mustShow = false)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertShows(compose, name, "Calendar")
        ScreenShots.assertShows(compose, name, "Today")
        val today = core.calendarView.value.sections.first()
        when (fill) {
            // Nothing on: today says so in words, never a bare heading.
            Fill.EMPTY -> today.emptyLine?.let { ScreenShots.assertShows(compose, name, it) }
            Fill.ONE -> ScreenShots.assertShows(compose, name, "Swimming lesson")
            Fill.MANY -> {
                ScreenShots.assertShows(compose, name, "Swimming lesson")
                ScreenShots.assertShows(compose, name, "Call with Sam")
            }
        }
        return core
    }

    /** Runs the main looper and the frames by turns, until [done] holds, then lets the screen settle. */
    private fun until(done: () -> Boolean) {
        repeat(40) {
            if (done()) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        repeat(30) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun shows(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    /** Every button and tab drawn answers a 48 dp touch. */
    private fun assertTouchTargets(name: String) {
        val tappable = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) or
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        val small = compose.onAllNodes(hasClickAction() and tappable)
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?"
                "\"$label\" ${(n.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    /** Scrolls the agenda (not the week strip) until a node matching [m] is composed; fails with [what] if none. */
    private fun reach(name: String, m: SemanticsMatcher, what: String): SemanticsNodeInteractionCollection {
        if (!shows(m)) {
            val lists = compose.onAllNodes(hasScrollToNodeAction())
            for (i in 0 until lists.fetchSemanticsNodes().size) {
                runCatching { lists[i].performScrollToNode(m) }
                if (shows(m)) break
            }
            until { shows(m) }
        }
        if (!shows(m)) fail("$name: no $what")
        return compose.onAllNodes(m)
    }

    /**
     * Works by hand (quality gate (3)): on the Agenda with a full week, a day off with four all-day things and one event
     * hidden from my day, every button and tab answers a 48 dp touch; "+1 more" unfolds the fourth all-day thing; Show
     * brings the hidden event back; a day pill lights its day; an event opens its detail and Close shuts it; Board
     * switches the view.
     */
    private fun agendaByHand(core: MekaCore) {
        val name = "calendar-by-hand"
        assertTouchTargets(name)

        // "+1 more" under Friday's all-day rows.
        reach(name, hasText("+1 more") and hasClickAction(), "\"+1 more\"")[0].performSemanticsAction(SemanticsActions.OnClick)
        until { !shows(hasText("+1 more")) }
        if (shows(hasText("+1 more"))) fail("$name: \"+1 more\" didn't unfold")
        listOf("Inset day", "Book fair", "Mufti day", "Bin day").forEach { reach(name, hasText(it), "\"$it\" after +1 more") }
        assertTouchTargets(name)

        // Show on the hidden event.
        reach(name, hasText("Show") and hasClickAction(), "Show on the hidden event")[0].performSemanticsAction(SemanticsActions.OnClick)
        until { core.calendarView.value.sections.all { it.hidden.isEmpty() } }
        if (core.calendarView.value.sections.any { it.hidden.isNotEmpty() }) fail("$name: Show left the event hidden")

        // A day pill lights its day.
        val week = core.calendarView.value.weeks.first()
        val day = week.days.firstOrNull { it.sectionId != null && !it.isToday } ?: fail("$name: no other day to tap")
        val pill = hasContentDescription(day.accessibilityLabel) and hasClickAction()
        reach(name, pill, "pill for ${day.accessibilityLabel}")[0].performSemanticsAction(SemanticsActions.OnClick)
        val lit = pill and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        until { shows(lit) }
        if (!shows(lit)) fail("$name: tapping ${day.accessibilityLabel} didn't light it")

        // An event opens its detail; Close shuts it.
        reach(name, hasText("Swimming lesson") and hasClickAction(), "Swimming lesson to tap")[0].performSemanticsAction(SemanticsActions.OnClick)
        val close = hasText("Close") and hasClickAction()
        until { shows(close) }
        if (!shows(close)) fail("$name: tapping Swimming lesson opened nothing")
        assertTouchTargets(name)
        compose.onAllNodes(close)[0].performSemanticsAction(SemanticsActions.OnClick)
        until { !shows(close) }
        if (shows(close)) fail("$name: Close left the detail open")

        // Board switches the view (the Agenda's summary line goes with it).
        val summary = core.calendarView.value.summary
        reach(name, hasText(WeekBoardRules.VIEW_BOARD) and hasClickAction(), "Board switch")[0].performSemanticsAction(SemanticsActions.OnClick)
        until { !shows(hasText(summary)) }
        if (shows(hasText(summary))) fail("$name: Board left the Agenda showing")
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverEmpty() { shoot("cover-dark-empty", Fill.EMPTY) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverOne() { shoot("cover-dark-one", Fill.ONE) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverMany() { shoot("cover-dark-many", Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverManyLight() { shoot("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun agendaByHand() { agendaByHand(shoot("cover-dark-by-hand", Fill.MANY, hand = true)) }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerEmpty() { shoot("inner-dark-empty", Fill.EMPTY) }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerOne() { shoot("inner-dark-one", Fill.ONE) }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerMany() { shoot("inner-dark-many", Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerManyLight() { shoot("inner-light-many", Fill.MANY, ThemeChoice.LIGHT) }
}
