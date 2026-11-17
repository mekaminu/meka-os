package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.core.domain.NeedsYouLayoutRules
import kotlin.test.fail

/**
 * Needs you's TO DECIDE cards as the screen draws them (walk-through 2026-11-15, A17): on the open Fold
 * ([NeedsYouLayoutRules.twoColumns]) they sit in the left of two equal columns with the right column's quiet line
 * beside them, never stretched across the whole width; on the cover, one column. The shot is the whole width either
 * way, tagged "screen".
 */
@Composable
internal fun NeedsYouDecideShotPage(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().background(Meka.colors.background).testTag("screen")) {
        val column: @Composable (Modifier) -> Unit = { m ->
            Column(m.padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs), content = content)
        }
        if (NeedsYouLayoutRules.twoColumns(maxWidth.value.toInt(), taskOpen = false)) {
            Row(Modifier.fillMaxWidth()) {
                column(Modifier.weight(1f).testTag(DECIDE_SHOT_LEFT))
                Column(Modifier.weight(1f).padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl)) {
                    Text(NeedsYouLayoutRules.RIGHT_EMPTY, style = MekaType.itemMeta, color = Meka.colors.textTertiary,
                        modifier = Modifier.padding(start = MekaSpace.xxs))
                }
            }
        } else {
            column(Modifier.fillMaxWidth())
        }
    }
}

internal const val DECIDE_SHOT_LEFT = "decide-shot-left"

/** On the open Fold every card under [tag] stays inside the left column (the A17 finding: ~1,920 px wide cards). */
internal fun assertDecideInLeftColumn(compose: ComposeContentTestRule, name: String, tag: String) {
    val screen = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
    val wide = NeedsYouLayoutRules.twoColumns((screen.width / compose.density.density).toInt(), taskOpen = false)
    val left = compose.onAllNodesWithTag(DECIDE_SHOT_LEFT).fetchSemanticsNodes().firstOrNull()
        ?: if (wide) fail("$name: the open Fold draws one column, not two") else return
    if (left.boundsInRoot.right > screen.left + screen.width / 2f + 1f) fail("$name: the left column is wider than half the open Fold")
    compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().forEach { n ->
        if (n.boundsInRoot.right > left.boundsInRoot.right + 1f) fail("$name: a card runs past the left column")
    }
}
