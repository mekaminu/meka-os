package os.meka.core.domain

/**
 * The Mac's Text Size (Fix first, "Mac text is too big", Meka 2026-10-15): one multiplier applied to every type token,
 * chosen in View → Text Size (⌘− · ⌘0 · ⌘+) or Settings → Appearance, and kept on that Mac like the theme. Non-AI and
 * pure; the Fold keeps its own sizes (Android's font scale).
 */
enum class TextSizeChoice(val id: String, val label: String, val multiplier: Double) {
    SMALLER("smaller", "Smaller", 0.9),
    DEFAULT("default", "Default", 1.0),
    LARGER("larger", "Larger", 1.15),
    LARGEST("largest", "Largest", 1.3),
}

object TextSizeRules {
    /** The choice in effect: what was stored, else Default (nothing stored or an unknown id). */
    fun choice(id: String?): TextSizeChoice = TextSizeChoice.entries.firstOrNull { it.id == id } ?: TextSizeChoice.DEFAULT

    /** The choices in their order, for the menu and the segmented control. */
    val all: List<TextSizeChoice> get() = TextSizeChoice.entries

    /** ⌘+: one step bigger, staying at the largest. */
    fun bigger(id: String?): TextSizeChoice = step(choice(id), +1)

    /** ⌘−: one step smaller, staying at the smallest. */
    fun smaller(id: String?): TextSizeChoice = step(choice(id), -1)

    /** Whether ⌘+ / ⌘− would change anything (the menu greys the item out at either end). */
    fun canGrow(id: String?): Boolean = choice(id) != TextSizeChoice.entries.last()
    fun canShrink(id: String?): Boolean = choice(id) != TextSizeChoice.entries.first()

    /** The multiplier for a stored id. */
    fun multiplier(id: String?): Double = choice(id).multiplier

    /** A point size at this text size, rounded to the half point so text stays crisp. */
    fun size(base: Double, id: String?): Double = kotlin.math.round(base * multiplier(id) * 2.0) / 2.0

    /** The line under Text size in Appearance. */
    fun line(id: String?): String = when (choice(id)) {
        TextSizeChoice.DEFAULT -> "The Mac's usual size · ⌘+ and ⌘− change it"
        TextSizeChoice.SMALLER -> "A little smaller than usual · ⌘0 for the usual size"
        else -> "Bigger than usual · ⌘0 for the usual size"
    }

    private fun step(from: TextSizeChoice, by: Int): TextSizeChoice {
        val all = TextSizeChoice.entries
        return all[(from.ordinal + by).coerceIn(0, all.size - 1)]
    }
}
