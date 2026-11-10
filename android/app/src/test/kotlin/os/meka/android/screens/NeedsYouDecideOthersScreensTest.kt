package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.today.AppointmentDecideView
import os.meka.android.today.DateNightDecideView
import os.meka.android.today.NEEDS_YOU_DECIDE_TAG
import os.meka.android.today.NeedsYouHead
import os.meka.android.today.SchoolCoverDecideView
import os.meka.core.domain.AppointmentNudge
import os.meka.core.domain.DateNightNudge
import os.meka.core.domain.NeedsYouLayoutRules
import os.meka.core.domain.SchoolCover
import kotlin.test.fail

/**
 * The approved Needs you look, slice 4 on the Fold (`docs/design/needs-you/needs-you.dc.html`): a school day off, date
 * night and a due appointment as compact TO DECIDE cards, from invented cards (rule 13), on the cover and inner screens,
 * Dark and Light (saved under `needs-you/decide-others-*`). Each tap still does what the full card's did, and the
 * appointment's Booked… opens its When? field inside the card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NeedsYouDecideOthersScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val cover = SchoolCover(
        id = "inset-1", title = "Alex and Sam are off Mon 21 Dec", line = "INSET day · in 7 days",
        question = "You're in the office that day. Who's covering?", detail = "Mon 21 Dec shows as work from home",
        days = listOf(20_808L), spoken = "Alex and Sam are off Monday 21 December.",
    )
    private val night = DateNightNudge(20_808L, "In 7 days · from 19:00", "Date night · Fri 18 Dec", "Book somewhere and arrange cover",
        "Booked takes this off Needs you.", "Date night in 7 days")
    private val dentist = AppointmentNudge("dentist", "Due Mon 14 Dec · every 6 months", "Dentist",
        "Last went Sun 14 Jun. Booked the next one?", "", "Dentist due today.")

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
                Column(
                    Modifier.fillMaxWidth().background(Meka.colors.background).testTag("screen")
                        .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    NeedsYouHead(NeedsYouLayoutRules.head(NeedsYouLayoutRules.DECIDE_HEAD, 3))
                    SchoolCoverDecideView(cover, { home -> taps += if (home) "home" else "covered" })
                    DateNightDecideView(night, onBooked = { taps += "booked" }, onSkip = { taps += "skip" })
                    AppointmentDecideView(dentist, null, onBooked = { taps += "when:$it" }, onWent = { taps += "went" },
                        onNotYet = { taps += "not yet" }, onCall = { taps += "call" }, onEmail = { taps += "email" })
                }
            }
        }
        turns()
    }

    private fun onScreen(text: String) = ScreenShots.texts(compose).any { it.contains(text) }

    private fun shoot(name: String, theme: ThemeChoice = ThemeChoice.DARK) {
        draw(theme)
        listOf("TO DECIDE · 3", "Alex and Sam are off Mon 21 Dec", "INSET day · in 7 days", "Date night · Fri 18 Dec", "Dentist",
            "I'll work from home", "Covered", "Booked", "Skip this one", "Booked…", "Went", "Not yet", "Call to book", "Copy email",
        ).forEach { if (!onScreen(it)) fail("$name: $it is missing") }
        if (compose.onAllNodesWithTag(NEEDS_YOU_DECIDE_TAG, useUnmergedTree = true).fetchSemanticsNodes().size != 3) fail("$name: not three cards")
        ScreenShots.assertEachOneLine(compose, name, listOf("I'll work from home", "Covered", "Skip this one", "Booked…", "Went", "Not yet",
            "Call to book", "Copy email"))
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

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun bookedOpensTheWhenFieldInsideTheCard() {
        draw(ThemeChoice.DARK)
        if (onScreen(dentist.whenHint)) fail("the When? field shows before Booked…")
        compose.onNodeWithText("Booked…", useUnmergedTree = true).performClick()
        turns(1_000)
        if (!onScreen(dentist.whenHint)) fail("Booked… didn't open the When? field")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", "decide-others-booked-open"))
    }
}
