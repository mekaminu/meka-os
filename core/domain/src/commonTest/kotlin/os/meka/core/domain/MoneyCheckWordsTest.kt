package os.meka.core.domain

import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Obi's wording of the money check: only ids and numbers the rules gave him, his order, kept on both devices. Invented data. */
class MoneyCheckWordsTest {
    private val oct16 = CivilDate.toEpochDay(2026, 10, 16)
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    private fun line(day: Long, pence: Long, merchant: String, c: MoneyCategory) = MoneyLine(day, pence, merchant, c)

    private val interest = listOf(
        line(d(8, 20), -6_500, "AMEX INTEREST", MoneyCategory.FEES),
        line(d(9, 20), -7_000, "AMEX INTEREST", MoneyCategory.FEES),
        line(d(10, 2), -1_200, "LATE PAYMENT CHARGE", MoneyCategory.FEES),
    )
    private val coffees = (0 until 12).map { line(oct16 - it, -400, "COSTA COFFEE", MoneyCategory.COFFEE) }
    private val lines = interest + coffees

    private fun view(words: MoneyCheckWordsState = MoneyCheckWordsState()) =
        assertNotNull(MoneyCheck.view(lines, oct16, MoneyTips.regular(lines, oct16), MoneyIdeaState(), words = words))

    @Test
    fun onlyTheIdeasOwnNumbersPass() {
        val i = view().ideas.first { it.id == "interest" }
        // The rules said £147, ~£600/yr and the payments' £12, £70 and £65.
        assertNotNull(MoneyCheckWords.check(i, "Card interest is your biggest leak", "You paid £147 in 3 months, about £600 a year. Clearing the cards stops it."))
        assertNotNull(MoneyCheckWords.check(i, "Card interest", "£147.00 went on interest and fees; that's ~£600 a year."))
        // A number he never sent (made up, or stale after new payments) falls back.
        assertNull(MoneyCheckWords.check(i, "Card interest", "You paid £180 in interest in 3 months, so clear the cards."))
        assertNull(MoneyCheckWords.check(i, "Card interest at 24.9%", "£147 in 3 months. Clear the cards first."))
        // Links, markup, too short or too long.
        assertNull(MoneyCheckWords.check(i, "Card interest", "See https://example.com for a better card deal."))
        assertNull(MoneyCheckWords.check(i, "Hi", "£147 in three months of interest."))
        assertNull(MoneyCheckWords.check(i, "Card interest", "x".repeat(MoneyCheckWords.MAX_WHY + 1)))
        // Whitespace is one line.
        assertEquals("Card interest" to "£147 in 3 months.", MoneyCheckWords.check(i, " Card\ninterest ", "£147 in  3 months."))
    }

    @Test
    fun hisOrderAndWordsShowWithTheDayAndNewIdeasLead() {
        val plain = view()
        assertEquals(listOf("interest", "habit-coffee"), plain.ideas.map { it.id })
        assertNull(plain.by)
        val words = MoneyCheckWordsState(
            mapOf("habit-coffee" to MoneyIdeaWords("Coffee most days", "12 coffees in 30 days; halving saves ~£290/yr.", 0)),
            atMs = 1L, day = d(10, 11),
        )
        val v = view(words)
        // interest isn't in his last run (new since), so it leads; coffee shows in his words.
        assertEquals(listOf("interest", "habit-coffee"), v.ideas.map { it.id })
        val coffee = v.ideas[1]
        assertEquals("Coffee most days", coffee.title)
        assertTrue(coffee.byObi)
        assertEquals("Coffee 12× in 30 days", coffee.ruleTitle)
        assertEquals("Coffee most days, ~£290/yr. 12 coffees in 30 days; halving saves ~£290/yr.", coffee.spoken)
        assertEquals("Written by Obi · Sun 11 Oct", v.by)
        assertEquals(plain.total, v.total)
        // Both ranked: his order wins over the rules' biggest-first.
        val both = view(words.copy(words = words.words + ("interest" to MoneyIdeaWords("Card interest", "£147 in 3 months.", 1))))
        assertEquals(listOf("habit-coffee", "interest"), both.ideas.map { it.id })
        // Words that no longer pass keep the rules' words (and no "Written by" when none pass).
        val stale = view(MoneyCheckWordsState(mapOf("habit-coffee" to MoneyIdeaWords("Coffee", "15 coffees cost £60.", 0)), 1L, d(10, 11)))
        assertEquals("Coffee 12× in 30 days", stale.ideas.first { it.id == "habit-coffee" }.title)
        assertNull(stale.by)
    }

    @Test
    fun weeklyUnlessAsked() {
        assertTrue(MoneyCheckWords.due(MoneyCheckWordsState(), 0))
        val s = MoneyCheckWordsState(atMs = 1_000)
        assertFalse(MoneyCheckWords.due(s, 1_000 + MoneyCheckWords.EVERY_MS - 1))
        assertTrue(MoneyCheckWords.due(s, 1_000 + MoneyCheckWords.EVERY_MS))
    }

    @Test
    fun hisWordsSyncAndAnOlderRunsWordsGo() {
        var now = 5_000L
        var opN = 0
        var clock = 1L
        val replica = Replica("hh", "android", HlcClock("android", { clock++ }), InMemoryReplicaStore(), MekaSchema) { "op" + opN++ }
        val money = Money(replica) { now }
        money.saveCheckWords(listOf("subs" to ("Subscriptions" to "Worth a look."), "interest" to ("Card interest" to "£147 in 3 months.")), now, d(10, 11))
        val s = money.checkWords()
        assertEquals(5_000L, s.atMs)
        assertEquals(d(10, 11), s.day)
        assertEquals(0, s.words.getValue("subs").rank)
        assertEquals(1, s.words.getValue("interest").rank)
        // Words don't put an idea away.
        assertEquals(MoneyIdeaState(), money.ideaState())
        assertTrue(money.hiddenTips().isEmpty())
        now = 9_000
        money.saveCheckWords(listOf("interest" to ("Interest" to "£147 in 3 months.")), now, d(10, 18))
        val again = money.checkWords()
        assertEquals(setOf("interest"), again.words.keys)
        assertEquals(0, again.words.getValue("interest").rank)
        // Delete all money data takes them too.
        money.deleteAll()
        assertEquals(MoneyCheckWordsState(), money.checkWords())
    }
}
