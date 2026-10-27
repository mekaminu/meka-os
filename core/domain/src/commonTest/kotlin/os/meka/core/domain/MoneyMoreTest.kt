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

/** Money review, Accounts' rest: renaming an account, the MORE card's rows and Export all payments. Invented banks (rule 13). */
class MoneyMoreTest {
    private val oct16 = CivilDate.toEpochDay(2026, 10, 16)

    private fun money(): Money {
        var clockMs = 1L
        var opN = 0
        val replica = Replica("hh", "android", HlcClock("android", { clockMs++ }), InMemoryReplicaStore(), MekaSchema) { "op" + opN++ }
        return Money(replica) { 1L }
    }

    private val csv = "Date,Description,Amount\n15/09/2026,TESCO STORES,-10.00\n16/09/2026,\"ACME, LTD SALARY\",2500.00\n"

    @Test
    fun renamingAnAccountShowsTheNewNameAndUndoPutsItBack() {
        val m = money()
        assertFalse(m.import("Oakbank Current Account", csv).refused)
        val id = m.accounts().single().id
        val said = m.renameAccount(id, "  Joint   bills ")
        assertFalse(said.refused)
        assertEquals("Now called Joint bills", said.line)
        assertEquals("Joint bills", m.accounts().single().nick)
        // The stored name stays as it came (transfers are found by the bank's words).
        assertEquals("Oakbank Current Account", m.accounts().single().name)
        val v = MoneyRules.view(m.accounts(), m.txns(), m.rules(), oct16, LocalCalendar.UTC, 0, m.hiddenTips())
        assertEquals("Joint bills", MoneyAccountList.items(v).single().name)
        val sheet = assertNotNull(MoneyAccountList.sheet(v, id))
        assertEquals("Joint bills", sheet.title)
        assertEquals("Oakbank Current Account", sheet.fullName)
        assertEquals("Joint bills", sheet.nick)
        // Undo gives it back its own name.
        assertTrue(m.undoCategory(assertNotNull(said.undo)))
        assertNull(m.accounts().single().nick)
        // Blank clears a name; a number typed in is masked; a gone account is refused.
        m.renameAccount(id, "Card 4929123456789180")
        assertFalse(MoneyAccountNames.revealsNumber(m.accounts().single().nick!!))
        assertEquals("Back to its own name · Oakbank Current Account", m.renameAccount(id, " ").line)
        assertNull(m.accounts().single().nick)
        assertTrue(m.renameAccount("nope", "X").refused)
        assertEquals(MoneyAccountNames.NICK_MAX, MoneyAccountNames.cleanNick("x".repeat(60))!!.length)
    }

    @Test
    fun aRenamedCardKeepsItsNameOnBills() {
        val m = money()
        m.import("Oakbank Credit Card", csv)
        val id = m.accounts().single().id
        m.setCardKind(id, card = true, charge = false)
        m.renameAccount(id, "Travel card")
        assertEquals("Travel card", m.cards().single().name)
    }

    @Test
    fun theMoreCardHasTheSideHustleSharedAndDataRows() {
        val today = 20_000L
        val accounts = listOf(MoneyAccount("a", "Oakbank Current", "csv", 0L))
        val lines = listOf(MoneyBusinessLine("a", today - 3, 2_000_00, "client", MoneyCategory.INCOME))
        val business = MoneyBusiness.view(accounts, lines, setOf("a"), emptyMap(), 20, today)
        val shared = MoneyShared.view(true, emptyList(), emptyMap(), emptyMap(), emptyList(), MoneySharedSetting(), "Alex", today)
        val v = MoneyView(locked = false, summary = "", empty = null, month = null, accounts = emptyList(), earlier = false, later = false,
            business = business, shared = shared)
        val rows = MoneyMore.rows(v)
        assertEquals(listOf(MoneyMoreKind.SIDE_HUSTLE, MoneyMoreKind.SHARED_COSTS, MoneyMoreKind.DATA), rows.map { it.kind })
        assertEquals("Tax year so far · Profit £2,000 · put aside about £400", rows[0].line)
        assertEquals("Shared with Alex", rows[1].title)
        assertEquals(MoneyMore.SHARED_START, rows[1].line)
        assertEquals(MoneyMore.DATA_LINE, rows[2].line)
        assertEquals("Shared with Alex", MoneyMore.title(MoneyMoreKind.SHARED_COSTS, v))
        // Nothing marked yet: the start line; with neither block, only Import, export and delete.
        val fresh = MoneyBusiness.view(accounts, emptyList(), emptySet(), emptyMap(), 20, today)
        assertEquals(MoneyMore.SIDE_HUSTLE_START, MoneyMore.rows(v.copy(business = fresh, shared = null))[0].line)
        assertEquals(listOf(MoneyMoreKind.DATA), MoneyMore.rows(v.copy(business = null, shared = null)).map { it.kind })
        assertNull(MoneyMore.setAsideShort("Nothing to put aside while there's no profit"))
    }

    @Test
    fun exportAllPaymentsIsACsvOfEveryLine() {
        val m = money()
        assertTrue(MoneyExport.result(m.accounts(), m.txns(), m.rules(), oct16).said.refused)
        m.import("Oakbank Current Account", csv)
        m.renameAccount(m.accounts().single().id, "Joint bills")
        val r = MoneyExport.result(m.accounts(), m.txns(), m.rules(), oct16)
        assertEquals("2 payments from 1 account · choose where to save it", r.said.line)
        assertEquals("navilife-payments-2026-10-16.csv", r.fileName)
        val lines = r.csv!!.trimEnd().split("\r\n")
        assertEquals(MoneyExport.COLUMNS.joinToString(","), lines[0])
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("2026-09-15,Joint bills,TESCO STORES,"), lines[1])
        assertTrue(lines[1].endsWith(",,10.00"), lines[1])
        // A comma in the words is quoted, money in is in its own column.
        assertTrue(lines[2].contains("\"ACME, LTD SALARY\""), lines[2])
        assertTrue(lines[2].endsWith(",2500.00,"), lines[2])
    }
}
