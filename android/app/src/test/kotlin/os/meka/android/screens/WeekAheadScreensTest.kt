package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasScrollAction
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.WeekAheadPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.AboutField
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.EventFields
import os.meka.core.domain.GoalHorizon
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.WeekAheadRules
import os.meka.core.domain.MekaSchema
import os.meka.core.facade.MekaCore
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import os.meka.core.sync.ReplicaStore
import os.meka.core.sync.fv
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 45 (build plan: "the rest of Ask's places", Week ahead next): Ask → More → Week ahead on the
 * Fold's cover and inner screens with nothing on, one event and a full week (office days with a short Thursday, the
 * younger boy's INSET day while at the office, a swimming lesson and football from the Kids calendar, a long meeting
 * at a place, the garden bin, two goals with one picked, the partner named), Dark and Light. Saved under `week-ahead/`
 * and read for nonsense, cut lines, broken words, split dates, times and numbers, a word left alone and a line starting
 * with a dot. By hand: Sunday heads-up's On turns it off (its times fold away) and back on; a goal's row picks it for
 * the week ("1 picked · up to 3") and a second tap unpicks it. Events are written as the calendar mirror writes them.
 * Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class WeekAheadScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val london = TimeZone.of("Europe/London")
    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime(2026, 10, day, h, m).toInstant(london).toEpochMilliseconds()

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private val longTitle = "Quarterly planning with the design and engineering leads"

    private fun seed(store: ReplicaStore, fill: Fill) {
        if (fill == Fill.EMPTY) return
        var n = 0
        val mirror = Replica("hh", "server", HlcClock("server", { now }), store, MekaSchema) { "seed${n++}" }
        fun event(id: String, title: String, start: Long, end: Long, calendar: String, place: String? = null) =
            mirror.commitLocal(EntityTypes.EVENT, id, buildMap {
                put(EventFields.TITLE, title.fv()); put(EventFields.START_AT, start.fv()); put(EventFields.END_AT, end.fv())
                put(EventFields.PROVIDER, "google".fv()); put(EventFields.ACCOUNT, "alex@example.com".fv())
                put(EventFields.CALENDAR, calendar.fv())
                place?.let { put(EventFields.LOCATION, it.fv()) }
            })
        event("ev1", "Swimming lesson", at(21, 18, 30), at(21, 19, 15), "Kids", place = "Oakfield Leisure Centre")
        if (fill != Fill.MANY) return
        event("ev2", longTitle, at(22, 10, 0), at(22, 11, 30), "Personal", place = "Harbour House, Dock Street")
        event("ev3", "Dinner with Sam and Jo", at(23, 19, 30), at(23, 22, 0), "Personal")
        event("ev4", "Football training", at(24, 10, 0), at(24, 11, 0), "Kids", place = "Oakfield Rec")
        event("ev5", "Car boot sale", at(25, 8, 0), at(25, 11, 0), "Personal")
    }

    private fun setUp(theme: ThemeChoice, fill: Fill): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val store = InMemoryReplicaStore()
        seed(store, fill)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = store, transport = null,
            secureRandom = Random(45), timeZone = { london }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            if (fill != Fill.EMPTY) core.setWorkSchedule(listOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60 + 30, true)
            if (fill == Fill.MANY) {
                core.setWorkDayHours(4, 9 * 60, 15 * 60 + 30)
                core.setAboutYou(AboutField.PARTNER_NAME, "Jordan")
                core.addAboutYouChild("Alex, U7, Oakfield School")
                core.addAboutYouChild("Sam, U10, Oakfield School")
                check(core.addSchool("INSET 23 Oct") != null)
                check(!core.addBin("Garden waste every other Friday from 23 Oct").refused)
                val run = core.addGoal("Run 5k", null, GoalHorizon.MONTH)
                core.addGoal("Learn Spanish", null, GoalHorizon.QUARTER)
                check(core.pickWeekGoal(run, true) != null)
            }
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { WeekAheadPane(core, onClose = {}) }
            }
        }
        turns()
        return core
    }

    /** Runs the main looper and the frames by turns (a tap reaches the recomposer through it). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /** Waits (real time too: the core finishes on its own thread) until [what] holds. */
    private fun until(what: () -> Boolean) {
        var i = 0
        while (!what() && i++ < 80) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns(1_000)
    }

    /** Scrolls the pane to [matcher]; the clock runs while it does (a lazy list's scroll waits for a frame). */
    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollAction()).performScrollToNode(matcher)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        turns(1_000)
    }

    private fun tap(name: String, what: String, matcher: SemanticsMatcher) {
        scrollTo(matcher)
        val nodes = compose.onAllNodes(matcher and hasClickAction())
        if (nodes.fetchSemanticsNodes().isEmpty()) fail("$name: no $what to tap")
        nodes[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
    }

    private fun check(core: MekaCore, name: String, atTop: Boolean = true) {
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("week-ahead", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        // The title is at the top; a shot scrolled to the foot has left it behind.
        if (atTop) ScreenShots.assertShows(compose, name, "Week ahead")
        val v = core.weekAheadView.value
        val setting = core.weekAheadSettingView.value
        val lines = listOfNotNull(v.summary, v.mail, v.protectedLine, v.balance, setting.summary, v.goals.line.takeIf { it.isNotEmpty() }) +
            v.sync + v.days.flatMap { d -> d.lines.map { it.text } + listOfNotNull(d.dayLine, d.weather, d.protectedLine).joinToString(" · ") } +
            v.goals.goals.flatMap { listOf(it.title, it.meta) }
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertNoDotEndingALine(compose, name, lines)
        ScreenShots.assertReadable(compose, name, v.days.flatMap { d -> d.lines.map { it.text } }, mustShow = false)
        // The range under the title reads on one line ("This week · Wed 21 – Sun 25 Oct").
        ScreenShots.assertEachOneLine(compose, name, listOf("${v.title} · ${v.rangeLabel}"))
    }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val core = setUp(theme, fill)
        val v = core.weekAheadView.value
        if (v.days.map { it.label }.firstOrNull()?.startsWith("Wed") != true) fail("$name: the week starts ${v.days.firstOrNull()?.label}, not today (Wed)")
        check(core, name)
        when (fill) {
            Fill.EMPTY -> {
                // Nothing on but the default work hours: the summary says the office days and that it's otherwise quiet.
                if (v.summary != WeekAheadRules.QUIET && !v.summary.endsWith("otherwise quiet")) fail("$name: nothing on, but \"${v.summary}\"")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept(v.summary))
                // The balance still says what the plan leaves short ("family 0 h of 8 h …"), kept whole at its dots.
                v.balance?.let { ScreenShots.assertShows(compose, name, LoneWordGlue.kept(it)) }
                if (v.sync.isNotEmpty() || v.goals.goals.isNotEmpty()) fail("$name: nothing on, but a family sync or goals")
            }
            Fill.ONE -> {
                if (v.days.first().lines.none { "Swimming lesson" in it.text }) fail("$name: today's swimming lesson isn't on Wednesday")
                ScreenShots.assertShows(compose, name, "Swimming lesson")
            }
            Fill.MANY -> {
                if (v.days.size != 5) fail("$name: ${v.days.size} days from Wednesday to Sunday")
                if (v.sync.none { "office" in it }) fail("$name: the INSET day at the office isn't in the family sync (${v.sync})")
                if (v.goals.line != "1 picked · up to 3") fail("$name: the goals say \"${v.goals.line}\"")
                if (v.days.first { it.label.startsWith("Thu") }.dayLine != "Office 09:00–15:30") fail("$name: Thursday's day line ${v.days.first { it.label.startsWith("Thu") }.dayLine}")
                ScreenShots.assertShows(compose, name, "Swimming lesson")
            }
        }
    }

    // ---- Shots ----

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() = shoot("cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() = shoot("cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() = shoot("cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() = shoot("cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerEmpty() = shoot("inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() = shoot("inner-dark-many", Fill.MANY)

    /** The bottom of the full week on the cover screen: the family sync, the goals and the heads-up, read the same way. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverManyFoot() {
        val name = "cover-dark-many-foot"
        val core = setUp(ThemeChoice.DARK, Fill.MANY)
        // Section labels draw upper case; the heads-up's chip is found by what TalkBack reads.
        scrollTo(hasContentDescription("Sunday heads-up, on"))
        check(core, name, atTop = false)
        ScreenShots.assertShows(compose, name, "Sunday heads-up, on")
    }

    // ---- By hand ----

    /** Sunday heads-up's chip turns it off (the times fold away, the line says Off) and back on. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun headsUpOffAndOnByHand() {
        val name = "cover-dark-heads-up"
        val core = setUp(ThemeChoice.DARK, Fill.MANY)
        if (!core.weekAheadSettingView.value.on) fail("$name: the heads-up starts off")
        tap(name, "the heads-up's On", hasContentDescription("Sunday heads-up, on"))
        until { !core.weekAheadSettingView.value.on }
        if (core.weekAheadSettingView.value.on) fail("$name: tapping On left the heads-up on")
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(core.weekAheadSettingView.value.summary))
        if (compose.onAllNodes(hasContentDescription("Sundays at 18:00")).fetchSemanticsNodes().isNotEmpty()) fail("$name: off, but the times still show")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("week-ahead", name))
        tap(name, "the heads-up's Off", hasContentDescription("Sunday heads-up, off"))
        until { core.weekAheadSettingView.value.on }
        if (!core.weekAheadSettingView.value.on) fail("$name: tapping Off left the heads-up off")
        scrollTo(hasContentDescription("Sundays at 18:00"))
        ScreenShots.assertShows(compose, name, "18:00")
    }

    /** A goal's row picks it for the week and a second tap unpicks it; the line above says so each time. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun aGoalPicksAndUnpicksByHand() {
        val name = "cover-dark-goal-pick"
        val core = setUp(ThemeChoice.DARK, Fill.MANY)
        val spanish = core.weekAheadView.value.goals.goals.first { it.title == "Learn Spanish" }
        if (spanish.picked) fail("$name: Learn Spanish starts picked")
        tap(name, "Learn Spanish", hasContentDescription(spanish.spoken))
        until { core.weekAheadView.value.goals.picked == 2 }
        if (core.weekAheadView.value.goals.line != "2 picked · up to 3") fail("$name: after a pick \"${core.weekAheadView.value.goals.line}\"")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("week-ahead", name))
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept("2 picked · up to 3"))
        val picked = core.weekAheadView.value.goals.goals.first { it.title == "Learn Spanish" }
        tap(name, "Learn Spanish again", hasContentDescription(picked.spoken))
        until { core.weekAheadView.value.goals.picked == 1 }
        if (core.weekAheadView.value.goals.line != "1 picked · up to 3") fail("$name: after unpicking \"${core.weekAheadView.value.goals.line}\"")
    }
}
