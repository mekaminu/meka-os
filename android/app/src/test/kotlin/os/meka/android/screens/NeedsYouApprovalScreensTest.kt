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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasContentDescription
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
import os.meka.android.today.NeedsYouDecideShell
import os.meka.android.today.NeedsYouHead
import os.meka.core.domain.ApprovalCard
import os.meka.core.domain.ApprovalKind
import os.meka.core.domain.ApprovalRules
import os.meka.core.domain.NeedsYouDecideRules
import os.meka.core.domain.NeedsYouLayoutRules
import kotlin.test.fail

/**
 * The approved Needs you look, slice 5 on the Fold (`docs/design/needs-you/needs-you.dc.html`): approvals (a trade and a
 * payment, invented, rule 13) as compact TO DECIDE cards: the icon, the title over "Trade · Kestrel · Asked … · expires
 * …", what would happen kept whole, Approve · Decline and the fingerprint note; cover and inner, Dark and Light (saved
 * under `needs-you/decide-approvals-*`). The taps reach the approval's own Approve (which asks for the fingerprint) and Decline.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NeedsYouApprovalScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val trade = ApprovalCard(
        id = "t1", kind = ApprovalKind.TRADE, label = "TRADE · KESTREL", title = "Buy 0.05 BTC",
        lines = listOf("At market, about £2,100", "Stop at £39,000"), why = "Asked 21:40 · expires in 14 min",
        biometric = true, urgent = true, approveLabel = ApprovalRules.APPROVE, declineLabel = ApprovalRules.DECLINE,
        spoken = "Kestrel asks: Trade. Buy 0.05 BTC. At market, about £2,100. Stop at £39,000. Asked 21:40 · expires in 14 min",
    )
    private val payment = trade.copy(
        id = "p1", kind = ApprovalKind.PAYMENT, label = "PAYMENT · OAKFIELD SCHOOL", title = "Pay £12.50 for the school trip",
        lines = listOf("From your current account"), why = "Asked 09:12 · expires tomorrow 09:12", urgent = false,
        spoken = "Oakfield School asks: Payment. Pay £12.50 for the school trip.",
    )

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
                    NeedsYouHead(NeedsYouLayoutRules.head(NeedsYouLayoutRules.DECIDE_HEAD, 2))
                    listOf(trade, payment).forEach { a ->
                        NeedsYouDecideShell(NeedsYouDecideRules.approval(a), onYes = { taps += "approve:${a.id}" }, onNo = { taps += "decline:${a.id}" })
                    }
                }
            }
        }
        turns()
    }

    private fun onScreen(text: String) = ScreenShots.texts(compose).any { it.contains(text) }

    private fun shoot(name: String, theme: ThemeChoice = ThemeChoice.DARK) {
        draw(theme)
        listOf("TO DECIDE · 2", "Buy 0.05 BTC", "Trade · Kestrel · Asked 21:40 · expires in 14 min", "At market, about £2,100",
            "Stop at £39,000", "Pay £12.50 for the school trip", "From your current account", ApprovalRules.LOCK_FOLD,
        ).forEach { if (!onScreen(it)) fail("$name: $it is missing") }
        if (onScreen("TRADE · KESTREL")) fail("$name: the label still shouts in capitals")
        if (compose.onAllNodesWithTag(NEEDS_YOU_DECIDE_TAG, useUnmergedTree = true).fetchSemanticsNodes().size != 2) fail("$name: not two cards")
        ScreenShots.assertEachOneLine(compose, name, listOf(ApprovalRules.APPROVE, ApprovalRules.DECLINE))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", name))
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun decideApprovalsCoverDark() = shoot("decide-approvals-cover-dark")
    @Test @Config(qualifiers = ScreenShots.COVER) fun decideApprovalsCoverLight() = shoot("decide-approvals-cover-light", ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun decideApprovalsInnerDark() = shoot("decide-approvals-inner-dark")

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun approveAndDeclineReachTheApprovalsOwnTaps() {
        draw(ThemeChoice.DARK)
        // The pills' spoken names carry the card's title, so each finds its own card.
        compose.onNode(hasContentDescription("${ApprovalRules.APPROVE}: Buy 0.05 BTC")).performClick()
        compose.onNode(hasContentDescription("${ApprovalRules.DECLINE}: Pay £12.50 for the school trip")).performClick()
        turns(300)
        val want = listOf("approve:t1", "decline:p1")
        if (taps != want) fail("the taps answered $taps, not $want")
    }
}
