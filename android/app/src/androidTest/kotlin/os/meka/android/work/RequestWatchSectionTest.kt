package os.meka.android.work

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.PeopleLists
import os.meka.core.domain.RequestWatch

/**
 * V1, requests: "Straight away" beside each watched person on the closed Fold's width (412 dp) flips whether their
 * requests notify at once or wait for the digest. Runs on the Fold or an emulator; CI compiles it.
 */
class RequestWatchSectionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun eachWatchedPersonHasANotifyStraightAwayPill() {
        val flips = mutableListOf<Pair<String, Boolean>>()
        var on by mutableStateOf(setOf<String>())
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(412.dp)) {
                    RequestWatchSection(
                        PeopleLists(family = setOf("Ada")), RequestWatch(people = setOf("Wife")), listening = true, index = 0,
                        setWatch = {},
                        notifiesNow = { it in on },
                        setNotifyNow = { name, value -> flips += name to value; on = if (value) on + name else on - name },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Notify straight away for Wife, off").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Notify straight away for Wife, on").assertIsDisplayed()
        compose.onNodeWithContentDescription("Notify straight away for Ada, off").assertIsDisplayed()
        compose.onNodeWithText("Straight away").assertIsDisplayed()
        assertEquals(listOf("Wife" to true), flips)
        assertEquals(setOf("Wife"), on)
    }
}
