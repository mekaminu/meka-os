package os.meka.android.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign

/**
 * Walk-through 2026-11-08 A27: a time stepper's − and + were bare glyphs centred in (or padded to) their 48 dp
 * targets, so the + ended ~16 dp short of the On pills drawn to the row's edge above and below it. One stepper glyph
 * for every settings row: a 48 dp target whose glyph sits at its centre, or, for the row's last one ([atEnd]), at its
 * end, so the + ends on the same edge as the pills while the whole 48 dp still takes the tap.
 */
object StepGlyphSpec {
    const val LESS = "−"
    const val MORE = "+"
}

@Composable
fun StepGlyph(glyph: String, description: String, atEnd: Boolean, tag: String? = null, onClick: () -> Unit) {
    Box(
        Modifier.let { m -> tag?.let { m.testTag(it) } ?: m }
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(MekaRadius.m))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .sizeIn(minWidth = MekaSpace.touch, minHeight = MekaSpace.touch),
        contentAlignment = if (atEnd) Alignment.CenterEnd else Alignment.Center,
    ) {
        Text(glyph, style = MekaType.itemTitle, color = Meka.colors.accent, textAlign = TextAlign.Center,
            modifier = Modifier.let { m -> tag?.let { m.testTag("$it-glyph") } ?: m })
    }
}
