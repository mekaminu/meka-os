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

/** Money: a monthly cap on one of his own categories ("Vape £60"). Invented shops and names (rule 13). */
class MoneyOwnCapsTest {
    private val oct16 = CivilDate.toEpochDay(2026, 10, 16)
    private var opN = 0
    private var clockMs = 1_000L
    private fun replica(store: InMemoryReplicaStore = InMemoryReplicaStore()): Replica =
        Replica("hh", "android", HlcClock("android", { clockMs++ }), store, MekaSchema) { "op" + opN++ }

    private val file = "Date,Description,Amount\n" +
        "03/09/2026,CORNER NEWS,-6.00\n" +
        "01/10/2026,CORNER NEWS,-8.00\n05/10/2026,CORNER NEWS,-9.50\n06/10/2026,TESCO,-20.00\n" +
        "07/10/2026,MYSTERY LTD,-5.00\n10/10/2026,SALARY OAKFIELD LTD,1000.00\n"

    private fun money(r: Replica = replica()): Money =
        Money(r) { oct16 * CivilDate.DAY_MS }.also { m -> r.addListener { m.changed(it) }; m.import("Current account", file) }

    private fun view(m: Money): MoneyView = MoneyRules.view(
        m.accounts(), m.txns(), m.rules(), oct16, LocalCalendar.UTC, 0,
        challenges = m.challenges(), own = m.ownCategories(), ownRules = m.ownRules(),
    )

    private fun vape(m: Money): MoneyOwnCategory {
        m.addOwnCategory("Vape", 2, MoneyIcon.CUP, m.txns().first { it.text == "CORNER NEWS" }.id)
        return m.ownCategories().single()
    }

    @Test
    fun aCapOnHisCategoryCountsItsPayeesThisMonth() {
        val m = money()
        val v0 = vape(m)
        val saved = m.capOwnCategory(v0.id, "£20")
        assertFalse(saved.refused)
        assertEquals("Vape capped at £20 a month · on both devices", saved.line)
        assertEquals(mapOf(v0.id to 2_000L), m.challenges().ownCaps)
        val v = view(m)
        val bar = v.challenges!!.caps.single()
        assertEquals("own:${v0.id}", bar.key)
        assertEquals("Vape this month", bar.title)
        assertEquals("£17.50 of £20", bar.amount)
        assertTrue(bar.line.startsWith("£2.50 left"))
        // Within it in September (£6), so a month in a row.
        assertEquals(1, bar.run)
        assertFalse(bar.lit) // 88%, lit from 90%
        // Its row on Accounts carries the cap.
        val row = v.ownRows.single()
        assertNotNull(row.cap)
        assertEquals("1 payee · £17.50 of £20 this month", row.line)
    }

    @Test
    fun typedCapsFindHisCategoriesFirstAndBuiltInOnesStillWork() {
        val m = money()
        val v0 = vape(m)
        assertEquals("Vape capped at £60 a month · on both devices", m.setCap("vape 60 a month").line)
        assertEquals(6_000L, m.challenges().ownCaps[v0.id])
        assertEquals("Groceries capped at £300 a month · on both devices", m.setCap("Groceries £300").line)
        assertEquals(30_000L, m.challenges().caps[MoneyCategory.GROCERIES])
        assertNull(MoneyChallenges.parseOwnCap("Vape", m.ownCategories()))
        assertEquals(v0.id, MoneyChallenges.parseOwnCap("Vapes £40", m.ownCategories())!!.first.id)
        assertNull(MoneyChallenges.parseOwnCap("Groceries £40", m.ownCategories()))
    }

    @Test
    fun removeUndoAndRefusals() {
        val m = money()
        val v0 = vape(m)
        assertEquals(MoneyChallenges.BAD_AMOUNT, m.capOwnCategory(v0.id, "lots").line)
        assertTrue(m.capOwnCategory("k-gone", "£5").refused)
        assertTrue(m.capOwnCategory(v0.id, "").refused) // nothing to take off yet
        val set = m.capOwnCategory(v0.id, "£20")
        assertTrue(m.undoCategory(set.undo!!))
        assertTrue(m.challenges().ownCaps.isEmpty())
        m.capOwnCategory(v0.id, "£20")
        val off = m.removeChallenge("own:${v0.id}")
        assertEquals("Vape cap removed", off.line)
        assertTrue(m.challenges().ownCaps.isEmpty())
        assertTrue(m.undoCategory(off.undo!!))
        assertEquals(2_000L, m.challenges().ownCaps[v0.id])
        // A deleted category's cap waits unseen; Delete all takes it away.
        m.deleteOwnCategory(v0.id)
        assertTrue(view(m).challenges!!.caps.isEmpty())
        m.deleteAll()
        assertTrue(m.challenges().ownCaps.isEmpty())
    }

    @Test
    fun goalsStreaksNameHisCategoryAndItSyncs() {
        val store = InMemoryReplicaStore()
        val m = money(replica(store))
        val v0 = vape(m)
        m.capOwnCategory(v0.id, "£20")
        val lines = MoneyRules.askLines(m.accounts(), m.txns(), m.rules()).map { l ->
            MoneyChallengeLine(l.day, l.pence, l.category, m.ownRules()[l.merchant]?.takeIf { l.category == MoneyCategory.OTHER })
        }
        val s = MoneyChallenges.streaks(lines, m.challenges(), oct16, m.ownCategories())!!
        assertEquals("Vape cap", s.rows.single().title)
        assertEquals("Within it last month · on track this month", s.rows.single().line)
        val b = Replica("hh", "mac", HlcClock("mac", { clockMs++ }), InMemoryReplicaStore(), MekaSchema) { "mop" + opN++ }
        b.applyRemoteBatch(store.pendingPush(10_000))
        assertEquals(2_000L, Money(b) { oct16 * CivilDate.DAY_MS }.challenges().ownCaps[v0.id])
    }
}
