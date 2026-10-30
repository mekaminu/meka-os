package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
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
import os.meka.android.ask.BINS_LIST_TAG
import os.meka.android.ask.BinsPane
import os.meka.android.ask.GIFTS_LIST_TAG
import os.meka.android.ask.GiftsPane
import os.meka.android.ask.READING_LIST_TAG
import os.meka.android.ask.ReadingPane
import os.meka.android.ask.TRIPS_LIST_TAG
import os.meka.android.ask.TripsPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.BinRules
import os.meka.core.domain.BoardFeedRules
import os.meka.core.domain.GiftRules
import os.meka.core.domain.ReadingGoalRules
import os.meka.core.domain.ReadingRules
import os.meka.core.domain.TripRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 18 (build plan: "the rest of Ask's places"): Trips, Gifts, Bins and Reading, each on the
 * Fold's cover and inner screens with nothing in, one thing and a full set, Dark (and the full set in Light). Saved
 * under `trips/`, `gifts/`, `bins/` and `reading/` (the `fold-screens` artifact, then the `screens` branch's
 * docs/screens/fold/…) and read for nonsense, cut lines, words broken in two and dates split across lines; each
 * pane's summary line must be the one the core says. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class PlansPlacesScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(37), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Place(val area: String, val title: String, val listTag: String) {
        TRIPS("trips", "Trips", TRIPS_LIST_TAG), GIFTS("gifts", "Gifts", GIFTS_LIST_TAG), BINS("bins", "Bins", BINS_LIST_TAG),
        READING("reading", ReadingRules.TITLE, READING_LIST_TAG),
    }

    private enum class Fill { EMPTY, ONE, MANY }

    private suspend fun fill(core: MekaCore, place: Place, fill: Fill) {
        if (fill == Fill.EMPTY) return
        when (place) {
            Place.TRIPS -> {
                check(!core.addTrip("Barcelona 14–18 Nov").refused)
                if (fill == Fill.MANY) {
                    check(!core.addTrip("Flight BA478 Sat 14 Nov 07:40 ref ABC123").refused)
                    check(!core.addTrip("Hotel Arts on the seafront with breakfast 14–18 Nov ref HX7781").refused)
                    check(!core.addTrip("Dinner at the tapas bar by the harbour Sun 15 Nov 20:30").refused)
                    check(!core.addTrip("Flight BA479 Wed 18 Nov 18:20 ref ABC123").refused)
                    check(!core.addTrip("Lisbon 4–7 Dec").refused)
                }
            }
            Place.GIFTS -> {
                check(!core.addOccasion("Alex's birthday 31 Oct 2019").refused)
                if (fill == Fill.MANY) {
                    check(!core.addOccasion("Wedding anniversary 20 Aug 2015").refused)
                    check(!core.addOccasion("Sam's birthday 12 Dec 2016").refused)
                    check(!core.addOccasion("Grandma Rose's eightieth birthday party at the community hall 6 Nov 1946").refused)
                    val first = core.giftsView.value.rows.first()
                    check(core.addGiftIdea(first.id, "Football boots, size 13"))
                    check(core.addGiftIdea(first.id, "A space Lego set with the rocket and launch pad"))
                }
            }
            Place.BINS -> {
                check(!core.addBin("Black bin Tue fortnightly from 13 Oct").refused)
                if (fill == Fill.MANY) {
                    check(!core.addBin("Food caddy every Tuesday").refused)
                    check(!core.addBin("Recycling Tue fortnightly from 20 Oct").refused)
                    check(!core.addBin("Garden waste every other Friday from 23 Oct").refused)
                }
            }
            Place.READING -> {
                check(!core.addReading("reading Atomic Habits by James Clear, 320 pages").refused)
                if (fill == Fill.MANY) {
                    val id = core.readingView.value.onTheGo.single().id
                    check(!core.readingProgress(id, "120").refused)
                    check(!core.addReading("Dune by Frank Herbert, 400 pages").refused)
                    check(!core.addReading("podcast The Rest Is History").refused)
                    check(!core.addReading("The Very Long Book About Everything That Ever Happened in the North by Alexandra Longname-Smith, 900 pages").refused)
                    check(!core.setReadingGoal("12 books").refused)
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
                        Place.TRIPS -> TripsPane(core, onClose = {})
                        Place.GIFTS -> GiftsPane(core, onClose = {})
                        Place.BINS -> BinsPane(core, onClose = {})
                        Place.READING -> ReadingPane(core, onClose = {})
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
        ScreenShots.assertShows(compose, name, place.title)
        // Slice 20: each add field's hint is one example on one line, and a trip's time difference stays whole.
        ScreenShots.assertEachOneLine(compose, name, listOf(TripRules.ADD_HINT, TripRules.BOOKING_HINT, GiftRules.ADD_HINT, BinRules.ADD_HINT))
        ScreenShots.assertClocksWhole(compose, name)
        when (place) {
            Place.TRIPS -> {
                val v = core.tripsView.value
                ScreenShots.assertReadable(compose, name, v.rows.flatMap { listOf(it.name, it.line) }, mustShow = false)
                ScreenShots.assertShows(compose, name, v.summary)
                if (fill == Fill.EMPTY && v.summary != TripRules.EMPTY_LINE) fail("$name: \"${v.summary}\" with no trips")
                if (fill == Fill.MANY && v.rows.size != 2) fail("$name: ${v.rows.size} rows for two trips")
                ScreenShots.assertShows(compose, name, TripRules.addHint(hasTrips = !v.isEmpty))
            }
            Place.GIFTS -> {
                val v = core.giftsView.value
                ScreenShots.assertReadable(compose, name, v.rows.flatMap { listOf(it.title, it.line) }, mustShow = false)
                ScreenShots.assertShows(compose, name, v.summary)
                if (fill == Fill.EMPTY && v.summary != GiftRules.EMPTY_LINE) fail("$name: \"${v.summary}\" with nothing entered")
                if (fill == Fill.MANY && v.rows.size != 4) fail("$name: ${v.rows.size} rows for four occasions")
                // Slice 19: who it is comes before "birthday", never the party's words jumbled into a name.
                if (fill == Fill.MANY && v.rows.none { it.title == "Grandma Rose's birthday" }) fail("$name: Grandma Rose's row reads ${v.rows.map { it.title }}")
            }
            Place.BINS -> {
                val v = core.binsView.value
                ScreenShots.assertReadable(compose, name, v.rows.flatMap { listOf(it.name, it.line) }, mustShow = false)
                ScreenShots.assertShows(compose, name, v.summary)
                if (fill == Fill.EMPTY && v.summary != BinRules.EMPTY_LINE) fail("$name: \"${v.summary}\" with no bins")
                if (fill == Fill.MANY && v.rows.size != 4) fail("$name: ${v.rows.size} rows for four bins")
                // Slice 19: a start date just gone is this year's, so a Tuesday bin stays a Tuesday bin.
                v.rows.filter { it.name == "Black bin" || it.name == "Recycling" }.forEach { r ->
                    if ("Tuesday" !in r.line) fail("$name: ${r.name} reads \"${r.line}\", typed as a Tuesday")
                }
            }
            Place.READING -> {
                val v = core.readingView.value
                val rows = v.onTheGo + v.upNext
                ScreenShots.assertReadable(compose, name, rows.flatMap { listOf(it.title, it.meta) }, mustShow = false)
                ScreenShots.assertShows(compose, name, v.summary)
                if (fill == Fill.EMPTY && v.summary != ReadingRules.EMPTY_SUMMARY) fail("$name: \"${v.summary}\" with nothing in")
                if (fill == Fill.MANY && rows.size != 4) fail("$name: ${rows.size} rows for four things")
                // Slice 19: nothing put down reads as "moved on", and next up is the first added.
                if (fill == Fill.MANY) {
                    val month = v.month ?: fail("$name: no month line after reading this month")
                    if ("moved on" in month || "next up: Dune" !in month) fail("$name: the month line reads \"$month\"")
                    if (v.upNext.firstOrNull()?.title != "Dune") fail("$name: up next starts ${v.upNext.map { it.title }}")
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

    /**
     * Waits (core thread, looper and frames by turns) until [done] holds, then lets the screen settle for [settle] ms
     * (short where an Undo is about to be tapped: Trips' lasts 6 s).
     */
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
     * Every button and switch drawn answers a 48 dp touch (Bins' Remove and its reminder chips, and Gifts' To a day…
     * chips, were ~32 before this check).
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
     * Works by hand, Trips: a tap on a trip opens its timeline; × takes a booking off and Undo puts it back; Remove trip
     * takes the trip and its bookings; the field adds a trip.
     */
    private fun tripsByHand(core: MekaCore) {
        val name = "trips-by-hand"
        val p = Place.TRIPS
        assertTouchTargets(name)
        val trip = core.tripsView.value.rows.firstOrNull { it.name == "Barcelona" } ?: fail("$name: Barcelona isn't on Trips")
        val line = trip.timeline.flatMap { it.lines }.firstOrNull() ?: fail("$name: Barcelona has no bookings")
        fun bookings() = core.tripsView.value.rows.firstOrNull { it.id == trip.id }?.timeline?.flatMap { it.lines }.orEmpty()
        val remove = hasContentDescription("Remove ${line.text}")
        tap(p, name, "Barcelona", hasContentDescription(trip.spoken))
        until { shows(remove) }
        if (!shows(remove)) fail("$name: tapping Barcelona didn't open its timeline")
        assertTouchTargets(name)
        tap(p, name, "×", remove)
        until(500) { bookings().none { it.bookingId == line.bookingId } }
        if (bookings().any { it.bookingId == line.bookingId }) fail("$name: × left \"${line.text}\"")
        tap(p, name, "Undo", hasContentDescription("Undo:", substring = true))
        until { bookings().any { it.bookingId == line.bookingId } }
        if (bookings().none { it.bookingId == line.bookingId }) fail("$name: Undo didn't put \"${line.text}\" back")
        tap(p, name, "Remove trip", hasContentDescription("Remove Barcelona and its bookings"))
        until { core.tripsView.value.rows.none { it.id == trip.id } }
        if (core.tripsView.value.rows.any { it.id == trip.id }) fail("$name: Remove trip left Barcelona")
        type(p, name, TripRules.ADD_DESCRIPTION, "Rome 20–22 Nov")
        until { core.tripsView.value.rows.any { it.name == "Rome" } }
        if (core.tripsView.value.rows.none { it.name == "Rome" }) fail("$name: Done on the field added no trip")
        reach(p, name, hasText("Rome"), "new trip on screen")
    }

    /**
     * Works by hand, Gifts: a tap opens an occasion; × takes an idea off and the field adds one; To a day… unfolds its
     * day chips (48 dp) and one makes the task, Undo takes it back; Gift sorted marks it; Remove takes the occasion off.
     */
    private fun giftsByHand(core: MekaCore) {
        val name = "gifts-by-hand"
        val p = Place.GIFTS
        assertTouchTargets(name)
        val row = core.giftsView.value.rows.first()
        fun cur() = core.giftsView.value.rows.firstOrNull { it.id == row.id }
        val idea = row.ideas.firstOrNull() ?: fail("$name: ${row.title} has no ideas")
        val removeIdea = hasContentDescription("Remove the idea ${idea.text}")
        tap(p, name, row.title, hasContentDescription(row.spoken))
        until { shows(removeIdea) }
        if (!shows(removeIdea)) fail("$name: tapping ${row.title} didn't open its ideas")
        assertTouchTargets(name)
        tap(p, name, "×", removeIdea)
        until { cur()?.ideas?.none { it.id == idea.id } == true }
        if (cur()?.ideas?.any { it.id == idea.id } != false) fail("$name: × left the idea \"${idea.text}\"")
        type(p, name, GiftRules.IDEA_HINT, "A bike helmet")
        until { cur()?.ideas?.any { it.text == "A bike helmet" } == true }
        if (cur()?.ideas?.none { it.text == "A bike helmet" } != false) fail("$name: Done on the idea field added nothing")
        val date = row.day ?: fail("$name: ${row.title} has no day for To a day…")
        val day = core.dateFeedDays(date).firstOrNull() ?: fail("$name: no day chips up to ${row.title}")
        val chip = hasContentDescription("${day.spoken}: ${row.title}")
        tap(p, name, BoardFeedRules.TO_DAY, hasContentDescription(BoardFeedRules.dateSpoken(row.title)))
        until { shows(chip) }
        if (!shows(chip)) fail("$name: To a day… didn't unfold its day chips")
        assertTouchTargets(name)
        tap(p, name, day.label, chip)
        val undo = hasText("Undo") and hasClickAction()
        until { shows(undo) }
        if (!shows(undo)) fail("$name: the ${day.label} chip made no task to undo")
        tap(p, name, "Undo", undo)
        until { !shows(undo) }
        if (shows(undo)) fail("$name: Undo stayed after taking the task back")
        tap(p, name, "Gift sorted", hasText("Gift sorted"))
        until { cur()?.sorted == true }
        if (cur()?.sorted != true) fail("$name: Gift sorted didn't mark ${row.title}")
        reach(p, name, hasText("Not sorted yet"), "Not sorted yet after Gift sorted")
        tap(p, name, "Remove", hasContentDescription("Remove ${row.title}"))
        until { cur() == null }
        if (cur() != null) fail("$name: Remove left ${row.title}")
    }

    /** Works by hand, Bins: Remove takes a bin off, the field adds it back, a reminder chip moves the evening's heads-up. */
    private fun binsByHand(core: MekaCore) {
        val name = "bins-by-hand"
        val p = Place.BINS
        assertTouchTargets(name)
        val caddy = core.binsView.value.rows.firstOrNull { it.name.startsWith("Food") } ?: fail("$name: no food caddy")
        tap(p, name, "Remove", hasContentDescription("Remove ${caddy.name}"))
        until { core.binsView.value.rows.none { it.id == caddy.id } }
        if (core.binsView.value.rows.any { it.id == caddy.id }) fail("$name: Remove left ${caddy.name}")
        type(p, name, BinRules.ADD_DESCRIPTION, "Food caddy every Tuesday")
        until { core.binsView.value.rows.any { it.name == caddy.name } }
        if (core.binsView.value.rows.none { it.name == caddy.name }) fail("$name: Done on the field added no bin")
        val before = core.binsView.value.remindMinute
        val off = hasContentDescription("Bins reminder at", substring = true) and
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off")
        reach(p, name, off, "reminder chip that's off")
        assertTouchTargets(name)
        tap(p, name, "a reminder chip", off)
        until { core.binsView.value.remindMinute != before }
        if (core.binsView.value.remindMinute == before) fail("$name: the reminder chip didn't move the heads-up")
    }

    /**
     * Works by hand, Reading: how far moves a book on; Start, Finished, Undo, Put down, Pick up and Remove each move a
     * row; the goal field sets the year's goal and Clear takes it off; the field adds a book up next.
     */
    private fun readingByHand(core: MekaCore) {
        val name = "reading-by-hand"
        val p = Place.READING
        assertTouchTargets(name)
        fun v() = core.readingView.value
        val book = v().onTheGo.firstOrNull() ?: fail("$name: nothing on the go")
        type(p, name, ReadingRules.PROGRESS_HINT, "200")
        until { v().onTheGo.first { it.id == book.id }.progress.contains("200") }
        if (!v().onTheGo.first { it.id == book.id }.progress.contains("200")) fail("$name: how far left \"${book.title}\" at ${book.progress}")
        val dune = v().upNext.firstOrNull { it.title == "Dune" } ?: fail("$name: Dune isn't up next")
        tap(p, name, "Start", hasContentDescription("Start: Dune"))
        until { v().onTheGo.any { it.id == dune.id } }
        if (v().onTheGo.none { it.id == dune.id }) fail("$name: Start didn't put Dune on the go")
        assertTouchTargets(name)
        tap(p, name, "Finished", hasContentDescription("Finished: Dune"))
        until { v().finished.any { it.id == dune.id } }
        if (v().finished.none { it.id == dune.id }) fail("$name: Finished didn't finish Dune")
        tap(p, name, "Undo", hasContentDescription("Undo:", substring = true))
        until { v().onTheGo.any { it.id == dune.id } }
        if (v().onTheGo.none { it.id == dune.id }) fail("$name: Undo didn't put Dune back on the go")
        tap(p, name, "Put down", hasContentDescription("Put down: Dune"))
        until { v().putDown.any { it.id == dune.id } }
        if (v().putDown.none { it.id == dune.id }) fail("$name: Put down didn't put Dune down")
        tap(p, name, "Pick up", hasContentDescription("Pick up: Dune"))
        until { v().onTheGo.any { it.id == dune.id } }
        if (v().onTheGo.none { it.id == dune.id }) fail("$name: Pick up didn't put Dune on the go")
        val long = v().upNext.firstOrNull { it.title.startsWith("The Very Long Book") } ?: fail("$name: the long book isn't up next")
        tap(p, name, "Remove", hasContentDescription("Remove ${long.title}"))
        until { (v().onTheGo + v().upNext).none { it.id == long.id } }
        if ((v().onTheGo + v().upNext).any { it.id == long.id }) fail("$name: Remove left \"${long.title}\"")
        type(p, name, ReadingGoalRules.HINT, "20 books")
        until { v().goalTarget == 20 }
        if (v().goalTarget != 20) fail("$name: the goal field set ${v().goalTarget} books, not 20")
        tap(p, name, "Clear the goal", hasContentDescription("Clear the reading goal"))
        until { v().goalTarget == 0 }
        if (v().goalTarget != 0) fail("$name: Clear left a goal of ${v().goalTarget}")
        type(p, name, ReadingRules.HINT, "Project Hail Mary by Andy Weir, 480 pages")
        until { v().upNext.any { it.title == "Project Hail Mary" } }
        if (v().upNext.none { it.title == "Project Hail Mary" }) fail("$name: Done on the field added nothing up next")
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun tripsByHand() { tripsByHand(shoot(Place.TRIPS, "cover-dark-trips-by-hand", Fill.MANY)) }
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun giftsByHand() { giftsByHand(shoot(Place.GIFTS, "cover-dark-gifts-by-hand", Fill.MANY)) }
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun binsByHand() { binsByHand(shoot(Place.BINS, "cover-dark-bins-by-hand", Fill.MANY)) }
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun readingByHand() { readingByHand(shoot(Place.READING, "cover-dark-reading-by-hand", Fill.MANY)) }

    @Test @Config(qualifiers = ScreenShots.COVER) fun tripsCoverEmpty() { shoot(Place.TRIPS, "cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun tripsCoverOne() { shoot(Place.TRIPS, "cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun tripsCoverMany() { shoot(Place.TRIPS, "cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun tripsCoverManyLight() { shoot(Place.TRIPS, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun tripsInnerEmpty() { shoot(Place.TRIPS, "inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun tripsInnerMany() { shoot(Place.TRIPS, "inner-dark-many", Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.COVER) fun giftsCoverEmpty() { shoot(Place.GIFTS, "cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun giftsCoverOne() { shoot(Place.GIFTS, "cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun giftsCoverMany() { shoot(Place.GIFTS, "cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun giftsCoverManyLight() { shoot(Place.GIFTS, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun giftsInnerEmpty() { shoot(Place.GIFTS, "inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun giftsInnerMany() { shoot(Place.GIFTS, "inner-dark-many", Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.COVER) fun binsCoverEmpty() { shoot(Place.BINS, "cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun binsCoverOne() { shoot(Place.BINS, "cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun binsCoverMany() { shoot(Place.BINS, "cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun binsCoverManyLight() { shoot(Place.BINS, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun binsInnerEmpty() { shoot(Place.BINS, "inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun binsInnerMany() { shoot(Place.BINS, "inner-dark-many", Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.COVER) fun readingCoverEmpty() { shoot(Place.READING, "cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun readingCoverOne() { shoot(Place.READING, "cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun readingCoverMany() { shoot(Place.READING, "cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun readingCoverManyLight() { shoot(Place.READING, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun readingInnerEmpty() { shoot(Place.READING, "inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun readingInnerMany() { shoot(Place.READING, "inner-dark-many", Fill.MANY) }
}
