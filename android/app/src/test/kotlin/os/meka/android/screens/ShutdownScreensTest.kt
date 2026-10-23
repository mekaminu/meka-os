package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
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
import os.meka.android.today.ShutdownPane
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.EventFields
import os.meka.core.domain.Feeling
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.OnThisDayRules
import os.meka.core.domain.ShutdownRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import os.meka.core.sync.ReplicaStore
import os.meka.core.sync.fv
import kotlin.random.Random
import kotlin.test.fail

/**
* The quality gate, slice 47 (build plan: "the rest of Ask's places", Shutdown next): Shut down the day on the Fold's
 * cover and inner screens at 19:10 on a Wednesday (work ended 17:30) with nothing done or left, one done and one left,
 * and a full evening (seven done, so "+ 1 more"; an overdue task, one planned for 14:00, one that repeats every day and
 * a long one; tomorrow an office day with a standup, a long meeting at a place and a task), Dark and Light, plus the full
 * evening's foot on the cover screen. Saved under `shutdown/` and read for nonsense, cut lines, broken words, split
 * dates, times and numbers, a word left alone, a dot starting or ending a line, and every button's touch at least
 * 48 dp. By hand: a left task's Tomorrow carries it to tomorrow; its circle completes it; Tired is kept as today's
 * feeling; the evening card's Turn off turns it off; Shut down shuts the day down. Events are written as the calendar
 * mirror writes them. Invented data only (rule 13). Slice 48 adds the shots' findings as checks: rows in the regular
 * body weight, a left task's chips right under its words, tomorrow's titles close to their times, the wake line's
 * durations whole, On this day's offer on one line, and Shut down's label centred with no empty band above it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class ShutdownScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val london = TimeZone.of("Europe/London")
    private val now = 1_792_606_200_000L // Wed 21 Oct 2026, 19:10 in London

    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime(2026, 10, day, h, m).toInstant(london).toEpochMilliseconds()

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private val longMeeting = "Quarterly planning with the design and engineering leads"
    private val longTask = "Sort out the boys' swimming kit and the school trip forms before Friday"

    private fun seed(store: ReplicaStore, fill: Fill) {
        if (fill != Fill.MANY) return
        var k = 0
        val mirror = Replica("hh", "server", HlcClock("server", { now }), store, MekaSchema) { "seed${k++}" }
        fun event(id: String, title: String, start: Long, end: Long, place: String? = null) =
            mirror.commitLocal(EntityTypes.EVENT, id, buildMap {
                put(EventFields.TITLE, title.fv()); put(EventFields.START_AT, start.fv()); put(EventFields.END_AT, end.fv())
                put(EventFields.PROVIDER, "google".fv()); put(EventFields.ACCOUNT, "alex@example.com".fv())
                put(EventFields.CALENDAR, "Personal".fv())
                place?.let { put(EventFields.LOCATION, it.fv()) }
            })
        event("ev1", "Standup", at(22, 9, 30), at(22, 9, 45))
        event("ev2", longMeeting, at(22, 11, 0), at(22, 12, 30), place = "Harbour House, Dock Street")
    }

    private fun setUp(theme: ThemeChoice, fill: Fill): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val store = InMemoryReplicaStore()
        seed(store, fill)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = store, transport = null,
            secureRandom = Random(47), timeZone = { london }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            when (fill) {
                Fill.EMPTY -> Unit
                Fill.ONE -> {
                    core.complete(core.addTask("Book the MOT"))
                    core.addTask("Fix the gate latch")
                }
                Fill.MANY -> {
                    core.setWorkSchedule(listOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60 + 30, true)
                    listOf(
                        "Order new bins", "Reply to the school about the trip", "Book the MOT", "Pay the window cleaner",
                        "Renew the parking permit", "Call the garage about the clutch", "Water the tomatoes",
                    ).forEach { core.complete(core.addTask(it)) }
                    core.schedule(core.addTask("Fix the gate latch"), at(21, 14, 0))
                    core.setDue(core.addTask("Renew the car insurance"), at(21, 12, 0))
                    val bins = core.addTask("Take the recycling out")
                    core.setRepeat(bins, core.repeatChoices(bins).first { it.label == "Every day" }.rule)
                    core.addTask(longTask)
                    core.addOnBoardSized("Post the birthday card", core.weekBoard.value.todayEpochDay + 1, 15)
                }
            }
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { ShutdownPane(core, onClose = {}) }
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

    /** Scrolls the pane (its one lazy list; the chip rows scroll sideways too) to [matcher], the clock running. */
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

    /** Every button drawn answers a 48 dp touch (the chips under a left task and the wake alarm's were ~32 dp). */
    private fun assertTouchTargets(name: String) {
        val small = compose.onAllNodes(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?"
                "\"$label\" ${(n.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    private fun check(core: MekaCore, name: String, atTop: Boolean = true) {
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("shutdown", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertDurationsWhole(compose, name)
        if (atTop) ScreenShots.assertShows(compose, name, "Shut down the day")
        val v = core.shutdownView.value
        val p = v.tomorrow
        val w = core.wakeView.value
        val lines = listOfNotNull(
            v.doneCountLine, ShutdownRules.cardLine(v.cardOff, v.onLeave), v.firstLine, p.workLine, p.summary, p.weatherLine,
            p.train?.text, p.school?.text, w.line, OnThisDayRules.OFFER,
        ) + v.left.mapNotNull { it.line } + p.rows.mapNotNull { it.detail }
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertNoDotEndingALine(compose, name, lines)
        ScreenShots.assertReadable(compose, name, v.left.map { it.task.title } + p.rows.map { it.title }, mustShow = false)
        assertTouchTargets(name)
        assertRegularWeight(name, v.left.map { it.task.title } + p.rows.map { it.title })
        assertChipsFollowTheirTask(name, v.left.size)
        assertTomorrowTimesClose(name, p.rows.filter { it.time != null }.map { it.title })
        assertShutDownButton(name)
        if (compose.onAllNodes(hasText(OnThisDayRules.OFFER), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            ScreenShots.assertOneLine(compose, name, OnThisDayRules.OFFER)
        }
    }

    private fun layout(n: SemanticsNode): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()
    }

    private fun textNodes(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
    private fun dp(px: Float) = px / compose.density.density

    /** Task and tomorrow rows use the regular body weight (Fold review 2026-10-08); slice 47's shots had them bold. */
    private fun assertRegularWeight(name: String, titles: List<String>) {
        val bold = titles.distinct().flatMap { t -> textNodes(t).mapNotNull { n ->
            val w = layout(n)?.layoutInput?.style?.fontWeight ?: return@mapNotNull null
            if (w.weight > FontWeight.Normal.weight) "\"$t\" weight ${w.weight}" else null
        } }
        if (bold.isNotEmpty()) fail("$name: rows drawn heavier than body:\n" + bold.joinToString("\n") { "- $it" })
    }

    /** A left task's Tomorrow chip sits right under its words (slice 47's shots: ~20 dp below its line). */
    private fun assertChipsFollowTheirTask(name: String, left: Int) {
        if (left == 0) return
        val chips = compose.onAllNodes(hasText("Tomorrow") and hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
        chips.forEach { chip ->
            val top = chip.boundsInRoot.top
            val above = texts.filter { it.id != chip.id && it.boundsInRoot.bottom <= top + 1f && it.boundsInRoot.left >= chip.boundsInRoot.left - 1f }
                .maxByOrNull { it.boundsInRoot.bottom } ?: return@forEach
            val gap = dp(top - above.boundsInRoot.bottom)
            // The chip's 48 dp touch leaves ~7 dp over its pill; anything more is a gap of its own.
            if (gap > 2f) fail("$name: a Tomorrow chip's touch starts ${gap.toInt()} dp under the words above it (at most 2)")
        }
    }

    /** Tomorrow's titles start close to their times (slice 47: a 92 dp column pushed them far right). */
    private fun assertTomorrowTimesClose(name: String, titles: List<String>) {
        titles.forEach { t ->
            val title = textNodes(t).firstOrNull() ?: return@forEach
            val row = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
                .filter { it.boundsInRoot.right <= title.boundsInRoot.left && it.boundsInRoot.top in (title.boundsInRoot.top - 4f)..(title.boundsInRoot.top + 4f) }
                .maxByOrNull { it.boundsInRoot.right } ?: return@forEach
            val time = layout(row) ?: return@forEach
            val gap = dp(title.boundsInRoot.left - (row.boundsInRoot.left + time.getLineRight(0)))
            if (gap > 32f) fail("$name: \"$t\" starts ${gap.toInt()} dp after its time (at most 32)")
        }
    }

    /** Shut down's label is centred in its button, which follows the section above without a tall empty band. */
    private fun assertShutDownButton(name: String) {
        val button = textNodes("Shut down").firstOrNull() ?: return
        val l = layout(button) ?: return
        val left = l.getLineLeft(0)
        val right = button.size.width - l.getLineRight(0)
        if (kotlin.math.abs(left - right) > 4f) fail("$name: Shut down's label isn't centred (${dp(left).toInt()} dp left, ${dp(right).toInt()} dp right)")
        val notNow = textNodes(OnThisDayRules.NOT_NOW).firstOrNull() ?: return
        // The label's node sits inside the button's 16 dp padding.
        val band = dp(button.boundsInRoot.top - notNow.boundsInRoot.bottom) - 16f
        if (band > 28f) fail("$name: ${band.toInt()} dp of empty band above Shut down (at most 28)")
    }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val core = setUp(theme, fill)
        val v = core.shutdownView.value
        // With work hours (the full evening) the evening started at 17:30; without, it waits for 20:00.
        if (fill == Fill.MANY && !v.evening) fail("$name: 19:10 after work, but the evening hasn't started")
        check(core, name)
        when (fill) {
            Fill.EMPTY -> {
                if (v.left.isNotEmpty() || v.done.isNotEmpty()) fail("$name: nothing done or left, but ${v.done.size} done and ${v.left.size} left")
                ScreenShots.assertShows(compose, name, "Nothing left from today.")
            }
            Fill.ONE -> {
                if (v.done.map { it.title } != listOf("Book the MOT")) fail("$name: done today is ${v.done.map { it.title }}")
                if (v.left.map { it.task.title } != listOf("Fix the gate latch")) fail("$name: left is ${v.left.map { it.task.title }}")
                ScreenShots.assertShows(compose, name, "Book the MOT")
                ScreenShots.assertShows(compose, name, "Fix the gate latch")
            }
            Fill.MANY -> {
                if (v.done.size != ShutdownRules.DONE_SHOWN || v.doneMore != "+ 1 more") fail("$name: ${v.done.size} done shown with \"${v.doneMore}\" (seven done)")
                if (v.left.none { it.overdue && it.line == "Overdue" }) fail("$name: the car insurance isn't overdue (${v.left.map { it.line }})")
                if (v.left.none { it.canSkip }) fail("$name: the recycling repeats, but it can't be skipped")
                if (v.left.none { it.line == "Planned 14:00" }) fail("$name: the gate latch isn't \"Planned 14:00\" (${v.left.map { it.line }})")
                if (v.tomorrow.workLine == null) fail("$name: tomorrow is an office Thursday, but no work line")
                if (v.firstLine?.contains("09:30") != true) fail("$name: tomorrow starts at 09:30, but the line is \"${v.firstLine}\"")
                if (v.tomorrow.rows.none { it.title == "Post the birthday card" }) fail("$name: tomorrow's task isn't in Tomorrow (${v.tomorrow.rows.map { it.title }})")
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

    /** The foot of the full evening on the cover screen: the wake alarm, On this day, Shut down and the card's switch. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverManyFoot() {
        val name = "cover-dark-many-foot"
        val core = setUp(ThemeChoice.DARK, Fill.MANY)
        scrollTo(hasText("Shut down"))
        check(core, name, atTop = false)
        ScreenShots.assertShows(compose, name, "WAKE ALARM")
    }

    // ---- By hand ----

    /** A left task's Tomorrow carries it: it leaves Left from today and shows in Tomorrow. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun tomorrowCarriesItByHand() {
        val name = "cover-dark-carry"
        val core = setUp(ThemeChoice.DARK, Fill.ONE)
        tap(name, "Tomorrow under the gate latch", hasText("Tomorrow"))
        until { core.shutdownView.value.left.isEmpty() }
        val v = core.shutdownView.value
        if (v.left.isNotEmpty()) fail("$name: Tomorrow left the gate latch in Left from today")
        if (v.tomorrow.rows.none { it.title == "Fix the gate latch" }) fail("$name: the gate latch isn't in Tomorrow (${v.tomorrow.rows.map { it.title }})")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("shutdown", name))
        ScreenShots.assertShows(compose, name, "Nothing left from today.")
    }

    /** A left task's circle completes it; it joins Done today. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun aTaskTicksByHand() {
        val name = "cover-dark-tick"
        val core = setUp(ThemeChoice.DARK, Fill.ONE)
        tap(name, "the gate latch's circle", hasContentDescription("Complete Fix the gate latch"))
        until { core.shutdownView.value.done.any { it.title == "Fix the gate latch" } }
        val v = core.shutdownView.value
        if (v.left.isNotEmpty()) fail("$name: the ticked task is still left (${v.left.map { it.task.title }})")
        if (v.done.none { it.title == "Fix the gate latch" }) fail("$name: the ticked task isn't in Done today")
    }

    /** Tired is kept as today's feeling. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun aFeelingPicksByHand() {
        val name = "cover-dark-feeling"
        val core = setUp(ThemeChoice.DARK, Fill.MANY)
        tap(name, "Tired", hasText("Tired"))
        until { runBlocking { core.feelingToday() } == Feeling.TIRED }
        if (runBlocking { core.feelingToday() } != Feeling.TIRED) fail("$name: tapping Tired didn't keep it")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("shutdown", name))
        ScreenShots.assertShows(compose, name, "Tired, selected")
    }

    /** The evening card's Turn off turns the card off, and the line under it says so. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun theCardTurnsOffByHand() {
        val name = "cover-dark-card-off"
        val core = setUp(ThemeChoice.DARK, Fill.ONE)
        if (core.shutdownView.value.cardOff) fail("$name: the card starts off")
        tap(name, "Turn off", hasText("Turn off"))
        until { core.shutdownView.value.cardOff }
        if (!core.shutdownView.value.cardOff) fail("$name: Turn off left the card on")
        scrollTo(hasText("Turn on"))
        ScreenShots.assertShows(compose, name, "Turn on")
    }

    /** Shut down calls it a day: the day is shut down on every device and the check says so. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun shutDownByHand() {
        val name = "cover-dark-shut-down"
        val core = setUp(ThemeChoice.DARK, Fill.ONE)
        if (core.shutdownView.value.doneToday) fail("$name: the day starts shut down")
        tap(name, "Shut down", hasText("Shut down"))
        until { core.shutdownView.value.doneToday }
        if (!core.shutdownView.value.doneToday) fail("$name: Shut down didn't shut the day down")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("shutdown", name))
        ScreenShots.assertShows(compose, name, "Day shut down")
    }
}
