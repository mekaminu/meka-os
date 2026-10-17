package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Money manager, bank sync (2026-10-17, Meka chose Lunch Flow, Needs Meka #38): Money fills itself from his banks,
 * read-only. Navilife's server holds the Lunch Flow key (Secrets Manager only; never a device, the repo or a log), reads
 * his accounts, balances and posted transactions through Lunch Flow's Personal API every 6 hours (and when he taps
 * Refresh), and writes them into the same synced entities statement files use ([Money]):
 *
 * - `money_account` with [MoneyAccountFields.SOURCE] = [SOURCE], id [accountId] ("l" + a hash of Lunch Flow's id), its
 *   name, balance (as of the sync's day, never "typed") and [MoneyBankAccountFields.STATUS] ([OK] or [RECONNECT]);
 * - `money_txn` per posted transaction, id [txnId] from Lunch Flow's own id, the words being the merchant and description.
 *
 * The server's state goes in one server-authored `context_mode/money_sync` ([MoneyBankSyncFields]); a device asks for a
 * sync by writing `context_mode/money_refresh` ([MoneyBankRefreshFields]). An account he removes in Navilife stays
 * removed (the server never brings it back) until he taps [RESTORE]. Nothing is ever written back to a bank, and no
 * amount or account detail is logged (the deploy report counts only: [reportLine]).
 *
 * Statement files he imported before keep working: a statement account whose lines match a bank account's (same
 * amount within [MATCH_DAYS] days, at least [MATCH_MIN] lines and [MATCH_SHARE] of its lines in the bank's span) is the
 * same account, so those lines and its balance count once ([merge]). Rules only, no AI; tests use invented accounts.
 */
object MoneyBankAccountFields {
    /** [MoneyBank.OK] or [MoneyBank.RECONNECT] (the bank's consent ran out in Lunch Flow). */
    const val STATUS = "bankStatus"
}

object MoneyBankSyncFields {
    /** [MoneyBank.OK] · [MoneyBank.RECONNECT] (an account to reconnect) · [MoneyBank.ERROR] · [MoneyBank.KEY] (key refused). */
    const val STATE = "state"
    const val LAST_OK = "lastOkMs"
    const val LAST_TRY = "lastTryMs"
    /** Accounts synced (counts only). */
    const val ACCOUNTS = "accounts"
    /** Transactions held from the banks (counts only). */
    const val TXNS = "txns"
    /** Accounts to reconnect in Lunch Flow. */
    const val RECONNECT = "reconnect"
    /** Accounts left out because they aren't in pounds. */
    const val SKIPPED = "skipped"
}

object MoneyBankRefreshFields {
    /** When a device last asked for a sync (Refresh, or Sync them again). */
    const val ASKED = "askedAtMs"
}

/** What the server last wrote in `context_mode/money_sync`. */
data class MoneyBankSync(
    val state: String, val lastOkMs: Long?, val lastTryMs: Long?, val accounts: Int, val txns: Int,
    val reconnect: Int = 0, val skipped: Int = 0,
)

/** One account to reconnect, as the Money screen says it. */
data class MoneyBankReconnect(val id: String, val line: String)

/**
 * FROM YOUR BANKS on the Money screen: [line] ("Through Lunch Flow · updated 06:10 · 6 accounts"), [lit] when
 * something needs him, [refreshing] while a Refresh he asked for hasn't run yet, [reconnect] per account, and
 * [restoreLine] when accounts he removed aren't synced (with [RESTORE]).
 */
data class MoneyBankView(
    val head: String, val line: String, val lit: Boolean, val refreshing: Boolean, val reconnect: List<MoneyBankReconnect>,
    val restoreLine: String?, val spoken: String,
)

/** Which statement lines also came from a bank ([hidden], counted once) and which statement account is which bank's ([sameAs]). */
data class MoneyMerge(val txns: List<MoneyTxn>, val sameAs: Map<String, String>, val hidden: Int)

class MoneyBankStore(private val replica: Replica) {
    fun sync(): MoneyBankSync? = replica.entity(EntityTypes.CONTEXT_MODE, MoneyBank.SYNC_ID)?.let { e -> MoneyBank.read { e[it] } }

    fun askedAtMs(): Long? = replica.entity(EntityTypes.CONTEXT_MODE, MoneyBank.REFRESH_ID)?.get(MoneyBankRefreshFields.ASKED)?.longOrNull

    /** Bank accounts he removed (or deleted with all money data), which the server no longer syncs. */
    fun removed(): List<String> = replica.entities(EntityTypes.MONEY_ACCOUNT, includeDeleted = true)
        .filter { it.ref.entityId.startsWith(MoneyBank.ID_PREFIX) && it.deleted }.map { it.ref.entityId }

    /** Asks Navilife's server to sync now. */
    fun refresh(nowMs: Long) {
        replica.commitLocal(EntityTypes.CONTEXT_MODE, MoneyBank.REFRESH_ID, mapOf(MoneyBankRefreshFields.ASKED to nowMs.fv()))
    }

    /** Brings back the bank accounts he removed: the server syncs them again at once. Returns how many. */
    fun restore(nowMs: Long): Int {
        val ids = removed()
        if (ids.isEmpty()) return 0
        replica.batched {
            ids.forEach { replica.commitLocal(EntityTypes.MONEY_ACCOUNT, it, mapOf(os.meka.core.sync.DELETED_FIELD to false.fv())) }
            refresh(nowMs)
        }
        return ids.size
    }
}

object MoneyBank {
    const val SYNC_ID = "money_sync"
    const val REFRESH_ID = "money_refresh"
    const val SOURCE = "bank"
    /** Bank accounts' ids start with this (statement files' with "a-"). */
    const val ID_PREFIX = "l"
    const val OK = "ok"
    const val RECONNECT = "reconnect"
    const val ERROR = "error"
    const val KEY = "key"
    const val HEAD = "FROM YOUR BANKS"
    const val REFRESH = "Refresh"
    const val REFRESHING = "Refreshing…"
    const val RESTORE = "Sync them again"
    const val LUNCH_FLOW_URL = "https://www.lunchflow.app"
    const val OPEN = "Open Lunch Flow ›"
    const val REFRESH_SAID = "Asked Navilife's server to fetch your banks now · it takes a minute"
    const val TOO_SOON = "Your banks were fetched a moment ago · try again in a minute"
    /** The server syncs this often by itself, and an hour after a failure. */
    const val PERIOD_MS = 6 * 60 * 60_000L
    const val RETRY_MS = 60 * 60_000L
    /** A Refresh is acted on at most this often. */
    const val MIN_GAP_MS = 60_000L
    /** A Refresh still waiting after this long isn't shown as refreshing. */
    const val REFRESH_SHOWN_MS = 10 * 60_000L
    /** How far back the first sync of an account reads, and how far before the last sync later ones re-read. */
    const val FIRST_DAYS = 730L
    const val OVERLAP_DAYS = 14L
    const val MATCH_DAYS = 3L
    const val MATCH_MIN = 3
    const val MATCH_SHARE = 0.6

    fun accountId(lunchFlowId: String): String = ID_PREFIX + ActivityRules.fnv64("lunchflow:account:$lunchFlowId")

    fun txnId(accountId: String, lunchFlowTxnId: String): String = "t" + ActivityRules.fnv64("lunchflow:$accountId:$lunchFlowTxnId")

    /** A transaction's words: the merchant, then the description when it adds something ("Tesco · TESCO STORES 2041"). */
    fun text(merchant: String?, description: String?): String {
        val m = merchant?.trim().orEmpty()
        val d = description?.trim().orEmpty()
        val words = when {
            m.isEmpty() -> d
            d.isEmpty() || d.equals(m, ignoreCase = true) || d.contains(m, ignoreCase = true) && d.length <= m.length + 2 -> m
            else -> "$m · $d"
        }
        return words.replace(Regex("\\s+"), " ").take(140).ifEmpty { "Payment" }
    }

    /** An account's name in Navilife: its own name, with the bank's in front unless it already says it. */
    fun name(name: String?, institution: String?): String {
        val n = name?.trim().orEmpty()
        val i = institution?.trim().orEmpty()
        val out = when {
            n.isEmpty() -> i
            i.isEmpty() || n.contains(i, ignoreCase = true) -> n
            else -> "$i $n"
        }
        return out.replace(Regex("\\s+"), " ").take(40).ifEmpty { "Bank account" }
    }

    fun read(get: (String) -> FieldValue?): MoneyBankSync? {
        val state = get(MoneyBankSyncFields.STATE)?.textOrNull ?: return null
        return MoneyBankSync(
            state, get(MoneyBankSyncFields.LAST_OK)?.longOrNull, get(MoneyBankSyncFields.LAST_TRY)?.longOrNull,
            get(MoneyBankSyncFields.ACCOUNTS)?.longOrNull?.toInt() ?: 0, get(MoneyBankSyncFields.TXNS)?.longOrNull?.toInt() ?: 0,
            get(MoneyBankSyncFields.RECONNECT)?.longOrNull?.toInt() ?: 0, get(MoneyBankSyncFields.SKIPPED)?.longOrNull?.toInt() ?: 0,
        )
    }

    fun fields(s: MoneyBankSync): Map<String, FieldValue> = linkedMapOf(
        MoneyBankSyncFields.STATE to s.state.fv(),
        MoneyBankSyncFields.LAST_OK to (s.lastOkMs?.fv() ?: FieldValue.Null),
        MoneyBankSyncFields.LAST_TRY to (s.lastTryMs?.fv() ?: FieldValue.Null),
        MoneyBankSyncFields.ACCOUNTS to s.accounts.toLong().fv(),
        MoneyBankSyncFields.TXNS to s.txns.toLong().fv(),
        MoneyBankSyncFields.RECONNECT to s.reconnect.toLong().fv(),
        MoneyBankSyncFields.SKIPPED to s.skipped.toLong().fv(),
    )

    /**
     * Whether the server should sync now: never synced, a Refresh asked since the last try (at most every
     * [MIN_GAP_MS]), or [PERIOD_MS] since the last try ([RETRY_MS] after a failure).
     */
    fun due(s: MoneyBankSync?, askedAtMs: Long?, nowMs: Long): Boolean {
        val last = s?.lastTryMs ?: return true
        if (askedAtMs != null && askedAtMs > last && nowMs - last >= MIN_GAP_MS) return true
        val wait = if (s.state == ERROR || s.state == KEY) RETRY_MS else PERIOD_MS
        return nowMs - last >= wait
    }

    /** Whether a Refresh he asked for is still waiting for the server. */
    fun refreshing(s: MoneyBankSync?, askedAtMs: Long?, nowMs: Long): Boolean =
        askedAtMs != null && nowMs - askedAtMs in 0 until REFRESH_SHOWN_MS && (s?.lastTryMs ?: 0L) < askedAtMs

    private fun at(ms: Long, nowMs: Long, cal: LocalCalendar): String = HealthRules.at(ms, nowMs, cal)

    /** FROM YOUR BANKS, or null before the server has synced anything (no key yet). */
    fun view(
        s: MoneyBankSync?, accounts: List<MoneyAccount>, removed: Int, askedAtMs: Long?, nowMs: Long, cal: LocalCalendar,
    ): MoneyBankView? {
        if (s == null) return null
        val banks = accounts.filter { it.source == SOURCE }
        val reconnect = banks.filter { it.bankStatus == RECONNECT }.sortedBy { it.name.lowercase() }.map { a ->
            MoneyBankReconnect(a.id, "Reconnect ${a.name} in Lunch Flow · " +
                (a.importedAtMs?.let { "last updated ${CivilDate.shortLabel(cal.epochDayOf(it))}" } ?: "not updated yet") +
                " · its bank asks every 90 days or so")
        }
        val n = banks.size
        val count = "$n account${if (n == 1) "" else "s"}"
        val updated = s.lastOkMs?.let { "updated ${at(it, nowMs, cal)}" }
        val line = when (s.state) {
            KEY -> "Lunch Flow refused Navilife's key · make a new one in Lunch Flow and put it in Navilife's secret (Needs Meka)" +
                (updated?.let { " · last $it" } ?: "")
            ERROR -> "Couldn't reach Lunch Flow " + (s.lastTryMs?.let { "at ${at(it, nowMs, cal)}" } ?: "") +
                " · trying again within the hour" + (updated?.let { " · last $it" } ?: "")
            else -> listOfNotNull("Through Lunch Flow, read-only", updated, count).joinToString(" · ")
        }.replace("  ", " ")
        val skipped = if (s.skipped > 0) " · ${s.skipped} not in pounds left out" else ""
        val restore = if (removed > 0) "$removed bank account${if (removed == 1) "" else "s"} you removed ${if (removed == 1) "isn't" else "aren't"} synced" else null
        return MoneyBankView(
            HEAD, line + skipped, lit = s.state == KEY || s.state == ERROR || reconnect.isNotEmpty(),
            refreshing = refreshing(s, askedAtMs, nowMs), reconnect = reconnect, restoreLine = restore,
            spoken = (listOf(line) + reconnect.map { it.line }).joinToString(". "),
        )
    }

    /** Health's Bank sync row; null before the server has synced anything. Never a name or an amount. */
    fun healthRow(s: MoneyBankSync?, nowMs: Long, cal: LocalCalendar): HealthRow? {
        s ?: return null
        val title = "Bank sync"
        val last = s.lastOkMs?.let { "last ${at(it, nowMs, cal)}" }
        return when {
            s.state == KEY -> HealthRow(HEALTH_KEY, title, "Lunch Flow refused the key · make a new one" + (last?.let { " · $it" } ?: ""), HealthState.BAD)
            s.state == ERROR -> HealthRow(HEALTH_KEY, title, "Couldn't reach Lunch Flow" + (last?.let { " · $it" } ?: ""),
                if (s.lastOkMs != null && nowMs - s.lastOkMs < 2 * 24 * 60 * 60_000L) HealthState.WARN else HealthState.BAD)
            s.reconnect > 0 -> HealthRow(HEALTH_KEY, title,
                "${s.reconnect} account${if (s.reconnect == 1) "" else "s"} to reconnect in Lunch Flow · open Money", HealthState.WARN)
            else -> HealthRow(HEALTH_KEY, title, listOfNotNull("On", last, "${s.accounts} account${if (s.accounts == 1) "" else "s"}").joinToString(" · "), HealthState.OK)
        }
    }

    const val HEALTH_KEY = "bank"

    /** The deploy report's line: counts only, never a name or an amount. */
    fun reportLine(s: MoneyBankSync?, utc: (Long) -> String): String = if (s == null) "not synced yet (no key, or the first sync hasn't run)" else
        listOfNotNull(
            s.state, "${s.accounts} accounts · ${s.txns} transactions",
            s.reconnect.takeIf { it > 0 }?.let { "$it to reconnect" }, s.skipped.takeIf { it > 0 }?.let { "$it not in pounds" },
            s.lastOkMs?.let { "last ok ${utc(it)}" }, s.lastTryMs?.let { "last try ${utc(it)}" },
        ).joinToString(" · ")

    /**
     * Statement lines that came from a bank too, counted once: for each statement account, the first bank account whose
     * lines match enough of its lines inside the bank's span (same pence, days within [MATCH_DAYS]) is the same
     * account; those matched statement lines are left out. Unmatched lines stay.
     */
    fun merge(accounts: List<MoneyAccount>, txns: List<MoneyTxn>): MoneyMerge {
        val banks = accounts.filter { it.source == SOURCE }
        if (banks.isEmpty()) return MoneyMerge(txns, emptyMap(), 0)
        val byAccount = txns.groupBy { it.account }
        val hidden = HashSet<String>()
        val sameAs = LinkedHashMap<String, String>()
        for (csv in accounts.filter { it.source != SOURCE }) {
            val lines = byAccount[csv.id].orEmpty()
            if (lines.size < MATCH_MIN) continue
            for (bank in banks) {
                val bankLines = byAccount[bank.id].orEmpty()
                if (bankLines.isEmpty()) continue
                val from = bankLines.minOf { it.day }
                val to = bankLines.maxOf { it.day }
                val inSpan = lines.filter { it.day in (from - MATCH_DAYS)..(to + MATCH_DAYS) }.sortedBy { it.day }
                if (inSpan.size < MATCH_MIN) continue
                val pool = bankLines.groupBy { it.pence }.mapValues { (_, l) -> l.map { it.day }.sorted().toMutableList() }
                val matched = inSpan.filter { t ->
                    val days = pool[t.pence] ?: return@filter false
                    val i = days.indexOfFirst { kotlin.math.abs(it - t.day) <= MATCH_DAYS }
                    if (i < 0) false else { days.removeAt(i); true }
                }
                if (matched.size >= MATCH_MIN && matched.size >= inSpan.size * MATCH_SHARE) {
                    matched.forEach { hidden += it.id }
                    sameAs[csv.id] = bank.id
                    break
                }
            }
        }
        return MoneyMerge(if (hidden.isEmpty()) txns else txns.filter { it.id !in hidden }, sameAs, hidden.size)
    }
}
