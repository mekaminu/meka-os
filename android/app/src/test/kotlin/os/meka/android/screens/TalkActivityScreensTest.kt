package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.activity.ActivityPane
import os.meka.android.ask.TalkAutoListen
import os.meka.android.ask.TalkPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.ActivityFilter
import os.meka.core.domain.ActivityRules
import os.meka.core.domain.BargeInRules
import os.meka.core.domain.CallSignals
import os.meka.core.domain.DeviceAlerts
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.Digest
import os.meka.core.domain.NoticeTarget
import os.meka.core.domain.PeopleLists
import os.meka.core.domain.TalkOnOpenRules
import os.meka.core.domain.TalkStartRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 27 (build plan: "Talk's and Activity's shots"): Ask → More → Talk (as it comes, and with
 * When I open Navilife on and Talk over off) and Activity (nothing yet, one thing done for you, and a full day: things
 * Ada added with Undo, a shopping add, reminders and a digest that reached you, a scam number blocked twice), each on
 * the Fold's cover and inner screens, Dark (and the full one in Light). Saved under `talk/` and `activity/` and read for
 * nonsense, cut lines, broken words, split dates, times and numbers, and lines leaving a word alone. "Works by hand":
 * Talk over's Turn off changes its line (slice 28), Activity's filter chip narrows the days and Undo takes a change back.
 * Slice 28: "add book the boiler service on friday" is a task on Friday (not Reading's), and the blocked number's two
 * calls, a minute apart, read "2 calls stopped".
 * Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class TalkActivityScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val start = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London
    /** The fixture's clock: the full day's calls come a minute apart, as real ones do. */
    private var now = start

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(53), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private fun settle(area: String, name: String) {
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path(area, name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
    }

    // ---- Talk ----

    private fun talk(name: String, listenOnOpen: Boolean = false, talkOver: Boolean = true, theme: ThemeChoice = ThemeChoice.DARK) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        context.useCoreForScreens(core())
        TalkAutoListen.setListenOnOpen(context, listenOnOpen)
        TalkAutoListen.setTalkOver(context, talkOver)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { TalkPane(onClose = {}) }
            }
        }
        settle("talk", name)
        val view = TalkStartRules.setup(mac = false, assistantHeld = false, samsung = false)
        ScreenShots.assertShows(compose, name, view.title)
        ScreenShots.assertShows(compose, name, view.intro)
        val sections = view.sections + TalkOnOpenRules.section(listenOnOpen) + BargeInRules.section(talkOver)
        ScreenShots.assertReadable(compose, name, sections.flatMap { listOf(it.status) + it.steps }, mustShow = false)
        ScreenShots.assertEachOneLine(compose, name, sections.mapNotNull { it.action } + sections.map { it.label.uppercase() })
        ScreenShots.assertNoLoneWords(compose, name, sections.flatMap { listOf(it.status) + it.steps } + view.intro)
    }

    /**
     * Works by hand (slice 28): Talk over's Turn off is a live button, and its line says Off once tapped. Slice 27's
     * version scrolled to the button first and the paused test clock never went idle (CI run 1103); the tap is now the
     * button's own click action, which needs no scroll, and the main looper and the clock are moved on by hand.
     */
    private fun talkOverByHand() {
        val on = BargeInRules.section(true)
        val off = BargeInRules.section(false)
        val buttons = compose.onAllNodes(hasText(on.action!!) and hasClickAction())
        val count = buttons.fetchSemanticsNodes().size
        if (count == 0) fail("Talk: no ${on.action} button")
        // Talk over's own button: the last section saying it carries it.
        buttons[count - 1].performSemanticsAction(SemanticsActions.OnClick)
        // The tap's state change reaches the recomposer through a post on the main looper, which the paused clock never
        // runs (CI run 1106: the line still said On). Run the looper and the frames by turns until the colour has
        // blended; a waitForIdle here, with the blend waiting on the paused clock, is what never went idle in 1103.
        repeat((SETTLE_MS / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        if (TalkAutoListen.talkOver(context)) fail("Talk: Turn off didn't turn Talk over off")
        ScreenShots.assertShows(compose, "talk-by-hand", LoneWordGlue.kept(off.status))
        if (compose.onAllNodes(hasText(off.action!!) and hasClickAction()).fetchSemanticsNodes().isEmpty()) {
            fail("Talk: no ${off.action} after Turn off")
        }
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun talkCover() = talk("cover-dark")
    @Test @Config(qualifiers = ScreenShots.COVER) fun talkCoverChanged() = talk("cover-dark-changed", listenOnOpen = true, talkOver = false)
    @Test @Config(qualifiers = ScreenShots.COVER) fun talkCoverLight() = talk("cover-light", theme = ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun talkInner() = talk("inner-dark")
    @Test @Config(qualifiers = ScreenShots.COVER) fun talkByHand() { talk("cover-dark-by-hand"); talkOverByHand() }

    // ---- Activity ----

    private suspend fun fill(core: MekaCore, fill: Fill) {
        if (fill == Fill.EMPTY) return
        core.askMeka("add call the garage tomorrow")
        if (fill != Fill.MANY) return
        core.askMeka("add milk and eggs to the shopping")
        core.askMeka("add book the boiler service on friday")
        val result = core.governNotifications(null, DeviceAlerts.ALL)
        core.notificationsPostedKeys(result, result.post.map { it.key } + MekaCore.DIGEST_KEY)
        core.digestPosted(Digest("Evening digest · 2 things", "2 to chase", listOf("a", "b"), 2, "Evening digest", NoticeTarget.LISTS))
        check(core.blockCaller("01632 960691", "Scam call claiming to be the police"))
        core.screenIncomingCall("+441632960691", PeopleLists(), emptyList(), CallSignals.NONE)
        now += 60_000
        core.screenIncomingCall("+441632960691", PeopleLists(), emptyList(), CallSignals.NONE)
    }

    private fun activity(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        now = start
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            fill(core, fill)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { ActivityPane(core, onClose = {}) }
            }
        }
        settle("activity", name)
        val v = core.activityView.value
        ScreenShots.assertShows(compose, name, "Activity")
        val rows = v.days.flatMap { it.rows }
        when (fill) {
            Fill.EMPTY -> {
                if (!v.isEmpty) fail("$name: ${rows.size} rows with nothing done")
                ScreenShots.assertShows(compose, name, v.emptyLine)
            }
            Fill.ONE -> if (rows.size != 1) fail("$name: ${rows.size} rows for one thing done: $rows")
            Fill.MANY -> {
                if (rows.size < 5) fail("$name: only ${rows.size} rows for a full day: $rows")
                if (rows.none { it.canUndo }) fail("$name: nothing Ada did can be undone")
                val stopped = core.blockedCallers.value.stopped
                if (stopped.singleOrNull()?.count != 2L) fail("$name: the blocked number's two calls aren't counted as two: $stopped")
                // Slice 28: "add book the boiler service on friday" is Friday's task, never a book on Reading.
                if (rows.none { it.summary.contains("“Book the boiler service”") && it.summary.contains("Fri") }) {
                    fail("$name: the boiler service isn't Friday's task: ${rows.map { it.summary }}")
                }
                ScreenShots.assertShows(compose, name, v.weekLine)
            }
        }
        ScreenShots.assertReadable(compose, name, rows.flatMap { listOfNotNull(it.summary, it.detail, it.why) }, mustShow = false)
        ScreenShots.assertEachOneLine(compose, name, ActivityRules.filters(v).map { it.label } + rows.map { it.time })
        ScreenShots.assertNoLoneWords(compose, name, rows.flatMap { listOfNotNull(it.summary, it.why) })
        return core
    }

    /** Works by hand: a filter chip narrows the days to its kind, and Undo takes a change back. */
    private fun activityByHand(core: MekaCore) {
        val v = core.activityView.value
        val chips = ActivityRules.filters(v)
        val reminders = chips.firstOrNull { it.filter == ActivityFilter.REMINDERS } ?: fail("Activity: no Reminders chip in $chips")
        val doneForYou = v.days.flatMap { it.rows }.filter { it.canUndo }
        if (doneForYou.isEmpty()) fail("Activity: nothing to undo")
        compose.onAllNodes(hasText(reminders.label) and hasClickAction())[0].performClick()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        val still = doneForYou.filter { r -> compose.onAllNodes(hasText(LoneWordGlue.kept(r.summary)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        if (still.isNotEmpty()) fail("Activity: Reminders still shows ${still.map { it.summary }}")
        compose.onAllNodes(hasText(ActivityFilter.ALL.label) and hasClickAction())[0].performClick()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        val undos = compose.onAllNodes(hasText("Undo") and hasClickAction()).fetchSemanticsNodes().size
        if (undos == 0) fail("Activity: no Undo on screen")
        compose.onAllNodes(hasText("Undo") and hasClickAction())[0].performScrollTo().performClick()
        // The undo runs on the core's own thread; give it real time, then let the screen catch up.
        repeat(20) {
            if (core.activityView.value.days.flatMap { it.rows }.count { it.canUndo } < doneForYou.size) return@repeat
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        val left = core.activityView.value.days.flatMap { it.rows }.count { it.canUndo }
        if (left >= doneForYou.size) fail("Activity: Undo didn't take anything back ($left of ${doneForYou.size} still undoable)")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun activityCoverEmpty() { activity("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun activityCoverOne() { activity("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun activityCoverMany() { activity("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun activityCoverManyLight() { activity("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun activityInnerEmpty() { activity("inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun activityInnerMany() { activity("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun activityByHand() { activityByHand(activity("cover-dark-by-hand", Fill.MANY)) }
}
