package os.meka.android.shell

/**
 * The app shell's rules (build plan M1, App shell), kept free of Compose so they are unit-tested and match the Mac
 * (macos/MekaOS/Shell/ShellNav.swift) rule for rule.
 */
enum class ShellDestination(val label: String) {
    TODAY("Today"),
    NEEDS_YOU("Needs you"),
    LISTS("Lists"),
    GOALS("Goals"),
    REVIEW("Review"),
    VAULT("Vault"),
}

enum class ShellLayout {
    /** Closed Fold: content above a bottom bar. */
    BOTTOM_BAR,

    /** Open Fold and wide windows: a rail on the left, content (Today keeps its own two panes) on the right. */
    RAIL,
}

object ShellNav {
    /** Same breakpoint Today uses for its two panes. */
    const val WIDE_DP = 600f

    fun layoutFor(widthDp: Float): ShellLayout = if (widthDp >= WIDE_DP) ShellLayout.RAIL else ShellLayout.BOTTOM_BAR

    /**
     * Destinations shown in the bar or rail. The closed Fold has room for five; the Vault (V2) lives on the rail
     * and the Mac sidebar until it has something in it.
     */
    fun destinations(layout: ShellLayout): List<ShellDestination> = when (layout) {
        ShellLayout.RAIL -> ShellDestination.entries
        ShellLayout.BOTTOM_BAR -> ShellDestination.entries - ShellDestination.VAULT
    }

    /** Which bar item is lit for [current]; null when [current] isn't in the bar (Vault reached on the rail, then folded). */
    fun barSelection(current: ShellDestination, layout: ShellLayout): ShellDestination? =
        current.takeIf { it in destinations(layout) }

    /** Content slides the way you moved along the bar: +1 forward (from the right), -1 back, 0 for no change. */
    fun direction(from: ShellDestination, to: ShellDestination): Int = to.ordinal.compareTo(from.ordinal).coerceIn(-1, 1)

    /** Badge text on Needs you: nothing at zero, the count up to 9, then "9+". */
    fun badge(count: Int): String? = when {
        count <= 0 -> null
        count > 9 -> "9+"
        else -> count.toString()
    }

    /** Screen-reader label for a destination, including the badge. */
    fun accessibilityLabel(d: ShellDestination, needsYouCount: Int): String {
        val n = if (d == ShellDestination.NEEDS_YOU) needsYouCount else 0
        return if (n > 0) "${d.label}, $n waiting" else d.label
    }
}
