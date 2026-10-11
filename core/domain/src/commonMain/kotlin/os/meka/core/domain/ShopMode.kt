package os.meka.core.domain

/**
 * Shop mode (Checklists in tasks, slice 2; Meka, 2026-10-10: "going shopping with a list, check it off as I shop"): a
 * task's checklist or the shared Shopping list full screen, big text, still to get first, the screen kept awake. It
 * reads Meka's own synced data on the device, so it works offline; ticks sync when the phone is back online (a tick on
 * the shared list then shows on Jeanette's page). Plain, non-AI rules shared by both apps.
 */
data class ShopRow(val id: String, val text: String, val done: Boolean, val meta: String?)

data class ShopModeView(
    /** The task's title, or "Shopping". */
    val title: String,
    /** Still to get, in the list's order. */
    val toGet: List<ShopRow>,
    /** Ticked (a checklist) or Got (the shopping list), under [doneLabel]. */
    val done: List<ShopRow>,
    /** "Ticked" or "Got". */
    val doneLabel: String,
    /** "4 left · 3 ticked", "All 7 ticked", "Nothing on the list". */
    val line: String,
) {
    val allDone: Boolean get() = toGet.isEmpty() && done.isNotEmpty()
    val isEmpty: Boolean get() = toGet.isEmpty() && done.isEmpty()
}

object ShopModeRules {
    const val OPEN = "Shop mode"
    const val CLOSE = "Done"
    const val OFFLINE_NOTE = "Works offline · ticks sync when you're back online"
    const val EMPTY = "Nothing on the list"

    fun ofChecklist(task: Task): ShopModeView {
        val v = ChecklistRules.view(task)
        return ShopModeView(
            title = task.title.ifBlank { "Checklist" },
            toGet = v.toTick.map { ShopRow(it.id, it.text, false, null) },
            done = v.ticked.map { ShopRow(it.id, it.text, true, null) },
            doneLabel = ChecklistRules.TICKED_LABEL,
            line = line(v.toTick.size, v.ticked.size, "ticked"),
        )
    }

    fun ofShopping(v: ShoppingView): ShopModeView = ShopModeView(
        title = "Shopping",
        toGet = v.toBuy.map { ShopRow(it.id, it.title, false, it.meta) },
        done = v.got.map { ShopRow(it.id, it.title, true, null) },
        doneLabel = "Got",
        line = line(v.toBuy.size, v.got.size, "got"),
    )

    /** "4 left · 3 ticked", "4 left", "All 3 ticked" ("All got" for one), [EMPTY]. */
    fun line(left: Int, done: Int, doneWord: String): String = when {
        left == 0 && done == 0 -> EMPTY
        left == 0 -> if (done == 1) "All $doneWord" else "All $done $doneWord"
        done == 0 -> "$left left"
        else -> "$left left · $done $doneWord"
    }

    /** Shop mode is offered once a list has something on it. */
    fun offer(itemCount: Int): Boolean = itemCount > 0
}
