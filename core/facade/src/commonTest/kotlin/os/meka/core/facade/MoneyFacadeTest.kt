package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.LifeTile
import os.meka.core.domain.MoneyRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MoneyAccountFields
import os.meka.core.domain.MoneyBank
import os.meka.core.domain.MoneyBankAccountFields
import os.meka.core.domain.MoneyBankSync
import os.meka.core.domain.MoneyTxnFields
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.fv

/** Money manager, slice 1, through the facade: locked until opened, a statement imported on one app shows on the other. */
class MoneyFacadeTest {
    private companion object { val SLOW_RUNNER = 5.minutes }

    private var now = 1_792_144_800_000L // Fri 16 Oct 2026, 11:00 in London
    private val ops = InMemoryServerOpStore()

    private fun core(device: String, zone: String = "Europe/London") = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of(zone) }, nowMs = { now },
    )

    private val statement = "Date,Description,Amount\n" +
        "10/09/2026,TESCO STORES 2041,-60.00\n" +
        "02/10/2026,TESCO STORES 2041,-40.00\n" +
        "03/10/2026,COSTA COFFEE,-3.20\n" +
        "05/10/2026,SALARY OAKFIELD LTD,2500.00\n"

    @Test
    fun lockedUntilOpenedThenSyncsToTheMac() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.moneyView.value.locked)
        assertTrue(fold.lifeLines.value.needsSetUp(LifeTile.MONEY))
        // Nothing goes in while it's locked.
        assertTrue(fold.importMoneyStatement("Current account", statement).refused)
        fold.openMoney()
        assertFalse(fold.moneyView.value.locked)
        assertEquals(MoneyRules.EMPTY, fold.moneyView.value.empty)
        val r = fold.importMoneyStatement("Current account", statement)
        assertEquals("Added 4 transactions to Current account · 10 Sep 2026 – 5 Oct 2026", r.line)
        val m = fold.moneyView.value.month!!
        assertEquals("October so far", m.label)
        assertEquals("Spent £43.20 · in £2,500", m.spentLine)
        assertEquals(listOf("Groceries", "Coffee"), m.categories.map { it.label })
        // The tile says which account and when, never an amount.
        assertEquals("Current account · updated Fri 16 Oct", fold.lifeLines.value.of(LifeTile.MONEY)?.text)
        // A month back, and forward again.
        fold.moneyStep(-1)
        assertEquals("September 2026", fold.moneyView.value.month!!.label)
        assertFalse(fold.moneyView.value.earlier)
        fold.moneyStep(1)
        assertEquals("October so far", fold.moneyView.value.month!!.label)
        // Costa as Eating out, from the Fold; the Mac sees it once it's opened there.
        val costa = fold.moneyView.value.month!!.txns.first { it.merchant == "Costa Coffee" }
        val saved = fold.setMoneyCategory(costa.id, "eating_out")
        assertEquals("Costa Coffee is Eating out from now on", saved.line)
        fold.syncNow(); mac.syncNow()
        assertTrue(mac.moneyView.value.locked)
        assertEquals("Current account · updated Fri 16 Oct", mac.lifeLines.value.of(LifeTile.MONEY)?.text)
        mac.openMoney()
        assertEquals(listOf("Groceries", "Eating out"), mac.moneyView.value.month!!.categories.map { it.label })
        // Undo on the Fold puts it back.
        assertTrue(fold.undoMoneyCategory(saved.undo!!))
        fold.syncNow(); mac.syncNow()
        assertEquals(listOf("Groceries", "Coffee"), mac.moneyView.value.month!!.categories.map { it.label })
        // Locking drops the figures; Delete all clears both devices.
        mac.closeMoney()
        assertTrue(mac.moneyView.value.month == null && mac.moneyView.value.locked)
        assertEquals(MoneyRules.DELETED_ALL, fold.deleteAllMoney().line)
        fold.syncNow(); mac.syncNow()
        mac.openMoney()
        assertEquals(MoneyRules.EMPTY, mac.moneyView.value.empty)
        assertTrue(mac.lifeLines.value.needsSetUp(LifeTile.MONEY))
    }
    @Test
    fun bankLinesFromTheServerShowOnTheFoldAndRefreshAsksForMore() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        fold.openMoney()
        assertNull(fold.moneyView.value.bank)
        // What Navilife's server writes after a sync through Lunch Flow (invented account and line).
        val clock = HlcClock("server", { now })
        var n = 0
        fun put(type: String, id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
            ops.append(Op("srvbank${n++}", "hh", type, id, f, v, clock.now(), emptyList(), "server"))
        }
        val acct = MoneyBank.accountId("101")
        put(EntityTypes.MONEY_ACCOUNT, acct, mapOf(
            MoneyAccountFields.NAME to "Oakbank Current".fv(), MoneyAccountFields.SOURCE to MoneyBank.SOURCE.fv(),
            MoneyBankAccountFields.STATUS to MoneyBank.OK.fv(), MoneyAccountFields.IMPORTED_AT to now.fv(),
        ))
        put(EntityTypes.MONEY_TXN, MoneyBank.txnId(acct, "x1"), mapOf(
            MoneyTxnFields.ACCOUNT to acct.fv(), MoneyTxnFields.DAY to CivilDate.toEpochDay(2026, 10, 2).fv(),
            MoneyTxnFields.PENCE to (-4_000L).fv(), MoneyTxnFields.TEXT to "Tesco · TESCO STORES 2041".fv(),
        ))
        put(EntityTypes.CONTEXT_MODE, MoneyBank.SYNC_ID, MoneyBank.fields(MoneyBankSync(MoneyBank.OK, now, now, 1, 1)))
        fold.syncNow()
        val v = fold.moneyView.value
        assertEquals("Through Lunch Flow, read-only · updated just now · 1 account", v.bank!!.line)
        assertEquals(listOf("Groceries"), v.month!!.categories.map { it.label })
        assertTrue(v.accounts.single().line.startsWith("From your bank · 1 transaction"))
        // Refresh asks the server (once; a second tap while it waits is refused).
        now += 120_000
        assertEquals(MoneyBank.REFRESH_SAID, fold.moneyBankRefresh().line)
        assertTrue(fold.moneyView.value.bank!!.refreshing)
        assertTrue(fold.moneyBankRefresh().refused)
        fold.syncNow()
        assertTrue(ops.after("hh", 0, 10_000).any { it.op.entityId == MoneyBank.REFRESH_ID && it.op.deviceId == "android" })
        // Removed here, it stays off until Sync them again.
        fold.removeMoneyAccount(acct)
        assertEquals("1 bank account you removed isn't synced", fold.moneyView.value.bank!!.restoreLine)
        assertFalse(fold.moneyBankRestore().refused)
        assertNull(fold.moneyView.value.bank!!.restoreLine)
    }

    @Test
    fun tipsHiddenOnTheFoldStayHiddenOnTheMac() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        // Ten coffees in the last ten days (£40) and three monthly gym payments.
        val csv = "Date,Description,Amount\n" +
            (7..16).joinToString("") { "$it/10/2026,COSTA COFFEE,-4.00\n" } +
            "01/08/2026,PUREGYM,-35.00\n01/09/2026,PUREGYM,-35.00\n01/10/2026,PUREGYM,-35.00\n"
        assertTrue(fold.hideMoneyTips("habit").refused) // locked
        fold.openMoney()
        fold.importMoneyStatement("Current account", csv)
        val v = fold.moneyView.value
        assertEquals(listOf("Coffee 10× · £40 in the last 30 days"), v.tips.filter { it.kind == "habit" }.map { it.title })
        assertEquals("1 regular payment · about £35 a month · £420 a year", v.regular?.head)
        val hid = fold.hideMoneyTips("habit")
        assertEquals("Tips on habits hidden · on both devices", hid.line)
        assertTrue(fold.moneyView.value.tips.none { it.kind == "habit" })
        fold.syncNow(); mac.syncNow()
        mac.openMoney()
        assertTrue(mac.moneyView.value.tips.none { it.kind == "habit" })
        assertEquals("Hidden: tips on habits", mac.moneyView.value.hiddenTips)
        // Show them again on the Mac; the Fold follows.
        mac.showMoneyTips()
        mac.syncNow(); fold.syncNow()
        assertEquals(1, fold.moneyView.value.tips.count { it.kind == "habit" })
        // Undo of a hide works through the same Undo as a category change.
        val again = fold.hideMoneyTips("habit")
        assertTrue(fold.undoMoneyCategory(again.undo!!))
        assertEquals(1, fold.moneyView.value.tips.count { it.kind == "habit" })
    }
    @Test
    fun anAlertPutAwayOnTheFoldIsAwayOnTheMac() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        // The same £23.40 at the same shop twice on Tue 13 Oct.
        val csv = "Date,Description,Amount\n13/10/2026,AMAZON MARKETPLACE,-23.40\n13/10/2026,AMAZON MARKETPLACE,-23.40\n"
        assertTrue(fold.moneyAlertFine("w1").refused) // locked
        fold.openMoney()
        fold.importMoneyStatement("Current account", csv)
        val a = fold.moneyView.value.alerts.single()
        assertEquals("Possibly charged twice · Amazon Marketplace", a.title)
        assertEquals("£23.40 twice on Tue 13 Oct · Current account", a.line)
        val fine = fold.moneyAlertFine(a.id)
        assertEquals("Put away · on both devices", fine.line)
        assertTrue(fold.moneyView.value.alerts.isEmpty())
        fold.syncNow(); mac.syncNow()
        mac.openMoney()
        assertTrue(mac.moneyView.value.alerts.isEmpty())
        // Undo brings it back.
        assertTrue(fold.undoMoneyCategory(fine.undo!!))
        assertEquals(1, fold.moneyView.value.alerts.size)
        // Delete all money data clears it on both.
        fold.deleteAllMoney()
        assertTrue(fold.moneyView.value.alerts.isEmpty())
    }

    @Test
    fun moneyQuestionsAreAnsweredOnTheDeviceOnlyOnceUnlocked() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        fun said(o: os.meka.core.domain.AskOutcome) = (o as os.meka.core.domain.AskOutcome.Answered).answer
        // Nothing in Money yet: it says so (no AI is connected here, so every answer below is the device's own).
        assertEquals(os.meka.core.domain.MoneyAskRules.EMPTY, said(fold.askMeka("how much did I spend on coffee?")).text)
        fold.openMoney()
        fold.importMoneyStatement("Current account", statement)
        fold.closeMoney()
        // Locked: no figures, an Unlock button instead.
        val locked = said(fold.askMeka("how much did I spend on groceries last month?"))
        assertEquals(os.meka.core.domain.MoneyAskRules.LOCKED, locked.text)
        assertEquals(os.meka.core.domain.MoneyAskRules.UNLOCK, locked.unlockMoney)
        // After the fingerprint: answered, Talk's follow-ups too, for five minutes.
        fold.unlockMoneyAsk()
        assertEquals(
            "You spent £60 on groceries last month (September), 1 payment. Your statements start 10 Sep 2026, so that's from then.",
            said(fold.askMeka("how much did I spend on groceries last month?")).text,
        )
        assertEquals(
            "You spent £3.20 on coffee this month so far, 1 payment. Your statements reach Mon 5 Oct.",
            said(fold.talk("how much have I spent on coffee this month?", emptyList())).text,
        )
        now += 6 * 60_000L
        assertEquals(os.meka.core.domain.MoneyAskRules.LOCKED, said(fold.askMeka("what did I spend at Tesco?")).text)
        // Open Money also answers; locking it (the app going away) ends an unlock from Ask at once.
        fold.unlockMoneyAsk()
        fold.lockMoneyAsk()
        assertEquals(os.meka.core.domain.MoneyAskRules.LOCKED, said(fold.askMeka("what did I spend at Tesco?")).text)
        fold.openMoney()
        assertEquals("You spent £40 at Tesco Stores this month so far, 1 payment. Your statements reach Mon 5 Oct.",
            said(fold.askMeka("what did I spend at Tesco?")).text)
    }

    @Test
    fun payingAbroadSaysWhichCardHadNoFeesAndLeadsWhileAbroad() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val card = "Date,Description,Amount\n" +
            "20/07/2026,RESTAURANTE SOL MADRID EUR 116.20,-100.00\n" +
            "20/07/2026,NON-STERLING TRANSACTION FEE,-2.99\n" +
            "10/10/2026,TESCO STORES 2041,-20.00\n"
        val travel = "Date,Description,Amount\n" +
            "22/07/2026,TAPAS BAR EUR 46.50,-40.00\n" +
            "23/07/2026,CAFE CENTRAL EUR 11.60,-10.00\n"
        fold.openMoney()
        fold.importMoneyStatement("Credit card", card)
        fold.importMoneyStatement("Travel card", travel)
        val v = fold.moneyView.value.travel!!
        assertEquals(
            "Abroad, pay with Travel card: no foreign fees on your 2 payments abroad in the last year. Credit card charged £2.99 in fees (about 3%).",
            v.tip,
        )
        assertEquals(listOf("Travel card", "Credit card"), v.rows.map { it.name })
        assertNull(v.banner) // at home, no trip near
        // Ask answers it the same, on the device.
        val a = (fold.askMeka("which card should I use abroad?") as os.meka.core.domain.AskOutcome.Answered).answer.text
        assertTrue(a.startsWith("Abroad, pay with Travel card"), a)
        // A trip in a few days, or the phone in another time zone, puts the banner first.
        fold.closeMoney()
        fold.addTrip("Barcelona 20–24 Oct")
        fold.openMoney()
        assertEquals("Barcelona in 4 days · if it's abroad, pay with Travel card: no foreign fees there before", fold.moneyView.value.travel!!.banner)
        fold.syncNow()
        val away = core("mac", zone = "Europe/Madrid")
        away.syncNow()
        away.openMoney()
        assertEquals("You're abroad · pay with Travel card: no foreign fees there before", away.moneyView.value.travel!!.banner)
    }

    @Test
    fun paydayAndSafeToSpendFollowTheStatementsBalanceAndATypedOneOnBothDevices() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        // Paid on the 30th; a Water bill on the radar before payday; the file's Balance column ends at £640 on Thu 15 Oct.
        val csv = "Date,Description,Amount,Balance\n" +
            "15/10/2026,TESCO STORES 2041,-60.00,640.00\n" +
            "30/09/2026,ACME PAYROLL,2500.00,700.00\n" +
            "28/08/2026,ACME PAYROLL,2500.00,900.00\n" +
            "30/07/2026,ACME PAYROLL,2500.00,800.00\n"
        fold.addRenewal("Water bill", os.meka.core.domain.ObligationKind.BILL, os.meka.core.domain.CivilDate.toEpochDay(2026, 10, 25), os.meka.core.domain.RenewalRepeat.NONE, "£40", null)
        assertTrue(fold.moneyBalance("a-current-account", "£300").refused) // locked
        fold.openMoney()
        fold.importMoneyStatement("Current account", csv)
        val p = fold.moneyView.value.payday!!
        assertEquals("Payday Fri 30 Oct · in 14 days", p.payLine)
        assertEquals(listOf("Water bill"), p.rows.map { it.title })
        assertEquals("Current account · £640 · from your statement, Thu 15 Oct", p.balanceLine)
        assertEquals("Safe to spend until payday · about £600 · about £43 a day", p.safe)
        // He types today's balance: tight, so it's lit; the Mac sees it after a sync.
        val typed = fold.moneyBalance("a-current-account", "£120")
        assertEquals("Current account's balance is £120 · safe to spend follows it", typed.line)
        assertEquals("After the bills before payday you'd have about £80 · about £6 a day", fold.moneyView.value.payday!!.safe)
        assertTrue(fold.moneyView.value.payday!!.safeLit)
        fold.syncNow(); mac.syncNow()
        mac.openMoney()
        assertEquals("Current account · £120 · you typed it today", mac.moneyView.value.payday!!.balanceLine)
        // Undo puts the statement's balance back; nonsense is refused with its words kept.
        assertTrue(fold.undoMoneyCategory(typed.undo!!))
        assertEquals("Current account · £640 · from your statement, Thu 15 Oct", fold.moneyView.value.payday!!.balanceLine)
        assertEquals(os.meka.core.domain.MoneyPayday.BALANCE_BAD, fold.moneyBalance("a-current-account", "loads").line)
    }

    @Test
    fun aRefundBackInTheStatementClosesOnBothDevicesWithUndo() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        // Waiting since Fri 9 Oct for £40 from Amazon; the statement has it back on Thu 15 Oct.
        now -= 7 * 86_400_000L
        val wid = fold.addWaiting("Refund · Amazon order · £40", "Amazon", null)
        now += 7 * 86_400_000L
        val csv = "Date,Description,Amount\n01/10/2026,AMAZON.CO.UK,-40.00\n15/10/2026,AMAZON.CO.UK,40.00\n16/10/2026,COSTA COFFEE,-3.20\n"
        assertTrue(fold.moneyRefundClose(wid).refused) // locked
        fold.openMoney()
        fold.importMoneyStatement("Current account", csv)
        val r = fold.moneyView.value.refunds!!.rows.single()
        assertEquals(os.meka.core.domain.MoneyRefunds.BACK, r.state)
        assertEquals("+£40 from Amazon.co.uk on Thu 15 Oct · Current account", r.line)
        val closed = fold.moneyRefundClose(wid)
        assertEquals("Closed Refund · Amazon order · £40 · it's back in your statement", closed.line)
        assertTrue(fold.moneyView.value.refunds == null)
        fold.syncNow(); mac.syncNow()
        mac.openMoney()
        assertTrue(mac.moneyView.value.refunds == null)
        // Undo puts the row back on Waiting for, here and on the Mac.
        assertTrue(fold.undoMoneyCategory(closed.undo!!))
        assertEquals(1, fold.moneyView.value.refunds!!.rows.size)
        fold.syncNow(); mac.syncNow()
        assertEquals(1, mac.moneyView.value.refunds!!.rows.size)
        // A stale token does nothing.
        assertFalse(fold.undoMoneyCategory(closed.undo!!))
        // Copy the chase marks it chased (it isn't late here, but he can still copy one from a late row).
        assertEquals("Chase copied · paste it to the shop · next look Fri 23 Oct", fold.moneyRefundChased(wid).line)
    }

    @Test
    fun theForecastsWhatIfsStayOnThisDeviceAndGoWhenMoneyLocks() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        // Jul–Sep: £2,500 in, £300 eating out and £150 groceries out each month.
        val csv = "Date,Description,Amount\n" + listOf("07", "08", "09").joinToString("") { m ->
            "01/$m/2026,SALARY OAKFIELD LTD,2500.00\n10/$m/2026,NANDOS,-300.00\n12/$m/2026,TESCO STORES 2041,-150.00\n28/$m/2026,TESCO STORES 2041,-0.50\n"
        }
        assertTrue(fold.moneyForecastExtra("£200").refused) // locked
        fold.openMoney()
        fold.importMoneyStatement("Current account", csv)
        val f = fold.moneyView.value.forecast!!
        assertEquals("About £2,050 a month left over", f.left)
        assertEquals("Halve Eating out", f.cuts.first().label)
        fold.moneyForecastCut("eating_out")
        assertEquals("What if: £200 a month more coming in", fold.moneyForecastExtra("£200").line)
        assertTrue(fold.moneyForecastExtra("lots").refused)
        val g = fold.moneyView.value.forecast!!
        assertTrue(g.cuts.first().on)
        assertEquals("put by at your pace · +£2,100 with your what-ifs", g.rows[0].line)
        // Never synced: the Mac sees the plain forecast.
        fold.syncNow(); mac.syncNow()
        mac.openMoney()
        assertEquals("put by at your pace", mac.moneyView.value.forecast!!.rows[0].line)
        // Locking drops them.
        fold.closeMoney(); fold.openMoney()
        assertEquals("put by at your pace", fold.moneyView.value.forecast!!.rows[0].line)
    }

    @Test
    fun aDoubleChargeThisWeekIsAHeadsUpOnBothAppsWithoutAmountsUntilPutAway() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        // Money manager, nudges: the same £23.40 twice yesterday, on top of September's lines.
        val csv = "Date,Description,Amount\n10/09/2026,TESCO STORES 2041,-60.00\n" +
            "15/10/2026,AMAZON MARKETPLACE,-23.40\n15/10/2026,AMAZON MARKETPLACE,-23.40\n"
        fold.openMoney()
        fold.importMoneyStatement("Current account", csv)
        fold.closeMoney()
        fold.syncNow(); mac.syncNow()
        // Money stays locked: the heads-up names what and where, never the amount.
        // On its own or folded into a digest with the day's other heads-ups.
        val res = mac.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        val n = (res.post.map { "${it.title} · ${it.text}" } + res.digest?.lines.orEmpty()).single { it.contains("charged twice") }
        assertEquals("Possibly charged twice · Amazon Marketplace · " + os.meka.core.domain.MoneyNudges.ALERT_TEXT, n)
        assertFalse(n.contains("£"))
        // Put away on the Fold: it doesn't come there (or on the Mac after a sync).
        fold.openMoney()
        fold.moneyAlertFine(fold.moneyView.value.alerts.single().id)
        fun told(g: os.meka.core.domain.GovernorResult) = (g.post.map { it.title } + g.digest?.lines.orEmpty()).any { it.contains("charged twice") }
        assertFalse(told(fold.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)))
        fold.syncNow(); mac.syncNow()
        assertFalse(told(mac.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)))
    }

    @Test
    fun aCardMarkedAndTypedOnTheFoldRemindsOnTheMacUntilPaid() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        val csv = "Date,Description,Amount\n05/10/2026,INTEREST CHARGED,-12.40\n06/10/2026,TESCO STORES 2041,-30.00\n"
        assertTrue(fold.moneyCardKind("a-credit-card", card = true, charge = false).refused) // locked
        fold.openMoney()
        fold.importMoneyStatement("Credit card", csv)
        val offered = fold.moneyView.value.cards!!
        assertTrue(offered.rows.isEmpty())
        assertEquals(listOf("Credit card"), offered.offers.map { it.name })
        assertEquals("Credit card is a card · type what its statement says", fold.moneyCardKind("a-credit-card", card = true, charge = false).line)
        assertEquals(os.meka.core.domain.MoneyCards.BAD_DUE, fold.moneyCardField("a-credit-card", "due", "whenever").line)
        assertEquals("Credit card's due day is the 19th", fold.moneyCardField("a-credit-card", "due", "19th").line)
        assertEquals("Credit card's statement balance is £640", fold.moneyCardField("a-credit-card", "statement", "£640").line)
        fold.moneyCardField("a-credit-card", "limit", "£2,000")
        val row = fold.moneyView.value.cards!!.rows.single()
        assertEquals("Due Mon 19 Oct · in 3 days", row.dueLine)
        assertEquals("32% of your £2,000 limit", row.useLine)
        assertEquals("Interest £12.40 charged Mon 5 Oct", row.feesLine)
        // The tile lights without an amount; the Mac gets the card and the heads-up.
        assertEquals("Credit card due Mon 19 Oct", fold.lifeLines.value.of(LifeTile.MONEY)?.text)
        fold.syncNow(); mac.syncNow()
        val g = mac.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        val n = g.post.single { it.source == os.meka.core.domain.NoticeSource.CARDS }
        assertEquals("Credit card payment due Mon 19 Oct", n.title)
        mac.openMoney()
        assertEquals("Due Mon 19 Oct · in 3 days", mac.moneyView.value.cards!!.rows.single().dueLine)
        // Paid stops the reminder; Undo brings it back.
        val paid = fold.moneyCardPaid("a-credit-card")
        assertEquals("Credit card marked paid for Mon 19 Oct · no reminder until the next one", paid.line)
        assertEquals("Due Thu 19 Nov · in 34 days", fold.moneyView.value.cards!!.rows.single().dueLine)
        assertTrue(fold.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL).post.none { it.source == os.meka.core.domain.NoticeSource.CARDS })
        assertTrue(fold.undoMoneyCategory(paid.undo!!))
        assertEquals("Due Mon 19 Oct · in 3 days", fold.moneyView.value.cards!!.rows.single().dueLine)
        // Not a card: it goes back to the offers; Undo makes it a card again with what was typed.
        val off = fold.moneyCardKind("a-credit-card", card = false, charge = false)
        assertTrue(fold.moneyView.value.cards!!.rows.isEmpty())
        assertTrue(fold.undoMoneyCategory(off.undo!!))
        assertEquals("Statement £640 · pay in full to avoid interest", fold.moneyView.value.cards!!.rows.single().statementLine)
        // A charge card.
        assertEquals("Credit card is a charge card · paid in full each month", fold.moneyCardKind("a-credit-card", card = true, charge = true).line)
        assertEquals("Charge card", fold.moneyView.value.cards!!.rows.single().kindLine)
    }

    @Test
    fun aSideHustleMarkedOnTheFoldCountsOnTheMacWithUndoAndGoesWithDeleteAll() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        fold.openMoney()
        fold.importMoneyStatement("Current account", statement)
        fold.importMoneyStatement("Shop account", "Date,Description,Amount\n01/09/2026,STRIPE PAYOUT,600.00\n03/09/2026,CANVA PRO,-12.00\n")
        assertEquals(os.meka.core.domain.MoneyBusiness.INTRO, fold.moneyView.value.business!!.intro)
        val shop = fold.moneyView.value.business!!.accounts.first { it.name == "Shop account" }
        val r = fold.moneyBusinessAccount(shop.id, true)
        assertTrue(r.line.startsWith("Shop account is your side hustle's account"))
        assertEquals("Profit £588", fold.moneyView.value.business!!.profitLine)
        // A payer on the current account marked Business counts too, on the month's row as well.
        val costa = fold.moneyView.value.month!!.txns.first { it.merchant == "Costa Coffee" }
        val m = fold.moneyBusinessMerchant(costa.id, true)
        assertTrue(fold.moneyView.value.month!!.txns.first { it.id == costa.id }.business)
        assertEquals("Profit £584.80", fold.moneyView.value.business!!.profitLine)
        assertTrue(fold.undoMoneyCategory(m.undo!!))
        assertEquals("Profit £588", fold.moneyView.value.business!!.profitLine)
        // Slice 13b: the year-end export for an accountant.
        assertEquals(listOf("2025-26", "2026-27"), fold.moneyView.value.business!!.export!!.chips.map { it.key })
        val e = fold.moneyBusinessExport("2026-27")
        assertEquals("navilife-side-hustle-2026-27.csv", e.fileName)
        assertTrue(e.csv!!.contains(",600.00,") && e.csv!!.contains("Profit,,588.00,"), e.csv)
        assertEquals("2026–27: 2 payments · in £600 · out £12 · profit £588 · choose where to save it", e.said.line)
        assertNull(fold.moneyBusinessExport("2025-26").csv)
        fold.moneySetAside("30")
        fold.syncNow(); mac.syncNow()
        mac.openMoney()
        val b = mac.moneyView.value.business!!
        assertEquals("Profit £588", b.profitLine)
        assertEquals("At 30%, put aside about £176.40 · your share, not a tax bill", b.setAsideLine)
        fold.deleteAllMoney()
        assertEquals(20, os.meka.core.domain.MoneyBusiness.setAside(null))
        assertNull(fold.moneyView.value.business)
    }

    @Test
    fun challengesSetOnTheFoldShowOnTheMacWithUndoAndGoWithDeleteAll() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        fold.openMoney()
        fold.importMoneyStatement("Current account", statement)
        val c0 = fold.moneyView.value.challenges!!
        assertEquals(os.meka.core.domain.MoneyChallenges.INTRO, c0.intro)
        assertEquals("Counting no-spend days · on both devices", fold.moneyNoSpend(true).line)
        val ns = fold.moneyView.value.challenges!!.noSpend!!
        assertEquals("3 no-spend days in October · of 5 days so far", ns.line)
        assertEquals("2 days in a row · your best this month", ns.streakLine)
        assertEquals("Your statements reach Mon 5 Oct, so that's as far as these count", fold.moneyView.value.challenges!!.throughLine)
        assertTrue(fold.moneyFunMoney("lots").refused)
        fold.moneyFunMoney("£50")
        assertEquals("£0 of £50", fold.moneyView.value.challenges!!.funMoney!!.amount)
        assertEquals("Coffee capped at £10 a month · on both devices", fold.moneyCap("Coffee £10").line)
        val g = fold.moneyCap("groceries £30")
        val caps = fold.moneyView.value.challenges!!.caps
        assertEquals(listOf("Groceries this month", "Coffee this month"), caps.map { it.title })
        assertEquals("£10 over", caps[0].line)
        assertTrue(caps[0].lit)
        assertEquals("£6.80 left · within it last month", caps[1].line)
        assertTrue(fold.undoMoneyCategory(g.undo!!))
        assertEquals(1, fold.moneyView.value.challenges!!.caps.size)
        // The streaks in Goals: no amounts, and there whether Money is open or not.
        fold.closeMoney()
        val gs = fold.goalsView.value.money!!
        assertEquals(listOf("No-spend days", "Fun money", "Coffee cap"), gs.rows.map { it.title })
        assertEquals("2 days in a row · 3 this month", gs.rows[0].line)
        assertTrue(gs.rows.none { '£' in it.line })
        fold.openMoney()
        fold.syncNow(); mac.syncNow()
        assertEquals(3, mac.goalsView.value.money!!.rows.size)
        mac.openMoney()
        val m = mac.moneyView.value.challenges!!
        assertTrue(m.noSpendOn)
        assertEquals("£3.20 of £10", m.caps.single().amount)
        val r = mac.moneyChallengeRemove("coffee")
        assertEquals("Coffee cap removed", r.line)
        assertTrue(mac.moneyView.value.challenges!!.caps.isEmpty())
        assertTrue(mac.undoMoneyCategory(r.undo!!))
        mac.moneyChallengeRemove("fun")
        assertNull(mac.moneyView.value.challenges!!.funMoney)
        mac.syncNow(); fold.syncNow()
        assertNull(fold.moneyView.value.challenges!!.funMoney)
        assertEquals(1, fold.moneyView.value.challenges!!.caps.size)
        fold.deleteAllMoney()
        assertNull(fold.moneyView.value.challenges)
        assertNull(fold.goalsView.value.money)
        fold.importMoneyStatement("Card", "Date,Description,Amount\n06/10/2026,PRET A MANGER,-5.00\n")
        assertEquals(os.meka.core.domain.MoneyChallenges.INTRO, fold.moneyView.value.challenges!!.intro)
    }

    @Test
    fun sharedCostsMarkedOnTheFoldAddUpWithWhatSheTypedOnTheMac() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        fold.setAboutYou(os.meka.core.domain.AboutField.PARTNER_NAME, "Jordan Smith")
        fold.openMoney()
        fold.importMoneyStatement("Current account", statement)
        val sh = fold.moneyView.value.shared!!
        assertEquals("SHARED WITH JORDAN", sh.head)
        assertTrue(sh.intro!!.contains("what Jordan paid"))
        // Tesco always shared (both lines), Costa just this once.
        val tesco = fold.moneyView.value.month!!.txns.first { it.merchant.startsWith("Tesco") }
        val r = fold.moneySharedMerchant(tesco.id, true)
        assertTrue(r.line.startsWith("Every Tesco"))
        assertTrue(fold.moneyView.value.month!!.txns.first { it.id == tesco.id }.sharedAlways)
        val costa = fold.moneyView.value.month!!.txns.first { it.merchant == "Costa Coffee" }
        val c = fold.moneySharedTxn(costa.id, true)
        assertEquals("You paid £103.20 · 3 payments", fold.moneyView.value.shared!!.youLine)
        assertTrue(fold.undoMoneyCategory(c.undo!!))
        assertEquals("You paid £100 · 2 payments", fold.moneyView.value.shared!!.youLine)
        assertEquals("Jordan owes you £50", fold.moneyView.value.shared!!.balanceLine)
        fold.syncNow(); mac.syncNow()
        mac.openMoney()
        assertEquals("Jordan paid £80 for Big shop · counted in who paid what", mac.moneySharedAdd("Big shop £80").line)
        assertEquals("Jordan owes you £10", mac.moneyView.value.shared!!.balanceLine)
        mac.moneySharedShare("60")
        assertEquals("You owe Jordan £8", mac.moneyView.value.shared!!.balanceLine)
        val settle = mac.moneySharedSettle()
        assertEquals("Settled up with Jordan · counting again from today", settle.line)
        assertEquals("All square", mac.moneyView.value.shared!!.balanceLine)
        assertNull(mac.moneyView.value.shared!!.settle)
        assertTrue(mac.undoMoneyCategory(settle.undo!!))
        mac.syncNow(); fold.syncNow()
        val f = fold.moneyView.value.shared!!
        assertEquals("Jordan paid £80 · 1 payment", f.partnerLine)
        assertEquals("You owe Jordan £8", f.balanceLine)
        assertEquals("Split 60/40 · your share £108", f.splitLine)
        fold.deleteAllMoney()
        assertNull(fold.moneyView.value.shared)
    }

    @Test
    fun netWorthTypedOnTheFoldShowsOnTheMacWithUndoAndGoesWithDeleteAll() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.moneyWorthAdd("Pension £42,000").refused) // locked
        fold.openMoney()
        // Before any statement: the block says what to do, and typing counts.
        assertEquals(os.meka.core.domain.MoneyWorth.EMPTY, fold.moneyView.value.worth!!.empty)
        assertEquals("Added Pension · £42,000 · counted in your net worth", fold.moneyWorthAdd("Pension £42,000").line)
        assertEquals(os.meka.core.domain.MoneyWorth.NO_NAME, fold.moneyWorthAdd("£300").line)
        fold.moneyWorthAdd("Car loan £3,200")
        assertEquals("£38,800", fold.moneyView.value.worth!!.total)
        // A statement with a balance column counts the account and draws the line.
        val csv = "Date,Description,Amount,Balance\n20/09/2026,TESCO STORES 2041,-50.00,1050.00\n02/10/2026,TESCO STORES 2041,-50.00,1000.00\n"
        fold.importMoneyStatement("Current account", csv)
        val w = fold.moneyView.value.worth!!
        assertEquals("£39,800", w.total)
        assertEquals(listOf("Sep", "Oct"), w.points.map { it.label })
        assertEquals("Down £50 since the end of Sep", w.changeLine)
        // The same name updates it; Undo puts the old value back.
        val up = fold.moneyWorthAdd("Pension £43,000")
        assertEquals("Pension is now £43,000 · counted in your net worth", up.line)
        fold.syncNow(); mac.syncNow()
        mac.openMoney()
        assertEquals("£40,800", mac.moneyView.value.worth!!.total)
        assertTrue(fold.undoMoneyCategory(up.undo!!))
        fold.syncNow(); mac.syncNow()
        assertEquals("£39,800", mac.moneyView.value.worth!!.total)
        // Remove on the Mac, Undo brings it back.
        val gone = mac.moneyWorthRemove("n-car-loan")
        assertEquals("Removed Car loan from your net worth", gone.line)
        assertEquals("£43,000", mac.moneyView.value.worth!!.total)
        assertTrue(mac.undoMoneyCategory(gone.undo!!))
        assertEquals("£39,800", mac.moneyView.value.worth!!.total)
        // Delete all takes the typed things too.
        fold.deleteAllMoney()
        fold.syncNow(); mac.syncNow()
        assertEquals(os.meka.core.domain.MoneyWorth.EMPTY, mac.moneyView.value.worth!!.empty)
    }

    @Test
    fun tradingAddressesAddOnOneAppShowOnTheOtherAndUndo() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        val address = "0x" + "ab12".repeat(10)
        // Locked: nothing goes in.
        assertTrue(fold.tradingWalletAdd("Main $address").refused)
        fold.openMoney(); mac.openMoney()
        assertEquals(os.meka.core.domain.TradingTracker.EMPTY, fold.moneyView.value.trading!!.empty)
        // A key is never taken.
        assertTrue(fold.tradingWalletAdd("0x" + "1f".repeat(32)).refused)
        val added = fold.tradingWalletAdd("Main $address")
        assertFalse(added.refused)
        assertEquals("Main", fold.moneyView.value.trading!!.wallets.single().name)
        fold.syncNow(); mac.syncNow()
        val id = mac.moneyView.value.trading!!.wallets.single().id
        val removed = mac.tradingWalletRemove(id)
        assertTrue(mac.moneyView.value.trading!!.wallets.isEmpty())
        assertTrue(mac.undoMoneyCategory(removed.undo!!))
        assertEquals("Main", mac.moneyView.value.trading!!.wallets.single().name)
        // Delete all money data takes the addresses too, on both.
        mac.deleteAllMoney()
        mac.syncNow(); fold.syncNow()
        assertEquals(os.meka.core.domain.TradingTracker.EMPTY, fold.moneyView.value.trading!!.empty)
    }

    @Test
    fun tradingAlertLevelsChosenOnTheFoldReachTheMac() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        fold.openMoney(); mac.openMoney()
        fold.tradingWalletAdd("Main 0x" + "ab12".repeat(10))
        val alerts = fold.moneyView.value.trading!!.alerts!!
        assertEquals(listOf("10"), alerts.liqChips.filter { it.on }.map { it.key })
        assertEquals(listOf("20"), alerts.dropChips.filter { it.on }.map { it.key })
        assertEquals("Alert within 5% of liquidation · on both devices", fold.setTradingAlert(os.meka.core.domain.TradingAlerts.LIQ, "5"))
        assertEquals("Drop alert off", fold.setTradingAlert(os.meka.core.domain.TradingAlerts.DROP, "off"))
        // Slice 7: a big order and a day's funding.
        assertEquals(listOf("5"), alerts.fillChips.filter { it.on }.map { it.key })
        assertEquals(listOf("1"), alerts.fundingChips.filter { it.on }.map { it.key })
        assertEquals("Heads-up for an order worth 2× the account · on both devices", fold.setTradingAlert(os.meka.core.domain.TradingAlerts.FILL, "2"))
        assertEquals("Funding alert off", fold.setTradingAlert(os.meka.core.domain.TradingAlerts.FUNDING, "off"))
        // An unknown choice changes nothing.
        fold.setTradingAlert(os.meka.core.domain.TradingAlerts.DROP, "15")
        assertEquals(listOf("5"), fold.moneyView.value.trading!!.alerts!!.liqChips.filter { it.on }.map { it.key })
        fold.syncNow(); mac.syncNow()
        val m = mac.moneyView.value.trading!!.alerts!!
        assertEquals(listOf("5"), m.liqChips.filter { it.on }.map { it.key })
        assertEquals(listOf("off"), m.dropChips.filter { it.on }.map { it.key })
        assertEquals(listOf("2"), m.fillChips.filter { it.on }.map { it.key })
        assertEquals(listOf("off"), m.fundingChips.filter { it.on }.map { it.key })
        // Slice 12: dollars or pounds, on the same setting, both devices; the alerts stay as they were.
        assertEquals(listOf("usd"), mac.moneyView.value.trading!!.currency!!.chips.filter { it.on }.map { it.key })
        assertTrue(mac.setTradingAlert(os.meka.core.domain.TradingCurrencyRules.KIND, "gbp").startsWith("Trading in pounds"))
        mac.syncNow(); fold.syncNow()
        val f = fold.moneyView.value.trading!!
        assertEquals(listOf("gbp"), f.currency!!.chips.filter { it.on }.map { it.key })
        assertEquals(listOf("5"), f.alerts!!.liqChips.filter { it.on }.map { it.key })
    }

    @Test
    fun theYearSoFarIsFoldedAndJanuaryReviewsLastYearWithAHeadsUpOnTheMac() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        fold.openMoney()
        fold.importMoneyStatement("Current account", statement)
        // October: this year so far, folded, transfers and income left out of what was spent.
        val soFar = fold.moneyView.value.year!!
        assertEquals("2026 SO FAR", soFar.head)
        assertFalse(soFar.unfolded)
        assertEquals("Your year so far · Spent £103.20 · in £2,500", soFar.folded)
        assertTrue(mac.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL).post.none { it.title.contains("year in review") })
        // 2 January 2027: last year's review leads, open, and the Mac gets one heads-up with no amounts.
        now = 1_798_884_000_000L
        fold.syncNow(); mac.syncNow()
        fold.closeMoney(); fold.openMoney()
        val y = fold.moneyView.value.year!!
        assertEquals("2026 IN REVIEW", y.head)
        assertTrue(y.unfolded)
        assertEquals(listOf("Groceries", "Coffee"), y.categories.map { it.label })
        val n = mac.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL).post.single { it.title.contains("year in review") }
        assertEquals("Your 2026 money year in review is ready", n.title)
        assertFalse(n.text.contains("£"))
    }

    @Test
    fun lastMonthsReviewLeadsInItsFirstDaysAndTheMacGetsTheHeadsUp() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        fold.openMoney()
        val september = "Date,Description,Amount\n" +
            "01/09/2026,SALARY OAKFIELD LTD,2500.00\n" +
            "03/09/2026,TESCO STORES 2041,-60.00\n" +
            "10/09/2026,COSTA COFFEE,-3.20\n" +
            "18/09/2026,TESCO STORES 2041,-45.00\n" +
            "29/09/2026,PIZZA PLACE,-22.00\n" +
            "30/09/2026,COSTA COFFEE,-3.40\n"
        // Sat 3 Oct 2026, noon in London (the clock set before the import, so nothing is synced from the future):
        // September's review leads this month, and the Mac gets one heads-up, no amounts.
        now = 1_791_025_200_000L
        fold.importMoneyStatement("Current account", september)
        fold.syncNow(); mac.syncNow()
        fold.closeMoney(); fold.openMoney()
        val r = fold.moneyView.value.review!!
        assertEquals("SEPTEMBER IN REVIEW", r.head)
        assertTrue(r.lastMonth)
        assertEquals("In September you spent £133.60 and £2,500 came in, so about £2,366.40 was left over.", r.sentences.first())
        // On its own or folded into a digest with the day's other heads-ups.
        val res = mac.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        val n = (res.post.map { "${it.title} · ${it.text}" } + res.digest?.lines.orEmpty()).single { it.contains("money review") }
        assertTrue(n.startsWith("Your September money review is ready · "), n)
        assertFalse(n.contains("£"))
        // Mid-month this month has none; paging back to September shows its own.
        now = 1_792_144_800_000L
        fold.closeMoney(); fold.openMoney()
        assertNull(fold.moneyView.value.review)
        fold.moneyStep(-1)
        val back = fold.moneyView.value.review!!
        assertEquals("SEPTEMBER IN REVIEW", back.head)
        assertFalse(back.lastMonth)
    }
}
