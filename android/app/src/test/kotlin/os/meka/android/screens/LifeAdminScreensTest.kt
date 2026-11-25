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
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
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
import os.meka.android.ask.LIFE_ADMIN_HOUR_SHEET_TAG
import os.meka.android.ask.LIFE_ADMIN_JOB_SHEET_TAG
import os.meka.android.ask.LIFE_ADMIN_LIST_TAG
import os.meka.android.ask.LIFE_ADMIN_WEEK_SHEET_TAG
import os.meka.android.ask.LIFE_EMPTY_TAG
import os.meka.android.ask.LIFE_TILE_TAG
import os.meka.core.domain.CarRules
import os.meka.core.domain.LifeAdminJobKind
import os.meka.core.domain.LifeAdminPageRules
import os.meka.core.domain.LifeAdminRules
import os.meka.core.domain.LifeAdminSetting
import os.meka.core.domain.LifePageRules
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
                core.addRenewal("Home insurance", ObligationKind.INSURANCE, today + ShotCar.HOME_INSURANCE_DAYS, RenewalRepeat.YEARLY, null, null)
                core.addTask("Call the GP about the results")
            }
            Fill.MANY -> {
                core.setLifeAdmin(LifeAdminRules.DEFAULT_WEEKDAY, 19 * 60)
                // Walk-through 2026-11-22 A13: the car's dates are Car's shots' own, and the home insurance's day is the
                // one shot's (it was Fri 30 Oct here beside Mon 26 Oct there).
                ShotCar.dates(core, many = true)
                core.addRenewal("Home insurance for the flat and its contents", ObligationKind.INSURANCE, today + ShotCar.HOME_INSURANCE_DAYS, RenewalRepeat.YEARLY, null, null)
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

    private fun shows(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    /** The words drawn on the page now. */
    private fun drawn(): List<String> = compose.onAllNodes(SemanticsMatcher("text") { it.config.getOrNull(SemanticsProperties.Text) != null }, useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text.replace('\u00A0', ' ') } }

    /**
     * Scrolls the pane's own list (bounded: at most 20 steps of 60 % down, then 40 up) until a node matching [m] is
     * composed, then returns the matches, failing with [what] if none.
     */
    private fun reach(name: String, m: SemanticsMatcher, what: String): SemanticsNodeInteractionCollection {
        fun found() = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
        if (!found()) {
            val list = compose.onNodeWithTag(LIFE_ADMIN_LIST_TAG)
            val step = list.fetchSemanticsNode().size.height * 0.6f
            for (dy in List(20) { step } + List(40) { -step }) {
                if (found()) break
                list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
                compose.mainClock.advanceTimeBy(1_000)
            }
            turns(500)
        }
        val n = compose.onAllNodes(m)
        if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no $what")
        return n
    }

    private fun tap(name: String, what: String, matcher: SemanticsMatcher) {
        reach(name, matcher and hasClickAction(), "\"$what\" to tap")[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun tiles() = compose.onAllNodes(hasTestTag(LIFE_TILE_TAG), useUnmergedTree = true).fetchSemanticsNodes()

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
        val page = core.lifeAdminPage(v)
        val lines = page.cards.flatMap { c -> c.rows.flatMap { listOf(it.name, it.line) } + listOfNotNull(c.empty) } +
            (if (LifeAdminPageRules.blank(v)) listOf(LifeAdminPageRules.EMPTY_LINE) else emptyList())
        ScreenShots.assertNoLoneWords(compose, name, lines.filter { it.isNotEmpty() })
        ScreenShots.assertNoDotStartingALine(compose, name, lines.filter { it.isNotEmpty() })
        assertTouchTargets(name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        // Week 5 A16: the page shape. No open chip rows on the page (they live in + Add's sheet), and never the old
        // page's summary, WHEN label or note.
        val words = drawn()
        (LifeAdminRules.WEEKDAY_CHOICES + LifeAdminRules.START_CHOICES.map(LocalClock::formatMinute)).forEach { chip ->
            if (shows(hasText(chip) and hasClickAction())) fail("$name: the \"$chip\" chip is on the page, not in + Add's sheet")
        }
        listOf("WHEN", "GATHERED INTO IT", LifeAdminRules.NOTE, LifeAdminRules.OFF_LINE).forEach { w ->
            if (words.any { it.contains(w) }) fail("$name: \"$w\" is still on the page")
        }
        if (!shows(hasContentDescription(LifePageRules.INFO_LABEL))) fail("$name: no ⓘ beside the title")
        if (!shows(hasContentDescription("Add"))) fail("$name: no + Add")
        when (fill) {
            Fill.EMPTY -> {
                if (v.on) fail("$name: the hour is on with nothing set ($v)")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept(LifeAdminPageRules.EMPTY_LINE))
                if (tiles().isNotEmpty()) fail("$name: tiles on the blank page")
                if (compose.onAllNodes(hasTestTag(LIFE_EMPTY_TAG), useUnmergedTree = true).fetchSemanticsNodes().size != 1) fail("$name: not one blank card")
                tap(name, LifeAdminPageRules.ADD_FIRST, hasText(LifeAdminPageRules.ADD_FIRST))
                until { shows(hasTestTag(LIFE_ADMIN_HOUR_SHEET_TAG)) }
                if (!shows(hasText("Sat") and hasClickAction() and hasAnyAncestor(hasTestTag(LIFE_ADMIN_HOUR_SHEET_TAG)))) {
                    fail("$name: ${LifeAdminPageRules.ADD_FIRST} didn't open the hour's chooser")
                }
            }
            Fill.ONE -> {
                if (v.jobCount != 2) fail("$name: ${v.jobCount} jobs gathered, not the renewal and the call (${v.jobs})")
                if (v.jobs.first().title != "Home insurance") fail("$name: the renewal isn't first (${v.jobs})")
                if (tiles().size != 3) fail("$name: ${tiles().size} tiles, not three")
                ScreenShots.assertShows(compose, name, LifeAdminPageRules.JOBS)
                ScreenShots.assertShows(compose, name, LifeAdminPageRules.HOUR)
                ScreenShots.assertShows(compose, name, v.thisWeek!!.whenLine)
            }
            Fill.MANY -> {
                if (v.jobCount <= LifePageRules.MAX_ROWS) fail("$name: ${v.jobCount} jobs; the fixture should overflow the card")
                if (v.overdueCount != 1) fail("$name: ${v.overdueCount} overdue, not the school form")
                if (v.jobs.first().title != ShotCar.INSURANCE) fail("$name: the nearest renewal isn't first (${v.jobs})")
                // A13: every car date gathered here (the insurance, and the MOT within its four weeks) is on Car's Dates too.
                val carJobs = v.jobs.filter { it.kind == LifeAdminJobKind.RENEWAL && CarRules.PRESETS.any { p -> p.title == it.title } }.map { it.title }
                if (carJobs.size < 2) fail("$name: the car's insurance and MOT aren't both gathered (${v.jobs.map { it.title }})")
                val missing = ShotCar.missingFromCar(core, carJobs)
                if (missing.isNotEmpty()) fail("$name: Life admin gathers $missing, which Car's Dates don't hold")
                val firstTask = v.jobs.firstOrNull { it.kind == LifeAdminJobKind.TASK }
                if (firstTask?.overdue != true) fail("$name: the overdue form isn't the first task (${v.jobs.map { it.title }})")
                // Five rows and See all, never all eleven jobs as cards.
                if (shows(hasText(LoneWordGlue.kept(v.jobs[LifePageRules.MAX_ROWS].title)))) fail("$name: a sixth job is on the page before See all")
                reach(name, hasText("${LifePageRules.SEE_ALL} ›"), "See all on Gathered into it")
                // Week 5 A16: open, the page runs the full width like its siblings, not a centred column.
                val screen = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
                val pane = compose.onNodeWithTag(LIFE_ADMIN_LIST_TAG).fetchSemanticsNode().boundsInRoot
                if (pane.width < screen.width - 2 * compose.density.density) fail("$name: the page is a ${(pane.width / compose.density.density).toInt()} dp column")
            }
        }
        return core
    }

    /**
     * Works by hand: See all shows every job and Fewer folds it; the setting's row opens the chooser, where Sun moves the
     * hour to Sunday; this week's row opens Skip this week, which skips it, and Keep it keeps it; a task's row opens Done,
     * which ticks it off; Turn off in the chooser turns it off and the page is one blank card again.
     */
    private fun byHand(core: MekaCore) {
        val name = "life-admin-by-hand"
        fun v() = core.lifeAdminView.value
        val hourSheet = hasAnyAncestor(hasTestTag(LIFE_ADMIN_HOUR_SHEET_TAG))
        val weekSheet = hasAnyAncestor(hasTestTag(LIFE_ADMIN_WEEK_SHEET_TAG))
        val jobSheet = hasAnyAncestor(hasTestTag(LIFE_ADMIN_JOB_SHEET_TAG))
        fun closeSheet(sheet: SemanticsMatcher) {
            tap(name, "the sheet's Close", hasText("Close") and sheet)
            until { !shows(sheet) }
        }

        tap(name, LifePageRules.SEE_ALL, hasText("${LifePageRules.SEE_ALL} ›"))
        turns()
        val last = LoneWordGlue.kept(v().allJobs.last().title)
        reach(name, hasText(last), "the last job after See all")
        tap(name, LifeAdminPageRules.FEWER, hasText("${LifeAdminPageRules.FEWER} ›"))
        turns()

        tap(name, "the hour's setting", hasText(LifeAdminRules.everyLine(LifeAdminSetting(v().weekday, v().startMin))))
        until { shows(hasTestTag(LIFE_ADMIN_HOUR_SHEET_TAG)) }
        assertTouchTargets(name)
        tap(name, "Sun", hasText("Sun") and hourSheet)
        until { v().weekday == 7 }
        if (v().weekday != 7) fail("$name: Sun left the hour on day ${v().weekday}")
        closeSheet(hourSheet)

        val week = v().thisWeek ?: fail("$name: no hour this week once it's on Sunday")
        tap(name, week.whenLine, hasText(week.whenLine) and !weekSheet)
        until { shows(hasTestTag(LIFE_ADMIN_WEEK_SHEET_TAG)) }
        tap(name, LifeAdminRules.SKIP_LABEL, hasText(LifeAdminRules.SKIP_LABEL) and weekSheet)
        until { v().skipped }
        if (!v().skipped) fail("$name: Skip this week left the hour booked")
        ScreenShots.awaitShows(compose, name, LifeAdminRules.KEEP_LABEL)
        closeSheet(weekSheet)
        // Week 5 A11: the by-hand shot shows what the hand did (the hour moved to Sunday, this week skipped, the next
        // hour beside it), not the one state again.
        ScreenShots.awaitShows(compose, name, LifeAdminPageRules.SKIPPED_LINE)
        ScreenShots.reshootAfterHand(compose, "life-admin", "cover-dark-by-hand")
        tap(name, week.whenLine, hasText(week.whenLine) and !weekSheet)
        until { shows(hasTestTag(LIFE_ADMIN_WEEK_SHEET_TAG)) }
        tap(name, LifeAdminRules.KEEP_LABEL, hasText(LifeAdminRules.KEEP_LABEL) and weekSheet)
        until { !v().skipped }
        if (v().skipped) fail("$name: Keep it left the hour skipped")
        closeSheet(weekSheet)

        val task = v().jobs.first { it.kind == LifeAdminJobKind.TASK }
        tap(name, task.title, hasText(LoneWordGlue.kept(task.title)) and !jobSheet)
        until { shows(hasTestTag(LIFE_ADMIN_JOB_SHEET_TAG)) }
        tap(name, LifeAdminPageRules.DONE, hasContentDescription("${LifeAdminPageRules.DONE}, ${task.title}"))
        until { v().allJobs.none { it.id == task.id } }
        if (v().allJobs.any { it.id == task.id }) fail("$name: Done left ${task.title} gathered")

        tap(name, "+ Add", hasContentDescription("Add"))
        until { shows(hasTestTag(LIFE_ADMIN_HOUR_SHEET_TAG)) }
        tap(name, LifeAdminRules.OFF_LABEL, hasText(LifeAdminRules.OFF_LABEL) and hourSheet)
        until { !v().on }
        if (v().on) fail("$name: Turn off left the hour on")
        ScreenShots.awaitShows(compose, name, LoneWordGlue.kept(LifeAdminPageRules.EMPTY_LINE))
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { pane("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { pane("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { pane("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() { pane("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerEmpty() { pane("inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { pane("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHand() { byHand(pane("cover-dark-by-hand", Fill.MANY)) }
}
