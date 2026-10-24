package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The orb says hello like a person, slice 4: fresh openers Obi writes, checked against the same rules. */
class HelloFreshTest {
    private val f = HelloFreshRules
    private val day = 20_747L // a Wednesday
    private val evening = 19 * 60
    private val hour = 60 * 60_000L

    private fun raw(part: String, open: String, ask: String = "What can I do for you?") = HelloFreshRaw(part, open, ask)

    @Test
    fun theHandWrittenPlainOpenersWouldAllPass() {
        mapOf(
            HelloPart.MORNING to HelloOpeners.MORNING, HelloPart.AFTERNOON to HelloOpeners.AFTERNOON,
            HelloPart.EVENING to HelloOpeners.EVENING, HelloPart.LATE to HelloOpeners.LATE,
        ).forEach { (part, pool) -> pool.forEach { assertNull(f.problem(it.open, it.ask, part), "${it.open} ${it.ask}") } }
    }

    @Test
    fun aLineThatBreaksAnyRuleIsNeverKept() {
        val e = HelloPart.EVENING
        assertEquals("name", f.problem("Evening, there. Good day?", "What's up?", e))
        assertEquals("name", f.problem("Evening, {n}. Good day, {n}?", "What's up?", e))
        assertEquals("name", f.problem("{n}, evening.", "What's up?", e))
        assertEquals("name", f.problem("Evening, {n}.", "What's up, {n}?", e))
        assertEquals("ask", f.problem("Evening, {n}.", "Tell me what you need.", e))
        assertEquals("length", f.problem("Evening, {n}. " + "Very ".repeat(12) + "good.", "What's up?", e))
        assertEquals("chars", f.problem("Evening, {n}. 3 things?", "What's up?", e))
        assertEquals("chars", f.problem("Evening, {n}. See https://x?", "What's up?", e))
        assertEquals("chars", f.problem("Evening, {n} 😀.", "What's up?", e))
        assertEquals("capital", f.problem("Evening, {n}. Seen Sam lately?", "What's up?", e))
        assertEquals("blocked", f.problem("Evening, {n}. How's the wife?", "What's up?", e))
        assertEquals("blocked", f.problem("Evening, {n}. Payday soon, money in the bank?", "What's up?", e))
        assertEquals("capital", f.problem("Evening, {n}. It's Obi.", "What's up?", e))
        assertEquals("capital", f.problem("Evening, {n}. Happy Friday.", "What's up?", e))
        assertEquals("blocked", f.problem("Evening, {n}. Good weekend?", "What's up?", e))
        assertEquals("blocked", f.problem("Evening, {n}. ada here.", "What's up?", e))
        assertEquals("part", f.problem("Good morning, {n}.", "What's first?", e))
        assertEquals("part", f.problem("Evening, {n}.", "What's up?", HelloPart.MORNING))
        assertNull(f.problem("Evening, {n}. I'm all ears.", "What's on your mind?", e))
    }

    @Test
    fun checkedLinesGetTheirOwnIdsAndNoCopies() {
        val got = f.check(
            listOf(
                raw("evening", "Hey {n}, settling in for the night?"),
                raw("evening", "Hey {n}, settling in for the night!"), // the same words
                raw("evening", "Evening, {n}. Good day?"), // a hand-written one
                raw("evening", "Good evening, {n}. Seen Sam?"), // a name
                raw("late", "Hey {n}, the owls are out."),
                raw("noon", "Hi {n}, lovely to hear you."),
            ),
            gen = 3,
        )
        assertEquals(listOf("Hey {n}, settling in for the night?"), got[HelloPart.EVENING]!!.map { it.open })
        assertEquals(listOf("xe30"), got[HelloPart.EVENING]!!.map { it.id })
        assertEquals(listOf("xl30"), got[HelloPart.LATE]!!.map { it.id })
        assertNull(got[HelloPart.MORNING])
        // The ids travel through the shared recent list like any other.
        assertEquals(listOf("xe30", "xl30"), HelloOpeners.decodeIds(HelloOpeners.encodeIds(listOf("xe30", "xl30"))))
        assertTrue(got.values.flatten().none { o -> HelloOpeners.ALL.any { it.id == o.id } })
        // At most six a part.
        val many = (1..9).map { raw("morning", "Hi {n}, number ${"abcdefghi"[it - 1]} here.") }
        assertEquals(f.PER_PART, f.check(many, 1)[HelloPart.MORNING]!!.size)
    }

    @Test
    fun askedOnceAWeekAndNeverTwiceInADay() {
        val now = 1_000L * hour
        assertTrue(f.due(HelloFreshSet(), now))
        assertFalse(f.due(HelloFreshSet(triedMs = now - 2 * hour), now))
        val kept = f.took(HelloFreshSet(), listOf(raw("evening", "Hey {n}, settling in for the night?")), now)
        assertEquals(1, kept.gen)
        assertEquals(1, kept.size)
        assertFalse(f.due(kept, now + 3 * 24 * hour))
        assertTrue(f.due(kept, now + 8 * 24 * hour))
        // Nothing passing keeps the old batch but counts the try.
        val failed = f.took(kept, listOf(raw("evening", "Seen Sam?")), now + 8 * 24 * hour)
        assertEquals(kept.byPart, failed.byPart)
        assertFalse(f.due(failed, now + 8 * 24 * hour + hour))
    }

    @Test
    fun keptAndReadBackCheckedAgain() {
        val set = f.took(
            HelloFreshSet(gen = 4),
            listOf(raw("evening", "Hey {n}, settling in for the night?"), raw("morning", "Morning, {n}. Up with the lark?", "What's first?")),
            7_000L,
        )
        val back = f.decode(f.encode(set))
        assertEquals(set, back)
        assertEquals(HelloFreshSet(), f.decode(null))
        assertEquals(HelloFreshSet(), f.decode("rubbish"))
        // A line tampered with in the store is dropped on the way back.
        assertEquals(0, f.decode("1;5;5\nevening|Hi Sam {n}, hello.|What?").size)
    }

    @Test
    fun freshOnesJoinThePlainPoolOnlyAndNeverRepeatWithinTen() {
        val set = f.took(
            HelloFreshSet(),
            (0 until 6).map { raw("evening", "Hey {n}, fresh line ${"abcdef"[it]} for you.", "Anything on your mind?") },
            1L,
        )
        val plain = HelloOpeners.moment(evening, day, 0L, HelloOpenerMemory(), introduced = true, welcome = false, fresh = set)
        assertEquals(6, HelloOpeners.candidates(plain).count { it.id.startsWith("x") })
        val first = HelloOpeners.moment(evening, day, 0L, HelloOpenerMemory(), introduced = false, welcome = false, fresh = set)
        assertTrue(HelloOpeners.candidates(first).none { it.id.startsWith("x") })
        val morning = HelloOpeners.moment(9 * 60, day, 0L, HelloOpenerMemory(), introduced = true, welcome = false, fresh = set)
        assertTrue(HelloOpeners.candidates(morning).none { it.id.startsWith("x") })
        // Over many taps the fresh ones are said, and none twice within ten.
        var memory = HelloOpenerMemory()
        val said = mutableListOf<String>()
        repeat(60) { i ->
            val m = HelloOpeners.moment(evening, day + i / 6, i * hour, memory, introduced = true, welcome = false, fresh = set)
            val o = HelloOpeners.pick(m, memory.recent, day + i / 6)
            assertFalse(o.id in memory.recent, o.id)
            said += o.id
            memory = HelloOpeners.said(memory, o, i * hour)
        }
        assertNotNull(said.firstOrNull { it.startsWith("x") })
        val o = set.of(HelloPart.EVENING).first()
        assertTrue("Alex" in o.openFor("Alex"))
    }
}
