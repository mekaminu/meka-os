package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlin.random.Random
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.today.UnopenedOfferSection
import os.meka.core.domain.MailUnopenedCard
import os.meka.core.domain.MailUnopenedRow
import os.meka.core.domain.MailUnopenedRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * Walk-through 2026-11-15 A20: Needs you's "Not opened in 30 days" card had **Show all N** as a bare text link about
 * 34 dp tall. It is now a whole 48 dp target (the words stay where they were, centred in the height), and a tap still
 * unfolds the rest of the senders and turns it into Show fewer. Invented senders only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class, qualifiers = ScreenShots.COVER)
class UnopenedShowAllTargetTest {
    @get:Rule
    val compose = createComposeRule()

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(3), timeZone = { TimeZone.of("Europe/London") }, nowMs = { 0L },
    )

    private fun row(i: Int) = MailUnopenedRow("s$i", "Shop $i", "4 emails · none opened")

    private val card = (1..10).map(::row).let { all ->
        MailUnopenedCard(
            title = MailUnopenedRules.TITLE, headline = "Unsubscribe from 10?", unsubscribeLabel = "Unsubscribe from 10",
            note = MailUnopenedRules.NOTE, rows = all.take(8), moreRows = all.drop(8),
            showAllLabel = MailUnopenedRules.showAllLabel(10), moreLine = "+ 2 more · Show all 10 to see them or keep any",
            ids = all.map { it.id }, spoken = "Not opened in 30 days. Unsubscribe from 10?",
        )
    }

    @Test
    fun showAllIsAWholeTargetAndStillUnfoldsTheRest() {
        val core = core()
        compose.setContent {
            MekaTheme {
                Column(Modifier.width(344.dp).background(Meka.colors.background).padding(16.dp)) {
                    UnopenedOfferSection(card, core, undo = { _, _ -> })
                }
            }
        }
        compose.waitForIdle()
        val name = "Unopened"
        ScreenShots.assertTallTarget(compose, name, hasText(MailUnopenedRules.showAllLabel(10)) and hasClickAction())
        compose.onNodeWithTag("unopened-show-all").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Shop 10").assertExists()
        ScreenShots.assertTallTarget(compose, name, hasText(MailUnopenedRules.SHOW_FEWER) and hasClickAction())
    }
}
