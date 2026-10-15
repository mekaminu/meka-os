package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.GoalHorizon
import os.meka.core.domain.ReflectionRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Weekly reflection through the facade (slice 1): Sunday evening's look back, its wins and goal, synced, the journal. */
class ReflectionFacadeTest {
    private var now = 1_792_346_400_000L // Sun 18 Oct 2026, 19:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun sundaysReflectionKeepsWinsPicksTheGoalAndReachesTheMacsJournal() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val task = fold.addTask("Ring the garage")
        fold.complete(task)
        val goal = fold.addGoal("Run 5k", null, GoalHorizon.MONTH)
        fold.tick()

        val open = fold.reflectionView.value
        assertTrue(open.asking)
        assertFalse(open.saved)
        assertEquals(ReflectionRules.TITLE_THIS_WEEK, open.title)
        assertTrue(open.facts.startsWith("1 done"), open.facts)
        assertTrue(open.wentWellHints.any { it.text == "Ring the garage" })
        assertEquals(goal, open.mattersHints.first().goalId)

        // Nothing typed: refused, nothing written.
        val refused = fold.saveReflection(" ", "", "\n", null, keepWins = true)
        assertTrue(refused.refused)
        assertEquals(ReflectionRules.EMPTY_REFUSED, refused.line)

        val saved = fold.saveReflection("Rang the garage\n- got the gym in twice", "Late-night email", "Run 5k twice", goal, keepWins = true)
        assertFalse(saved.refused)
        assertEquals("Kept in your reflections · 2 added to your wins · “Run 5k” is one of next week's goals", saved.line)
        val after = fold.reflectionView.value
        assertTrue(after.saved)
        assertFalse(after.asking)
        assertEquals("Rang the garage\nGot the gym in twice", after.wentWell)
        assertTrue(fold.reviewView.value.reviewed)
        assertFalse(fold.reviewView.value.card.offered)
        assertEquals(listOf("Rang the garage", "Got the gym in twice"), fold.winsView.value.week.map { it.text })
        assertEquals(fold.reviewView.value.weekStart + 7, fold.goalsView.value.goals.single { it.id == goal }.weekOf)

        // Saving again (an edit) replaces the answers; the same line is kept in Wins once.
        assertEquals("Kept in your reflections", fold.saveReflection("Rang the garage", "", "", null, keepWins = true).line)
        assertEquals(2, fold.winsView.value.week.size)

        fold.syncNow(); mac.syncNow()
        assertTrue(mac.reflectionView.value.saved)
        assertEquals("Rang the garage", mac.reflectionView.value.wentWell)
        assertEquals("", mac.reflectionView.value.drop)

        // Last week on the Mac: this week's reflection is in the journal; removing it and Undo.
        mac.showReviewWeek(-1)
        val last = mac.reflectionView.value
        assertEquals(ReflectionRules.TITLE_PAST_WEEK, last.title)
        assertEquals("Your reflections · 1 week", last.journalTitle)
        val ws = last.journal.single().weekStart
        assertTrue(mac.removeReflection(ws, true))
        assertTrue(mac.reflectionView.value.journal.isEmpty())
        assertTrue(mac.removeReflection(ws, false))
        assertEquals("Week of 12 Oct", mac.reflectionView.value.journal.single().label)
    }

    @Test
    fun skippingPutsItAwayForTheWeekAndReflectNowBringsItBack() = runTest {
        val fold = core("android")
        fold.tick()
        assertTrue(fold.reflectionView.value.asking)
        assertEquals(ReflectionRules.QUIET_WEEK, fold.reflectionView.value.facts)
        fold.skipReflection(true)
        assertTrue(fold.reflectionView.value.skipped)
        assertFalse(fold.reflectionView.value.asking)
        assertEquals(ReflectionRules.SKIPPED_LINE, fold.reflectionView.value.line)
        fold.skipReflection(false)
        assertTrue(fold.reflectionView.value.asking)
    }

    @Test
    fun theOneThingShowsOnTodayAndInTheBriefTheNextWeek() = runTest {
        val fold = core("android")
        fold.tick()
        fold.saveReflection("Rang the garage", "", "Run 5k twice", null, keepWins = false)
        // Sunday evening: next week's one thing doesn't show yet.
        assertEquals(null, fold.today.value.reflection)
        now += 14 * 3_600_000L // Mon 19 Oct, 09:00
        fold.tick()
        val line = fold.today.value.reflection!!
        assertEquals("This week's one thing · Run 5k twice", line.text)
        assertTrue(line.lit)
        assertEquals("The one thing that matters this week: Run 5k twice.", fold.briefView.value.story.weekMatters)
    }

    @Test
    fun reflectOnMyWeekOpensItMidweekAndUndoesASkip() = runTest {
        val fold = core("android")
        now += 3 * 24 * 3_600_000L // Wed 21 Oct, 19:00
        fold.tick()
        assertFalse(fold.reflectionView.value.asking)
        fold.skipReflection(true)
        fold.openReflection()
        val v = fold.reflectionView.value
        assertTrue(v.asking)
        assertFalse(v.skipped)
        assertEquals(fold.reviewView.value.weekStart, v.weekStart)
        assertTrue(fold.reviewView.value.isCurrent)
        fold.saveReflection("A calm week", "", "", null, keepWins = false)
        assertFalse(fold.reflectionView.value.asking)
    }
}
