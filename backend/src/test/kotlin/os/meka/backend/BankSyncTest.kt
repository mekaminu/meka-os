package os.meka.backend

import os.meka.backend.integrations.BankAccountRead
import os.meka.backend.integrations.BankFeed
import os.meka.backend.integrations.BankKeyRefused
import os.meka.backend.integrations.BankTxnRead
import os.meka.backend.integrations.LunchFlowFeed
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.Money
import os.meka.core.domain.MoneyBank
import os.meka.core.domain.MoneyBankStore
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money's bank sync on the server: Lunch Flow's answers become synced accounts and lines, once each. Invented data only. */
class BankSyncTest {
    private var nowMs = 1_792_224_000_000L // 2026-10-17
    private val day get() = Math.floorDiv(nowMs, 86_400_000L)
    private val ops = InMemoryServerOpStore()
    private val woken = mutableListOf<String>()
    private var configured = true
    private var refuse = false
    private val accounts = mutableListOf(
        BankAccountRead("101", "Current Account", "Oakbank", "GBP", true),
        BankAccountRead("202", "Riverside Card", "Riverside", "GBP", true),
        BankAccountRead("303", "Euro wallet", "Somewhere", "EUR", true),
    )
    private val lines = mutableMapOf(
        "101" to mutableListOf(
            BankTxnRead("x1", CivilDate.toEpochDay(2026, 10, 1), -1_250, "Tesco", "TESCO STORES 2041", "GBP"),
            BankTxnRead("x2", CivilDate.toEpochDay(2026, 10, 5), 250_000, "Acme Ltd", "SALARY", "GBP"),
        ),
        "202" to mutableListOf(BankTxnRead("y1", CivilDate.toEpochDay(2026, 10, 9), -2_340, "Amazon", null, "GBP")),
    )
    val asked = mutableListOf<Pair<String, Long>>()
    private val feed = object : BankFeed {
        override fun configured() = configured
        override fun accounts(): List<BankAccountRead> { if (refuse) throw BankKeyRefused(); return accounts.toList() }
        override fun transactions(accountId: String, fromDay: Long, toDay: Long): List<BankTxnRead> {
            asked += accountId to fromDay
            return lines[accountId].orEmpty().filter { it.day in fromDay..toDay }
        }
        override fun balance(accountId: String) = if (accountId == "101") 148_810L to "GBP" else -2_340L to "GBP"
    }
    private val sync = BankSync(ops, BankSync.scanning(ops), { "hh" }, feed, { nowMs }, { Math.floorDiv(it, 86_400_000L) }, onWritten = { woken += it })

    private var stores = 0
    private val held = HashMap<Replica, InMemoryReplicaStore>()

    /** Meka's Fold after pulling everything the server holds. */
    private fun fold(): Replica {
        var n = 0
        val k = stores++
        val store = InMemoryReplicaStore()
        val replica = Replica("hh", "fold", HlcClock("fold", { nowMs }), store, MekaSchema) { "fold$k-${n++}" }
        replica.applyRemoteBatch(ops.after("hh", 0, 100_000).map { it.op })
        held[replica] = store
        return replica
    }

    /** What the Fold did reaches the server. */
    private fun push(r: Replica) = held.getValue(r).pendingPush(100_000).forEach { ops.append(it) }

    @Test
    fun accountsAndLinesReachTheFoldOnceAndOnlyChangesAreWritten() {
        assertTrue(sync.tick())
        assertEquals(listOf("hh"), woken)
        val money = Money(fold()) { nowMs }
        val accts = money.accounts().sortedBy { it.name }
        assertEquals(listOf("Oakbank Current Account", "Riverside Card"), accts.map { it.name })
        assertTrue(accts.all { it.source == MoneyBank.SOURCE && it.bankStatus == MoneyBank.OK && it.id.startsWith("l") })
        assertEquals(148_810L, accts[0].balance!!.pence)
        assertFalse(accts[0].balance!!.typed)
        assertEquals(setOf(-1_250L, 250_000L, -2_340L), money.txns().map { it.pence }.toSet())
        assertEquals("Tesco · TESCO STORES 2041", money.txns().first { it.pence == -1_250L }.text)
        // The first sync reads two years back; the euro account is left out.
        assertTrue(asked.all { it.second == day - MoneyBank.FIRST_DAYS })
        val s = MoneyBankStore(fold()).sync()!!
        assertEquals(MoneyBank.OK, s.state)
        assertEquals(2, s.accounts)
        assertEquals(3, s.txns)
        assertEquals(1, s.skipped)
        assertEquals(3, s.listed)
        // Not due again for six hours; a Refresh makes it due a minute after the last try.
        val written = ops.after("hh", 0, 100_000).size
        nowMs += 30_000
        assertFalse(sync.tick())
        val replica = fold()
        MoneyBankStore(replica).refresh(nowMs)
        push(replica)
        assertFalse(sync.tick()) // within the minute
        nowMs += 60_000
        assertFalse(sync.tick()) // due, but nothing new for the devices
        val after = ops.after("hh", 0, 100_000).drop(written + 1).map { it.op }
        // Only the try time and each account's sync time moved; every line stayed as it was.
        assertTrue(after.none { it.entityType == EntityTypes.MONEY_TXN })
        assertTrue(asked.drop(2).all { it.second == day - MoneyBank.OVERLAP_DAYS })
        // A new line next time is written once.
        lines["101"]!!.add(BankTxnRead("x3", day, -499, "Spotify", "SPOTIFY P1234", "GBP"))
        nowMs += MoneyBank.PERIOD_MS
        assertTrue(sync.tick())
        assertEquals(4, Money(fold()) { nowMs }.txns().size)
    }

    @Test
    fun aReconnectAndARemovedAccountAreRespected() {
        sync.tick()
        // He removes the card in Navilife; its bank asks him to reconnect the current account.
        val replica = fold()
        val money = Money(replica) { nowMs }
        val card = money.accounts().first { it.name == "Riverside Card" }
        money.removeAccount(card.id)
        push(replica)
        accounts[0] = accounts[0].copy(active = false)
        nowMs += MoneyBank.PERIOD_MS
        assertTrue(sync.tick())
        val f = fold()
        val after = Money(f) { nowMs }
        assertEquals(listOf("Oakbank Current Account"), after.accounts().map { it.name })
        assertEquals(MoneyBank.RECONNECT, after.accounts()[0].bankStatus)
        assertEquals(2, after.txns().size) // the card's line stays gone
        val store = MoneyBankStore(f)
        assertEquals(1, store.sync()!!.reconnect)
        assertEquals(MoneyBank.RECONNECT, store.sync()!!.state)
        assertEquals(listOf(card.id), store.removed())
        // Sync them again: the card and its line come back at the next sync.
        nowMs += 5_000
        store.restore(nowMs)
        push(f)
        nowMs += 61_000
        assertTrue(sync.tick())
        assertEquals(3, Money(fold()) { nowMs }.txns().size)
        assertEquals(2, Money(fold()) { nowMs }.accounts().size)
    }

    @Test
    fun aRefusedKeySaysSoAndNothingRunsWithoutOne() {
        configured = false
        assertFalse(sync.tick())
        assertTrue(ops.after("hh", 0, 100).isEmpty())
        configured = true
        refuse = true
        assertTrue(sync.tick())
        assertEquals(MoneyBank.KEY, MoneyBankStore(fold()).sync()!!.state)
        assertEquals("key refused", sync.lastReport)
        // Retried an hour later.
        nowMs += 30 * 60_000
        assertFalse(sync.tick())
        refuse = false
        nowMs += 31 * 60_000
        assertTrue(sync.tick())
        assertEquals(MoneyBank.OK, MoneyBankStore(fold()).sync()!!.state)
    }

    @Test
    fun lunchFlowsAnswersAreReadWithTheKeyInAHeaderOnly() {
        val seen = mutableListOf<Pair<String, Map<String, String>>>()
        var status = 200
        val http = LunchFlowFeed.Http { url, headers ->
            seen += url to headers
            status to when {
                url.endsWith("/accounts") -> """{"accounts":[{"id":101,"connection_id":7,"name":"Current Account","institution_name":"Oakbank","institution_logo":"x","provider":"gocardless","currency":"GBP","status":"ACTIVE"},{"id":202,"name":"Card","institution_name":"Riverside","status":"EXPIRED"}],"total":2}"""
                url.contains("/transactions") -> """{"transactions":[{"id":"t1","accountId":101,"amount":-12.34,"currency":"GBP","date":"2026-10-15","merchant":"Tesco","description":"TESCO STORES","isPending":false},{"id":"t2","accountId":101,"amount":-5,"currency":"GBP","date":"2026-10-16","merchant":"Cafe","description":"","isPending":true},{"id":"t3","accountId":101,"amount":12.339999999999998,"currency":"GBP","date":"2026-10-16T10:00:00Z","merchant":"Refund","description":"X","isPending":false}],"total":3}"""
                else -> """{"balance":{"amount":1488.1,"currency":"GBP"}}"""
            }
        }
        val f = LunchFlowFeed({ "lf_test_key_123" }, http)
        val a = f.accounts()
        assertEquals(listOf("101", "202"), a.map { it.id })
        assertEquals(listOf(true, false), a.map { it.active })
        val t = f.transactions("101", CivilDate.toEpochDay(2026, 10, 1), CivilDate.toEpochDay(2026, 10, 17))
        assertEquals(listOf(-1_234L, 1_234L), t.map { it.pence })
        assertEquals(CivilDate.toEpochDay(2026, 10, 16), t[1].day)
        assertEquals(148_810L to "GBP", f.balance("101"))
        assertTrue(seen.all { it.second == mapOf("x-api-key" to "lf_test_key_123") && "lf_test_key" !in it.first })
        assertTrue(seen[1].first.startsWith("https://www.lunchflow.app/api/v1/accounts/101/transactions?from=2026-10-01&to=2026-10-17"))
        status = 403
        assertFailsWith<BankKeyRefused> { f.accounts() }
        assertEquals("abcdefgh12", LunchFlowFeed.parseKey("""{"api_key": " abcdefgh12 "}"""))
        assertEquals("abcdefgh12", LunchFlowFeed.parseKey("abcdefgh12"))
        assertNull(LunchFlowFeed.parseKey("{}"))
    }
}
