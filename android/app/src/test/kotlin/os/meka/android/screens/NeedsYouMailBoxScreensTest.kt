package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
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
import os.meka.android.today.MailNeedsRowView
import os.meka.android.today.NEEDS_YOU_MAIL_LEAD_TAG
import os.meka.android.today.NEEDS_YOU_MAIL_ROW_TAG
import os.meka.android.today.NeedsYouGroupHeadView
import os.meka.android.today.NeedsYouGroupLead
import os.meka.android.today.NeedsYouMailRowView
import os.meka.android.today.NeedsYouPager
import os.meka.core.domain.MailDraft
import os.meka.core.domain.MailMomentRules
import os.meka.core.domain.MailNeedsRow
import os.meka.core.domain.NeedsYouGroup
import os.meka.core.domain.NeedsYouGroupRules
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Needs you grouped and folding, slice 2b on the folded Fold (`docs/design/needs-you/needs-you-grouped.dc.html`): Mail to
 * answer opened shows the ten-minute inbox as its lead line (Start · Not today), then thin rows (sender · time over the
 * subject, never the body) five a page with ‹ ›; a tap on a row unfolds Reply · Done · Task · More under it and a second
 * tap folds them away. Invented names (rule 13). Saved under `needs-you/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NeedsYouMailBoxScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val senders = listOf("Sam Taylor", "PayPal", "Oakfield School", "Oakfield School", "Council", "Dentist", "Energy Co")
    private val every = senders.mapIndexed { i, who ->
        MailNeedsRow(
            id = "m$i", from = who, line = "Personal · 0$i:12", subject = "Subject $i", preview = "Hi Alex, the card ending 1234",
            business = false, spoken = "$who, Subject $i", replyLine = "To $who", scam = who == "PayPal",
            warning = if (who == "PayPal") "⚠ Possible scam · sent from mail-x.com, not paypal.com" else null, time = "0$i:12",
        )
    }
    private var starts = 0
    private var notToday = 0
    private var done = 0

    private fun turns(ms: Long = 1_500L) {
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
                var page by remember { mutableIntStateOf(0) }
                var openRows by remember { mutableStateOf(setOf<String>()) }
                val pg = NeedsYouGroupRules.page(every.size, page)
                Column(
                    Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen").verticalScroll(rememberScrollState())
                        .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    NeedsYouGroupHeadView(
                        NeedsYouGroupRules.head(NeedsYouGroup.MAIL, every.size, NeedsYouGroupRules.mailPeek(true, every, null), true, pg), {},
                    )
                    NeedsYouGroupLead(NeedsYouGroupRules.mailLead(true, false)!!, NeedsYouGroupRules.MAIL_START, { starts++ },
                        Modifier.testTag(NEEDS_YOU_MAIL_LEAD_TAG), second = MailMomentRules.NOT_TODAY, onSecond = { notToday++ })
                    pg.of(every).forEach { row ->
                        val open = row.id in openRows
                        NeedsYouMailRowView(NeedsYouGroupRules.mailRow(row), open) {
                            openRows = if (open) openRows - row.id else openRows + row.id
                        }
                        if (open) {
                            MailNeedsRowView(
                                row, onDone = { done++ }, onTask = {}, onWaiting = {}, onSnooze = {},
                                onDraft = { MailDraft(null, "") }, onSend = { _, _ -> }, head = false,
                            )
                        }
                    }
                    if (pg.pages > 1) NeedsYouPager(pg, { page = it })
                }
            }
        }
        turns()
    }

    private fun onScreen(text: String) = ScreenShots.texts(compose).any { it.contains(text) }
    private val mailRow = SemanticsMatcher("a thin mail row") { n ->
        n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("$NEEDS_YOU_MAIL_ROW_TAG-") == true
    }
    private fun rows() = compose.onAllNodes(mailRow, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun tapRow(id: String) {
        compose.onNodeWithTag("$NEEDS_YOU_MAIL_ROW_TAG-$id", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        turns()
    }

    private fun shoot(name: String, theme: ThemeChoice = ThemeChoice.DARK) {
        draw(theme)
        if (!onScreen("Ten-minute inbox · one at a time")) fail("$name: the ten-minute inbox isn't the box's lead line")
        if (!onScreen("1–5 of 7")) fail("$name: the box head doesn't say which page")
        assertEquals(NeedsYouGroupRules.PAGE_SIZE, rows(), "$name: Mail to answer shows one page of five")
        if (onScreen("card ending")) fail("$name: a thin row shows the email's body")
        if (!onScreen("Possible scam")) fail("$name: a possible scam isn't flagged on its row")
        if (onScreen("Reply")) fail("$name: a row's answers show before it is tapped")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", "mail-box-$name"))
        val row = compose.onNodeWithTag("$NEEDS_YOU_MAIL_ROW_TAG-m0", useUnmergedTree = true).fetchSemanticsNode()
        if (row.size.height < (52 * row.layoutInfo.density.density).toInt()) fail("$name: a mail row is under a 52 dp tap")

        compose.onAllNodes(hasText(NeedsYouGroupRules.MAIL_START))[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.onAllNodes(hasText(MailMomentRules.NOT_TODAY))[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
        assertEquals(1, starts, "$name: Start didn't start the ten-minute inbox")
        assertEquals(1, notToday, "$name: Not today wasn't offered")

        tapRow("m0")
        if (!onScreen("Reply") || !onScreen("Done") || !onScreen("Task")) fail("$name: a tapped row doesn't unfold Reply · Done · Task")
        if (!onScreen("More")) fail("$name: More is missing under a tapped row")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", "mail-box-$name-open"))
        compose.onAllNodes(hasContentDescription("archive Subject 0", substring = true))[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
        assertEquals(1, done, "$name: Done under the row didn't answer that email")
        tapRow("m0")
        if (onScreen("Reply")) fail("$name: a second tap didn't fold the answers away")

        compose.onAllNodes(hasContentDescription("Next page"))[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
        assertEquals(2, rows(), "$name: › shows the last two emails")
        if (!onScreen("6–7 of 7")) fail("$name: the page line didn't move on")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverDark() = shoot("cover-dark")
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverLight() = shoot("cover-light", ThemeChoice.LIGHT)
}
