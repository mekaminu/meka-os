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
import os.meka.android.ask.VoiceNotesPane
import os.meka.android.capture.VoiceNoteStore
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.DateGlue
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.VoiceNote
import os.meka.core.domain.VoiceNoteRules
import os.meka.core.domain.VoiceNoteWords
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 56 (build plan: "the rest of Ask's places' shots", Voice notes): Ask → Daily → Voice notes
 * with no notes, one note not yet read for cards, and five (one tidied with the people it mentions and two cards
 * offered, one kept, one where nothing was heard, one with nothing to add, one long ramble), each on the Fold's cover
 * and inner screens, Dark (and the full one in Light). Saved under `voice-notes/` and read for nonsense, cut lines,
 * broken words, split dates, times and numbers, the pane's own lines leaving a word alone or starting a line with a dot,
 * each row's buttons on one line, every button answering a 48 dp touch, and every row's day and time by the core's
 * clock and zone (they were the phone's own). By hand: a row opens to say how long it's kept, Keep keeps it, Delete
 * deletes it with Undo, and Undo puts it back. The notes are held in memory (Robolectric has no Keystore); invented
 * words only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class VoiceNotesScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
    }

    private enum class Fill { EMPTY, ONE, MANY }

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(56), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun notes(fill: Fill): List<VoiceNote> = when (fill) {
        Fill.EMPTY -> emptyList()
        Fill.ONE -> listOf(
            VoiceNote(
                "vn-one", now - 3 * HOUR - 13 * MIN, 83_000L, VoiceNoteWords.HEARD,
                "Remember to book the boiler service for Friday and ask Sam about the school trip money",
            ),
        )
        Fill.MANY -> listOf(
            VoiceNote(
                "vn-tidy", now - 35 * MIN, 48_000L, VoiceNoteWords.HEARD,
                "Right so the boiler needs booking for Friday and I need to ask Sam about the trip money before Monday",
                cards = 2, tidy = "Book the boiler service for Friday. Ask Sam about the trip money before Monday.",
                people = listOf("Sam"),
            ),
            VoiceNote(
                "vn-kept", now - DAY - 9 * HOUR, 125_000L, VoiceNoteWords.HEARD,
                "Ideas for the garden: move the raised beds to the sunny side, find out what the shed base costs, and " +
                    "plant the bulbs before it gets cold. Also think about a water butt by the back door and ask Alex " +
                    "whether the old fence panels can go to the tip on Saturday morning.",
                kept = true,
            ),
            VoiceNote("vn-quiet", now - 2 * DAY + 4 * HOUR + 15 * MIN, 12_000L, VoiceNoteWords.NOTHING, ""),
            VoiceNote(
                "vn-none", now - 6 * DAY - 5 * HOUR - 15 * MIN, 30_000L, VoiceNoteWords.HEARD,
                "Just testing whether this thing works", cards = 0,
            ),
            VoiceNote("vn-failed", now - 9 * DAY, 40_000L, VoiceNoteWords.FAILED, ""),
        )
    }

    /** Runs the main looper and the frames by turns (a tap's change reaches the recomposer through the looper). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /** Waits (the store's thread, looper and frames by turns) until [done] holds, then lets the screen settle. */
    private fun until(done: () -> Boolean) {
        repeat(40) {
            if (done()) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
    }

    /** [text] is on screen as the pane draws it (glued words read plain). */
    private fun onScreen(text: String): Boolean = ScreenShots.texts(compose).any { DateGlue.plain(it).contains(text) }

    /** Every button drawn answers a 48 dp touch. */
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

    private fun pane(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        val notes = notes(fill)
        VoiceNoteStore.holdInMemoryForScreens(notes)
        runBlocking { core.tick() }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { VoiceNotesPane(core, onClose = {}) }
            }
        }
        turns()
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("voice-notes", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, VoiceNoteRules.TITLE)
        val summary = VoiceNoteRules.summary(notes)
        if (!onScreen(summary)) fail("$name: the summary \"$summary\" isn't on screen")
        val rows = core.voiceNoteRows(notes, onMac = false)
        val lines = listOf(summary, VoiceNoteRules.NOTE) + rows.flatMap { listOfNotNull(it.peopleLine, it.cardsLine) }
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertEachOneLine(compose, name, listOf("✦ " + VoiceNoteRules.MAKE_CARDS, VoiceNoteRules.KEEP, "Kept", VoiceNoteRules.DELETE))
        assertTouchTargets(name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        // Each row's day and time by the core's clock and zone (17:15 in London on Wed 21 Oct), never the phone's.
        val titles = when (fill) {
            Fill.EMPTY -> emptyList()
            Fill.ONE -> listOf("Today 14:02 · 1:23")
            Fill.MANY -> listOf("Today 16:40 · 0:48", "Yesterday 08:15 · 2:05")
        }
        titles.forEach { t ->
            if (rows.none { it.title == t }) fail("$name: no row titled \"$t\" (rows: ${rows.map { it.title }})")
            if (!onScreen(t)) fail("$name: \"$t\" isn't on screen")
        }
        when (fill) {
            Fill.EMPTY -> if (rows.isNotEmpty()) fail("$name: rows with no notes ($rows)")
            Fill.ONE -> ScreenShots.assertShows(compose, name, VoiceNoteRules.MAKE_CARDS)
            Fill.MANY -> {
                if (!onScreen("Mentions Sam")) fail("$name: the tidied note doesn't say who it mentions")
                if (!onScreen("2 cards offered")) fail("$name: the tidied note doesn't say its cards")
                if (!onScreen("Book the boiler service for Friday.")) fail("$name: the tidied note doesn't show the tidy note")
            }
        }
        return core
    }

    /** Works by hand: a row opens to say how long it's kept; Keep keeps it; Delete deletes it with Undo; Undo puts it back. */
    private fun byHand(core: MekaCore) {
        val name = "voice-notes-by-hand"
        val row = core.voiceNoteRows(VoiceNoteStore.notes.value.orEmpty(), onMac = false).single()
        val open = compose.onAllNodes(hasContentDescription(row.title + ". ", substring = true) and hasClickAction())
        if (open.fetchSemanticsNodes().isEmpty()) fail("$name: the row can't be opened")
        open[0].performSemanticsAction(SemanticsActions.OnClick)
        until { onScreen(row.keepLine) }
        if (!onScreen(row.keepLine)) fail("$name: opening the row didn't say \"${row.keepLine}\"")

        val keep = compose.onAllNodes(hasText(VoiceNoteRules.KEEP) and hasClickAction())
        if (keep.fetchSemanticsNodes().isEmpty()) fail("$name: no Keep")
        keep[0].performSemanticsAction(SemanticsActions.OnClick)
        until { VoiceNoteStore.notes.value?.singleOrNull()?.kept == true && onScreen("Kept · Navilife won't delete it") }
        if (VoiceNoteStore.notes.value?.singleOrNull()?.kept != true) fail("$name: Keep didn't keep the note")
        if (!onScreen("Kept · Navilife won't delete it")) fail("$name: a kept note doesn't say so")

        val delete = compose.onAllNodes(hasText(VoiceNoteRules.DELETE) and hasClickAction())
        if (delete.fetchSemanticsNodes().isEmpty()) fail("$name: no Delete")
        delete[0].performSemanticsAction(SemanticsActions.OnClick)
        val deleted = "Deleted the note from Today 14:02"
        until { VoiceNoteStore.notes.value.orEmpty().isEmpty() && onScreen(deleted) }
        if (VoiceNoteStore.notes.value.orEmpty().isNotEmpty()) fail("$name: Delete left the note")
        if (!onScreen(deleted)) fail("$name: Delete didn't say \"$deleted\"")

        val undo = compose.onAllNodes(hasText("Undo") and hasClickAction())
        if (undo.fetchSemanticsNodes().isEmpty()) fail("$name: no Undo once a note is deleted")
        undo[0].performSemanticsAction(SemanticsActions.OnClick)
        until { VoiceNoteStore.notes.value.orEmpty().size == 1 && onScreen("Put back") }
        if (VoiceNoteStore.notes.value.orEmpty().size != 1) fail("$name: Undo didn't put the note back")
        if (!onScreen(row.title)) fail("$name: the note put back isn't on screen")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { pane("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { pane("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { pane("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() { pane("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerEmpty() { pane("inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { pane("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHand() { byHand(pane("cover-dark-by-hand", Fill.ONE)) }
}
