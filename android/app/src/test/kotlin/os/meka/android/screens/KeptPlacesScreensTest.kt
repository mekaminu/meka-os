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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
import os.meka.android.ask.DATE_NIGHT_EVENING_SHEET_TAG
import os.meka.android.ask.DATE_NIGHT_LIST_TAG
import os.meka.android.ask.DATE_NIGHT_ROW_SHEET_TAG
import os.meka.android.ask.DateNightPane
import os.meka.android.ask.EMERGENCY_LIST_TAG
import os.meka.android.ask.EMERGENCY_ROW_SHEET_TAG
import os.meka.android.ask.EmergencyPane
import os.meka.android.ask.FACTS_LIST_TAG
import os.meka.android.ask.FACTS_ROW_SHEET_TAG
import os.meka.android.ask.FactsPane
import os.meka.android.ask.LEAVE_LIST_TAG
import os.meka.android.ask.LEAVE_ROW_SHEET_TAG
import os.meka.android.ask.LIFE_ADD_SHEET_TAG
import os.meka.android.ask.LeavePane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.DateNightPageRules
import os.meka.core.domain.DateNightRules
import os.meka.core.domain.EmergencyPageRules
import os.meka.core.domain.EmergencyRules
import os.meka.core.domain.EmergencySection
import os.meka.core.domain.FactRules
import os.meka.core.domain.FactsPageRules
import os.meka.core.domain.LeavePageRules
import os.meka.core.domain.LeaveRules
import os.meka.core.domain.LocalClock
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 23 (build plan: "the rest of Ask's places"): Date night, Annual leave, Emergency card and
 * What Navilife knows, each on the Fold's cover and inner screens with nothing in, one thing and a full set, Dark (and
 * the full set in Light). Saved under `date-night/`, `leave/`, `emergency/` and `facts/` (the `fold-screens` artifact,
 * then the `screens` branch's docs/screens/fold/…) and read for nonsense, cut lines, words broken in two, dates and
 * times split across lines, and each add field's hint on one line; each pane's summary must be the one the core says.
 * Invented data only (rule 13): the numbers are Ofcom's drama range.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class KeptPlacesScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(43), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Place(val area: String, val title: String, val listTag: String) {
        DATE_NIGHT("date-night", "Date night", DATE_NIGHT_LIST_TAG), LEAVE("leave", "Annual leave", LEAVE_LIST_TAG),
        EMERGENCY("emergency", "Emergency card", EMERGENCY_LIST_TAG), FACTS("facts", "What Navilife knows", FACTS_LIST_TAG),
    }

    private enum class Fill { EMPTY, ONE, MANY }

    private suspend fun fill(core: MekaCore, place: Place, fill: Fill) {
        if (fill == Fill.EMPTY) return
        when (place) {
            Place.DATE_NIGHT -> {
                val friday = 5
                check(core.setDateNight(friday, 19 * 60, DateNightRules.firstNight(friday, core.todayEpochDay())) != null)
                if (fill == Fill.MANY) {
                    val second = core.dateNightView.value.nights.getOrNull(1) ?: fail("no second night")
                    check(core.skipDateNight(second.day, true) != null)
                }
            }
            Place.LEAVE -> {
                check(core.addLeave("27–30 Oct")?.refused == false)
                if (fill == Fill.MANY) {
                    check(core.addLeave("Spain 18 Dec – 4 Jan")?.refused == false)
                    check(core.addLeave("Fri 13 Nov")?.refused == false)
                    check(core.addLeave("Long weekend at the cottage in the Lake District 5–8 Feb")?.refused == false)
                    check(core.addLeave("2–3 Jul")?.refused == false)
                    check(core.addSchool("Half term 26–30 Oct") != null)
                    check(core.addSchool("Christmas holidays 21 Dec – 1 Jan") != null)
                }
            }
            Place.EMERGENCY -> {
                check(!core.addEmergency(EmergencySection.CALL.key, ShotLife.GP).refused)
                if (fill == Fill.MANY) {
                    check(!core.addEmergency(EmergencySection.CALL.key, "Alex's school office: 01632 960456").refused)
                    check(!core.addEmergency(EmergencySection.CALL.key, "Neighbour with the spare key: Pat two doors down, 07700 900123").refused)
                    check(!core.addEmergency(EmergencySection.DOCUMENTS.key, "Passports: study desk, top drawer").refused)
                    check(!core.addEmergency(EmergencySection.DOCUMENTS.key, "Wills and the deeds to the house: the grey box file in the loft above the landing").refused)
                    check(!core.addEmergency(EmergencySection.ACCOUNTS.key, "Home insurance: Oakfield Mutual").refused)
                    check(!core.addEmergency(EmergencySection.ACCOUNTS.key, "Energy: Oakfield Energy").refused)
                }
            }
            Place.FACTS -> {
                check(!core.addFact("Car reg: AB12 CDE").refused)
                if (fill == Fill.MANY) {
                    check(!core.addFact(ShotLife.GP).refused)
                    check(!core.addFact("Alex's shoe size: 13").refused)
                    check(!core.addFact("Energy supplier: Oakfield Energy").refused)
                    check(!core.addFact("Boiler: Oakfield Combi 30, serviced every autumn by the plumber on the high street").refused)
                    check(!core.addFact("Wi-Fi name: Oakfield-Home-5G").refused)
                    val kept = core.factsView.value.groups.flatMap { it.rows }.first { it.label.startsWith("Wi-Fi") }
                    check(core.setFactAi(kept.id, use = false))
                }
            }
        }
    }

    private fun shoot(place: Place, name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            fill(core, place, fill)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    when (place) {
                        Place.DATE_NIGHT -> DateNightPane(core, onClose = {})
                        Place.LEAVE -> LeavePane(core, onClose = {})
                        Place.EMERGENCY -> EmergencyPane(core, onClose = {})
                        Place.FACTS -> FactsPane(core, onClose = {})
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path(place.area, name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, place.title)
        // Each add field's hint is one example on one line (the quality gate's rule for hints, slice 20), and each
        // row's buttons keep their words whole.
        ScreenShots.assertEachOneLine(compose, name, listOf(LeaveRules.ADD_HINT, FactRules.HINT, FactRules.COPY_BUTTON,
            FactRules.KEEP_BUTTON, FactRules.USE_BUTTON, FactsPageRules.CHANGE_HINT, DateNightRules.SKIP_LABEL, DateNightRules.KEEP_LABEL, "Remove") +
            EmergencySection.entries.flatMap { listOf(it.hint, it.moreHint) })
        when (place) {
            Place.DATE_NIGHT -> {
                val v = core.dateNightView.value
                ScreenShots.assertShows(compose, name, v.summaryShown)
                // The page shape (slice 11): the three numbers, and Coming up's first row (or its empty line).
                val page = core.dateNightPage(v)
                if (page.tiles.size != 3) fail("$name: ${page.tiles.size} number tiles")
                page.tiles.forEach { ScreenShots.assertShows(compose, name, it.number) }
                page.cards.first().rows.firstOrNull()?.let { ScreenShots.assertShows(compose, name, it.name) }
                ScreenShots.assertReadable(compose, name, page.cards.flatMap { c -> c.rows.flatMap { listOf(it.name, it.line) } }, mustShow = false)
                if (fill == Fill.EMPTY) ScreenShots.assertShows(compose, name, DateNightPageRules.COMING_EMPTY)
                if (fill == Fill.EMPTY && v.on) fail("$name: date night on with nothing chosen")
                if (fill != Fill.EMPTY && v.nights.isEmpty()) fail("$name: no nights coming up once it's on")
                if (fill == Fill.MANY && v.nights.none { it.skipped }) fail("$name: the skipped night isn't skipped")
            }
            Place.LEAVE -> {
                val v = core.leaveView.value
                ScreenShots.assertShows(compose, name, v.summary)
                // The page shape (slice 9): the three numbers, and Booked's first row (or its empty line).
                val page = core.leavePage(v)
                if (page.tiles.size != 3) fail("$name: ${page.tiles.size} number tiles")
                page.tiles.forEach { ScreenShots.assertShows(compose, name, it.number) }
                page.cards.first().rows.firstOrNull()?.let { ScreenShots.assertShows(compose, name, it.name) }
                ScreenShots.assertReadable(compose, name, page.cards.flatMap { c -> c.rows.flatMap { listOf(it.name, it.line) } }, mustShow = false)
                if (fill == Fill.EMPTY) ScreenShots.assertShows(compose, name, LeavePageRules.BOOKED_EMPTY)
                if (fill == Fill.EMPTY && v.summary != LeaveRules.EMPTY_LINE) fail("$name: \"${v.summary}\" with nothing booked")
                if (fill == Fill.MANY && v.booked.size + v.taken.size != 5) fail("$name: ${v.booked.size + v.taken.size} rows for five stretches")
                if (fill == Fill.MANY && v.holidays.isEmpty()) fail("$name: no school holidays after typing two")
            }
            Place.EMERGENCY -> {
                val v = core.emergencyView.value
                ScreenShots.assertShows(compose, name, v.summary)
                // The page shape (slice 12): the three numbers, and Who to call's first row (or its empty line).
                val page = core.emergencyPage(v)
                if (page.tiles.size != 3) fail("$name: ${page.tiles.size} number tiles")
                page.tiles.forEach { ScreenShots.assertShows(compose, name, it.number) }
                page.cards.first().rows.firstOrNull()?.let { ScreenShots.assertShows(compose, name, it.name) }
                ScreenShots.assertReadable(compose, name, page.cards.flatMap { c -> c.rows.flatMap { listOf(it.name, it.line) } }, mustShow = false)
                if (fill == Fill.EMPTY) page.cards.first().empty?.let { ScreenShots.assertShows(compose, name, it) }
                if (fill == Fill.EMPTY && v.summary != EmergencyRules.EMPTY_SUMMARY) fail("$name: \"${v.summary}\" with nothing on the card")
                if (fill == Fill.MANY && v.count != 7) fail("$name: ${v.count} lines for seven")
                // A part's example never names a line it already holds (walk-through 2026-11-02, A14).
                v.groups.forEach { g ->
                    val example = EmergencyRules.read(g.hint)?.title
                    if (g.rows.any { it.title == example }) fail("$name: ${g.label}'s hint \"${g.hint}\" repeats a line")
                }
            }
            Place.FACTS -> {
                val v = core.factsView.value
                ScreenShots.assertShows(compose, name, v.summary)
                // The page shape (slice 13): the three numbers, and the first group's first row (or its empty line).
                val page = core.factsPage(v)
                if (page.tiles.size != 3) fail("$name: ${page.tiles.size} number tiles")
                page.tiles.forEach { ScreenShots.assertShows(compose, name, it.number) }
                page.cards.first().rows.firstOrNull()?.let { ScreenShots.assertShows(compose, name, it.name) }
                ScreenShots.assertReadable(compose, name, page.cards.flatMap { c -> c.rows.flatMap { listOf(it.name, it.line) } }, mustShow = false)
                if (fill == Fill.EMPTY) page.cards.first().empty?.let { ScreenShots.assertShows(compose, name, it) }
                if (fill == Fill.EMPTY && v.summary != FactRules.EMPTY_SUMMARY) fail("$name: \"${v.summary}\" with nothing in")
                if (fill == Fill.MANY && v.count != 6) fail("$name: ${v.count} facts for six")
            }
        }
        return core
    }

    /** Runs the main looper and the frames by turns (a tap's change reaches the recomposer through the looper). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /** Waits (core thread, looper and frames by turns) until [done] holds, then lets the screen settle for [settle] ms. */
    private fun until(settle: Long = SETTLE_MS, done: () -> Boolean) {
        repeat(40) {
            if (done()) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns(settle)
    }

    /**
     * Scrolls the pane's own list (bounded: at most 20 steps of 60 % down, then 40 up, never performScrollToNode with
     * the clock paused) until a node matching [m] is composed, then returns the matches, failing with [what] if none.
     */
    private fun reach(place: Place, name: String, m: SemanticsMatcher, what: String): SemanticsNodeInteractionCollection {
        fun found() = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
        if (!found()) {
            val list = compose.onNodeWithTag(place.listTag)
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

    private fun tap(place: Place, name: String, what: String, matcher: SemanticsMatcher) {
        reach(place, name, matcher and hasClickAction(), "\"$what\" to tap")[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Types [text] into the field TalkBack reads as [hint] and presses Done on the keyboard. */
    private fun type(place: Place, name: String, hint: String, text: String) {
        val field = hasContentDescription(hint) and hasSetTextAction()
        reach(place, name, field, "\"$hint\" field")[0].performTextInput(text)
        turns(500)
        compose.onAllNodes(field)[0].performImeAction()
    }

    private fun shows(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    /**
     * Every button drawn answers a 48 dp touch (Date night's Skip this one / Keep it, Leave's Book, − / + and Remove,
     * and the Emergency card's Remove were 30–40 dp before this check).
     */
    private fun assertTouchTargets(name: String) {
        val tappable = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) or
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        val small = compose.onAllNodes(hasClickAction() and tappable)
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: "?"
                "\"$label\" ${(n.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    /**
     * Works by hand, Date night (the page shape, slice 11): every button a 48 dp touch; a night's row opens its sheet,
     * Booked books it and Not booked yet takes that back, Skip this one skips it and Keep it keeps it again; + Add → The
     * evening's sheet moves the start with a time chip, the evening with a weekday chip, the first night with the other
     * First night chip, and Turn off turns it off.
     */
    private fun dateNightByHand(core: MekaCore) {
        val name = "date-night-by-hand"
        val p = Place.DATE_NIGHT
        assertTouchTargets(name)
        fun v() = core.dateNightView.value
        val rowSheet = hasAnyAncestor(hasTestTag(DATE_NIGHT_ROW_SHEET_TAG))
        val evening = hasAnyAncestor(hasTestTag(DATE_NIGHT_EVENING_SHEET_TAG))
        fun closeSheet(sheet: SemanticsMatcher) {
            tap(p, name, "the sheet's Close", hasText("Close") and sheet)
            until { !shows(sheet) }
        }
        val night = v().nights.firstOrNull { !it.skipped } ?: fail("$name: no kept night")
        fun cur() = v().nights.firstOrNull { it.day == night.day }
        tap(p, name, night.label, hasText(night.label) and !rowSheet)
        until { shows(hasContentDescription("Skip ${night.label}")) }
        assertTouchTargets(name)
        tap(p, name, DateNightPageRules.BOOKED, hasContentDescription("${DateNightPageRules.BOOKED}, ${night.label}"))
        until { cur()?.booked == true }
        if (cur()?.booked != true) fail("$name: Booked didn't book ${night.label}")
        tap(p, name, DateNightPageRules.NOT_BOOKED, hasContentDescription("${DateNightPageRules.NOT_BOOKED}, ${night.label}"))
        until { cur()?.booked == false }
        if (cur()?.booked != false) fail("$name: Not booked yet left ${night.label} booked")
        tap(p, name, DateNightRules.SKIP_LABEL, hasContentDescription("Skip ${night.label}"))
        until { cur()?.skipped == true }
        if (cur()?.skipped != true) fail("$name: Skip this one didn't skip ${night.label}")
        tap(p, name, DateNightRules.KEEP_LABEL, hasContentDescription("Keep ${night.label}"))
        until { cur()?.skipped == false }
        if (cur()?.skipped != false) fail("$name: Keep it didn't keep ${night.label}")
        closeSheet(rowSheet)
        tap(p, name, "+ Add", hasContentDescription("Add"))
        until { shows(hasTestTag(DATE_NIGHT_EVENING_SHEET_TAG)) }
        assertTouchTargets(name)
        val time = DateNightRules.START_CHOICES.first { it != v().startMin }
        tap(p, name, LocalClock.formatMinute(time), hasText(LocalClock.formatMinute(time)) and evening)
        until { v().startMin == time }
        if (v().startMin != time) fail("$name: the ${LocalClock.formatMinute(time)} chip left the start at ${v().startMin}")
        val weekday = if (v().weekday == 6) 4 else 6
        tap(p, name, DateNightRules.WEEKDAY_CHOICES[weekday - 1], hasText(DateNightRules.WEEKDAY_CHOICES[weekday - 1]) and evening)
        until { v().weekday == weekday && v().on }
        if (v().weekday != weekday || !v().on) fail("$name: the ${DateNightRules.WEEKDAY_CHOICES[weekday - 1]} chip didn't move the evening")
        val other = v().starts.firstOrNull { !it.chosen }
        if (other != null) {
            tap(p, name, other.label, hasText(other.label) and evening)
            until { v().starts.firstOrNull { it.label == other.label }?.chosen == true }
            if (v().starts.firstOrNull { it.label == other.label }?.chosen != true) fail("$name: First night ${other.label} wasn't chosen")
        }
        tap(p, name, DateNightRules.OFF_LABEL, hasText(DateNightRules.OFF_LABEL) and evening)
        until { !v().on }
        if (v().on) fail("$name: Turn off left date night on")
    }

    /**
     * Works by hand, Annual leave (the page shape, slice 9): every button a 48 dp touch; a school holiday's row opens
     * its sheet and Book books its work days; Spain's row opens its sheet and Remove takes it off; + Add → A stretch of
     * leave adds one; the Allowance row's sheet moves the days a year and the leave year's start with − / +.
     */
    private fun leaveByHand(core: MekaCore) {
        val name = "leave-by-hand"
        val p = Place.LEAVE
        assertTouchTargets(name)
        fun v() = core.leaveView.value
        val sheet = hasAnyAncestor(hasTestTag(LEAVE_ROW_SHEET_TAG))
        fun closeSheet() {
            tap(p, name, "the sheet's Close", hasText("Close") and sheet)
            until { !shows(sheet) }
        }
        val holiday = v().holidays.firstOrNull { it.bookLabel != null } ?: fail("$name: no school holiday to book")
        tap(p, name, holiday.title, hasText(holiday.title) and !sheet)
        val book = hasContentDescription("${holiday.bookLabel} for ${holiday.title}") and sheet
        until { shows(book) }
        assertTouchTargets(name)
        tap(p, name, holiday.bookLabel!!, book)
        until { v().holidays.firstOrNull { it.id == holiday.id }?.bookLabel != holiday.bookLabel }
        if (v().holidays.firstOrNull { it.id == holiday.id }?.bookLabel == holiday.bookLabel) fail("$name: ${holiday.bookLabel} booked nothing for ${holiday.title}")
        closeSheet()
        val row = v().booked.firstOrNull { it.title == "Spain" } ?: fail("$name: Spain isn't booked")
        tap(p, name, row.title, hasText(row.title) and !sheet)
        until { shows(hasContentDescription("Remove ${row.title}")) }
        tap(p, name, "Remove", hasContentDescription("Remove ${row.title}"))
        until { v().booked.none { it.id == row.id } }
        if (v().booked.any { it.id == row.id }) fail("$name: Remove left ${row.title}")
        until { !shows(sheet) }
        val count = v().booked.size + v().taken.size
        tap(p, name, "+ Add", hasContentDescription("Add"))
        until { shows(hasTestTag(LIFE_ADD_SHEET_TAG)) }
        type(p, name, LeaveRules.ADD_DESCRIPTION, "Mon 16 Nov")
        until { v().booked.size + v().taken.size > count }
        if (v().booked.size + v().taken.size <= count) fail("$name: Done on the field added no leave")
        until { !shows(hasTestTag(LIFE_ADD_SHEET_TAG)) }
        val days = v().allowance
        tap(p, name, LeavePageRules.ALLOWANCE, hasText("${LeaveRules.daysWord(days)} a year") and !sheet)
        until { shows(hasContentDescription("${LeavePageRules.DAYS_A_YEAR}: more")) }
        assertTouchTargets(name)
        tap(p, name, "+", hasContentDescription("${LeavePageRules.DAYS_A_YEAR}: more"))
        until { v().allowance == days + 1 }
        if (v().allowance != days + 1) fail("$name: + left the allowance at ${v().allowance}")
        tap(p, name, "−", hasContentDescription("${LeavePageRules.DAYS_A_YEAR}: fewer"))
        until { v().allowance == days }
        if (v().allowance != days) fail("$name: − left the allowance at ${v().allowance}")
        val month = v().yearStartMonth
        tap(p, name, "+", hasContentDescription("${LeavePageRules.YEAR_STARTS}: more"))
        until { v().yearStartMonth != month }
        if (v().yearStartMonth == month) fail("$name: + didn't move the leave year's start")
        tap(p, name, "−", hasContentDescription("${LeavePageRules.YEAR_STARTS}: fewer"))
        until { v().yearStartMonth == month }
        if (v().yearStartMonth != month) fail("$name: − didn't put the leave year's start back")
        closeSheet()
    }

    /**
     * Works by hand, Emergency card (the page shape, slice 12): every button a 48 dp touch; a line's row opens its
     * sheet, its field changes the details and Remove takes it off; + Add → each part adds a line to that part, and a
     * PIN is turned away with the words kept.
     */
    private fun emergencyByHand(core: MekaCore) {
        val name = "emergency-by-hand"
        val p = Place.EMERGENCY
        assertTouchTargets(name)
        fun v() = core.emergencyView.value
        fun rows() = v().groups.flatMap { it.rows }
        val sheet = hasAnyAncestor(hasTestTag(EMERGENCY_ROW_SHEET_TAG))
        val call = v().groups.first { it.section == EmergencySection.CALL.key }.rows.firstOrNull() ?: fail("$name: no one to call")
        tap(p, name, call.title, hasText(call.title) and !sheet)
        until { shows(hasContentDescription("Remove ${call.title}")) }
        assertTouchTargets(name)
        type(p, name, EmergencyPageRules.CHANGE_HINT, "Dr Shah, 01632 654321")
        until { rows().firstOrNull { it.id == call.id }?.detail == "Dr Shah, 01632 654321" }
        if (rows().firstOrNull { it.id == call.id }?.detail != "Dr Shah, 01632 654321") fail("$name: the sheet's field didn't change ${call.title}")
        tap(p, name, "Remove", hasContentDescription("Remove ${call.title}"))
        until { rows().none { it.id == call.id } }
        if (rows().any { it.id == call.id }) fail("$name: Remove left ${call.title}")
        until { !shows(sheet) }
        for ((i, section) in EmergencySection.entries.withIndex()) {
            val before = v().groups.first { it.section == section.key }.rows.size
            tap(p, name, "+ Add", hasContentDescription("Add"))
            until { shows(hasTestTag(LIFE_ADD_SHEET_TAG)) }
            val choice = EmergencyPageRules.ADD_CHOICES[i]
            tap(p, name, choice.label, hasText(choice.label) and hasAnyAncestor(hasTestTag(LIFE_ADD_SHEET_TAG)))
            until { shows(hasContentDescription(EmergencyPageRules.spokenHint(choice.key)) and hasSetTextAction()) }
            if (section == EmergencySection.ACCOUNTS) {
                type(p, name, EmergencyPageRules.spokenHint(choice.key), "Bank PIN: 4821")
                until { shows(hasText(EmergencyRules.REFUSED_NUMBER)) || shows(hasText(EmergencyRules.REFUSED_SECRET)) }
                if (!shows(hasText(EmergencyRules.REFUSED_NUMBER)) && !shows(hasText(EmergencyRules.REFUSED_SECRET))) fail("$name: a PIN wasn't turned away")
                if (v().groups.first { it.section == section.key }.rows.size != before) fail("$name: a PIN went on the card")
                compose.onAllNodes(hasContentDescription(EmergencyPageRules.spokenHint(choice.key)) and hasSetTextAction())[0].performTextClearance()
                turns(300)
            }
            type(p, name, EmergencyPageRules.spokenHint(choice.key), "Test ${section.label}: by hand")
            until { v().groups.first { it.section == section.key }.rows.size > before }
            if (v().groups.first { it.section == section.key }.rows.size <= before) fail("$name: + Add → ${choice.label} added nothing")
            until { !shows(hasTestTag(LIFE_ADD_SHEET_TAG)) }
        }
        assertTouchTargets(name)
    }

    /**
     * Works by hand, What Navilife knows: Copy says what it copied; Keep from AI and Let MEKA use it flip a fact;
     * Remove takes it off; the field adds one.
     */
    private fun factsByHand(core: MekaCore) {
        val name = "facts-by-hand"
        val p = Place.FACTS
        assertTouchTargets(name)
        fun rows() = core.factsView.value.groups.flatMap { it.rows }
        val sheet = hasAnyAncestor(hasTestTag(FACTS_ROW_SHEET_TAG))
        val car = rows().firstOrNull { it.label.startsWith("Car reg") } ?: fail("$name: no car reg")
        fun cur() = rows().firstOrNull { it.id == car.id }
        // The fact's row opens its sheet: Copy, Keep from AI, the field and Remove are all in there.
        tap(p, name, car.label, hasText(car.label) and !sheet)
        until { shows(hasContentDescription("Remove ${car.label}")) }
        assertTouchTargets(name)
        tap(p, name, FactRules.COPY_BUTTON, hasContentDescription(car.copySpoken))
        val copied = hasText(FactRules.copiedLine(car))
        until { shows(copied) }
        if (!shows(copied)) fail("$name: Copy didn't say \"${FactRules.copiedLine(car)}\"")
        tap(p, name, car.aiButton, hasContentDescription("${car.aiButton}: ${car.label}"))
        until { cur()?.keptFromAi != car.keptFromAi }
        if (cur()?.keptFromAi == car.keptFromAi) fail("$name: ${car.aiButton} didn't change ${car.label}")
        val back = cur() ?: fail("$name: ${car.label} went")
        tap(p, name, back.aiButton, hasContentDescription("${back.aiButton}: ${back.label}"))
        until { cur()?.keptFromAi == car.keptFromAi }
        if (cur()?.keptFromAi != car.keptFromAi) fail("$name: ${back.aiButton} didn't put ${car.label} back")
        type(p, name, FactsPageRules.CHANGE_HINT, "XY99 ZZZ")
        until { cur()?.value == "XY99 ZZZ" }
        if (cur()?.value != "XY99 ZZZ") fail("$name: the sheet's field didn't change ${car.label}")
        tap(p, name, "Remove", hasContentDescription("Remove ${car.label}"))
        until { cur() == null }
        if (cur() != null) fail("$name: Remove left ${car.label}")
        until { !shows(sheet) }
        // + Add: a fact, then one kept from the AI (a card number is turned away first).
        for (choice in FactsPageRules.ADD_CHOICES) {
            val before = rows().size
            tap(p, name, "+ Add", hasContentDescription("Add"))
            until { shows(hasTestTag(LIFE_ADD_SHEET_TAG)) }
            tap(p, name, choice.label, hasText(choice.label) and hasAnyAncestor(hasTestTag(LIFE_ADD_SHEET_TAG)))
            until { shows(hasContentDescription(FactsPageRules.spokenHint(choice.key)) and hasSetTextAction()) }
            if (choice.key == FactsPageRules.ADD_FACT) {
                type(p, name, FactsPageRules.spokenHint(choice.key), "Savings card: 4929 1234 5678 9012")
                until { shows(hasText(FactRules.REFUSED_BANK)) }
                if (!shows(hasText(FactRules.REFUSED_BANK))) fail("$name: a card number wasn't turned away")
                if (rows().size != before) fail("$name: a card number went on the page")
                compose.onAllNodes(hasContentDescription(FactsPageRules.spokenHint(choice.key)) and hasSetTextAction())[0].performTextClearance()
                turns(300)
            }
            type(p, name, FactsPageRules.spokenHint(choice.key), "Test ${choice.key}: by hand")
            until { rows().size > before }
            if (rows().size <= before) fail("$name: + Add → ${choice.label} added nothing")
            if (FactsPageRules.keepsFromAi(choice.key) && rows().none { it.label.startsWith("Test") && it.keptFromAi }) {
                fail("$name: + Add → ${choice.label} didn't keep it from the AI")
            }
            until { !shows(hasTestTag(LIFE_ADD_SHEET_TAG)) }
        }
        assertTouchTargets(name)
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun dateNightByHand() {
        dateNightByHand(shoot(Place.DATE_NIGHT, "cover-dark-date-night-by-hand", Fill.MANY))
        ScreenShots.reshootAfterHand(compose, Place.DATE_NIGHT.area, "cover-dark-date-night-by-hand") // walk-through A15
    }
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun leaveByHand() {
        leaveByHand(shoot(Place.LEAVE, "cover-dark-leave-by-hand", Fill.MANY))
        ScreenShots.reshootAfterHand(compose, Place.LEAVE.area, "cover-dark-leave-by-hand") // walk-through A15
    }
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun emergencyByHand() {
        emergencyByHand(shoot(Place.EMERGENCY, "cover-dark-emergency-by-hand", Fill.MANY))
        ScreenShots.reshootAfterHand(compose, Place.EMERGENCY.area, "cover-dark-emergency-by-hand") // walk-through A15
    }
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun factsByHand() {
        factsByHand(shoot(Place.FACTS, "cover-dark-facts-by-hand", Fill.MANY))
        ScreenShots.reshootAfterHand(compose, Place.FACTS.area, "cover-dark-facts-by-hand") // walk-through A15
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun dateNightCoverEmpty() { shoot(Place.DATE_NIGHT, "cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun dateNightCoverOne() { shoot(Place.DATE_NIGHT, "cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun dateNightCoverMany() { shoot(Place.DATE_NIGHT, "cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun dateNightCoverManyLight() { shoot(Place.DATE_NIGHT, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun dateNightInnerEmpty() { shoot(Place.DATE_NIGHT, "inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun dateNightInnerMany() { shoot(Place.DATE_NIGHT, "inner-dark-many", Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.COVER) fun leaveCoverEmpty() { shoot(Place.LEAVE, "cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun leaveCoverOne() { shoot(Place.LEAVE, "cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun leaveCoverMany() { shoot(Place.LEAVE, "cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun leaveCoverManyLight() { shoot(Place.LEAVE, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun leaveInnerEmpty() { shoot(Place.LEAVE, "inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun leaveInnerMany() { shoot(Place.LEAVE, "inner-dark-many", Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.COVER) fun emergencyCoverEmpty() { shoot(Place.EMERGENCY, "cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun emergencyCoverOne() { shoot(Place.EMERGENCY, "cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun emergencyCoverMany() { shoot(Place.EMERGENCY, "cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun emergencyCoverManyLight() { shoot(Place.EMERGENCY, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun emergencyInnerEmpty() { shoot(Place.EMERGENCY, "inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun emergencyInnerMany() { shoot(Place.EMERGENCY, "inner-dark-many", Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.COVER) fun factsCoverEmpty() { shoot(Place.FACTS, "cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun factsCoverOne() { shoot(Place.FACTS, "cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun factsCoverMany() { shoot(Place.FACTS, "cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun factsCoverManyLight() { shoot(Place.FACTS, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun factsInnerEmpty() { shoot(Place.FACTS, "inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun factsInnerMany() { shoot(Place.FACTS, "inner-dark-many", Fill.MANY) }
}
