package os.meka.android.shell

/**
 * Rules behind the shell's shared-element transitions and the Fold unfold morph (build plan M1, App shell), kept free
 * of Compose so they are unit-tested. The parts both apps use match macos/MekaOS/Shell/SharedMotion.swift.
 */
object SharedMotion {
    /** Share of the width the detail pane takes when the Fold is open. */
    const val DETAIL_FRACTION = 0.45f

    /** How long tasks that Plan Apply just sent into Today stay softly lit. */
    const val LANDED_MS = 1200

    /** One key per task: the list row's title, the detail title and the plan block all share it. */
    fun taskKey(id: String): String = "task-$id"

    /** Width share of the detail pane: none on the closed Fold, [DETAIL_FRACTION] when open. */
    fun detailFraction(twoPane: Boolean): Float = if (twoPane) DETAIL_FRACTION else 0f

    /**
     * Where the detail pane starts. The first time a screen is shown it is already in place; after a fold or unfold it
     * starts from where the last layout left it, so unfolding grows the detail pane out beside the list.
     */
    fun startFraction(previousTwoPane: Boolean?, twoPane: Boolean): Float = detailFraction(previousTwoPane ?: twoPane)

    /** The detail pane fades in as it grows, so half-width text never shows at full strength. */
    fun detailAlpha(fraction: Float): Float = (fraction / DETAIL_FRACTION).coerceIn(0f, 1f)

    /**
     * Whether a list row draws its own title. It steps aside while its title is the detail pane's (closed Fold, item
     * open) or while it's a plan block about to fly into Today, so only one copy is ever on screen.
     */
    fun rowTitleVisible(id: String, selectedId: String?, singlePane: Boolean, planOpen: Boolean, landing: Set<String>): Boolean =
        !(singlePane && selectedId == id) && !(planOpen && id in landing)

    /** A task that just landed from the plan is lit once the plan has gone. */
    fun highlightLanded(id: String, planOpen: Boolean, landing: Set<String>): Boolean = !planOpen && id in landing
}
