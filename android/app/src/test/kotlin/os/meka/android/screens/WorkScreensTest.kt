package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
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
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.work.AT_WORK_NOTE
import os.meka.android.work.HOURS_OFF_LINE
import os.meka.android.work.HOURS_ON_LINE
import os.meka.android.work.WORK_COLUMN_MAX
import os.meka.android.work.WorkPane
import os.meka.android.work.callAssistantNote
import os.meka.core.domain.CallScreeningRules
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.PeopleLists
import os.meka.core.domain.ScreenUnknownRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 37 (build plan: "the remaining screens' shots", Work mode next): Ask → More → Work mode with
 * nothing set, one set of hours (Mon–Fri, a commute, one family member) and a full set (four work days with Thursday
 * short, at work, the call assistant on and screening unknown callers all the time, Leaving now on, a blocked number,
 * two family members (one without a number) and one always-notify), each on the Fold's cover and inner screens, Dark
 * (and the full one in Light). Saved under `work/` and read for nonsense, cut lines, broken words, split dates, times
 * and numbers, and Work mode's own lines leaving a word alone or starting a line with a dot. By hand: Stop work now
 * stops work and offers Back to my hours; a day's chip adds that day to the hours.
 * Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class WorkScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(37), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private suspend fun fill(core: MekaCore, fill: Fill): PeopleLists {
        if (fill == Fill.EMPTY) return PeopleLists()
        if (fill == Fill.ONE) {
            core.setWorkSchedule(listOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60 + 30, true)
            core.setCommute(60, 60)
            return PeopleLists(family = setOf("Jordan")).withNumbers("Jordan", listOf("07700 900123"))
        }
        core.setWorkSchedule(listOf(1, 2, 3, 4), 9 * 60, 17 * 60 + 30, true)
        core.setWorkDayHours(4, 9 * 60, 15 * 60 + 30)
        core.setCallAssistant(true)
        core.setScreenUnknownAlways(true)
        core.setCommute(75, 80)
        core.setLeavingNow(true, "Jordan", 45)
        check(core.blockCaller("01632 960691", "Scam call claiming to be the police"))
        return PeopleLists(family = setOf("Jordan", "Sam Okonkwo-Lee"), alwaysNotify = setOf("Riley"))
            .withNumbers("Jordan", listOf("07700 900123"))
            .withNumbers("Riley", listOf("07700 900456"))
    }

    /** Runs the main looper and the frames by turns (a tap's change reaches the recomposer through the looper). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun work(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        val lists = runBlocking {
            core.tick()
            fill(core, fill).also { core.tick() }
        }
        // The lists stay on the phone, sealed with the device's keystore, which Robolectric hasn't got: the list is
        // set before the save that can't seal it.
        runCatching { context.captures.setLists(lists) }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { WorkPane(core, onClose = {}) }
            }
        }
        turns()
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("work", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, "Work mode")
        val w = core.workMode.value
        // The call line as the Fold draws it whether Android has let Navilife screen calls or not.
        val callLine = listOf(true, false).map {
            CallScreeningRules.statusLine(w.callAssistant, w.atWork, it, w.callAssistantPaused, w.screenUnknownAlways)
        }
        // Work mode's own lines.
        val lines = listOf(
            w.line, AT_WORK_NOTE, callAssistantNote(), CallScreeningRules.testLine(),
            ScreenUnknownRules.line(w.screenUnknownAlways, w.callAssistant), ScreenUnknownRules.TRADE_OFF,
            if (w.schedule.enabled) HOURS_ON_LINE else HOURS_OFF_LINE,
            "Shown first after work. Their calls ring while the call assistant screens.",
            "Messages and missed calls from these people alert you straight away, and their calls ring.",
        )
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(w.line))
        ScreenShots.assertNoLoneWords(compose, name, lines + callLine)
        ScreenShots.assertNoDotStartingALine(compose, name, lines + callLine)
        ScreenShots.assertEachOneLine(compose, name, listOf("Stop work now", "Start work now", "Usual start", "Usual end"))
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        // Unfolded, the column keeps to Fasting's 600 dp, centred, so Stop work now and the steppers don't sit at the far edge.
        val pane = compose.onNodeWithTag("work-pane").fetchSemanticsNode().boundsInRoot
        val screen = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        val paneDp = pane.width / density
        if (paneDp > WORK_COLUMN_MAX.value + 0.5f) fail("$name: Work mode's column is ${paneDp.toInt()} dp wide (max ${WORK_COLUMN_MAX.value.toInt()})")
        val leftGap = pane.left - screen.left
        val rightGap = screen.right - pane.right
        if (kotlin.math.abs(leftGap - rightGap) > density * 2) fail("$name: Work mode's column isn't centred (${leftGap / density} dp left, ${rightGap / density} dp right)")
        // The commute's "Home" label sits clear of the To work chips above it (at least 8 dp of air), as its own heading.
        val toWorkChips = compose.onAllNodes(hasContentDescription("Commute to work:", substring = true)).fetchSemanticsNodes()
        val homeLabel = compose.onAllNodes(hasText("Home", substring = false)).fetchSemanticsNodes()
        if (toWorkChips.isNotEmpty() && homeLabel.isNotEmpty()) {
            val air = (homeLabel.first().boundsInRoot.top - toWorkChips.maxOf { it.boundsInRoot.bottom }) / density
            if (air < 8f) fail("$name: the commute's Home label sits ${air.toInt()} dp under the To work chips (want ≥ 8)")
        }
        when (fill) {
            Fill.EMPTY -> ScreenShots.assertShows(compose, name, "Add from contacts")
            Fill.ONE -> {
                if (!w.atWork) fail("$name: not at work at 17:15 on a Wednesday inside 09:00–17:30 (${w.line})")
                ScreenShots.assertShows(compose, name, "Stop work now")
                ScreenShots.assertShows(compose, name, "Jordan")
            }
            Fill.MANY -> {
                if (!w.callAssistant || !w.screenUnknownAlways) fail("$name: the call assistant isn't on and screening ($w)")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept(ScreenUnknownRules.line(true, true)))
                ScreenShots.assertShows(compose, name, "Sam Okonkwo-Lee")
                ScreenShots.assertShows(compose, name, "Riley")
            }
        }
        return core
    }

    /** Works by hand: Stop work now stops work (Back to my hours appears); Saturday's chip adds Saturday to the hours. */
    private fun byHand(core: MekaCore) {
        val stop = compose.onAllNodes(hasText("Stop work now") and hasClickAction())
        if (stop.fetchSemanticsNodes().isEmpty()) fail("Work: no Stop work now at work")
        stop[0].performSemanticsAction(SemanticsActions.OnClick)
        repeat(40) {
            if (!core.workMode.value.atWork) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
        if (core.workMode.value.atWork) fail("Work: Stop work now left work on")
        ScreenShots.assertShows(compose, "work-by-hand", "Start work now")
        ScreenShots.assertShows(compose, "work-by-hand", "Back to my hours")
        val sat = compose.onAllNodes(hasContentDescription("Sat") and hasClickAction())
        if (sat.fetchSemanticsNodes().isEmpty()) fail("Work: no Saturday chip")
        sat[0].performSemanticsAction(SemanticsActions.OnClick)
        repeat(40) {
            if (6 in core.workMode.value.schedule.days) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        if (6 !in core.workMode.value.schedule.days) fail("Work: Saturday's chip didn't add Saturday (${core.workMode.value.schedule.days})")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { work("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { work("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { work("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() { work("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerEmpty() { work("inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { work("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun byHand() { byHand(work("inner-dark-by-hand", Fill.ONE)) }
}
