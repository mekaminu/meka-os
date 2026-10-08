package os.meka.android.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Feedback motion (motion pass 2): "button press scale (0.97) everywhere". MekaTheme provides this as the
 * `LocalIndication`, so every `clickable` in the app (rows, pills, chips, cards) presses in to 0.97 on the complete
 * spring while held and springs back when let go, with a faint veil over it. A quick tap still shows the whole press:
 * the release waits for the press-in to land. Motion → Off: no scale, a brief dim only.
 */
class MekaPressIndication(private val reduced: Boolean, private val veil: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, reduced, veil)

    override fun equals(other: Any?) = other is MekaPressIndication && other.reduced == reduced && other.veil == veil
    override fun hashCode() = 31 * reduced.hashCode() + veil.hashCode()
}

private class PressNode(
    private val source: InteractionSource,
    private val reduced: Boolean,
    private val veil: Color,
) : Modifier.Node(), DrawModifierNode {
    private val shown = Animatable(1f)
    private var pressed = false
    private var pressIn: Job? = null

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> press(true)
                    is PressInteraction.Release, is PressInteraction.Cancel -> press(false)
                }
            }
        }
    }

    private fun press(down: Boolean) {
        pressed = down
        invalidateDraw()
        if (reduced) return
        val target = MotionMath.pressScale(down, reduced = false)
        if (down) {
            pressIn = coroutineScope.launch { shown.animateTo(target, MekaMotion.complete(false)) { invalidateDraw() } }
        } else {
            val landing = pressIn
            coroutineScope.launch {
                landing?.join()
                shown.animateTo(target, MekaMotion.complete(false)) { invalidateDraw() }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val s = shown.value
        if (s != 1f) scale(s) { this@draw.drawContent() } else drawContent()
        val dim = MotionMath.pressDim(pressed, reduced)
        if (dim > 0f) drawRect(veil.copy(alpha = dim))
    }
}
