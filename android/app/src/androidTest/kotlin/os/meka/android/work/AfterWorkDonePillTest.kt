package os.meka.android.work

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.AfterWorkDone
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.DonePillSpec
import os.meka.core.domain.PersonSummary

/**
 * Done on "While you were at work" cards (Meka, 2026-10-20 17:33) on the closed Fold's width (412 dp): the drawn pill
 * is at most 36 dp high (the 48 dp touch target sits outside it) and sits at the same x on a card with a short name
 * and one with a long one. Runs on the Fold or an emulator; CI compiles it.
 */
class AfterWorkDonePillTest {
    @get:Rule
    val compose = createComposeRule()

    private fun person(name: String, at: Long) = PersonSummary(
        personName = name, isFamily = false, urgent = name.length > 10, messages = 1, missedCalls = 0,
        apps = listOf(CaptureApp.WHATSAPP), firstAtMs = at, latestAtMs = at, latestText = "running late", items = emptyList(),
    )

    @Test
    fun doneIsASmallPillInTheSamePlaceOnEveryCard() {
        val short = person("Sam", 1_791_400_000_000L)
        val long = person("Alexandra Montgomery-Smythe from the football club", 1_791_400_060_000L)
        val done = mutableListOf<String>()
        compose.setContent {
            MekaTheme {
                Column(Modifier.width(412.dp)) {
                    listOf(short, long).forEach { p ->
                        PersonCard(p, expanded = false, Modifier, isBlocked = false, onBlock = {}, onReport = {}, audio = { null },
                            autoPlay = null, onAutoPlayed = {}, onDone = { done += p.personName }, onTap = {})
                    }
                }
            }
        }
        val a = compose.onNodeWithTag("after-work-done-${short.cardKey}", useUnmergedTree = true).getBoundsInRoot()
        val b = compose.onNodeWithTag("after-work-done-${long.cardKey}", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("drawn height ${a.height}", a.height.value <= DonePillSpec.MAX_DRAWN_HEIGHT_DP + 0.5f)
        assertTrue("drawn height ${b.height}", b.height.value <= DonePillSpec.MAX_DRAWN_HEIGHT_DP + 0.5f)
        assertEquals(a.left.value, b.left.value, 0.5f)
        assertEquals(a.right.value, b.right.value, 0.5f)
        val touch = compose.onNodeWithTag("after-work-done-${short.cardKey}-touch").getBoundsInRoot()
        assertTrue("touch height ${touch.height}", touch.height.value >= DonePillSpec.TOUCH_DP - 0.5f)
        compose.onNodeWithContentDescription(AfterWorkDone.doneLabel(long)).performClick()
        compose.waitUntil(3_000) { done.isNotEmpty() }
        assertEquals(listOf(long.personName), done)
    }
}
