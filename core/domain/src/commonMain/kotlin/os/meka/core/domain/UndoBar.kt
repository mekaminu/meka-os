package os.meka.core.domain

/**
 * Found and fixing week 5, A12 (the snackbar over half-visible text): the undo bar ("Done · archived at Navilife's next
 * check  Undo") floated over a page's list with a gap all round it, so a cut line of the card under it showed in that
 * gap and above the bar. The bar now sits on a backdrop of the page's own colour: solid from the bar's gap down, fading
 * in over [BACKDROP_FADE_DP] above it, so whatever scrolls under it dissolves instead of reading as sliced.
 */
object UndoBarRules {
    /** How tall the fade above the bar's solid backdrop is: about one meta line and its gap. */
    const val BACKDROP_FADE_DP = 24

    /** How opaque the backdrop is [yDp] below its top edge (0 at the top, whole from the end of the fade down). */
    fun backdropAlpha(yDp: Float): Float = (yDp / BACKDROP_FADE_DP).coerceIn(0f, 1f)
}
