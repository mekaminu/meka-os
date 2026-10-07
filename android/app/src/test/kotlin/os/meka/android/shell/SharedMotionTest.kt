package os.meka.android.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The parts both apps use must agree with macos/MekaOSTests/SharedMotionTests.swift. */
class SharedMotionTest {
    @Test
    fun oneKeyPerTaskSharedByRowDetailAndPlan() {
        assertEquals("task-abc", SharedMotion.taskKey("abc"))
        assertEquals(1200, SharedMotion.LANDED_MS)
    }

    @Test
    fun detailPaneTakesNoWidthClosedAndItsShareOpen() {
        assertEquals(0f, SharedMotion.detailFraction(false))
        assertEquals(0.45f, SharedMotion.detailFraction(true))
    }

    @Test
    fun firstShowIsAlreadyInPlaceButUnfoldGrowsFromNothing() {
        assertEquals(0.45f, SharedMotion.startFraction(null, true))
        assertEquals(0f, SharedMotion.startFraction(null, false))
        assertEquals(0f, SharedMotion.startFraction(false, true), "unfolding starts from the closed layout")
        assertEquals(0.45f, SharedMotion.startFraction(true, false), "folding starts from the open layout")
    }

    @Test
    fun detailFadesInAsItGrows() {
        assertEquals(0f, SharedMotion.detailAlpha(0f))
        assertEquals(0.5f, SharedMotion.detailAlpha(SharedMotion.DETAIL_FRACTION / 2))
        assertEquals(1f, SharedMotion.detailAlpha(0.45f))
        assertEquals(1f, SharedMotion.detailAlpha(0.6f), "spring overshoot never goes past full strength")
    }

    @Test
    fun rowStepsAsideOnlyWhileItsTwinIsOnScreen() {
        // Closed Fold, this task open in the detail pane: the detail has the title.
        assertFalse(SharedMotion.rowTitleVisible("a", "a", singlePane = true, planOpen = false, landing = emptySet()))
        // Another task open, or the Fold open (both panes visible at once): the row keeps its title.
        assertTrue(SharedMotion.rowTitleVisible("a", "b", singlePane = true, planOpen = false, landing = emptySet()))
        assertTrue(SharedMotion.rowTitleVisible("a", "a", singlePane = false, planOpen = false, landing = emptySet()))
        // Plan applied, pane still up: the block has the title until the pane leaves.
        assertFalse(SharedMotion.rowTitleVisible("a", null, singlePane = false, planOpen = true, landing = setOf("a")))
        assertTrue(SharedMotion.rowTitleVisible("a", null, singlePane = false, planOpen = false, landing = setOf("a")))
        assertTrue(SharedMotion.rowTitleVisible("b", null, singlePane = false, planOpen = true, landing = setOf("a")))
    }

    @Test
    fun landedTasksAreLitOnlyOnceThePlanHasGone() {
        assertFalse(SharedMotion.highlightLanded("a", planOpen = true, landing = setOf("a")))
        assertTrue(SharedMotion.highlightLanded("a", planOpen = false, landing = setOf("a")))
        assertFalse(SharedMotion.highlightLanded("b", planOpen = false, landing = setOf("a")))
    }

    @Test
    fun cardsAndMoreRowsShareTheirPaneTitle() {
        assertEquals("pane-brief", SharedMotion.paneKey(SharedMotion.BRIEF))
        assertEquals("pane-shutdown", SharedMotion.paneKey(SharedMotion.SHUTDOWN))
        assertEquals("pane-WORK", SharedMotion.paneKey(MoreItem.WORK))
        // The brief and shutdown More rows share their Today card's key: either title travels into the same pane title.
        assertEquals(SharedMotion.paneKey(SharedMotion.BRIEF), SharedMotion.paneKey(MoreItem.BRIEF))
        assertEquals(SharedMotion.paneKey(SharedMotion.SHUTDOWN), SharedMotion.paneKey(MoreItem.SHUTDOWN))
    }

    @Test
    fun aPlaceTitleTravelsOnlyFromWhereItWasOpened() {
        val fromRow = SharedMotion.placeKey(ShellDestination.REVIEW, PlaceVia.MORE)
        val fromCard = SharedMotion.placeKey(ShellDestination.REVIEW, PlaceVia.CARD)
        assertEquals("place-REVIEW-MORE", fromRow)
        assertTrue(fromRow != fromCard, "the review card and Ask's Review row never fly into each other")
        assertEquals(fromCard, SharedMotion.placeTitleKey(ShellDestination.REVIEW, fromCard))
        assertEquals(null, SharedMotion.placeTitleKey(ShellDestination.LISTS, fromCard), "another place's arrival")
        assertEquals(null, SharedMotion.placeTitleKey(ShellDestination.REVIEW, null))
    }

    @Test
    fun aPlainMoveToATabKeepsTheArrivalButStraightToAPlaceForgetsIt() {
        val a = SharedMotion.placeKey(ShellDestination.LISTS, PlaceVia.MORE)
        assertEquals(a, SharedMotion.arrivalAfterGo(ShellDestination.ASK, a), "back to Ask: the title flies back to its row")
        assertEquals(a, SharedMotion.arrivalAfterGo(ShellDestination.TODAY, a))
        assertEquals(null, SharedMotion.arrivalAfterGo(ShellDestination.LISTS, a), "a search result or notification")
    }
}
