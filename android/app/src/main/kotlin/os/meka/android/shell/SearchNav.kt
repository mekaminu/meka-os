package os.meka.android.shell

import os.meka.android.lists.ListTab
import os.meka.core.domain.SearchTarget

/** Something search asked a destination to open: its tab, and the row to unfold. */
data class OpenItem(val target: SearchTarget, val id: String)

/**
 * Where a search result goes (build plan M1, Search everything), kept free of Compose so it is unit-tested and matches
 * the Mac (macos/MekaOS/Search/SearchNav.swift) rule for rule. Tasks open their detail over the results instead.
 */
object SearchNav {
    /** The destination that shows [target]; null for results that open in place (tasks) or not at all (events). */
    fun destination(target: SearchTarget): ShellDestination? = when (target) {
        SearchTarget.LISTS_WAITING, SearchTarget.LISTS_SOMEDAY, SearchTarget.LISTS_DECISIONS, SearchTarget.LISTS_RENEWALS,
        SearchTarget.LISTS_SHOPPING -> ShellDestination.LISTS
        SearchTarget.GOALS -> ShellDestination.GOALS
        SearchTarget.TASK, SearchTarget.INFO -> null
    }

    /** The Lists tab for a Lists result. */
    fun listTab(target: SearchTarget): ListTab? = when (target) {
        SearchTarget.LISTS_WAITING -> ListTab.WAITING
        SearchTarget.LISTS_SOMEDAY -> ListTab.SOMEDAY
        SearchTarget.LISTS_DECISIONS -> ListTab.DECISIONS
        SearchTarget.LISTS_RENEWALS -> ListTab.RENEWALS
        SearchTarget.LISTS_SHOPPING -> ListTab.SHOPPING
        else -> null
    }

    /** Screen-reader hint for a result. */
    fun hint(target: SearchTarget): String? = when (target) {
        SearchTarget.TASK -> "Opens the task"
        SearchTarget.LISTS_WAITING -> "Opens Waiting for"
        SearchTarget.LISTS_SOMEDAY -> "Opens Someday"
        SearchTarget.LISTS_DECISIONS -> "Opens Decisions"
        SearchTarget.LISTS_RENEWALS -> "Opens Renewals"
        SearchTarget.LISTS_SHOPPING -> "Opens Shopping"
        SearchTarget.GOALS -> "Opens Goals"
        SearchTarget.INFO -> null
    }
}
