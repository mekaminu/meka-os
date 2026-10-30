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
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
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
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.today.BriefPane
import os.meka.core.domain.AboutField
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.EventFields
import os.meka.core.domain.HabitTiming
import os.meka.core.domain.HeadlineFields
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.MekaSchema
import os.meka.core.facade.MekaCore
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.Replica
import os.meka.core.sync.ReplicaStore
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.fv
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 46 (build plan: "the rest of Ask's places", Brief next): the morning brief on the Fold's
 * cover and inner screens at 07:40 on a Wednesday with nothing on, one event and a full morning (an office day, an
 * overdue task and three for today, a long meeting at a place, something to chase today, a habit, a fast running,
 * headlines from three topics, one with no link), Dark and Light, plus the full morning's foot on the cover screen.
 * Saved under `brief/` and read for nonsense, cut lines, broken words, split dates, times and numbers, a word left
 * alone and a dot starting or ending a line. By hand: Topics opens the topic chips and a chip turns a topic on, Done
 * folds them away; a length chip picks Short; a task's circle completes it; Got it marks the brief read and leaves
 * Done. Events are written as the calendar mirror writes them, headlines as the server's feed does. Invented data
 * only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class BriefScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val london = TimeZone.of("Europe/London")
    private val now = 1_792_564_800_000L // Wed 21 Oct 2026, 07:40 in London

    private fun at(h: Int, m: Int = 0) = LocalDateTime(2026, 10, 21, h, m).toInstant(london).toEpochMilliseconds()

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private val longTitle = "Quarterly planning with the design and engineering leads"

    private val ops = InMemoryServerOpStore()
    private val serverClock = HlcClock("server", { now })
    private var n = 0

    private fun headline(id: String, title: String, topic: String, agoMin: Long, source: String, url: String? = "https://news.example/$id") {
        listOfNotNull(
            HeadlineFields.TITLE to FieldValue.Text(title),
            url?.let { HeadlineFields.URL to FieldValue.Text(it) },
            HeadlineFields.SOURCE to FieldValue.Text(source),
            HeadlineFields.TOPIC to FieldValue.Text(topic),
            HeadlineFields.PUBLISHED_AT to FieldValue.Int64(now - agoMin * 60_000L),
            HeadlineFields.REMOVED to FieldValue.Bool(false),
        ).forEach { (field, value) ->
            ops.append(Op("srv${n++}", "hh", EntityTypes.HEADLINE, id, field, value, serverClock.now(), emptyList(), "server"))
        }
    }

    /** Navilife's server, as far as the brief asks it: sync (the headlines it mirrors). */
    private inner class Server : SyncTransport {
        val service = SyncService(ops)
        override suspend fun push(request: PushRequest) = service.push(request)
        override suspend fun pull(request: PullRequest) = service.pull(request)
    }

    private fun seed(store: ReplicaStore, fill: Fill) {
        if (fill == Fill.EMPTY) return
        var k = 0
        val mirror = Replica("hh", "server", HlcClock("server", { now }), store, MekaSchema) { "seed${k++}" }
        fun event(id: String, title: String, start: Long, end: Long, place: String? = null) =
            mirror.commitLocal(EntityTypes.EVENT, id, buildMap {
                put(EventFields.TITLE, title.fv()); put(EventFields.START_AT, start.fv()); put(EventFields.END_AT, end.fv())
                put(EventFields.PROVIDER, "google".fv()); put(EventFields.ACCOUNT, "alex@example.com".fv())
                put(EventFields.CALENDAR, "Personal".fv())
                place?.let { put(EventFields.LOCATION, it.fv()) }
            })
        event("ev1", "Swimming lesson", at(18, 30), at(19, 15), place = "Oakfield Leisure Centre")
        if (fill != Fill.MANY) return
        event("ev2", longTitle, at(10, 0), at(11, 30), place = "Harbour House, Dock Street")
        headline("top-0", "Rail fares to rise less than expected next year", "top", 40, "BBC News")
        headline("world-0", "Leaders meet to agree a new climate fund", "world", 95, "BBC News")
        headline("ai-0", "A new open model tops the coding charts", "ai", 150, "The Verge")
        // Some feeds send the headline only, with no link: it isn't a button.
        headline("top-1", "Storm warning for the coast this weekend", "top", 200, "BBC News", url = null)
    }

    private fun setUp(theme: ThemeChoice, fill: Fill): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val store = InMemoryReplicaStore()
        seed(store, fill)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = store, transport = if (fill == Fill.MANY) Server() else null,
            secureRandom = Random(46), timeZone = { london }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            if (fill == Fill.MANY) {
                core.syncNow()
                core.setWorkSchedule(listOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60 + 30, true)
                core.setAboutYou(AboutField.SHOWN_NAME, "Alex")
                val today = core.weekBoard.value.todayEpochDay
                core.addOnBoardSized("Order new bins", today - 1, null)
                core.addOnBoardSized("Book the MOT", today, 30)
                core.addOnBoardSized("Fix the gate latch", today, 60)
                core.addOnBoardSized("Plan Saturday's football run with Sam", today, 120)
                core.addWaiting("Quote for the new fence", "Jordan", 0)
                core.addHabit("Read 20 pages", 5, HabitTiming.EVENING, 20, null)
                core.startFast(720)
            }
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { BriefPane(core, onClose = {}) }
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

    /**
     * Scrolls the pane to [matcher]; the clock runs while it does (a lazy list's scroll waits for a frame). The pane is
     * the one lazy list (its chip rows scroll sideways too, so it's found by its scroll-to-index action).
     */
    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
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
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("brief", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        val v = core.briefView.value
        // The greeting by the core's clock (07:40), at the top; a shot scrolled to the foot has left it behind.
        if (atTop) ScreenShots.assertShows(compose, name, "Good morning")
        val lines = listOfNotNull(
            listOfNotNull(v.dateLabel, v.workLine).joinToString(" · "), v.protectedLine, v.weatherLine, v.school?.text,
            v.focusMore, v.needsYouLine, v.daySummary, v.planLine, v.pressureLine, v.waitingLine, v.habitsLine, v.fastingLine,
            "“${v.oneLiner}”",
        ) + v.asks.map { it.line } + v.waiting.map { it.meta } + v.headlines.map { it.meta } + v.attention.mapNotNull { it.detail }
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertNoDotEndingALine(compose, name, lines)
        ScreenShots.assertReadable(compose, name, v.dayLines.map { it.title } + v.headlines.map { it.title }, mustShow = false)
        // Topics is a real button: 48 dp to tap, like News's.
        compose.onAllNodes(hasTestTag("brief-topics")).fetchSemanticsNodes().firstOrNull()?.let { topics ->
            val touchDp = topics.boundsInRoot.height / compose.density.density
            if (touchDp < 47.5f) fail("$name: Topics is ${touchDp.toInt()} dp tall to tap (at least 48)")
        }
    }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val core = setUp(theme, fill)
        val v = core.briefView.value
        if (!v.dateLabel.startsWith("Wed")) fail("$name: the brief is for \"${v.dateLabel}\", not today (Wed)")
        check(core, name)
        when (fill) {
            Fill.EMPTY -> {
                // Nothing on: the day says so, never "0 events".
                if (v.dayLines.isNotEmpty()) fail("$name: nothing on, but ${v.dayLines.size} rows in Today")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept(v.daySummary))
            }
            Fill.ONE -> {
                if (v.dayLines.none { it.title == "Swimming lesson" }) fail("$name: the swimming lesson isn't in Today (${v.dayLines.map { it.title }})")
                ScreenShots.assertShows(compose, name, "Swimming lesson")
            }
            Fill.MANY -> {
                if (v.workLine == null) fail("$name: an office Wednesday, but no work line")
                if (v.headlines.size != 4) fail("$name: ${v.headlines.size} headlines, not the four from Top stories, World and AI (chosen by default)")
                if (v.fastingLine == null) fail("$name: a fast is running, but the brief doesn't say so")
                if (v.waitingLine == null) fail("$name: a chase today, but nothing waiting")
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

    /** The foot of the full morning on the cover screen: the headlines, the one-liner and Got it, read the same way. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverManyFoot() {
        val name = "cover-dark-many-foot"
        val core = setUp(ThemeChoice.DARK, Fill.MANY)
        scrollTo(hasText("Got it"))
        check(core, name, atTop = false)
        ScreenShots.assertShows(compose, name, "Got it")
    }

    // ---- By hand ----

    /** Topics opens the chips, a chip turns its topic on, Done folds them away. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun topicsOpenPickAndCloseByHand() {
        val name = "cover-dark-topics"
        val core = setUp(ThemeChoice.DARK, Fill.MANY)
        val barca = core.briefView.value.newsTopics.first { it.id == "barca" }
        val wasOn = barca.chosen
        tap(name, "Topics", hasTestTag("brief-topics"))
        ScreenShots.assertShows(compose, name, "Done")
        tap(name, "the Barça chip", hasText(barca.label))
        until { core.briefView.value.newsTopics.first { it.id == "barca" }.chosen != wasOn }
        if (core.briefView.value.newsTopics.first { it.id == "barca" }.chosen == wasOn) fail("$name: tapping Barça left it as it was")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("brief", name))
        tap(name, "Done", hasTestTag("brief-topics"))
        if (compose.onAllNodes(hasText("Topics") and hasTestTag("brief-topics"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            fail("$name: Done didn't fold the topics away")
        }
    }

    /** A length chip picks how long the spoken brief is. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun aLengthChipPicksByHand() {
        val name = "cover-dark-length"
        val core = setUp(ThemeChoice.DARK, Fill.MANY)
        if (core.briefView.value.lengths.first { it.id == "short" }.chosen) fail("$name: Short starts chosen")
        tap(name, "Short", hasText("Short"))
        until { core.briefView.value.lengths.first { it.id == "short" }.chosen }
        if (!core.briefView.value.lengths.first { it.id == "short" }.chosen) fail("$name: tapping Short didn't pick it")
    }

    /** A task's circle in Today completes it, and the row leaves. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun aTaskTicksByHand() {
        val name = "cover-dark-tick"
        val core = setUp(ThemeChoice.DARK, Fill.MANY)
        if (core.briefView.value.dayLines.none { it.title == "Fix the gate latch" }) fail("$name: Fix the gate latch isn't in Today")
        tap(name, "Fix the gate latch's circle", hasContentDescription("Complete Fix the gate latch"))
        until { core.briefView.value.dayLines.none { it.title == "Fix the gate latch" } }
        if (core.briefView.value.dayLines.any { it.title == "Fix the gate latch" }) fail("$name: the ticked task is still in Today")
    }

    /** Got it marks the brief read; the check shows and Done is left to close it. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun gotItMarksReadByHand() {
        val name = "cover-dark-got-it"
        val core = setUp(ThemeChoice.DARK, Fill.ONE)
        if (core.briefView.value.seenToday) fail("$name: the brief starts read")
        tap(name, "Got it", hasText("Got it"))
        until { core.briefView.value.seenToday }
        if (!core.briefView.value.seenToday) fail("$name: Got it didn't mark the brief read")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("brief", name))
        ScreenShots.assertShows(compose, name, "Done")
    }
}
