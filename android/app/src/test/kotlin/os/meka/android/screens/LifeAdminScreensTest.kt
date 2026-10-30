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
import os.meka.android.ask.LifeAdminPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.work.WORK_COLUMN_MAX
import os.meka.core.domain.LifeAdminJobKind
import os.meka.core.domain.LifeAdminRules
import os.meka.core.domain.LocalClock
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.ObligationKind
import os.meka.core.domain.RenewalRepeat
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate (build plan: "the rest of Ask's places' shots", Life admin next): Ask → More → Life admin hour off,
 * on with one renewal and one call, and on with a full week's jobs (renewals, an overdue form, more than the pane lists),
 * each on the Fold's cover and inner screens, Dark (and the full one in Light). Saved under `life-admin/` and read for
 * nonsense, cut lines, broken words, split dates, times and numbers, the pane's own lines leaving a word alone or
 * starting a line with a dot, every chip whole on one line and in view (they wrap, never scroll off the edge), and every
 * button answering a 48 dp touch. By hand: Sun moves the hour to Sunday; Skip this week skips it and Keep it keeps it;
 * Turn off turns it off. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class LifeAdminScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
        const val DAY_MS = 86_400_000L
    }

    private enum class Fill { EMPTY, ONE, MANY }

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(52), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private suspend fun fill(core: MekaCore, fill: Fill) {
        val today = core.todayEpochDay()
        when (fill) {
            Fill.EMPTY -> Unit
            Fill.ONE -> {
                core.setLifeAdmin(LifeAdminRules.DEFAULT_WEEKDAY, LifeAdminRules.DEFAULT_START)
                core.addRenewal("Home insurance", ObligationKind.INSURANCE, today + 5, RenewalRepeat.YEARLY, null, null)
                core.addTask("Call the GP about the results")
            }
            Fill.MANY -> {
                core.setLifeAdmin(LifeAdminRules.DEFAULT_WEEKDAY, 19 * 60)
                core.addRenewal("Car insurance", ObligationKind.INSURANCE, today + 3, RenewalRepeat.YEARLY, "£420", null)
                core.addRenewal("Home insurance for the flat and its contents", ObligationKind.INSURANCE, today + 9, RenewalRepeat.YEARLY, null, null)
                listOf(
                    "Call the GP about the results", "Renew the parking permit", "Reply to the council about the bins",
                    "Pay the window cleaner", "Book the boiler service", "Send the football kit money",
                    "Return the parcel to the shop before the refund window closes", "Scan the warranty letter",
                ).forEach { core.addTask(it) }
                // Added last (the newest job), so the shot shows it still comes first after the renewals.
                val form = core.addTask("Fill in the school trip form")
                core.setDue(form, now - 2 * DAY_MS)
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

    /** Every button drawn answers a 48 dp touch (Lists' chips are ~32 dp). */
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

    /** Every chip is there and sits inside the pane's width (they wrap; on the cover screen they scrolled off the edge). */
    private fun assertChipsInView(name: String, labels: List<String>) {
        val pane = compose.onNodeWithTag("life-admin-pane").fetchSemanticsNode().boundsInRoot
        labels.forEach { label ->
            val n = compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().firstOrNull()
                ?: fail("$name: no \"$label\" chip")
            val right = n.positionInRoot.x + n.size.width
            if (n.positionInRoot.x < pane.left - 1f || right > pane.right + 1f) {
                fail("$name: \"$label\" sits outside the pane (${n.positionInRoot.x / compose.density.density}–${right / compose.density.density} dp)")
            }
        }
    }

    /** Unfolded, the pane is a centred column at most 600 dp wide, not rows stretched across the page. */
    private fun assertColumnWidth(name: String) {
        val screen = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
        val pane = compose.onNodeWithTag("life-admin-pane").fetchSemanticsNode().boundsInRoot
        val d = compose.density.density
        if (pane.width / d > WORK_COLUMN_MAX.value + 1f) fail("$name: the pane is ${(pane.width / d).toInt()} dp wide, not a column")
        val left = pane.left - screen.left
        val right = screen.right - pane.right
        if (kotlin.math.abs(left - right) > 2 * d) fail("$name: the column isn't centred (${(left / d).toInt()} dp | ${(right / d).toInt()} dp)")
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
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { LifeAdminPane(core, onClose = {}) }
            }
        }
        turns()
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("life-admin", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, "Life admin hour")
        val v = core.lifeAdminView.value
        val lines = listOf(v.summary, LifeAdminRules.NOTE) + (if (v.on && v.jobs.isEmpty()) listOf(LifeAdminRules.NO_JOBS) else emptyList()) +
            v.jobs.mapNotNull { it.lineShown } + v.jobs.map { it.title }
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(v.summary))
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertNoDotEndingALine(compose, name, listOf(v.summary) + v.jobs.mapNotNull { it.lineShown })
        val chips = LifeAdminRules.WEEKDAY_CHOICES + LifeAdminRules.START_CHOICES.map(LocalClock::formatMinute)
        ScreenShots.assertEachOneLine(compose, name, chips)
        assertChipsInView(name, chips)
        assertTouchTargets(name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        when (fill) {
            Fill.EMPTY -> {
                if (v.on) fail("$name: the hour is on with nothing set ($v)")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept(LifeAdminRules.OFF_LINE))
            }
            Fill.ONE -> {
                if (v.jobCount != 2) fail("$name: ${v.jobCount} jobs gathered, not the renewal and the call (${v.jobs})")
                if (v.jobs.first().title != "Home insurance") fail("$name: the renewal isn't first (${v.jobs})")
                ScreenShots.assertShows(compose, name, v.jobs.first().lineShown!!)
                ScreenShots.assertShows(compose, name, LifeAdminRules.SKIP_LABEL)
            }
            Fill.MANY -> {
                if (v.jobCount <= v.jobs.size) fail("$name: ${v.jobCount} jobs all listed; the fixture should overflow")
                if (v.overdueCount != 1) fail("$name: ${v.overdueCount} overdue, not the school form")
                if (v.jobs.first().title != "Car insurance") fail("$name: the nearest renewal isn't first (${v.jobs})")
                val firstTask = v.jobs.firstOrNull { it.kind == LifeAdminJobKind.TASK }
                if (firstTask?.overdue != true) fail("$name: the overdue form isn't the first task (${v.jobs.map { it.title }})")
                assertColumnWidth(name)
            }
        }
        return core
    }

    /** Works by hand: Sun moves the hour to Sunday; Skip this week skips it, Keep it keeps it; Turn off turns it off. */
    private fun byHand(core: MekaCore) {
        val name = "life-admin-by-hand"
        val sun = compose.onAllNodes(hasText("Sun") and hasClickAction())
        if (sun.fetchSemanticsNodes().isEmpty()) fail("$name: no Sun chip")
        sun[0].performSemanticsAction(SemanticsActions.OnClick)
        until { core.lifeAdminView.value.weekday == 7 }
        if (core.lifeAdminView.value.weekday != 7) fail("$name: Sun left the hour on day ${core.lifeAdminView.value.weekday}")
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(core.lifeAdminView.value.summary))

        val skip = compose.onAllNodes(hasText(LifeAdminRules.SKIP_LABEL) and hasClickAction())
        if (skip.fetchSemanticsNodes().isEmpty()) fail("$name: no Skip this week")
        skip[0].performSemanticsAction(SemanticsActions.OnClick)
        until { core.lifeAdminView.value.skipped }
        if (!core.lifeAdminView.value.skipped) fail("$name: Skip this week left the hour booked")
        val keep = compose.onAllNodes(hasText(LifeAdminRules.KEEP_LABEL) and hasClickAction())
        if (keep.fetchSemanticsNodes().isEmpty()) fail("$name: no Keep it once skipped")
        keep[0].performSemanticsAction(SemanticsActions.OnClick)
        until { !core.lifeAdminView.value.skipped }
        if (core.lifeAdminView.value.skipped) fail("$name: Keep it left the hour skipped")

        val off = compose.onAllNodes(hasText(LifeAdminRules.OFF_LABEL) and hasClickAction())
        if (off.fetchSemanticsNodes().isEmpty()) fail("$name: no Turn off")
        off[0].performSemanticsAction(SemanticsActions.OnClick)
        until { !core.lifeAdminView.value.on }
        if (core.lifeAdminView.value.on) fail("$name: Turn off left the hour on")
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(LifeAdminRules.OFF_LINE))
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { pane("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { pane("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { pane("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() { pane("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerEmpty() { pane("inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { pane("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHand() { byHand(pane("cover-dark-by-hand", Fill.ONE)) }
}
