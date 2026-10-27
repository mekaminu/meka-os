package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money: his own categories (Meka 2026-10-26 23:18, "News ON The → a Vape category"). Invented shops and names (rule 13). */
class MoneyOwnCategoriesTest {
    private val oct16 = CivilDate.toEpochDay(2026, 10, 16)
    private var opN = 0
    private var clockMs = 1_000L
    private fun replica(): Replica = Replica("hh", "android", HlcClock("android", { clockMs++ }), InMemoryReplicaStore(), MekaSchema) { "op" + opN++ }

    private val file = "Date,Description,Amount\n" +
        "01/10/2026,CORNER NEWS,-8.00\n05/10/2026,CORNER NEWS,-9.50\n06/10/2026,TESCO,-20.00\n" +
        "07/10/2026,MYSTERY LTD,-5.00\n10/10/2026,SALARY OAKFIELD LTD,1000.00\n"

    private fun money(r: Replica = replica()): Money = Money(r) { oct16 * CivilDate.DAY_MS }.also { m -> r.addListener { m.changed(it) }; m.import("Current account", file) }

    private fun view(m: Money): MoneyView =
        MoneyRules.view(m.accounts(), m.txns(), m.rules(), oct16, LocalCalendar.UTC, 0, own = m.ownCategories(), ownRules = m.ownRules())

    @Test
    fun namesAreTidiedAndBuiltInOnesRefused() {
        assertEquals("Vape", MoneyOwnCategories.clean("  Vape  "))
        assertEquals("Nights out", MoneyOwnCategories.clean("Nights   out"))
        assertNull(MoneyOwnCategories.clean("   "))
        assertEquals(MoneyOwnCategories.MAX_NAME, MoneyOwnCategories.clean("x".repeat(40))!!.length)
        assertEquals(MoneyOwnCategories.idFor("vape"), MoneyOwnCategories.idFor(" Vape "))
        assertEquals(MoneyCategory.GROCERIES, MoneyOwnCategories.builtIn("groceries"))
        assertNull(MoneyOwnCategories.builtIn("Vape"))
        assertEquals(0, MoneyOwnCategories.nextTone(emptyList()))
        assertEquals(1, MoneyOwnCategories.nextTone(listOf(MoneyOwnCategory("k1", "A", 0, MoneyIcon.BAG))))
        assertEquals(7, MoneyOwnCategories.TONES.size)
    }

    @Test
    fun aNewCategoryTakesThePayeePastAndFutureAndShowsOnSpending() {
        val m = money()
        val news = m.txns().first { it.text == "CORNER NEWS" }
        val saved = m.addOwnCategory("Vape", 3, MoneyIcon.CUP, news.id)
        assertFalse(saved.refused)
        assertEquals("Made Vape · moved 2 payments from Corner News", saved.line)
        val own = m.ownCategories().single()
        assertEquals("Vape", own.name)
        assertEquals(3, own.tone)
        assertEquals(MoneyIcon.CUP, own.icon)
        // Underneath it's Other, so every older reckoning is unchanged; the rule says which of his it is.
        assertEquals(MoneyCategory.OTHER, m.rules()["CORNER NEWS"])
        assertEquals(own.id, m.ownRules()["CORNER NEWS"])
        val v = view(m)
        val sp = v.month!!.spending!!
        val row = sp.rows.first { it.key == own.key }
        assertEquals("Vape", row.label)
        assertEquals("£17.50", row.amount)
        assertEquals(3, row.tone)
        assertEquals(MoneyIcon.CUP, row.icon)
        // Other keeps only the mystery payment; the month's total is the same.
        assertEquals("£5", sp.rows.first { it.key == "other" }.amount)
        assertTrue(v.month!!.spentLine.startsWith("Spent £42.50"))
        assertTrue(sp.slices.any { it.key == own.key && it.tone == 3 })
        // No other slice wears his colour.
        assertEquals(1, sp.slices.count { it.tone == 3 })
        // The payments say Vape and their chip shows its icon.
        val txn = v.month!!.txns.first { it.merchant == "Corner News" }
        assertEquals(own.key, txn.category)
        assertEquals("Vape", txn.categoryLabel)
        assertTrue(txn.taught)
        assertEquals(MoneyIcon.CUP, MoneySpending.txnIcon(sp, txn.category))
        // The sheet offers his category first, lit for that payment.
        assertEquals(own.key, v.categoryOrder.first())
        val chips = MoneyPaymentSheet.chips(v.categoryOrder, txn.category, v.ownCategories)
        assertEquals("Vape", chips.first().label)
        assertTrue(chips.first().on)
        assertEquals(own, chips.first().own)
        // A future payment from the same payee lands there too.
        m.import("Current account", file + "15/10/2026,CORNER NEWS,-4.00\n")
        assertEquals("£21.50", view(m).month!!.spending!!.rows.first { it.key == own.key }.amount)
    }

    @Test
    fun undoPutsThePayeeBackAndTakesTheNewCategoryAway() {
        val m = money()
        val news = m.txns().first { it.text == "CORNER NEWS" }
        m.setCategory(news.id, "shopping")
        val saved = m.addOwnCategory("Vape", 0, MoneyIcon.BAG, news.id)
        assertTrue(m.undoCategory(saved.undo!!))
        assertTrue(m.ownCategories().isEmpty())
        assertEquals(MoneyCategory.SHOPPING, m.rules()["CORNER NEWS"])
        assertTrue(m.ownRules().isEmpty())
        // Made again by name: it comes back.
        val again = m.addOwnCategory("vape", 2, MoneyIcon.BAG, null)
        assertEquals("Made vape", again.line)
        assertEquals(1, m.ownCategories().size)
    }

    @Test
    fun anExistingNameIsReusedAndPickingItFromTheSheetMovesThePayee() {
        val m = money()
        m.addOwnCategory("Vape", 1, MoneyIcon.BAG, null)
        val tesco = m.txns().first { it.text == "TESCO" }
        // The same name again is the same category, not a second one.
        val same = m.addOwnCategory(" VAPE ", 5, MoneyIcon.GIFT, tesco.id)
        assertEquals("Tesco is Vape from now on", same.line)
        assertEquals(1, m.ownCategories().size)
        assertEquals(1, m.ownCategories().single().tone)
        // Undo of a reuse leaves the category.
        assertTrue(m.undoCategory(same.undo!!))
        assertEquals(1, m.ownCategories().size)
        assertEquals(MoneyCategory.GROCERIES, MoneyRules.categoryOf(tesco, m.rules()))
        // Picking it as a chip works like any category, with Undo.
        val key = m.ownCategories().single().key
        val pick = m.setCategory(tesco.id, key)
        assertEquals("Tesco is Vape from now on", pick.line)
        assertEquals(m.ownCategories().single().id, m.ownRules()["TESCO"])
        assertTrue(m.undoCategory(pick.undo!!))
        assertNull(m.ownRules()["TESCO"])
        // A key that isn't his is refused.
        assertTrue(m.setCategory(tesco.id, "own:knope").refused)
    }

    @Test
    fun refusalsAndDeleteAll() {
        val m = money()
        assertEquals(MoneyOwnCategories.NO_NAME, m.addOwnCategory("  ", 0, MoneyIcon.BAG, null).line)
        val dup = m.addOwnCategory("Groceries", 0, MoneyIcon.BAG, null)
        assertTrue(dup.refused)
        assertEquals("Groceries is already a category · pick it above", dup.line)
        assertTrue(m.addOwnCategory("Vape", 0, MoneyIcon.BAG, "t-gone").refused)
        assertTrue(m.ownCategories().isEmpty())
        m.addOwnCategory("Vape", 0, MoneyIcon.BAG, m.txns().first { it.text == "CORNER NEWS" }.id)
        m.deleteAll()
        assertTrue(m.ownCategories().isEmpty())
        assertTrue(m.ownRules().isEmpty())
    }

    @Test
    fun syncsToTheOtherDevice() {
        val store = InMemoryReplicaStore()
        val a = Replica("hh", "android", HlcClock("android", { clockMs++ }), store, MekaSchema) { "op" + opN++ }
        val m = money(a)
        m.addOwnCategory("Vape", 4, MoneyIcon.HEART, m.txns().first { it.text == "CORNER NEWS" }.id)
        val b = Replica("hh", "mac", HlcClock("mac", { clockMs++ }), InMemoryReplicaStore(), MekaSchema) { "mop" + opN++ }
        b.applyRemoteBatch(store.pendingPush(10_000))
        val onMac = Money(b) { oct16 * CivilDate.DAY_MS }
        assertEquals(listOf("Vape"), onMac.ownCategories().map { it.name })
        assertNotNull(onMac.ownRules()["CORNER NEWS"])
    }

    @Test
    fun aRemovedCategoryLeavesItsPayeesInOther() {
        val r = replica()
        val m = money(r)
        val news = m.txns().first { it.text == "CORNER NEWS" }
        m.addOwnCategory("Vape", 0, MoneyIcon.BAG, news.id)
        // Taken away (as the Categories screen will): the rule then counts as none, and Corner News is Other by its words.
        r.commitLocal(EntityTypes.MONEY_CATEGORY, m.ownCategories().single().id, mapOf(MoneyOwnFields.DELETED to FieldValue.Bool(true)))
        assertTrue(m.ownCategories().isEmpty())
        assertNull(m.rules()["CORNER NEWS"])
        assertTrue(m.ownRules().isEmpty())
        val v = view(m)
        assertEquals("other", v.month!!.txns.first { it.merchant == "Corner News" }.category)
    }

    @Test
    fun renameKeepsItsPayeesAndRefusesTakenNames() {
        val m = money()
        val news = m.txns().first { it.text == "CORNER NEWS" }
        m.addOwnCategory("Vape", 0, MoneyIcon.BAG, news.id)
        m.addOwnCategory("Treats", 1, MoneyIcon.CUP, null)
        val vape = m.ownCategories().first { it.name == "Vape" }
        assertEquals("You already have Treats", m.editOwnCategory(vape.id, " treats ", 2, MoneyIcon.GIFT).line)
        assertTrue(m.editOwnCategory(vape.id, "Groceries", 2, MoneyIcon.GIFT).refused)
        assertTrue(m.editOwnCategory(vape.id, " ", 2, MoneyIcon.GIFT).refused)
        val saved = m.editOwnCategory(vape.id, "Smokes", 5, MoneyIcon.GIFT)
        assertEquals("Renamed Vape to Smokes", saved.line)
        val now = m.ownCategories().first { it.id == vape.id }
        assertEquals("Smokes" to 5, now.name to now.tone)
        assertEquals(MoneyIcon.GIFT, now.icon)
        assertEquals(vape.id, m.ownRules()["CORNER NEWS"])
        // Making "Smokes" again from a sheet finds the renamed one, not a second.
        m.addOwnCategory("smokes", 3, MoneyIcon.BAG, m.txns().first { it.text == "MYSTERY LTD" }.id)
        assertEquals(2, m.ownCategories().size)
        assertEquals(listOf(vape.id, vape.id), m.ownRules().values.toList())
        // Undo puts the old name, colour and icon back.
        assertTrue(m.undoCategory(saved.undo!!))
        val back = m.ownCategories().first { it.id == vape.id }
        assertEquals(MoneyOwnCategory(vape.id, "Vape", 0, MoneyIcon.BAG, vape.createdAtMs), back)
        val rows = MoneyOwnCategories.rows(m.ownCategories(), m.ownRules())
        assertEquals(listOf("Treats" to "No payees yet", "Vape" to "2 payees"), rows.map { it.name to it.line })
    }

    @Test
    fun aHiddenCategoryIsQuietInTheRing() {
        val m = money()
        val news = m.txns().first { it.text == "CORNER NEWS" }
        m.addOwnCategory("Vape", 3, MoneyIcon.CUP, news.id)
        val id = m.ownCategories().single().id
        val shown = view(m).month!!.spending!!
        assertEquals(3, shown.rows.first { it.label == "Vape" }.tone)
        val hid = m.hideOwnCategory(id, true)
        assertEquals("Vape is hidden from the ring", hid.line)
        assertTrue(m.ownCategories().single().hidden)
        val quiet = view(m).month!!.spending!!
        assertEquals(MoneySpending.REST, quiet.rows.first { it.label == "Vape" }.tone)
        assertEquals("1 payee · hidden from the ring", view(m).ownRows.single().line)
        assertTrue(m.undoCategory(hid.undo!!))
        assertFalse(m.ownCategories().single().hidden)
    }

    @Test
    fun mergeAndDeleteMoveThePayeesAndUndoBringsThemBack() {
        val m = money()
        val news = m.txns().first { it.text == "CORNER NEWS" }
        m.addOwnCategory("Vape", 0, MoneyIcon.BAG, news.id)
        m.addOwnCategory("Treats", 1, MoneyIcon.CUP, m.txns().first { it.text == "MYSTERY LTD" }.id)
        val vape = m.ownCategories().first { it.name == "Vape" }
        val treats = m.ownCategories().first { it.name == "Treats" }
        // Targets: his others first, then the built-in spending ones, never Other, Income or itself.
        val targets = MoneyOwnCategories.mergeTargets(m.ownCategories(), vape.id)
        assertEquals(treats.key, targets.first().key)
        assertTrue(targets.none { it.key == vape.key || it.key == "other" || it.key == "income" })
        val merged = m.mergeOwnCategory(vape.id, treats.key)
        assertEquals("Merged Vape into Treats · 1 payee moved", merged.line)
        assertEquals(listOf("Treats"), m.ownCategories().map { it.name })
        assertEquals(treats.id, m.ownRules()["CORNER NEWS"])
        assertTrue(m.undoCategory(merged.undo!!))
        assertEquals(vape.id, m.ownRules()["CORNER NEWS"])
        // Into a built-in category.
        val toShop = m.mergeOwnCategory(vape.id, "shopping")
        assertEquals("Merged Vape into Shopping · 1 payee moved", toShop.line)
        assertEquals(MoneyCategory.SHOPPING, m.rules()["CORNER NEWS"])
        assertTrue(m.undoCategory(toShop.undo!!))
        // Delete: payees to Other, past and future, with Undo.
        assertEquals("Delete Vape? Its payee goes to Other.", MoneyOwnCategories.deleteAsk("Vape", 1))
        val gone = m.deleteOwnCategory(vape.id)
        assertEquals("Deleted Vape · its payee is in Other", gone.line)
        assertEquals(MoneyCategory.OTHER, m.rules()["CORNER NEWS"])
        assertNull(m.ownRules()["CORNER NEWS"])
        assertEquals("Other", view(m).month!!.txns.first { it.merchant == "Corner News" }.categoryLabel)
        assertTrue(m.undoCategory(gone.undo!!))
        assertEquals(vape.id, m.ownRules()["CORNER NEWS"])
        // Refusals: gone, into itself, into Income.
        assertTrue(m.mergeOwnCategory("knope", "shopping").refused)
        assertTrue(m.mergeOwnCategory(vape.id, vape.key).refused)
        assertTrue(m.mergeOwnCategory(vape.id, "income").refused)
    }
}
