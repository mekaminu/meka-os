package os.meka.android.screens

import androidx.compose.foundation.background
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
    private fun seed(store: ReplicaStore, fill: Fill) {
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
    }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val store = InMemoryReplicaStore()
        seed(store, fill)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = store, transport = null,
            secureRandom = Random(13), timeZone = { london }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            if (fill == Fill.MANY) core.schedule(core.addTask("Pay the window cleaner"), at(22, 12, 0))
            core.tick()
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
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverEmpty() = shoot("cover-dark-empty", Fill.EMPTY)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverOne() = shoot("cover-dark-one", Fill.ONE)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverMany() = shoot("cover-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverManyLight() = shoot("cover-light-many", Fill.MANY, ThemeChoice.LIGHT)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerEmpty() = shoot("inner-dark-empty", Fill.EMPTY)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerOne() = shoot("inner-dark-one", Fill.ONE)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerMany() = shoot("inner-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerManyLight() = shoot("inner-light-many", Fill.MANY, ThemeChoice.LIGHT)
}
