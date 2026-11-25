package os.meka.android.screens

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.today.APPOINTMENT_BOOK_SHEET_TAG
import os.meka.android.today.AppointmentDecideView
import os.meka.android.today.DateNightDecideView
import os.meka.android.today.NEEDS_YOU_DECIDE_TAG
import os.meka.android.today.NeedsYouHead
import os.meka.android.today.SchoolCoverDecideView
import os.meka.core.domain.AppointmentBookRules
import os.meka.core.domain.AppointmentNudge
import os.meka.core.domain.CivilDate
import os.meka.core.domain.DateNightNudge
import os.meka.core.domain.DateNightRules
import os.meka.core.domain.NeedsYouLayoutRules
import os.meka.core.domain.SchoolCover
import kotlin.test.fail

/**
 * The approved Needs you look, slice 4 on the Fold (`docs/design/needs-you/needs-you.dc.html`): a school day off, date
 * night and a due appointment as compact TO DECIDE cards, from invented cards (rule 13), on the cover and inner screens,
 * Dark and Light (saved under `needs-you/decide-others-*`). Each tap still does what the full card's did, and the
 * appointment's Booked… opens its sheet of days and times (A17).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NeedsYouDecideOthersScreensTest {
    private companion object {
        /** Mon 14 Dec 2026, the day the shots are drawn on (the dentist is due today). */
        const val TODAY = 20_801L
        /** Fri 18 Dec 2026, the date night four days on. */
        const val NIGHT = 20_805L
    }

    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val cover = SchoolCover(
        id = "inset-1", title = "Alex and Sam are off Mon 21 Dec", line = "INSET day · in 7 days",
        question = "You're in the office that day. Who's covering?", detail = "Mon 21 Dec shows as work from home",
        days = listOf(20_808L), spoken = "Alex and Sam are off Monday 21 December.",
    )
    // A7 (walk-through 2026-11-15): Fri 18 Dec is 4 days after the shots' Mon 14 Dec (the dentist's due day), not the
    // INSET Monday's 7, so its line comes from the rule that counts the days.
    private val night = DateNightNudge(NIGHT, "${DateNightRules.inDays(NIGHT, TODAY)} · from 19:00", "Date night · ${CivilDate.shortLabel(NIGHT)}",
        "Book somewhere and arrange cover", "Booked takes this off Needs you.", "Date night in 4 days")
    private val dentist = AppointmentNudge("dentist", "Due Mon 14 Dec · every 6 months", "Dentist",
        "Last went Sun 14 Jun. Booked the next one?", "", "Dentist due today.")
    private val book = AppointmentBookRules.sheet("Dentist", TODAY)

    private val taps = mutableListOf<String>()

    private fun turns(ms: Long = 2_000L) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun draw(theme: ThemeChoice) {
        ThemeChoice.save(ApplicationProvider.getApplicationContext<ShotApplication>(), theme)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                NeedsYouDecideShotPage {
                    NeedsYouHead(NeedsYouLayoutRules.head(NeedsYouLayoutRules.DECIDE_HEAD, 3))
                    SchoolCoverDecideView(cover, { home -> taps += if (home) "home" else "covered" })
                    DateNightDecideView(night, onBooked = { taps += "booked" }, onSkip = { taps += "skip" })
                    AppointmentDecideView(dentist, book, null, onBooked = { taps += "when:$it" }, onWent = { taps += "went" },
                        onNotYet = { taps += "not yet" }, onCall = { taps += "call" }, onEmail = { taps += "email" })
                }
            }
        }
        turns()
    }

    private fun onScreen(text: String) = ScreenShots.texts(compose).any { os.meka.core.domain.DateGlue.plain(it).contains(os.meka.core.domain.DateGlue.plain(text)) }

    private fun shoot(name: String, theme: ThemeChoice = ThemeChoice.DARK) {
        draw(theme)
        listOf("TO DECIDE · 3", "Alex and Sam are off Mon 21 Dec", "INSET day · in 7 days", "Date night · Fri 18 Dec", "In 4 days", "Dentist",
            "I'll work from home", "Covered", "Booked", "Skip this one", "Booked…", "Went", "Not yet", "Call to book", "Copy email",
        ).forEach { if (!onScreen(it)) fail("$name: $it is missing") }
        if (onScreen("In 7 days")) fail("$name: two different days both read \"in 7 days\"")
        if (compose.onAllNodesWithTag(NEEDS_YOU_DECIDE_TAG, useUnmergedTree = true).fetchSemanticsNodes().size != 3) fail("$name: not three cards")
        ScreenShots.assertEachOneLine(compose, name, listOf("I'll work from home", "Covered", "Skip this one", "Booked…", "Went", "Not yet",
            "Call to book", "Copy email"))
        ScreenShots.assertNothingCutUnder(compose, name, NEEDS_YOU_DECIDE_TAG)
        assertDecideInLeftColumn(compose, name, NEEDS_YOU_DECIDE_TAG)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", name))
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun decideOthersCoverDark() = shoot("decide-others-cover-dark")
    @Test @Config(qualifiers = ScreenShots.COVER) fun decideOthersCoverLight() = shoot("decide-others-cover-light", ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun decideOthersInnerDark() = shoot("decide-others-inner-dark")

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun eachTapDoesWhatTheFullCardDid() {
        draw(ThemeChoice.DARK)
        listOf("I'll work from home", "Covered", "Booked", "Skip this one", "Went", "Not yet", "Call to book", "Copy email").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).performClick()
        }
        turns(300)
        val want = listOf("home", "covered", "booked", "skip", "went", "not yet", "call", "email")
        if (taps != want) fail("the taps answered $taps, not $want")
    }

    /** A17 (walk-through 2026-11-22): Booked… opens a sheet of chips, not an open input box inside the card. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun bookedOpensItsSheetOfDaysAndTimesNotABoxOnTheCard() {
        draw(ThemeChoice.DARK)
        fun shown(text: String) = compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        if (shown(book.typeHint) || shown(AppointmentBookRules.QUESTION)) fail("the sheet shows before Booked…")
        if (shown("Tue 20 Oct")) fail("the old example date, two months before the card's due day, is still on the page")
        compose.onNodeWithText("Booked…", useUnmergedTree = true).performClick()
        turns(1_000)
        if (compose.onAllNodesWithTag(APPOINTMENT_BOOK_SHEET_TAG).fetchSemanticsNodes().size != 1) fail("Booked… didn't open its sheet")
        listOf(AppointmentBookRules.QUESTION, "Today", "Tomorrow", "Sun 20", "08:30", "17:00", "Or a later day", "Pick the day").forEach {
            if (!shown(it)) fail("the sheet has no $it")
        }
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        turns(300)
        if (taps.any { it.startsWith("when:") }) fail("Save answered with no day chosen")
        compose.onNodeWithText("Tomorrow", useUnmergedTree = true).performClick()
        compose.onNodeWithText("09:30", useUnmergedTree = true).performClick()
        turns(500)
        if (!shown("Tue 15 Dec · 09:30")) fail("the chosen day and time aren't said above Save")
        compose.onNodeWithTag(APPOINTMENT_BOOK_SHEET_TAG).captureRoboImage(ScreenShots.path("needs-you", "decide-others-booked-open"))
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        turns(1_000)
        if (taps.lastOrNull() != "when:Tue 15 Dec 9:30") fail("Save sent ${taps.lastOrNull()}, not the chosen day and time")
        if (compose.onAllNodesWithTag(APPOINTMENT_BOOK_SHEET_TAG).fetchSemanticsNodes().isNotEmpty()) fail("the sheet stayed open after Save")
    }
}
