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
import os.meka.android.ask.WorkingOnPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.BookingCallFields
import os.meka.core.domain.BookingCallRules
import os.meka.core.domain.BookingCallState
import os.meka.core.domain.BookingNumberRules
import os.meka.core.domain.CivilDate
import os.meka.core.domain.DateGlue
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.ObiJobFields
import os.meka.core.domain.ObiJobRules
import os.meka.core.domain.ObiJobState
import os.meka.core.domain.ObiStep
import os.meka.core.domain.ObiStepKind
import os.meka.core.domain.HelpPlace
import os.meka.core.domain.ObligationKind
import os.meka.core.domain.RenewalRepeat
import os.meka.core.domain.StandingErrand
import os.meka.core.domain.WorkingOnRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import os.meka.core.sync.ReplicaStore
import os.meka.core.sync.fv
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate for Ask → Daily → Obi is working on (Ada plans with you, part 3 slice 1): nothing to watch, one
 * chase due, and all three standing errands needing him, on the Fold's cover and inner screens, Dark (and the full one
 * in Light). Saved under `working-on/` and read for nonsense, cut lines, broken words, split dates and numbers, lone
 * words and stray dots, and every button answering a 48 dp touch. By hand: a card's button opens its place; Turn off
 * takes the card away and Turn on brings it back. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class WorkingOnScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
    }

    private enum class Fill { EMPTY, ONE, MANY, ERRANDS, CALL, FOUND }

    private var opened: HelpPlace? = null

    private fun core(store: ReplicaStore = InMemoryReplicaStore()): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = store, transport = null,
        secureRandom = Random(61), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
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

    private suspend fun fill(core: MekaCore, fill: Fill) {
        val today = core.todayEpochDay()
        when (fill) {
            Fill.EMPTY, Fill.ERRANDS, Fill.CALL, Fill.FOUND -> Unit
            Fill.ONE -> core.addWaiting("The drill back", "Casey", 0)
            Fill.MANY -> {
                core.addRenewal("Home insurance", ObligationKind.INSURANCE, today + 15, RenewalRepeat.YEARLY, null, null)
                core.addOccasion("Sam's birthday 25 Oct")
                core.addWaiting("The drill back", "Casey", 0)
            }
        }
    }

    /**
     * Part 3 slice 4: two errands as the server leaves them (written here as Navilife's server would): one that needs
     * him, with what Obi found, his question and a draft; one still working through its steps. Invented data only.
     */
    private fun seedErrands(store: ReplicaStore) {
        var k = 0
        val server = Replica("hh", "server", HlcClock("server", { now }), store, MekaSchema) { "seed${k++}" }
        server.commitLocal(EntityTypes.OBI_JOB, "job-boiler", mapOf(
            ObiJobFields.ASK to "Find someone to service the boiler".fv(), ObiJobFields.ASKED_AT to (now - 3_600_000L).fv(),
            ObiJobFields.STATE to ObiJobState.NEEDS_YOU.code.fv(),
            ObiJobFields.STEPS to ObiJobRules.encodeSteps(listOf(
                ObiStep(ObiStepKind.SEARCH, "Find boiler engineers near home", "Gas Safe boiler service", true),
                ObiStep(ObiStepKind.WORK, "Compare what they charge", done = true),
                ObiStep(ObiStepKind.DRAFT, "Draft a booking request", done = true),
            )).fv(),
            ObiJobFields.FOUND to "Two Gas Safe engineers nearby offer a yearly service from £85; Oakfield Heating has slots next week.".fv(),
            ObiJobFields.NEED to "Which morning next week suits you for the engineer?".fv(),
            ObiJobFields.DRAFT to "Hello, I'd like to book a yearly boiler service next week, ideally a morning. Could you let me know what you have? Thanks, Jordan".fv(),
            ObiJobFields.DID_AT to (now - 600_000L).fv(),
        ))
        server.commitLocal(EntityTypes.OBI_JOB, "job-fence", mapOf(
            ObiJobFields.ASK to "Get three quotes for the fence".fv(), ObiJobFields.ASKED_AT to (now - 1_800_000L).fv(),
            ObiJobFields.STATE to ObiJobState.WORKING.code.fv(),
            ObiJobFields.STEPS to ObiJobRules.encodeSteps(listOf(
                ObiStep(ObiStepKind.SEARCH, "Find fencing firms near home", "fence installers", true),
                ObiStep(ObiStepKind.WORK, "Note what each one charges"),
            )).fv(),
            ObiJobFields.DID_AT to (now - 300_000L).fv(),
        ))
    }

    /**
     * Try again: a booking call that wasn't booked, as the server leaves it (written here as Navilife's server would):
     * Rosa's Kitchen full at 7 on Saturday, with what they offered. Invented data only.
     */
    private fun seedNotBooked(store: ReplicaStore) {
        var k = 0
        val server = Replica("hh", "server", HlcClock("server", { now }), store, MekaSchema) { "seed${k++}" }
        server.commitLocal(EntityTypes.BOOKING_CALL, "call-rosa", mapOf(
            BookingCallFields.BUSINESS to "Rosa's Kitchen".fv(), BookingCallFields.NUMBER to "+441234567890".fv(),
            BookingCallFields.WHAT to "Table for 4".fv(), BookingCallFields.DAY to CivilDate.toEpochDay(2026, 10, 24).fv(),
            BookingCallFields.FROM to (19L * 60).fv(), BookingCallFields.TO to (20L * 60).fv(), BookingCallFields.PEOPLE to 4L.fv(),
            BookingCallFields.STATE to BookingCallState.NOT_BOOKED.code.fv(), BookingCallFields.ASKED_AT to (now - 7_200_000L).fv(),
            BookingCallFields.APPROVED_AT to (now - 7_000_000L).fv(), BookingCallFields.CALLED_AT to (now - 3_700_000L).fv(),
            BookingCallFields.ENDED_AT to (now - 3_600_000L).fv(), BookingCallFields.SECONDS to 95L.fv(),
            BookingCallFields.RESULT to "Not booked · they're full at 7 on Saturday · they offered 21:00".fv(),
        ))
    }

    /** The number looked up: a draft card with no number of its own and the number Obi found for it (the server's fields). */
    private fun seedFound(store: ReplicaStore) {
        var k = 0
        val server = Replica("hh", "server", HlcClock("server", { now }), store, MekaSchema) { "seed${k++}" }
        server.commitLocal(EntityTypes.BOOKING_CALL, "call-oak", mapOf(
            BookingCallFields.BUSINESS to "Oakfield Barbers".fv(), BookingCallFields.WHAT to "A haircut".fv(),
            BookingCallFields.DAY to (CivilDate.toEpochDay(2026, 10, 24) + 1).fv(),
            BookingCallFields.STATE to BookingCallState.DRAFT.code.fv(), BookingCallFields.ASKED_AT to (now - 120_000L).fv(),
            BookingCallFields.LOOKUP to BookingNumberRules.FOUND.fv(), BookingCallFields.LOOKED_UP_FOR to "Oakfield Barbers".fv(),
            BookingCallFields.FOUND_NUMBER to "+441632960111".fv(), BookingCallFields.FOUND_FROM to "oakfieldbarbers.example".fv(),
        ))
    }

    private fun pane(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val store = InMemoryReplicaStore()
        if (fill == Fill.ERRANDS) seedErrands(store)
        if (fill == Fill.CALL) seedNotBooked(store)
        if (fill == Fill.FOUND) seedFound(store)
        val core = core(store)
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            fill(core, fill)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    WorkingOnPane(core, onClose = {}, onOpen = { opened = it })
                }
            }
        }
        val v = runBlocking { core.workingOnView() }
        until { onScreen(WorkingOnRules.SWITCHES.uppercase()) }
        compose.waitForIdle()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("working-on", name))
        val lines = listOf(v.summary, WorkingOnRules.INTRO, ObiJobRules.INTRO, ObiJobRules.EMPTY, ObiJobRules.DRAFT_FOOT) +
            v.cards.flatMap { listOfNotNull(it.did, it.need) } + v.jobs.flatMap { listOfNotNull(it.title, it.status, it.did, it.need) } +
            v.switches.map { it.line } + v.calls.flatMap { listOfNotNull(it.status, it.result, it.lengthLine, it.triedAgainLine) }
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, WorkingOnRules.TITLE)
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertNoDotEndingALine(compose, name, lines)
        assertTouchTargets(name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        val cards = compose.onAllNodes(hasTestTag("working-on-card"), useUnmergedTree = true).fetchSemanticsNodes().size
        when (fill) {
            Fill.EMPTY -> {
                if (v.summary != WorkingOnRules.EMPTY_LINE) fail("$name: the summary reads \"${v.summary}\"")
                if (cards != 0) fail("$name: $cards cards with nothing to watch")
            }
            Fill.ONE -> {
                if (cards != 1) fail("$name: $cards cards, not 1")
                if (!onScreen("Time to chase: The drill back from Casey")) fail("$name: the chase isn't said")
            }
            Fill.ERRANDS -> {
                val jobs = compose.onAllNodes(hasTestTag("errand-card"), useUnmergedTree = true).fetchSemanticsNodes().size
                if (jobs != 2) fail("$name: $jobs errand cards, not 2")
                if (v.summary != "On 2 errands for you · 1 needs you") fail("$name: the summary reads \"${v.summary}\"")
                if (!onScreen("Which morning next week suits you")) fail("$name: the question isn't shown")
                if (!onScreen(ObiJobRules.DRAFT_LABEL.uppercase())) fail("$name: no draft box")
                if (!onScreen("Working · step 2 of 2")) fail("$name: the fence's status isn't shown")
            }
            Fill.CALL -> {
                val r = v.calls.singleOrNull() ?: fail("$name: ${v.calls.size} call cards, not 1")
                if (!r.canTryAgain) fail("$name: a not-booked call can't be tried again")
                if (compose.onAllNodes(hasTestTag("call-try-again"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) fail("$name: no Try again")
                if (!onScreen("they offered 21:00")) fail("$name: the result isn't shown")
            }
            Fill.FOUND -> {
                val r = v.calls.singleOrNull() ?: fail("$name: ${v.calls.size} call cards, not 1")
                if (!r.canUseFound || r.canCall) fail("$name: the found number isn't offered, or Call now is open before his tap")
                if (compose.onAllNodes(hasTestTag("call-use-found"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) fail("$name: no Use this number")
                if (!onScreen("Obi found 01632 960111")) fail("$name: the found number isn't shown")
            }
            Fill.MANY -> {
                if (cards != 3) fail("$name: $cards cards, not 3")
                if (v.summary != "Keeping an eye on 3 things · 3 need you") fail("$name: the summary reads \"${v.summary}\"")
                if (!onScreen(WorkingOnRules.NEED.uppercase())) fail("$name: no WHAT I NEED FROM YOU")
            }
        }
        return core
    }

    /** Try again by hand: a new card for the same brief, waiting for Call now; the old one says it was tried again. */
    private fun tryAgainByHand(core: MekaCore) {
        val name = "working-on-try-again"
        val n = compose.onAllNodes(hasContentDescription("${BookingCallRules.TRY_AGAIN}: Rosa's Kitchen") and hasClickAction())
        if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no Try again")
        n[0].performSemanticsAction(SemanticsActions.OnClick)
        fun calls() = runBlocking { core.workingOnView() }.calls
        until { calls().size == 2 && onScreen(BookingCallRules.TRIED_AGAIN) }
        val rows = calls()
        if (rows.size != 2) fail("$name: ${rows.size} call cards after Try again, not 2")
        val fresh = rows.first()
        if (!fresh.canCall || fresh.business != "Rosa's Kitchen" || fresh.peopleTile != "4") fail("$name: the new card isn't the same brief: $fresh")
        if (rows.last().canTryAgain || rows.last().triedAgainLine == null) fail("$name: the old card can still be tried again")
        if (!onScreen(BookingCallRules.TRIED_AGAIN)) fail("$name: Try again didn't say so")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("working-on", "cover-dark-tried-again"))
        ScreenShots.assertNoBrokenWords(compose, name)
    }

    /** Use this number by hand: the found number becomes the card's, Call now opens, nothing rings by itself. */
    private fun useFoundByHand(core: MekaCore) {
        val name = "working-on-use-found"
        val n = compose.onAllNodes(hasContentDescription("${BookingNumberRules.USE}: Oakfield Barbers") and hasClickAction())
        if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no Use this number")
        n[0].performSemanticsAction(SemanticsActions.OnClick)
        fun calls() = runBlocking { core.workingOnView() }.calls
        until { calls().singleOrNull()?.canCall == true && onScreen(BookingNumberRules.USED) }
        val r = calls().single()
        if (!r.canCall || r.canUseFound) fail("$name: Call now isn't open after Use this number: $r")
        if (r.numberLine != "01632 960111 · Obi found it") fail("$name: the number line reads \"${r.numberLine}\"")
        if (r.status != BookingCallRules.NEEDS_TAP) fail("$name: the card moved on by itself: ${r.status}")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("working-on", "cover-dark-used-found"))
        ScreenShots.assertNoBrokenWords(compose, name)
    }

    /** Works by hand: Open Waiting for opens Lists; Turn off takes the chaser's card away, Turn on brings it back. */
    private fun byHand(core: MekaCore) {
        val name = "working-on-by-hand"
        fun tap(description: String) {
            val n = compose.onAllNodes(hasContentDescription(description) and hasClickAction())
            if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no \"$description\"")
            n[0].performSemanticsAction(SemanticsActions.OnClick)
        }
        val chaser = StandingErrand.FOLLOW_UPS
        tap("${chaser.openLabel}: ${chaser.title}")
        until { opened != null }
        if (opened != HelpPlace.LISTS) fail("$name: ${chaser.openLabel} opened $opened, not Lists")
        fun cardShown() = runBlocking { core.workingOnView() }.cards.any { it.errand == chaser } &&
            compose.onAllNodes(hasTestTag("working-on-card"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        tap("Turn off: ${chaser.title}")
        until { !cardShown() }
        if (cardShown()) fail("$name: Turn off left the chaser's card")
        tap("Turn on: ${chaser.title}")
        until { cardShown() }
        if (!cardShown()) fail("$name: Turn on didn't bring the card back")
    }

    /**
     * Part 3 slice 4 by hand: Ask Obi for an errand opens the sheet, typing and Ask Obi adds the errand's card ("Obi is
     * on it"); Copy puts the draft on the clipboard ("Copied"); Stop on the new one says it is stopped.
     */
    private fun errandsByHand(core: MekaCore) {
        val name = "working-on-errands-by-hand"
        fun tapTag(tag: String) {
            val n = compose.onAllNodes(hasTestTag(tag) and hasClickAction(), useUnmergedTree = true)
            if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no \"$tag\"")
            n[0].performSemanticsAction(SemanticsActions.OnClick)
        }
        fun tap(description: String) {
            val n = compose.onAllNodes(hasContentDescription(description) and hasClickAction())
            if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no \"$description\"")
            n[0].performSemanticsAction(SemanticsActions.OnClick)
        }
        tapTag("errand-ask")
        until { onScreen(ObiJobRules.SHEET_TITLE) }
        if (!onScreen(ObiJobRules.SHEET_TITLE)) fail("$name: the ask sheet didn't open")
        compose.onNodeWithTag("errand-field", useUnmergedTree = true).performTextInput("plan half term")
        turns(500)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("working-on", "cover-dark-ask-sheet"))
        tapTag("errand-go")
        until { runBlocking { core.workingOnView() }.jobs.any { it.title == "Plan half term" } && onScreen("Obi is on it") }
        if (runBlocking { core.workingOnView() }.jobs.none { it.title == "Plan half term" }) fail("$name: Ask Obi didn't add the errand")
        until { compose.onAllNodes(hasTestTag("errand-card"), useUnmergedTree = true).fetchSemanticsNodes().size == 3 }
        tap("${ObiJobRules.COPY} the draft: Find someone to service the boiler")
        until { onScreen("Copied") }
        if (!onScreen("Copied")) fail("$name: Copy didn't say so")
        tap("${ObiJobRules.STOP}: Plan half term")
        until { runBlocking { core.workingOnView() }.jobs.first { it.title == "Plan half term" }.status == "Stopped" }
        if (runBlocking { core.workingOnView() }.jobs.first { it.title == "Plan half term" }.status != "Stopped") fail("$name: Stop didn't stop it")
        until { onScreen("Stopped") }
        if (!onScreen(ObiJobRules.STOPPED_LINE.substringBefore(" ·"))) fail("$name: no stopped line")
        // Part 3 slice 5: Answer opens a sheet with Obi's question; Tell Obi records it and the card says Obi has it.
        tap("${ObiJobRules.ANSWER}: Find someone to service the boiler")
        until { onScreen(ObiJobRules.ANSWER_TITLE) }
        if (!onScreen("Which morning next week suits you")) fail("$name: the answer sheet doesn't show Obi's question")
        compose.onNodeWithTag("answer-field", useUnmergedTree = true).performTextInput("tuesday morning")
        turns(500)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("working-on", "cover-dark-answer-sheet"))
        tapTag("answer-go")
        fun boiler() = runBlocking { core.workingOnView() }.jobs.first { it.title == "Find someone to service the boiler" }
        until { boiler().status == ObiJobRules.ANSWER_STATUS && onScreen("Obi has your answer") }
        if (boiler().youSaid != "Tuesday morning") fail("$name: the answer wasn't kept")
        if (boiler().canAnswer) fail("$name: it can be answered twice")
        until { compose.onAllNodes(hasTestTag("errand-you-said"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodes(hasTestTag("errand-you-said"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) fail("$name: no YOU SAID")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("working-on", "cover-dark-answered"))
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverErrands() { pane("cover-dark-errands", Fill.ERRANDS) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverErrandsLight() { pane("cover-light-errands", Fill.ERRANDS, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerErrands() { pane("inner-dark-errands", Fill.ERRANDS) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun errandsByHand() { errandsByHand(pane("cover-dark-errands-by-hand", Fill.ERRANDS)) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverNotBooked() { pane("cover-dark-not-booked", Fill.CALL) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverNotBookedLight() { pane("cover-light-not-booked", Fill.CALL, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerNotBooked() { pane("inner-dark-not-booked", Fill.CALL) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun tryAgainByHand() { tryAgainByHand(pane("cover-dark-try-again", Fill.CALL)) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverFound() { pane("cover-dark-found", Fill.FOUND) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverFoundLight() { pane("cover-light-found", Fill.FOUND, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun useFoundByHand() { useFoundByHand(pane("cover-dark-use-found", Fill.FOUND)) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { pane("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { pane("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { pane("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() { pane("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { pane("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHand() { byHand(pane("cover-dark-by-hand", Fill.ONE)) }
}
