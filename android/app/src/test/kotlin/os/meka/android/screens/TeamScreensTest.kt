package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
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
import os.meka.android.ask.TEAM_ASK_MIC_TAG
import os.meka.android.ask.TEAM_ASK_TAG
import os.meka.android.ask.TeamPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.CapturedItem
import os.meka.core.domain.DateGlue
import os.meka.core.domain.Notice
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.NoticeTarget
import os.meka.core.domain.NoticeTier
import os.meka.core.domain.PeopleLists
import os.meka.core.domain.TeamPauseRules
import os.meka.core.domain.TeamAskRules
import os.meka.core.domain.TeamRules
import os.meka.core.domain.TeamStatus
import os.meka.core.domain.TeamTarget
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate for Ask → Daily → Your team (slice 1): a quiet day and a day with reminders, on the Fold's cover and
 * inner screens, Dark (and Light). Saved under `team/` and read for nonsense, cut lines, broken words, split clocks,
 * lone words and stray dots, and every button answering a 48 dp touch. By hand: a card's tap raises its sheet with
 * today's log, Close drops it. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class TeamScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
    }

    private enum class Fill { QUIET, BUSY }

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(71), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun until(done: () -> Boolean) {
        repeat(60) {
            if (done()) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
    }

    private fun onScreen(text: String): Boolean = ScreenShots.texts(compose).any { DateGlue.plain(it).contains(text) }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

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

    private fun notice(key: String, source: NoticeSource, title: String, minutesAgo: Int) = Notice(
        key = key, source = source, tier = NoticeTier.HEADS_UP, title = title, text = "",
        atMs = now - minutesAgo * 60_000L, target = NoticeTarget.TODAY,
    )

    private var reviewed: TeamTarget? = null
    private val asked = mutableListOf<Boolean>()

    private fun pane(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            if (fill == Fill.BUSY) {
                core.notificationsPosted(listOf(
                    notice("t1", NoticeSource.TASK_REMINDER, "Call the garage", 300),
                    notice("t2", NoticeSource.BINS, "Black bin out tonight", 60),
                    notice("m1", NoticeSource.BILLS, "Water bill due Friday", 120),
                ))
                // Beside the mockup (2026-11-14): something waits, so the Waiting on you box is in the shot.
                core.readRequest(
                    CapturedItem("v1", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, "Sam", "Voice message (0:12)", null, now - 30 * 60_000L),
                    PeopleLists(), setOf("Sam"),
                )
            }
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    TeamPane(core, onClose = {}, onReview = { reviewed = it }, onAsk = { asked += it })
                }
            }
        }
        until { onScreen(TeamRules.TITLE) && count("team-card") == 5 }
        compose.waitForIdle()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("team", name))
        val v = runBlocking { core.teamView() }
        val lines = listOf(v.subtitle, TeamRules.FOOT) + v.cards.map { it.line } + v.waiting.flatMap { listOf(it.what, it.who) }
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, TeamRules.TITLE)
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertNoDotEndingALine(compose, name, lines)
        assertTouchTargets(name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        if (count("team-card") != 5) fail("$name: ${count("team-card")} helpers, not 5")
        if (count("team-tile") != 3) fail("$name: ${count("team-tile")} tiles, not 3")
        // Team, simpler: the Waiting on you box shows only when something waits, a row each.
        if (count("team-waiting") != (if (v.waiting.isEmpty()) 0 else 1)) fail("$name: the Waiting on you box doesn't match ${v.waiting.size} rows")
        if (count("team-wait-row") != v.waiting.size) fail("$name: ${count("team-wait-row")} waiting rows, not ${v.waiting.size}")
        if (count("team-helpers") != 1) fail("$name: no Helpers box")
        if (count("team-unfolded") != 0) fail("$name: a helper is unfolded before any tap")
        when (fill) {
            Fill.QUIET -> if (!onScreen(TeamRules.NOTHING_YET)) fail("$name: a quiet day doesn't say so")
            Fill.BUSY -> {
                if (!onScreen("2 reminders")) fail("$name: Obi's reminders aren't counted")
                if (!onScreen("1 heads-up")) fail("$name: Money's heads-up isn't counted")
                // Week 3's walk-through (A4): the tiles agree with the cards below them.
                if (onScreen(TeamStatus.NOT_SET_UP.label)) fail("$name: Money says Not set up beside a heads-up today")
                if (v.waiting.isEmpty()) fail("$name: nothing waits on a busy day, so the Waiting on you box isn't drawn")
            }
        }
        val live = v.cards.count { it.status.live }
        if (v.tiles.first().value != live.toString()) fail("$name: WORKING ${v.tiles.first().value}, but $live cards say Working")
        if (fill == Fill.QUIET && live != 0) fail("$name: a quiet day has $live helpers working")
        return core
    }

    /**
     * By hand: Obi's row unfolds to his role, his last things, Open · Pause · Whole day ›; Whole day raises his sheet
     * with today's log; Close drops it.
     */
    private fun byHand(core: MekaCore) {
        val name = "team-by-hand"
        val v = runBlocking { core.teamView() }
        val obi = v.card("obi")!!
        compose.onAllNodes(hasContentDescription(obi.rowSpoken) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        until { count("team-unfolded") == 1 && count("team-recent") == 2 }
        if (count("team-unfolded") != 1) fail("$name: Obi's row didn't unfold")
        if (count("team-recent") != 2) fail("$name: ${count("team-recent")} recent rows, not Obi's 2")
        if (count("team-open") != 1 || count("team-pause-short") != 1 || count("team-whole-day") != 1) {
            fail("$name: the unfolded row lacks Open · Pause · Whole day")
        }
        // Beside the mockup (2026-11-14): Open · Pause · Whole day › sit on one row on the cover screen.
        val tops = listOf("team-open", "team-pause-short", "team-whole-day").map { tag ->
            compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        }
        if (tops.distinct().size != 1) fail("$name: Open · Pause · Whole day › wrap onto ${tops.distinct().size} rows")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("team", "cover-dark-unfolded"))
        ScreenShots.assertSensible(compose, "$name-unfolded")
        ScreenShots.assertNoBrokenWords(compose, "$name-unfolded")
        ScreenShots.assertClocksWhole(compose, "$name-unfolded")
        ScreenShots.assertNoLoneWords(compose, "$name-unfolded", listOf(obi.roleLine) + obi.recent.map { it.text })
        assertTouchTargets("$name-unfolded")
        compose.onAllNodes(hasContentDescription("${os.meka.core.domain.TeamSimplerRules.WHOLE_DAY}: ${obi.title}") and hasClickAction())[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        until { count("team-sheet") == 1 && count("team-log") == 2 }
        if (count("team-sheet") != 1) fail("$name: the card didn't raise its sheet")
        if (count("team-log") != 2) fail("$name: ${count("team-log")} log rows, not Obi's 2")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("team", "cover-dark-sheet"))
        val lines = listOf(obi.level, obi.pauseLine.orEmpty()) + obi.log.map { it.text }
        ScreenShots.assertSensible(compose, "$name-sheet")
        ScreenShots.assertNoBrokenWords(compose, "$name-sheet")
        ScreenShots.assertClocksWhole(compose, "$name-sheet")
        ScreenShots.assertNoLoneWords(compose, "$name-sheet", lines)
        ScreenShots.assertNoDotEndingALine(compose, "$name-sheet", lines)
        assertTouchTargets("$name-sheet")
        // Slice 2: Pause for today, then Resume.
        if (count("team-pause") != 1) fail("$name: Obi's sheet has no Pause for today")
        compose.onAllNodes(hasContentDescription("${TeamPauseRules.PAUSE}: ${obi.title}") and hasClickAction())[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        until { runBlocking { core.teamView() }.card("obi")!!.paused && onScreen("Paused till midnight") }
        if (!runBlocking { core.teamView() }.card("obi")!!.paused) fail("$name: Pause for today didn't pause Obi")
        if (!onScreen("Paused till midnight")) fail("$name: the sheet doesn't say Obi is paused")
        ScreenShots.assertNoLoneWords(compose, "$name-paused", listOf(TeamPauseRules.pausedLine(obi.agent).orEmpty()))
        assertTouchTargets("$name-paused")
        compose.onAllNodes(hasContentDescription("${TeamPauseRules.RESUME}: ${obi.title}") and hasClickAction())[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        until { !runBlocking { core.teamView() }.card("obi")!!.paused }
        if (runBlocking { core.teamView() }.card("obi")!!.paused) fail("$name: Resume left Obi paused")
        compose.onAllNodes(hasContentDescription("${TeamRules.CLOSE} ${obi.title}") and hasClickAction())[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        until { count("team-sheet") == 0 }
        if (count("team-sheet") != 0) fail("$name: Close left the sheet up")
        if (reviewed != null) fail("$name: nothing was reviewed, yet $reviewed opened")
        // Slice 3: Ask the team… goes to Ask's field, its mic to Obi listening.
        if (!onScreen(TeamAskRules.BAR)) fail("$name: no Ask the team… at the foot")
        compose.onAllNodes(hasTestTag(TEAM_ASK_TAG) and hasClickAction(), useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.OnClick)
        until { asked.isNotEmpty() }
        compose.onAllNodes(hasTestTag(TEAM_ASK_MIC_TAG) and hasClickAction(), useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.OnClick)
        until { asked.size == 2 }
        if (asked != listOf(false, true)) fail("$name: Ask the team… asked $asked, not the field then listening")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverQuiet() { pane("cover-dark-quiet", Fill.QUIET) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverBusy() { pane("cover-dark-busy", Fill.BUSY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverBusyLight() { pane("cover-light-busy", Fill.BUSY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerBusy() { pane("inner-dark-busy", Fill.BUSY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHand() { byHand(pane("cover-dark-by-hand", Fill.BUSY)) }
}
