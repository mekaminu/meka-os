package os.meka.android.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.MekaDarkColors
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.core.domain.AskCard
import os.meka.core.domain.CardRise
import os.meka.core.domain.HelloCard
import os.meka.core.domain.HelloCards
import os.meka.core.domain.HelloGreeting

/**
 * "Hello, Navilife" slice 8: what rises round the stage's orb. The greeting's own cards ([greeting], until he answers),
 * else the latest answer's cards ([answer], with [done] the ones already tapped), and whether that answer holds
 * something only Ask draws ([needsAsk]: the stage steps back for it).
 */
data class StageCards(
    val greeting: HelloGreeting? = null,
    val answer: List<AskCard> = emptyList(),
    val done: Set<Int> = emptySet(),
    val needsAsk: Boolean = false,
)

private val cardShape = RoundedCornerShape(MekaRadius.m)

private fun Modifier.rising(r: CardRise, density: Float): Modifier = graphicsLayer {
    translationY = r.liftDp * density
    alpha = r.alpha
    scaleX = r.scale; scaleY = r.scale
}

/** A greeting card: the small capitals line (amber when urgent or late), the one line that matters, a quiet second line; a tap opens its screen. */
@Composable
internal fun StageHelloCard(card: HelloCard, rise: CardRise, density: Float, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val c = MekaDarkColors
    Column(
        modifier.width(HelloCards.CARD_DP.dp).rising(rise, density)
            .clip(cardShape).background(c.surface.copy(alpha = 0.92f))
            .border(1.dp, (if (card.warm) c.calendar3 else c.accent).copy(alpha = 0.35f), cardShape)
            .clickable(role = Role.Button, enabled = rise.alpha > 0.5f, onClick = onTap)
            .semantics { contentDescription = HelloCards.label(card) }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(card.eyebrow, style = MekaType.sectionLabel, color = if (card.warm) c.calendar3 else c.accent, maxLines = 1)
        Text(card.title, style = MekaType.body, color = c.textPrimary, maxLines = 2)
        card.detail?.let { Text(it, style = MekaType.caption, color = c.textSecondary, maxLines = 1) }
    }
}

/** An answer's card on the stage: its line and its one button; nothing happens until the button is tapped (as on Ask). */
@Composable
internal fun StageAnswerCard(card: AskCard, rise: CardRise, density: Float, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val c = MekaDarkColors
    Row(
        modifier.width(HelloCards.CARD_DP.dp).rising(rise, density)
            .clip(cardShape).background(c.surface.copy(alpha = 0.92f))
            .border(1.dp, c.accent.copy(alpha = 0.35f), cardShape)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(card.line, style = MekaType.caption, color = c.textPrimary, modifier = Modifier.weight(1f), maxLines = 3)
        Text(
            card.button, style = MekaType.itemMeta, color = c.onAccent,
            modifier = Modifier.padding(start = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill)).background(c.accent)
                .clickable(role = Role.Button, enabled = rise.alpha > 0.5f, onClick = onTap)
                .semantics { contentDescription = "${card.button}: ${card.line}" }
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        )
    }
}
