package os.meka.android.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.android.screens.ShotApplication
import os.meka.core.domain.MoneyAccountList
import os.meka.core.domain.MoneyBusiness

/**
 * Walk-through 2026-11-22 A12: on an account's sheet the switch rows' wrapped lines lost their first letter ("on Bills",
 * "hustle's tax year") because the row's 16 dp rounded clip sat right on the words. The row now taps with wordsTap, so
 * it draws exactly as the same words with no tap at all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class, qualifiers = "w344dp-h882dp-420dpi")
class MoneySwitchRowTest {
    @get:Rule
    val compose = createComposeRule()

    /** A narrow card (as on the cover) so the line wraps onto a second line at the row's left edge. */
    @Composable
    private fun Card(tag: String, row: @Composable () -> Unit) {
        Box(Modifier.testTag(tag).width(260.dp).background(Meka.colors.surface).padding(MekaSpace.m)) { row() }
    }

    /** The same row's words and pill, with no tap and no clip: what the words look like whole. */
    @Composable
    private fun Plain(label: String, line: String, on: Boolean) {
        Row(
            Modifier.fillMaxWidth().minTouch().padding(vertical = MekaSpace.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MekaType.body, color = Meka.colors.textPrimary)
                Text(moneyShown(line), style = MekaType.caption, color = Meka.colors.textSecondary)
            }
            TripPill(if (on) "On" else "Off", on = on, description = "$label, ${if (on) "on" else "off"}", onClick = {})
        }
    }

    @Test
    fun theSwitchRowsDrawTheirWrappedLinesWhole() {
        val rows = listOf(
            MoneyAccountList.CARD_SWITCH to MoneyAccountList.CARD_SWITCH_LINE,
            MoneyBusiness.SWITCH to MoneyAccountList.SIDE_HUSTLE_LINE,
        )
        compose.setContent {
            MekaTheme {
                Column {
                    rows.forEachIndexed { i, (label, line) ->
                        Card("plain$i") { Plain(label, line, on = false) }
                        Card("row$i") { MoneySwitchRow(label, line, on = false, tag = "switch$i") {} }
                    }
                }
            }
        }
        compose.waitForIdle()
        rows.indices.forEach { i ->
            val plain = compose.onNodeWithTag("plain$i").captureToImage().toPixelMap()
            val row = compose.onNodeWithTag("row$i").captureToImage().toPixelMap()
            assertEquals(plain.width, row.width)
            assertEquals(plain.height, row.height)
            var differ = 0
            for (y in 0 until plain.height) for (x in 0 until plain.width) if (plain[x, y] != row[x, y]) differ++
            assertTrue(plain.height > 0)
            assertEquals(0, differ, "\"${rows[i].second}\": the switch row draws its words differently from the words alone (a clip cutting a letter)")
        }
    }
}
