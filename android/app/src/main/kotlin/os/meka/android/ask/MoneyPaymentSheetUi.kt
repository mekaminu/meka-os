package os.meka.android.ask

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.minTouch
import os.meka.core.domain.MoneyPaymentSheet
import os.meka.core.domain.MoneySaved
import os.meka.core.domain.MoneySheetMark
import os.meka.core.domain.MoneyTxnRow

/**
 * Money: the payment sheet (Meka 2026-10-26 23:18, replacing the unthemed white dropdown). A tap on a payment raises it
 * from the bottom over a 50 % dim (motion catalogue: Sheets and panes; reduced motion cross-fades): the payee and amount,
 * the day and account, category chips in his order (lit where it is), what a pick does, Shared · Always shared · Side
 * hustle, the last change's line with Undo, and Done. A tap on the dim, Back or Done closes it. The sheet stays open after a
 * pick so the chip lights and the "Moved N payments…" line shows under the chips.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalAnimationApi::class)
@Composable
internal fun PaymentSheet(
    row: MoneyTxnRow?, order: List<String>, said: MoneySaved?,
    onCategory: (String) -> Unit, onMark: (MoneySheetMark, Boolean) -> Unit, onUndo: (String) -> Unit, onClose: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    // The last row shown, so the sheet keeps its content while it slides away.
    val shown = remember { arrayOfNulls<MoneyTxnRow>(1) }
    if (row != null) shown[0] = row
    BackHandler(enabled = row != null, onBack = onClose)
    AnimatedVisibility(visible = row != null, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced))) {
        val r = row ?: shown[0] ?: return@AnimatedVisibility
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = MotionMath.scrimAlpha(1f)))
                    .pointerInput(Unit) { detectTapGestures { onClose() } }
                    .semantics { contentDescription = "Close the payment" }
                    .testTag("money-sheet-dim"),
            )
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .animateEnterExit(
                        enter = if (reduced) fadeIn(MekaMotion.expand(true)) else slideInVertically(MekaMotion.expand(false)) { it / 3 },
                        exit = if (reduced) fadeOut(MekaMotion.expand(true)) else slideOutVertically(MekaMotion.expand(false)) { it / 3 },
                    )
                    .clip(RoundedCornerShape(topStart = MekaRadius.l, topEnd = MekaRadius.l))
                    .background(Meka.colors.surface)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(MekaSpace.m)
                    .testTag("money-sheet"),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Row(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading(); contentDescription = MoneyPaymentSheet.spoken(r) },
                    verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
                ) {
                    Text(r.merchant, style = MekaType.nowTitle, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                    Text(r.amount, style = MekaType.nowTitle, color = if (r.moneyIn) Meka.colors.accent else Meka.colors.textPrimary)
                }
                Text(MoneyPaymentSheet.sub(r), style = MekaType.caption, color = Meka.colors.textSecondary)
                r.receipt?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
                Text(MoneyPaymentSheet.CATEGORY_LABEL, style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                    modifier = Modifier.padding(top = MekaSpace.xs))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    for (c in MoneyPaymentSheet.chips(order, r.category)) {
                        TripPill(c.label, on = c.on, tag = "money-sheet-cat-${c.key}", description = c.spoken) { if (!c.on) onCategory(c.key) }
                    }
                }
                Text(MoneyPaymentSheet.ruleLine(r), style = MekaType.caption, color = Meka.colors.textTertiary)
                Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "money-sheet-said") { s ->
                    if (s != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Text(s.line, style = MekaType.caption, color = if (s.refused) Meka.colors.accent else Meka.colors.textSecondary,
                            modifier = Modifier.weight(1f).testTag("money-sheet-said"))
                        s.undo?.let { u ->
                            Text("Undo", style = MekaType.itemMeta, color = Meka.colors.accent,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onUndo(u) }
                                    .minTouch().padding(MekaSpace.xs).testTag("money-sheet-undo"))
                        }
                    }
                }
                Text(MoneyPaymentSheet.MARKS_LABEL, style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                    modifier = Modifier.padding(top = MekaSpace.xs))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    for (t in MoneyPaymentSheet.toggles(r)) {
                        TripPill(t.label, on = t.on, tag = "money-sheet-mark-${t.mark.name.lowercase()}", description = t.spoken) { onMark(t.mark, !t.on) }
                    }
                }
                Box(
                    Modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                        .clickable(role = Role.Button, onClick = onClose).minTouch().padding(MekaSpace.xs).testTag("money-sheet-done"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(MoneyPaymentSheet.DONE, style = MekaType.metaStrong, color = Meka.colors.onAccent)
                }
            }
        }
    }
}
