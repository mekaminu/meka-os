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
import androidx.compose.ui.test.onAllNodesWithText
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
import os.meka.android.today.NEEDS_YOU_DECIDE_TAG
import os.meka.android.today.NeedsYouDecideCardView
import os.meka.android.today.NeedsYouHead
import os.meka.android.today.RequestChoice
import os.meka.core.domain.CivilDate
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.MessageRequestRules
import os.meka.core.domain.NeedsYouDecideRules
import os.meka.core.domain.NeedsYouLayoutRules
import os.meka.core.domain.RequestKind
import os.meka.core.domain.RequestMessage
import os.meka.core.domain.RequestProposal
import kotlin.test.fail

/**
 * The approved Needs you look, slice 3 on the Fold (`docs/design/needs-you/needs-you.dc.html`): TO DECIDE as compact
 * cards (icon, what, from whom, Add · Change · No), from invented requests (rule 13), on the cover and inner screens,
 * Dark and Light. Saved under `needs-you/`. Each card's what and from stay on one line on the cover, and its buttons
 * answer with the request card's own choices.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NeedsYouDecideScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val cal = LocalCalendar.UTC
    private val day = CivilDate.toEpochDay(2026, 12, 14)

    private fun request(id: String, who: String, text: String, p: RequestProposal) =
        MessageRequestRules.card(RequestMessage(id, who, text, cal.toEpochMs(day, 9 * 60)), 0, p, day, cal)

    private val cards = listOf(
        request("m1", "Alex", "the Christmas show is that day", RequestProposal(RequestKind.WORK_FROM_HOME, "Work from home", day + 2, null)),
        request("m2", "Game testing group", "can you check the algorithm today?", RequestProposal(RequestKind.TASK, "Check the algorithm", day, null)),
    ).map(NeedsYouDecideRules::card)

    private val choices = mutableListOf<RequestChoice>()

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
                    NeedsYouHead(NeedsYouLayoutRules.head(NeedsYouLayoutRules.DECIDE_HEAD, cards.size))
                    cards.forEach { c -> NeedsYouDecideCardView(c, { choices += it }) }
                }
            }
        }
        turns()
    }

    private fun onScreen(text: String) = ScreenShots.texts(compose).any { it.contains(text) }

    /** On the narrow cover a long "what" may take its second line (the card allows two); on the inner screen it's one. */
    private fun shoot(name: String, theme: ThemeChoice = ThemeChoice.DARK, wide: Boolean = false) {
        draw(theme)
        listOf("TO DECIDE · 2", "Work from home · Wed 16 Dec", "Task: Check the algorithm · Today", "Alex · “the Christmas show",
            "Add", "Change", "No").forEach { if (!onScreen(it)) fail("$name: $it is missing") }
        if (compose.onAllNodesWithTag(NEEDS_YOU_DECIDE_TAG, useUnmergedTree = true).fetchSemanticsNodes().size != 2) fail("$name: not two cards")
        ScreenShots.assertEachOneLine(compose, name, listOf("Add", "Change", "No") +
            (if (wide) listOf("Work from home · Wed 16 Dec", "Task: Check the algorithm · Today") else emptyList()))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", name))
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun decideCoverDark() = shoot("decide-cover-dark")
    @Test @Config(qualifiers = ScreenShots.COVER) fun decideCoverLight() = shoot("decide-cover-light", ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun decideInnerDark() = shoot("decide-inner-dark", wide = true)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun theButtonsAnswerWithTheRequestsOwnChoices() {
        draw(ThemeChoice.DARK)
        compose.onAllNodesWithText("Add")[0].performClick()
        compose.onAllNodesWithText("Change")[0].performClick() // only the task has Change
        compose.onAllNodesWithText("No")[1].performClick()
        turns(300)
        if (choices != listOf(RequestChoice.ADD, RequestChoice.CHANGE, RequestChoice.DECLINE)) fail("the buttons answered $choices")
    }
}
