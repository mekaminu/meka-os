package os.meka.android.ask

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.AskFieldRules
import os.meka.core.domain.SearchGroup
import os.meka.core.domain.SearchHit
import os.meka.core.domain.SearchKind
import os.meka.core.domain.SearchTarget
import os.meka.core.domain.SearchView

/**
 * Fold review 2026-10-09 07:26, item 8: what's typed in Ask's field shows its best matches under it on the closed Fold's
 * width (412 dp); a task opens, an event is only shown, "See all" opens Search. Runs on the Fold or an emulator; CI
 * compiles it.
 */
class AskMatchListTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private var seeAll = 0

    private fun hit(id: String, title: String, kind: SearchKind, detail: String?, target: SearchTarget) =
        SearchHit(id, kind, title, detail, null, target, null, 5)

    private fun show() {
        val tasks = (1..6).map { hit("t$it", "Dentist $it", SearchKind.TASK, "Planned today 14:00", SearchTarget.TASK) }
        val event = hit("e", "Dentist check-up", SearchKind.EVENT, "Fri 9 Oct · 15:00–16:00", SearchTarget.INFO)
        val view = SearchView("den", listOf(SearchGroup(SearchKind.TASK, "Tasks", tasks, 0), SearchGroup(SearchKind.EVENT, "Calendar", listOf(event), 0)), 7)
        val m = AskFieldRules.matches(view, "den", canAsk = true)!!
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(412.dp)) { AskMatchList(m, onOpen = { opened += it.hit.id }, onSeeAll = { seeAll++ }) }
            }
        }
    }

    @Test
    fun matchesShowUnderTheFieldAndOpen() {
        show()
        compose.onAllNodesWithTag(ASK_MATCH_TAG).assertCountEquals(AskFieldRules.MAX_SHOWN)
        compose.onNodeWithContentDescription("Task: Dentist 1, Planned today 14:00").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Event: Dentist check-up, Fri 9 Oct · 15:00–16:00").assertIsDisplayed().performClick()
        assertEquals(listOf("t1"), opened)
        compose.onNodeWithText("See all 7 matches").assertIsDisplayed().performClick()
        assertEquals(1, seeAll)
    }
}
