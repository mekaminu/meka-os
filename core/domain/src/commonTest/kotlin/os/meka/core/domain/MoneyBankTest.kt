package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money manager, bank sync through Lunch Flow: names, words, when to sync, the view, Health, and counting lines once. Invented data. */
class MoneyBankTest {
    private val cal = object : LocalCalendar {
        override fun epochDayOf(epochMs: Long) = epochMs.floorDiv(86_400_000L)
        override fun minuteOfDay(epochMs: Long) = (epochMs.mod(86_400_000L) / 60_000L).toInt()
        override fun toEpochMs(epochDay: Long, minuteOfDay: Int) = epochDay * 86_400_000L + minuteOfDay * 60_000L
    }
    private val today = CivilDate.toEpochDay(2026, 10, 17)
    private val now = cal.toEpochMs(today, 9 * 60)
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)

    @Test
    fun namesAndWordsReadNaturally() {
        assertEquals("Oakbank Current Account", MoneyBank.name("Current Account", "Oakbank"))
        assertEquals("Oakbank Saver", MoneyBank.name("Oakbank Saver", "Oakbank"))
        assertEquals("Oakbank", MoneyBank.name("", "Oakbank"))
        assertEquals("Tesco · TESCO STORES 2041 LONDON", MoneyBank.text("Tesco", "TESCO STORES 2041 LONDON"))
        assertEquals("Tesco", MoneyBank.text("Tesco", "tesco"))
        assertEquals("CARD PAYMENT", MoneyBank.text(null, "CARD PAYMENT"))
        assertEquals("Payment", MoneyBank.text(" ", ""))
        assertTrue(MoneyBank.accountId("123").startsWith("l"))
        assertEquals(MoneyBank.txnId("l1", "abc"), MoneyBank.txnId("l1", "abc"))
        assertFalse(MoneyBank.txnId("l1", "abc") == MoneyBank.txnId("l2", "abc"))
    }

    @Test
    fun syncsWhenNeverSyncedEverySixHoursOrWhenAsked() {
        assertTrue(MoneyBank.due(null, null, now))
        val ok = MoneyBankSync(MoneyBank.OK, now - 1_000, now - 1_000, 6, 400)
        assertFalse(MoneyBank.due(ok, null, now))
        assertTrue(MoneyBank.due(ok.copy(lastTryMs = now - MoneyBank.PERIOD_MS), null, now))
        assertTrue(MoneyBank.due(ok.copy(state = MoneyBank.ERROR, lastTryMs = now - MoneyBank.RETRY_MS), null, now))
        // Asked after the last try: once a minute has gone since it.
        assertFalse(MoneyBank.due(ok, now - 500, now))
        assertTrue(MoneyBank.due(ok.copy(lastTryMs = now - 70_000), now - 10_000, now))
        assertTrue(MoneyBank.refreshing(ok.copy(lastTryMs = now - 70_000), now - 10_000, now))
        assertFalse(MoneyBank.refreshing(ok, now - 10_000, now))
    }

    @Test
    fun theViewSaysWhenAndWhatToReconnectWithNoAmounts() {
        val accounts = listOf(
            MoneyAccount("l1", "Oakbank Current", MoneyBank.SOURCE, now - 3_600_000, bankStatus = MoneyBank.OK),
            MoneyAccount("l2", "Riverside Card", MoneyBank.SOURCE, cal.toEpochMs(d(10, 15), 360), bankStatus = MoneyBank.RECONNECT),
            MoneyAccount("a-old", "Old statement", "csv", null),
        )
        assertNull(MoneyBank.view(null, accounts, 0, null, now, cal))
        val s = MoneyBankSync(MoneyBank.RECONNECT, cal.toEpochMs(today, 6 * 60 + 10), cal.toEpochMs(today, 6 * 60 + 10), 2, 300, reconnect = 1)
        val v = MoneyBank.view(s, accounts, removed = 1, askedAtMs = null, nowMs = now, cal = cal)!!
        assertEquals("Through Lunch Flow, read-only · updated 06:10 · 2 accounts", v.line)
        assertTrue(v.lit)
        assertEquals(listOf("Reconnect Riverside Card in Lunch Flow · last updated Thu 15 Oct · its bank asks every 90 days or so"), v.reconnect.map { it.line })
        assertEquals("1 bank account you removed isn't synced", v.restoreLine)
        assertFalse(v.refreshing)
        val err = MoneyBank.view(s.copy(state = MoneyBank.ERROR, lastTryMs = now - 60_000), accounts.take(1), 0, null, now, cal)!!
        assertTrue(err.line.startsWith("Couldn't reach Lunch Flow at 08:59"))
        assertTrue(err.lit)
    }

    @Test
    fun healthSaysOnReconnectOrBrokenWithCountsOnly() {
        assertNull(MoneyBank.healthRow(null, now, cal))
        val ok = MoneyBankSync(MoneyBank.OK, cal.toEpochMs(today, 370), cal.toEpochMs(today, 370), 6, 412)
        assertEquals("On · last 06:10 · 6 accounts", MoneyBank.healthRow(ok, now, cal)!!.line)
        assertEquals(HealthState.OK, MoneyBank.healthRow(ok, now, cal)!!.state)
        val re = MoneyBank.healthRow(ok.copy(state = MoneyBank.RECONNECT, reconnect = 1), now, cal)!!
        assertEquals(HealthState.WARN, re.state)
        assertEquals("1 account to reconnect in Lunch Flow · open Money", re.line)
        assertEquals(HealthState.BAD, MoneyBank.healthRow(ok.copy(state = MoneyBank.KEY), now, cal)!!.state)
        assertEquals(HealthState.WARN, MoneyBank.healthRow(ok.copy(state = MoneyBank.ERROR), now, cal)!!.state)
        assertEquals("ok · 6 accounts · 412 transactions · last ok T · last try T", MoneyBank.reportLine(ok) { "T" })
        val read = MoneyBank.read { MoneyBank.fields(ok)[it] }
        assertEquals(ok, read)
    }

    @Test
    fun aStatementOfTheSameAccountCountsOnce() {
        val accounts = listOf(
            MoneyAccount("l1", "Oakbank Current", MoneyBank.SOURCE, now, bankStatus = MoneyBank.OK),
            MoneyAccount("a-current", "Current account", "csv", now),
            MoneyAccount("a-other", "Other card", "csv", now),
        )
        val bank = listOf(
            MoneyTxn("b1", "l1", d(10, 1), -1_250, "Tesco"), MoneyTxn("b2", "l1", d(10, 3), -499, "Spotify"),
            MoneyTxn("b3", "l1", d(10, 5), 250_000, "Salary"), MoneyTxn("b4", "l1", d(10, 9), -2_340, "Amazon"),
        )
        val csv = listOf(
            MoneyTxn("c0", "a-current", d(9, 20), -800, "CARD PAYMENT TO CAFE"), // before the bank's lines: kept
            MoneyTxn("c1", "a-current", d(9, 30), -1_250, "CARD PAYMENT TO TESCO STORES"), // a day before its bank line: same
            MoneyTxn("c2", "a-current", d(10, 3), -499, "SPOTIFY"),
            MoneyTxn("c3", "a-current", d(10, 6), 250_000, "ACME LTD SALARY"),
        )
        val other = listOf(
            MoneyTxn("o1", "a-other", d(10, 2), -1_000, "A"), MoneyTxn("o2", "a-other", d(10, 4), -2_000, "B"),
            MoneyTxn("o3", "a-other", d(10, 6), -3_000, "C"),
        )
        val m = MoneyBank.merge(accounts, bank + csv + other)
        assertEquals(mapOf("a-current" to "l1"), m.sameAs)
        assertEquals(3, m.hidden)
        assertEquals(setOf("b1", "b2", "b3", "b4", "c0", "o1", "o2", "o3"), m.txns.map { it.id }.toSet())
        // Without a bank account nothing changes.
        assertEquals(csv.size, MoneyBank.merge(accounts.drop(1), csv).txns.size)
    }

    @Test
    fun theMoneyScreenCountsTheStatementOnceAndShowsTheBanks() {
        val accounts = listOf(
            MoneyAccount("l1", "Oakbank Current", MoneyBank.SOURCE, now, MoneyBalance(150_000, today, false), MoneyBank.OK),
            MoneyAccount("a-current", "Current account", "csv", now, MoneyBalance(150_000, d(10, 9), false)),
        )
        val txns = listOf(
            MoneyTxn("b1", "l1", d(10, 1), -1_250, "Tesco"), MoneyTxn("b2", "l1", d(10, 3), -499, "Spotify"),
            MoneyTxn("b3", "l1", d(10, 9), -2_340, "Amazon"),
            MoneyTxn("c1", "a-current", d(10, 1), -1_250, "TESCO"), MoneyTxn("c2", "a-current", d(10, 3), -499, "SPOTIFY"),
            MoneyTxn("c3", "a-current", d(10, 9), -2_340, "AMAZON"),
        )
        val s = MoneyBankSync(MoneyBank.OK, now, now, 1, 3)
        val v = MoneyRules.view(accounts, txns, emptyMap(), today, cal, 0, bankSync = s, nowMs = now)
        assertEquals("Spent £40.89", v.month!!.spentLine)
        assertTrue(v.summary.endsWith("3 in a statement and from your bank, counted once"))
        assertNotNull(v.bank)
        assertTrue(v.accounts.first { it.id == "l1" }.line.startsWith("From your bank · 3 transactions"))
        assertTrue(v.accounts.first { it.id == "a-current" }.line.contains("the same account as Oakbank Current"))
        // Net worth counts the balance once.
        assertEquals("£1,500", v.worth!!.total)
    }
}
