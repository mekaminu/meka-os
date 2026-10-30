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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import os.meka.android.ask.APPOINTMENTS_LIST_TAG
import os.meka.android.ask.AppointmentsPane
import os.meka.android.ask.KIDS_LIST_TAG
import os.meka.android.ask.KidsPane
import os.meka.android.ask.SCHOOL_LIST_TAG
import os.meka.android.ask.SchoolPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.AppointmentRules
import os.meka.core.domain.BoardFeedRules
import os.meka.core.domain.KidChoreRules
import os.meka.core.domain.KidSize
import os.meka.core.domain.KidsRules
import os.meka.core.domain.SchoolRules
import os.meka.core.domain.SchoolTerms
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 16 (build plan: "the rest of Ask's places"): the family-admin places, Appointments, Kids
 * and School, each on the Fold's cover and inner screens with nothing in, one thing and a full set, Dark (and the
 * full set in Light). Saved under `appointments/`, `kids/` and `school/` (the `fold-screens` artifact, then the
 * `screens` branch's docs/screens/fold/…) and read for nonsense, cut lines and words broken in two; each pane's
 * summary line must be the one the core says. Appointments' rows must read whole (their first finding: on the cover
 * screen "Every 6 months · last went Sat 12 Apr" was cut to one line). Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class AdminPlacesScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(31), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Place(val area: String, val title: String, val listTag: String) {
        APPOINTMENTS("appointments", "Appointments", APPOINTMENTS_LIST_TAG), KIDS("kids", KidsRules.TITLE, KIDS_LIST_TAG),
        SCHOOL("school", "School", SCHOOL_LIST_TAG),
    }

    private enum class Fill { EMPTY, ONE, MANY }

    private suspend fun children(core: MekaCore, fill: Fill) {
        core.addAboutYouChild("Alex, U7, Oakfield School")
        if (fill == Fill.MANY) core.addAboutYouChild("Sam, U10, Oakfield School")
    }

    private suspend fun fill(core: MekaCore, place: Place, fill: Fill) {
        if (fill == Fill.EMPTY) return
        when (place) {
            Place.APPOINTMENTS -> {
                check(!core.typeAppointment("Dentist every 6 months, last 12 Apr").refused)
                if (fill == Fill.MANY) {
                    check(!core.typeAppointment("Haircut every 6 weeks, last 26 Sep").refused)
                    check(!core.typeAppointment("Optician every 2 years, last March 2025").refused)
                    check(!core.typeAppointment("Hygienist every 6 months, booked Tue 3 Nov 9:30").refused)
                    check(!core.typeAppointment("Orthodontist check for the brace and retainer every 6 weeks, last 30 Sep").refused)
                }
            }
            Place.KIDS -> {
                children(core, fill)
                check(!core.setKidSize("Alex", "shoes", "13").refused)
                if (fill == Fill.MANY) {
                    check(!core.setKidSize("Alex", "tops", "6-7").refused)
                    check(!core.setKidSize("Sam", "shoes", "2").refused)
                    check(!core.addKidFee("Alex", "Football £35 monthly from 1 Nov").refused)
                    check(!core.addKidFee("Sam", "Swimming £60 on the 14th every month").refused)
                    check(!core.setKidPocketMoney("Sam", "£5").refused)
                    check(!core.addKidChore("Sam", "Tidy room £1").refused)
                }
            }
            Place.SCHOOL -> {
                children(core, fill)
                check(core.addSchool("INSET 27 Oct") != null)
                if (fill == Fill.MANY) {
                    check(core.addSchool("Half term 26–30 Oct") != null)
                    check(core.addSchool("Alex PE Tue") != null)
                    check(core.addSchool("Sam's trip to the science museum with a packed lunch 20 Nov") != null)
                    check(core.addSchool("Trip payment by 14 Nov") != null)
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
                        Place.APPOINTMENTS -> AppointmentsPane(core, onClose = {})
                        Place.KIDS -> KidsPane(core, onClose = {}, onAboutYou = {})
                        Place.SCHOOL -> SchoolPane(core, onClose = {})
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
        ScreenShots.assertShows(compose, name, place.title)
        when (place) {
            Place.APPOINTMENTS -> {
                val v = core.appointmentsView.value
                // Each row's name and how often / last went read whole (on two lines if they must), never "Every 6 mo…".
                ScreenShots.assertReadable(compose, name, v.rows.flatMap { listOf(it.title, it.line) }, mustShow = false)
                // Slice 16's findings: "last went 1 / Mar 2025" and "was due Mon / 12 Oct" never split.
                ScreenShots.assertDatesWhole(compose, name)
                ScreenShots.assertShows(compose, name, v.summary)
                if (fill == Fill.EMPTY && v.summary != AppointmentRules.EMPTY_SUMMARY) fail("$name: \"${v.summary}\" with nothing in")
                if (fill == Fill.MANY && v.rows.size != 5) fail("$name: ${v.rows.size} rows for five")
                v.rows.firstOrNull()?.let { ScreenShots.assertShows(compose, name, it.title) }
            }
            Place.KIDS -> {
                val v = core.kidsView.value
                ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
                ScreenShots.assertShows(compose, name, v.summary)
                if (fill == Fill.EMPTY) {
                    if (v.summary != KidsRules.NO_CHILDREN) fail("$name: \"${v.summary}\" with no children")
                    ScreenShots.assertShows(compose, name, "About you")
                }
                if (fill == Fill.MANY && v.kids.size != 2) fail("$name: ${v.kids.size} cards for two children")
                // Slice 16's findings: each size field's hint fits its pill on one line ("Boot size… (12)" wrapped).
                ScreenShots.assertEachOneLine(compose, name, KidSize.entries.map { it.hint }.toSet())
            }
            Place.SCHOOL -> {
                val v = core.schoolView.value
                ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
                ScreenShots.assertShows(compose, name, v.summary)
                if (fill == Fill.EMPTY && v.summary != SchoolRules.EMPTY_LINE) fail("$name: \"${v.summary}\" with nothing entered")
                if (fill != Fill.EMPTY && v.isEmpty) fail("$name: nothing kept from what was typed")
                // Slice 16's findings: with no council to offer, the council card ends just under its words.
                v.terms?.takeIf { it.choices.isEmpty() && it.refresh == null && it.filled == null }?.let { t ->
                    if ("Pick" in t.line) fail("$name: \"${t.line}\" asks him to pick a council with none to pick")
                    ScreenShots.assertHugs(compose, name, "school-terms", t.line.takeLast(14), maxGapDp = 24f)
                }
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

    /** Types [text] into the field TalkBack reads as [description] and presses Done on the keyboard. */
    private fun type(place: Place, name: String, description: String, text: String, replace: Boolean = false) {
        val field = hasContentDescription(description) and hasSetTextAction()
        val node = reach(place, name, field, "\"$description\" field")[0]
        if (replace) node.performTextReplacement(text) else node.performTextInput(text)
        turns(500)
        compose.onAllNodes(field)[0].performImeAction()
    }

    private fun shows(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    /**
     * Every button drawn answers a 48 dp touch (Appointments' Went and Remove, School's Remove and Fill in were about
     * 30–36 dp before this check).
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

    /** Works by hand, Appointments: Went notes a visit today, a Common ones chip adds one, Remove takes one off, the field adds one. */
    private fun appointmentsByHand(core: MekaCore) {
        val name = "appointments-by-hand"
        val p = Place.APPOINTMENTS
        assertTouchTargets(name)
        fun rows() = core.appointmentsView.value.rows
        val dentist = rows().firstOrNull { it.title.startsWith("Dentist") } ?: fail("$name: no dentist")
        fun cur() = rows().firstOrNull { it.id == dentist.id }
        tap(p, name, AppointmentRules.WENT_LABEL, hasContentDescription("Went to ${dentist.title} today"))
        until { cur()?.line != dentist.line }
        if (cur()?.line == dentist.line) fail("$name: Went left ${dentist.title} at \"${dentist.line}\"")
        core.appointmentsView.value.suggestions.firstOrNull()?.let { s ->
            val before = rows().size
            tap(p, name, "+ ${s.title}", hasContentDescription("Add ${s.title}, ${s.line}"))
            until { rows().size > before }
            if (rows().size <= before) fail("$name: the ${s.title} chip added nothing")
        }
        tap(p, name, "Remove", hasContentDescription("Remove ${dentist.title}"))
        until { cur() == null }
        if (cur() != null) fail("$name: Remove left ${dentist.title}")
        type(p, name, AppointmentRules.HINT, "Physio every 3 months, last 1 Oct")
        until { rows().any { it.title.startsWith("Physio") } }
        if (rows().none { it.title.startsWith("Physio") }) fail("$name: Done on the field added no appointment")
        assertTouchTargets(name)
    }

    /**
     * Works by hand, Kids: All still fit says so; Clear takes a size off and Undo puts it back; a size field keeps what's
     * typed; a fee's Paid moves it on and Undo back, Remove takes it off, the fee field adds one; a chore's day dot ticks,
     * Paid notes the week and Undo takes it back, Remove and Undo, the chore field adds one, the pocket money field keeps
     * a new amount.
     */
    private fun kidsByHand(core: MekaCore) {
        val name = "kids-by-hand"
        val p = Place.KIDS
        assertTouchTargets(name)
        fun kid(n: String) = core.kidsView.value.kids.firstOrNull { it.name == n } ?: fail("$name: no card for $n")
        val undo = hasText("Undo") and hasClickAction()
        // All still fit.
        tap(p, name, "All still fit", hasContentDescription("Alex's sizes all still fit"))
        val fit = hasText(KidsRules.fitLine("Alex"))
        until { shows(fit) }
        if (!shows(fit)) fail("$name: All still fit didn't say so")
        // Clear and Undo.
        val shoes = kid("Alex").sizes.firstOrNull { it.value == "13" } ?: fail("$name: Alex's size 13 isn't kept")
        fun shoesNow() = kid("Alex").sizes.first { it.size == shoes.size }.value
        tap(p, name, "Clear", hasContentDescription("Clear Alex's ${shoes.label.lowercase()} size"))
        until { shoesNow() == null }
        if (shoesNow() != null) fail("$name: Clear left Alex's ${shoes.label} at ${shoesNow()}")
        tap(p, name, "Undo", undo)
        until { shoesNow() == "13" }
        if (shoesNow() != "13") fail("$name: Undo didn't put Alex's ${shoes.label} back (${shoesNow()})")
        // A size field.
        val empty = kid("Alex").sizes.firstOrNull { it.value == null } ?: fail("$name: no empty size for Alex")
        fun emptyNow() = kid("Alex").sizes.first { it.size == empty.size }.value
        type(p, name, "Alex's ${empty.label.lowercase()} size, for example ${empty.hint}", empty.hint)
        until { emptyNow() != null }
        if (emptyNow() == null) fail("$name: Done on Alex's ${empty.label} field kept nothing")
        assertTouchTargets(name)
        // Club fees.
        val fee = kid("Alex").fees.firstOrNull() ?: fail("$name: no fee for Alex")
        fun feeNow() = kid("Alex").fees.firstOrNull { it.id == fee.id }
        tap(p, name, "Paid", hasContentDescription("${fee.title} paid"))
        until { feeNow()?.dueDay != fee.dueDay }
        if (feeNow()?.dueDay == fee.dueDay) fail("$name: Paid didn't move ${fee.title} on")
        tap(p, name, "Undo", undo)
        until { feeNow()?.dueDay == fee.dueDay }
        if (feeNow()?.dueDay != fee.dueDay) fail("$name: Undo didn't put ${fee.title} back")
        tap(p, name, "Remove", hasContentDescription("Remove ${fee.title}"))
        until { feeNow() == null }
        if (feeNow() != null) fail("$name: Remove left ${fee.title}")
        fun fees() = core.kidsView.value.kids.sumOf { it.fees.size }
        val feesBefore = fees()
        type(p, name, KidsRules.FEE_HINT, "Tennis £20 monthly from 5 Nov")
        until { fees() > feesBefore }
        if (fees() <= feesBefore) fail("$name: Done on the fee field added nothing")
        // Chores and pocket money (Sam's).
        val chore = kid("Sam").money.chores.firstOrNull() ?: fail("$name: no chore for Sam")
        fun choreNow() = kid("Sam").money.chores.firstOrNull { it.id == chore.id }
        val today = chore.days.firstOrNull { it.today } ?: fail("$name: no today dot on ${chore.title}")
        tap(p, name, today.letter, hasContentDescription("${chore.title}, ${today.spoken}"))
        until { choreNow()?.days?.firstOrNull { it.today }?.done == true }
        if (choreNow()?.days?.firstOrNull { it.today }?.done != true) fail("$name: today's dot didn't tick ${chore.title}")
        val pay = kid("Sam").money.payLabel ?: fail("$name: nothing to mark paid for Sam")
        val paidBefore = kid("Sam").money.paidLine
        tap(p, name, pay, hasContentDescription("$pay to Sam for this week"))
        until { kid("Sam").money.paidLine != paidBefore }
        if (kid("Sam").money.paidLine == paidBefore) fail("$name: $pay noted nothing")
        tap(p, name, "Undo", undo)
        until { kid("Sam").money.paidLine == paidBefore }
        if (kid("Sam").money.paidLine != paidBefore) fail("$name: Undo didn't take back $pay")
        tap(p, name, "Remove", hasContentDescription("Remove ${chore.title}"))
        until { choreNow() == null }
        if (choreNow() != null) fail("$name: Remove left ${chore.title}")
        tap(p, name, "Undo", undo)
        until { choreNow() != null }
        if (choreNow() == null) fail("$name: Undo didn't bring ${chore.title} back")
        // Each child's card has its own chore and pocket money fields; the first composed one takes the words.
        fun chores() = core.kidsView.value.kids.flatMap { it.money.chores }
        type(p, name, KidChoreRules.CHORE_HINT, "Feed the cat")
        until { chores().any { it.title == "Feed the cat" } }
        if (chores().none { it.title == "Feed the cat" }) fail("$name: Done on the chore field added nothing")
        fun pockets() = core.kidsView.value.kids.map { it.money.pocketValue }
        val pocket = pockets()
        type(p, name, KidChoreRules.POCKET_HINT, "£6", replace = true)
        until { pockets() != pocket }
        if (pockets() == pocket) fail("$name: the pocket money field kept $pocket")
        assertTouchTargets(name)
    }

    /**
     * Works by hand, School: Fill in (when a council is offered) and Undo; Remove takes a row off; the field adds one;
     * the INSET field adds days off; To a day… on a date unfolds its chips, a chip makes the task and Undo takes it back.
     */
    private fun schoolByHand(core: MekaCore) {
        val name = "school-by-hand"
        val p = Place.SCHOOL
        assertTouchTargets(name)
        fun v() = core.schoolView.value
        val undo = hasText("Undo") and hasClickAction()
        v().terms?.choices?.firstOrNull()?.let { c ->
            tap(p, name, "Fill in", hasContentDescription("Fill in ${c.name}'s holidays"))
            until { v().terms?.filled != null }
            if (v().terms?.filled == null) fail("$name: Fill in didn't fill ${c.name}'s holidays")
            tap(p, name, "Undo", undo)
            until { v().terms?.filled == null }
            if (v().terms?.filled != null) fail("$name: Undo left ${c.name}'s holidays in")
        }
        val half = v().off.firstOrNull { it.title.startsWith("Half term") } ?: fail("$name: no half term")
        tap(p, name, "Remove", hasContentDescription("Remove ${half.title}"))
        until { v().off.none { it.id == half.id } }
        if (v().off.any { it.id == half.id }) fail("$name: Remove left ${half.title}")
        val weekly = v().weekly.size
        type(p, name, SchoolRules.ADD_HINT, "Sam PE Thu")
        until { v().weekly.size > weekly }
        if (v().weekly.size <= weekly) fail("$name: Done on the field added nothing")
        val off = v().off.size
        type(p, name, SchoolTerms.INSET_HINT, "4 Dec 2026")
        until { v().off.size > off }
        if (v().off.size <= off) fail("$name: Done on the INSET field added no day off")
        assertTouchTargets(name)
        val row = v().dates.firstOrNull { it.day != null } ?: fail("$name: no dated row for To a day…")
        val day = core.dateFeedDays(row.day!!).firstOrNull() ?: fail("$name: no day chips up to ${row.title}")
        val chip = hasContentDescription("${day.spoken}: ${row.title}")
        tap(p, name, BoardFeedRules.TO_DAY, hasContentDescription(BoardFeedRules.dateSpoken(row.title)))
        until { shows(chip) }
        if (!shows(chip)) fail("$name: To a day… didn't unfold its day chips")
        assertTouchTargets(name)
        tap(p, name, day.label, chip)
        until { shows(undo) }
        if (!shows(undo)) fail("$name: the ${day.label} chip made no task to undo")
        tap(p, name, "Undo", undo)
        until { !shows(undo) }
        if (shows(undo)) fail("$name: Undo stayed after taking the task back")
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun appointmentsByHand() { appointmentsByHand(shoot(Place.APPOINTMENTS, "cover-dark-appointments-by-hand", Fill.MANY)) }
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun kidsByHand() { kidsByHand(shoot(Place.KIDS, "cover-dark-kids-by-hand", Fill.MANY)) }
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun schoolByHand() { schoolByHand(shoot(Place.SCHOOL, "cover-dark-school-by-hand", Fill.MANY)) }

    @Test @Config(qualifiers = ScreenShots.COVER) fun appointmentsCoverEmpty() { shoot(Place.APPOINTMENTS, "cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun appointmentsCoverOne() { shoot(Place.APPOINTMENTS, "cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun appointmentsCoverMany() { shoot(Place.APPOINTMENTS, "cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun appointmentsCoverManyLight() { shoot(Place.APPOINTMENTS, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun appointmentsInnerEmpty() { shoot(Place.APPOINTMENTS, "inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun appointmentsInnerMany() { shoot(Place.APPOINTMENTS, "inner-dark-many", Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.COVER) fun kidsCoverEmpty() { shoot(Place.KIDS, "cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun kidsCoverOne() { shoot(Place.KIDS, "cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun kidsCoverMany() { shoot(Place.KIDS, "cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun kidsCoverManyLight() { shoot(Place.KIDS, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun kidsInnerEmpty() { shoot(Place.KIDS, "inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun kidsInnerMany() { shoot(Place.KIDS, "inner-dark-many", Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.COVER) fun schoolCoverEmpty() { shoot(Place.SCHOOL, "cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun schoolCoverOne() { shoot(Place.SCHOOL, "cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun schoolCoverMany() { shoot(Place.SCHOOL, "cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun schoolCoverManyLight() { shoot(Place.SCHOOL, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun schoolInnerEmpty() { shoot(Place.SCHOOL, "inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun schoolInnerMany() { shoot(Place.SCHOOL, "inner-dark-many", Fill.MANY) }
}
