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
import os.meka.android.ask.ProtectedTimePane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.ProtectedMailRules
import os.meka.core.domain.ProtectedRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 49 (build plan: "the rest of Ask's places' shots", Protected time first): Ask → More →
 * Protected time off, with evenings from 19:00, and with evenings and weekends protected and tonight lifted just this
 * once, each on the Fold's cover and inner screens, Dark (and the full one in Light). Saved under `protected/` and read
 * for nonsense, cut lines, broken words, split dates, times and numbers, the pane's own lines leaving a word alone or
 * starting a line with a dot, every chip whole on one line and in view (they wrap, never scroll off the edge), and every
 * button answering a 48 dp touch. By hand: 19:30 moves the evening; Not protected tonight lifts tonight and says
 * so, and Undo puts it back. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class ProtectedTimeScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(49), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private suspend fun fill(core: MekaCore, fill: Fill) {
        when (fill) {
            Fill.EMPTY -> Unit
            Fill.ONE -> core.setProtectedTime(true, 19 * 60, false)
            Fill.MANY -> {
                core.setProtectedTime(true, 19 * 60, true)
                core.setProtectedOpenToday(true)
            }
        }
    }

    /** Runs the main looper and the frames by turns (a tap's change reaches the recomposer through the looper). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /** Waits (core thread, looper and frames by turns) until [done] holds, then lets the screen settle. */
    private fun until(done: () -> Boolean) {
        repeat(40) {
            if (done()) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
    }

    /** Every button drawn answers a 48 dp touch (the chips were ~32 dp). */
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

    /** Every chip sits inside the pane's width (they wrap; on the cover screen they used to scroll off the edge). */
    private fun assertChipsInView(name: String, labels: List<String>) {
        val pane = compose.onNodeWithTag("protected-pane").fetchSemanticsNode().boundsInRoot
        labels.forEach { label ->
            val nodes = compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes()
            // The evening's chips sit near the top and must be there; ones further down may be past the screen's foot.
            if (nodes.isEmpty()) {
                if (label == "Off" || label.contains(':')) fail("$name: no \"$label\" chip")
                return@forEach
            }
            val n = nodes.first()
            val right = n.positionInRoot.x + n.size.width
            if (n.positionInRoot.x < pane.left - 1f || right > pane.right + 1f) {
                fail("$name: \"$label\" sits outside the pane (${n.positionInRoot.x / compose.density.density}–${right / compose.density.density} dp)")
            }
        }
    }

    /**
     * Slice 50: the evening's six chips read just the time under "EVENINGS FROM" and take at most two rows (on the cover
     * screen they took three, "From" six times).
     */
    private fun assertEveningRows(name: String, labels: List<String>) {
        val tops = labels.mapNotNull { label ->
            compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().firstOrNull()?.positionInRoot?.y
        }
        val rows = tops.map { (it / compose.density.density / 8f).toInt() }.distinct().size
        if (rows > 2) fail("$name: the evening's chips take $rows rows (at most 2)")
    }

    private fun pane(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
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
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { ProtectedTimePane(core, onClose = {}) }
            }
        }
        turns()
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("protected", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, "Protected time")
        val v = core.protectedView.value
        val lines = listOfNotNull(v.summary, v.todayLine, v.mailLine, ProtectedRules.NOTE, if (v.on) ProtectedMailRules.NOTE else null)
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(v.summary))
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        val evening = listOf("Off") + ProtectedRules.FROM_CHOICES.map(ProtectedRules::fromChip)
        val chips = evening +
            listOf("Open", "Protected") + (if (v.on) listOf(ProtectedMailRules.ON, ProtectedMailRules.OFF) else emptyList())
        ScreenShots.assertEachOneLine(compose, name, chips)
        assertChipsInView(name, chips)
        assertEveningRows(name, evening)
        ScreenShots.assertShows(compose, name, ProtectedRules.EVENINGS_FROM_LABEL.uppercase())
        assertTouchTargets(name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        when (fill) {
            Fill.EMPTY -> {
                if (v.on) fail("$name: protected time is on with nothing set ($v)")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept(ProtectedRules.OFF_LINE))
            }
            Fill.ONE -> {
                if (v.todayLine != "Protected from 19:00 tonight") fail("$name: today's line reads \"${v.todayLine}\"")
                ScreenShots.assertShows(compose, name, "Not protected tonight")
                ScreenShots.assertShows(compose, name, ProtectedMailRules.ON)
            }
            Fill.MANY -> {
                if (!v.openToday) fail("$name: tonight isn't lifted ($v)")
                ScreenShots.assertShows(compose, name, "Undo · protect it again")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept(ProtectedRules.Lifted.EVENING.line))
            }
        }
        return core
    }

    /**
     * Works by hand: 19:30 moves the evening (the summary follows); Not protected tonight lifts tonight and says
     * what changed; Undo · protect it again puts it back.
     */
    private fun byHand(core: MekaCore) {
        val name = "protected-by-hand"
        val later = compose.onAllNodes(hasText("19:30") and hasClickAction())
        if (later.fetchSemanticsNodes().isEmpty()) fail("$name: no 19:30 chip")
        later[0].performSemanticsAction(SemanticsActions.OnClick)
        until { core.protectedView.value.fromMin == 19 * 60 + 30 }
        if (core.protectedView.value.fromMin != 19 * 60 + 30) fail("$name: 19:30 left the evening at ${core.protectedView.value.fromMin}")
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(ProtectedRules.summary(os.meka.core.domain.ProtectedSetting(true, 19 * 60 + 30, false))))

        val lift = compose.onAllNodes(hasText("Not protected tonight") and hasClickAction())
        if (lift.fetchSemanticsNodes().isEmpty()) fail("$name: no Not protected tonight")
        lift[0].performSemanticsAction(SemanticsActions.OnClick)
        until { core.protectedView.value.openToday }
        if (!core.protectedView.value.openToday) fail("$name: Not protected tonight left tonight protected")
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(ProtectedRules.Lifted.EVENING.done))

        val undo = compose.onAllNodes(hasText("Undo · protect it again") and hasClickAction())
        if (undo.fetchSemanticsNodes().isEmpty()) fail("$name: no Undo once tonight is lifted")
        undo[0].performSemanticsAction(SemanticsActions.OnClick)
        until { !core.protectedView.value.openToday }
        if (core.protectedView.value.openToday) fail("$name: Undo left tonight lifted")
        ScreenShots.assertShows(compose, name, "Not protected tonight")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { pane("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { pane("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { pane("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() { pane("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerEmpty() { pane("inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { pane("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHand() { byHand(pane("cover-dark-by-hand", Fill.ONE)) }
}
