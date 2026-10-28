package os.meka.core.domain

import os.meka.core.sync.EntityRef
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Money manager (V1, Meka approved 2026-10-12 21:19), slice 1: **a bank statement file in, this month's spending by
 * category out**, on Ask's **Money** tile behind his fingerprint (Touch ID on the Mac). Read-only insight on his own
 * numbers: Navilife never moves money, never pays anything, never holds a bank password, and nothing here is advice.
 *
 * Enable Banking (Open Banking, read-only) isn't set up yet (Needs Meka), so this slice is the path the board keeps for
 * any bank it doesn't cover: the CSV statement every UK bank and card lets you download ([MoneyCsv]). A later slice
 * fills the same entities from Enable Banking.
 *
 * Stored as synced entities (ADR-008 addendum 2026-10-16, additive; his encrypted database, never logs or the repo):
 * - `money_account` ([MoneyAccountFields]): one per account he names ("Current account"), id `a-<name folded>`.
 * - `money_txn` ([MoneyTxnFields]): one per statement line, id from the account, day, amount, words and how many
 *   identical lines came before it in the file, so importing an overlapping statement again adds nothing twice.
 * - `money_rule` ([MoneyRuleFields]): his correction "Tesco is Groceries", id `m-<merchant key>`; every payment to that
 *   merchant follows it (corrections teach). LWW, so Undo puts the old one back.
 *
 * Categories are rules over the merchant's words ([MoneyCategoryRules]): no AI in this slice. Pure rules
 * ([MoneyRules], [MoneyCsv]) over a thin store ([Money]). Tests use invented statements.
 */
object MoneyAccountFields {
    const val NAME = "name"
    /** "csv" (a statement file); "bank" from his banks through Lunch Flow ([MoneyBank]). */
    const val SOURCE = "source"
    const val IMPORTED_AT = "importedAtMs"
    /** Slice 6: the balance in signed pence (overdrawn is negative), from a statement's Balance column or typed; Null when unknown. */
    const val BALANCE = "balancePence"
    /** Slice 6: the epoch day the balance is as of. */
    const val BALANCE_DAY = "balanceDay"
    /** Slice 6: true when he typed it, false when a statement file gave it. */
    const val BALANCE_TYPED = "balanceTyped"
    /**
     * Money review, Accounts' rest (2026-10-27): the name he gave the account (Text, Null = the bank's or file's own name).
     * Shown on Accounts, Bills and the card nudges; the stored [NAME] stays as it came, so transfers between his accounts
     * are still found by the bank's words. Additive LWW field (ADR-008 addendum); an older app ignores it.
     */
    const val NICK = "nick"
}

object MoneyTxnFields {
    const val ACCOUNT = "account"
    /** Epoch day of the statement's date. */
    const val DAY = "day"
    /** Signed pence: money out is negative, money in positive. */
    const val PENCE = "pence"
    /** The statement's words for it ("CARD PAYMENT TO TESCO STORES 2041"). */
    const val TEXT = "text"
}

/** Slice 2: one kind of tip, id its [MoneyTipKind.key]. */
object MoneyTipsFields {
    /** True while he has that kind hidden. */
    const val HIDDEN = "hidden"
    /** Obi's money check: on an idea's entity ("idea-<id>"), the epoch day Remind me brings it back (null when not asked). */
    const val UNTIL = "until"
    /** Obi's wording ([MoneyCheckWords]), on an idea's entity: his title, his why and its place in his order (Null when his last run didn't word it). */
    const val WORD_TITLE = "wTitle"
    const val WORD_WHY = "wWhy"
    const val RANK = "rank"
    /** On [MoneyCheckWords.ENTITY]: when Obi last wrote the ideas (epoch ms) and that day (epoch day). */
    const val AT = "at"
    const val DAY = "day"
}

/** Slice 3: one watchdog alert he put away, id the alert's id ([MoneyWatch]). */
object MoneyAlertFields {
    /** True once he said "That's fine"; false after Undo; Null after Delete all money data. */
    const val FINE = "fine"
}

object MoneyRuleFields {
    const val MERCHANT = "merchant"
    /** A [MoneyCategory.key]; Null when the correction was taken back. */
    const val CATEGORY = "category"
}

/** What a payment is for. [spend] categories count as spending; income and transfers don't. */
enum class MoneyCategory(val key: String, val label: String, val spend: Boolean = true) {
    GROCERIES("groceries", "Groceries"),
    EATING_OUT("eating_out", "Eating out"),
    COFFEE("coffee", "Coffee"),
    TAKEAWAY("takeaway", "Takeaways"),
    TRANSPORT("transport", "Transport"),
    FUEL("fuel", "Fuel"),
    BILLS("bills", "Bills"),
    SUBSCRIPTIONS("subscriptions", "Subscriptions"),
    SHOPPING("shopping", "Shopping"),
    CLOTHES("clothes", "Clothes"),
    HOME("home", "Home"),
    HEALTH("health", "Health and fitness"),
    FUN("fun", "Going out and fun"),
    TRAVEL("travel", "Travel"),
    KIDS("kids", "Kids"),
    GIVING("giving", "Gifts and giving"),
    CASH("cash", "Cash"),
    FEES("fees", "Fees and interest"),
    OTHER("other", "Other"),
    INCOME("income", "Income", spend = false),
    TRANSFERS("transfers", "Transfers", spend = false),
    ;

    companion object {
        fun of(key: String?): MoneyCategory? = entries.firstOrNull { it.key == key }
    }
}

/** One statement line as read from a file: its day, signed pence and words. */
data class MoneyRow(val day: Long, val pence: Long, val text: String)

/**
 * What [MoneyCsv.read] made of a file: the [rows] it could read, how many lines it [skipped] (no date or amount), whether
 * it [flipped] the signs (a card statement listing purchases as positive), or why it read nothing ([problem]).
 */
data class MoneyCsvRead(
    val rows: List<MoneyRow>, val skipped: Int = 0, val flipped: Boolean = false, val problem: String? = null,
    /** Slice 6: the balance after the statement's latest line, when the file has a Balance column (never for a card read the other way round). */
    val balancePence: Long? = null, val balanceDay: Long? = null,
)

/** One stored statement line. */
data class MoneyTxn(val id: String, val account: String, val day: Long, val pence: Long, val text: String)

data class MoneyAccount(
    val id: String, val name: String, val source: String, val importedAtMs: Long?, val balance: MoneyBalance? = null,
    /** Bank sync: [MoneyBank.OK] or [MoneyBank.RECONNECT] for an account from his banks; null for a statement file's. */
    val bankStatus: String? = null,
    /** The name he gave it ([MoneyAccountFields.NICK]), or null. */
    val nick: String? = null,
)

/**
 * Reads a bank or card statement saved as CSV. Finds the header row (in the first 15 lines), then the date column
 * ("Date", "Transaction Date", "Posted Date", "Completed Date"…), the words ("Description", "Merchant", "Name", "Payee",
 * "Details", "Narrative", "Reference"…) and either one signed amount ("Amount", "Value", "Amount (GBP)") or a pair
 * ("Paid out"/"Paid in", "Money out"/"Money in", "Debit"/"Credit", "Out"/"In"). Dates as 15/10/2026, 15/10/26,
 * 2026-10-15 (a time after it is fine), 15 Oct 2026 or 15-Oct-26; amounts as "£1,234.56", "-12.30", "(12.30)" or
 * "12.30 DR". A card statement that lists purchases as positive (most lines positive, its payments negative and named
 * as a payment) is turned round so money out is always negative. Pure; no I/O.
 */
object MoneyCsv {
    const val MAX_ROWS = 5000
    const val NOT_CSV = "That file isn't a statement Navilife can read · save it from your bank as CSV (a spreadsheet file) and try again."
    const val NO_HEADER = "Couldn't find the date, description and amount columns · check it's the CSV of transactions from your bank."
    const val NO_ROWS = "No transactions in that file."

    private val DATE_HEADS = listOf("transaction date", "date", "posted date", "posting date", "completed date", "started date", "value date", "booking date", "date posted", "trans date")
    private val TEXT_HEADS = listOf("description", "transaction description", "merchant", "merchant name", "name", "payee", "details", "narrative", "memo", "reference", "counter party", "counterparty", "type description")
    private val AMOUNT_HEADS = listOf("amount", "amount (gbp)", "amount(gbp)", "value", "transaction amount", "amount gbp", "billing amount")
    private val OUT_HEADS = listOf("paid out", "money out", "debit", "debit amount", "out", "withdrawals", "spent")
    private val IN_HEADS = listOf("paid in", "money in", "credit", "credit amount", "in", "deposits", "received")
    private val BALANCE_HEADS = listOf("balance", "running balance", "balance (gbp)", "balance(gbp)", "balance gbp", "account balance", "closing balance")

    /** Splits one CSV line, honouring quotes ("a, b" stays one field; "" is a quote). */
    fun split(line: String, sep: Char = ','): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == sep -> { out += sb.toString().trim(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString().trim()
        return out
    }

    /** Lines of [text], keeping a quoted field's line break inside its line. */
    private fun lines(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        for (c in text.removePrefix("﻿")) {
            if (c == '"') quoted = !quoted
            if (!quoted && (c == '\n' || c == '\r')) {
                if (sb.isNotBlank()) out += sb.toString()
                sb.clear()
            } else sb.append(if (c == '\n' || c == '\r') ' ' else c)
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out
    }

    private fun norm(h: String): String = h.trim().trim('"').lowercase().replace(Regex("\\s+"), " ")

    private fun col(heads: List<String>, names: List<String>): Int? {
        for (n in names) heads.indexOf(n).takeIf { it >= 0 }?.let { return it }
        return null
    }

    fun read(text: String): MoneyCsvRead {
        if (text.isBlank()) return MoneyCsvRead(emptyList(), problem = NO_ROWS)
        val all = lines(text)
        if (all.none { it.contains(',') || it.contains(';') || it.contains('\t') }) return MoneyCsvRead(emptyList(), problem = NOT_CSV)
        if (all.first().trimStart().startsWith("OFXHEADER") || all.first().trimStart().startsWith("<")) return MoneyCsvRead(emptyList(), problem = NOT_CSV)
        var headAt = -1
        var sep = ','
        var heads: List<String> = emptyList()
        for ((i, l) in all.take(15).withIndex()) {
            val s = listOf(',', ';', '\t').maxBy { c -> l.count { it == c } }
            val h = split(l, s).map(::norm)
            val hasDate = col(h, DATE_HEADS) != null
            val hasAmount = col(h, AMOUNT_HEADS) != null || (col(h, OUT_HEADS) != null && col(h, IN_HEADS) != null)
            if (hasDate && hasAmount) { headAt = i; sep = s; heads = h; break }
        }
        if (headAt < 0) return MoneyCsvRead(emptyList(), problem = NO_HEADER)
        val dateCol = col(heads, DATE_HEADS)!!
        val amountCol = col(heads, AMOUNT_HEADS)
        val outCol = if (amountCol == null) col(heads, OUT_HEADS) else null
        val inCol = if (amountCol == null) col(heads, IN_HEADS) else null
        val textCols = TEXT_HEADS.mapNotNull { n -> heads.indexOf(n).takeIf { it >= 0 } }.distinct().take(2)
        val typeCol = heads.indexOf("type").takeIf { it >= 0 }
        val balanceCol = col(heads, BALANCE_HEADS)
        val balances = mutableListOf<Pair<Long, Long>>()
        val rows = mutableListOf<MoneyRow>()
        var skipped = 0
        for (l in all.drop(headAt + 1)) {
            if (rows.size >= MAX_ROWS) { skipped++; continue }
            val f = split(l, sep)
            val day = f.getOrNull(dateCol)?.let(::day)
            val pence = when {
                amountCol != null -> f.getOrNull(amountCol)?.let(::pence)
                else -> {
                    val out = f.getOrNull(outCol!!)?.let(::pence)?.let { -kotlin.math.abs(it) }
                    val inn = f.getOrNull(inCol!!)?.let(::pence)?.let { kotlin.math.abs(it) }
                    when {
                        out != null && out != 0L -> out
                        inn != null && inn != 0L -> inn
                        else -> out ?: inn
                    }
                }
            }
            val words = textCols.mapNotNull { f.getOrNull(it)?.trim()?.takeIf { w -> w.isNotEmpty() } }.distinct().joinToString(" · ")
                .ifEmpty { typeCol?.let { f.getOrNull(it) }.orEmpty() }
            if (day == null || pence == null || pence == 0L) { skipped++; continue }
            rows += MoneyRow(day, pence, words.ifEmpty { "Payment" }.take(140))
            balanceCol?.let { f.getOrNull(it) }?.let(::pence)?.let { balances += day to it }
        }
        if (rows.isEmpty()) return MoneyCsvRead(emptyList(), skipped, problem = NO_ROWS)
        // A card statement: purchases positive, its payments negative and called a payment.
        val positive = rows.count { it.pence > 0 }
        val payments = rows.filter { it.pence < 0 }
        val flip = amountCol != null && positive * 10 >= rows.size * 7 && payments.isNotEmpty() &&
            payments.all { PAYMENT.containsMatchIn(it.text) || REFUND.containsMatchIn(it.text) } && payments.any { PAYMENT.containsMatchIn(it.text) }
        val kept = if (flip) rows.map { it.copy(pence = -it.pence) } else rows
        // The balance after the latest day: newest-first files list it first on that day, oldest-first last.
        val bal = if (flip || balances.isEmpty()) null else {
            val latest = balances.maxOf { it.first }
            val onLatest = balances.filter { it.first == latest }
            if (balances.first().first > balances.last().first) onLatest.first() else onLatest.last()
        }
        return MoneyCsvRead(kept, skipped, flipped = flip, balancePence = bal?.second, balanceDay = bal?.first)
    }

    private val PAYMENT = Regex("""\b(payment|thank you|paid|direct debit)\b""", RegexOption.IGNORE_CASE)
    private val REFUND = Regex("""\b(refund|credit|reversal|returned)\b""", RegexOption.IGNORE_CASE)

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** A statement date as an epoch day, or null. Day-first (UK) for slashes. */
    fun day(raw: String): Long? {
        val s = raw.trim().trim('"').substringBefore('T').trim()
        Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})""").find(s)?.let { m ->
            return ymd(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        Regex("""^(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2,4})""").find(s)?.let { m ->
            return ymd(year(m.groupValues[3]), m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        Regex("""^(\d{1,2})[ \-]([A-Za-z]{3})[A-Za-z]*[ \-,]+(\d{2,4})""").find(s)?.let { m ->
            val mo = MONTHS.indexOf(m.groupValues[2].lowercase()) + 1
            if (mo > 0) return ymd(year(m.groupValues[3]), mo, m.groupValues[1].toInt())
        }
        return null
    }

    private fun year(y: String): Int = y.toInt().let { if (y.length == 2) 2000 + it else it }

    private fun ymd(y: Int, m: Int, d: Int): Long? {
        if (y !in 1990..2100 || m !in 1..12 || d < 1 || d > CivilDate.lengthOfMonth(y, m)) return null
        return CivilDate.toEpochDay(y, m, d)
    }

    /** An amount as signed pence, or null: "£1,234.56", "-12.30", "(12.30)", "12.30 DR", "+5", "12.3". */
    fun pence(raw: String): Long? {
        var s = raw.trim().trim('"').replace("£", "").replace("GBP", "", ignoreCase = true).replace(",", "").replace(" ", "")
        if (s.isEmpty()) return null
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) { negative = true; s = s.drop(1).dropLast(1) }
        if (s.endsWith("DR", ignoreCase = true)) { negative = true; s = s.dropLast(2) }
        if (s.endsWith("CR", ignoreCase = true)) s = s.dropLast(2)
        if (s.startsWith("-")) { negative = !negative; s = s.drop(1) } else if (s.startsWith("+")) s = s.drop(1)
        if (s.startsWith("£")) s = s.drop(1)
        val m = Regex("""^(\d+)(?:\.(\d{1,2}))?$""").matchEntire(s) ?: return null
        val pounds = m.groupValues[1].toLongOrNull() ?: return null
        val p = m.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong()
        val total = pounds * 100 + p
        return if (negative) -total else total
    }
}

/** The merchant behind a statement line, and which category its words point to. Non-AI, pure. */
object MoneyCategoryRules {
    private val NOISE = listOf(
        "CARD PAYMENT TO ", "CARD PAYMENT ", "CONTACTLESS PAYMENT ", "CONTACTLESS ", "PURCHASE ", "POS ", "VIS ", "DEBIT CARD ",
        "FASTER PAYMENT TO ", "FASTER PAYMENT ", "DIRECT DEBIT TO ", "DIRECT DEBIT ", "STANDING ORDER TO ", "STANDING ORDER ",
        "PAYMENT TO ", "BILL PAYMENT TO ", "APPLE PAY ", "GOOGLE PAY ", "CHIP AND PIN ",
    )

    /**
     * The merchant's key: the words upper-cased, the bank's own prefixes ("CARD PAYMENT TO") dropped, a reference after
     * "*" and long numbers and dates dropped, the first three words kept: "CARD PAYMENT TO TESCO STORES 2041 ON 14 OCT"
     * → "TESCO STORES". Never empty.
     */
    fun merchantKey(text: String): String {
        var s = " " + text.uppercase().replace('·', ' ') + " "
        var changed = true
        while (changed) {
            changed = false
            for (n in NOISE) if (s.trimStart().startsWith(n)) { s = " " + s.trimStart().removePrefix(n); changed = true }
        }
        s = s.substringBefore('*').let { if (it.isBlank()) s.replace("*", " ") else it }
        s = s.replace(Regex("""\bON \d{1,2} ?[A-Z]{3}\b.*"""), " ")
            .replace(Regex("""\d{1,2}[/.\-]\d{1,2}([/.\-]\d{2,4})?"""), " ")
            .replace(Regex("""\b\d{4,}\b"""), " ")
            .replace(Regex("""\b[A-Z]*\d+[A-Z\d]*\b""")) { m -> if (m.value.length <= 3 && m.value.any { it.isLetter() }) m.value else " " }
            .replace(Regex("""[^A-Z0-9&.' ]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        val words = s.split(' ').filter { it.isNotEmpty() && it !in setOf("GB", "GBR", "UK", "LTD", "LIMITED", "PLC", "LONDON") }
        return words.take(3).joinToString(" ").ifEmpty { text.trim().uppercase().take(24).ifEmpty { "PAYMENT" } }
    }

    /** "TESCO STORES" → "Tesco Stores"; short all-caps words (TFL, BP, EE) stay as they are. */
    fun merchantLabel(key: String): String = key.split(' ').joinToString(" ") { w ->
        when {
            w.length <= 3 && w.none { it.isDigit() } && w !in setOf("THE", "AND", "OF") -> w
            else -> w.lowercase().replaceFirstChar { it.titlecase() }
        }
    }

    /** Words → category, in order: the first that matches wins (so "UBER EATS" is a takeaway before "UBER" is transport). */
    private val RULES: List<Pair<MoneyCategory, Regex>> = listOf(
        MoneyCategory.TRANSFERS to w("transfer", "to savings", "from savings", "savings pot", "pot transfer", "own account", "american express", "american exp", "amex", "capital one", "mbna", "vanquis", "barclaycard", "credit card payment", "card repayment", "payment received", "payment thank you", "thank you for your payment", "monzo", "revolut", "starling", "top up", "topup"),
        MoneyCategory.TAKEAWAY to w("deliveroo", "just eat", "justeat", "uber eats", "ubereats", "domino", "papa john", "hungry"),
        MoneyCategory.COFFEE to w("costa", "starbucks", "pret", "caffe nero", "nero", "gail", "coffee", "cafe", "café", "black sheep", "joe & the juice"),
        MoneyCategory.EATING_OUT to w("mcdonald", "nando", "wagamama", "kfc", "burger king", "greggs", "restaurant", "pizza express", "pizzaexpress", "leon", "itsu", "wasabi", "five guys", "subway", "toby carvery", "harvester", "dishoom", "zizzi", "prezzo", "bella italia", "wetherspoon", "pub", "tap ", "bar ", "grill", "kitchen", "chicken"),
        MoneyCategory.GROCERIES to w("tesco", "sainsbury", "asda", "morrisons", "aldi", "lidl", "waitrose", "co-op", "coop", "co op", "ocado", "iceland", "m&s food", "m & s food", "simply food", "marks & spencer", "marks and spencer", "farmfoods", "budgens", "spar", "londis", "whole foods", "supermarket", "grocer"),
        MoneyCategory.FUEL to w("shell", "esso", "texaco", "petrol", "fuel", "bp ", "jet ", "gulf", "murco", "ev charge", "pod point", "chargepoint", "ionity", "instavolt"),
        MoneyCategory.TRANSPORT to w("tfl", "transport for london", "trainline", "national rail", "gwr", "southern", "thameslink", "southeastern", "south western", "lner", "avanti", "c2c", "greater anglia", "uber", "bolt", "addison lee", "taxi", "parking", "ringgo", "paybyphone", "justpark", "ncp", "dart charge", "congestion", "ulez", "bus", "rail", "oyster"),
        MoneyCategory.SUBSCRIPTIONS to w("netflix", "spotify", "disney", "amazon prime", "prime video", "primevideo", "apple.com", "apple com", "itunes", "icloud", "google storage", "google one", "youtube", "now tv", "nowtv", "dazn", "patreon", "microsoft", "xbox game pass", "adobe", "audible", "kindle unlimited", "chatgpt", "openai", "anthropic", "claude.ai", "duolingo", "strava", "github", "dropbox", "notion", "subscription"),
        MoneyCategory.BILLS to w("council tax", "british gas", "octopus", "edf", "e.on", "eon next", "ovo", "scottish power", "thames water", "anglian water", "severn trent", "water", "virgin media", "bt group", "bt ", "sky ", "sky digital", "vodafone", "ee ", "o2", "three", "giffgaff", "tv licence", "tv licensing", "insurance", "aviva", "direct line", "admiral", "lv=", "churchill", "hastings", "mortgage", "rent", "energy", "broadband", "dvla", "hmrc"),
        MoneyCategory.HEALTH to w("boots", "superdrug", "pharmacy", "chemist", "gym", "puregym", "the gym", "david lloyd", "nuffield", "dental", "dentist", "optician", "specsavers", "vision express", "physio", "bupa", "holland & barrett", "holland and barrett", "myprotein"),
        MoneyCategory.CLOTHES to w("next ", "primark", "h&m", "h & m", "zara", "uniqlo", "jd sports", "sports direct", "asos", "nike", "adidas", "tk maxx", "tkmaxx", "river island", "fat face", "clarks", "schuh", "mango", "gap ", "boohoo", "very.co"),
        MoneyCategory.HOME to w("screwfix", "toolstation", "wickes", "b&q", "b & q", "dunelm", "homebase", "the range", "ikea", "habitat", "wilko"),
        MoneyCategory.SHOPPING to w("amazon", "amzn", "ebay", "argos", "john lewis", "currys", "whsmith", "wh smith", "waterstones", "the works", "etsy", "temu", "shein", "apple store", "game ", "smyths", "hobbycraft", "poundland", "b&m", "home bargains", "marks & spencer"),
        MoneyCategory.FUN to w("cinema", "odeon", "vue ", "cineworld", "picturehouse", "everyman", "ticketmaster", "eventbrite", "see tickets", "steam", "playstation", "nintendo", "theatre", "bowling", "football club", "fc ", "stadium", "golf"),
        MoneyCategory.TRAVEL to w("booking.com", "airbnb", "easyjet", "ryanair", "british airways", "jet2", "tui", "expedia", "hotels.com", "hotel", "premier inn", "travelodge", "airport", "heathrow", "gatwick", "eurostar", "holiday"),
        MoneyCategory.KIDS to w("school", "nursery", "childcare", "after school", "parentpay", "scopay", "school money", "toys", "kids", "smyths toys"),
        MoneyCategory.GIVING to w("donation", "charity", "justgiving", "gofundme", "moonpig", "funky pigeon", "card factory", "interflora"),
        MoneyCategory.CASH to w("cash", "atm", "link atm", "cashpoint", "withdrawal"),
        MoneyCategory.FEES to w("interest", "overdraft", "late fee", "late payment", "non-sterling", "non sterling", "foreign transaction", "fx fee", "annual fee", "account fee", "monthly fee", "charge for"),
    )

    private val INCOME_WORDS = w("salary", "wages", "payroll", "bgc", "bacs credit", "hmrc", "child benefit", "dividend", "interest paid", "invoice", "refund from hmrc")

    /** Any of [words] as whole words (a plural or 's after one is fine): "bus" never matches "business". */
    private fun w(vararg words: String): Regex =
        Regex("(^|[^a-z0-9])(" + words.joinToString("|") { Regex.escape(it.trim().lowercase()) } + ")(s|'s)?([^a-z0-9]|$)", RegexOption.IGNORE_CASE)

    /** Money transfers: the words say this money in is pay or benefits ("SALARY", "HMRC"), so it is never one of his own moves. */
    fun incomeWords(text: String): Boolean = INCOME_WORDS.containsMatchIn(" " + text.lowercase() + " ")

    /**
     * What the words point to. Money out with no match is [MoneyCategory.OTHER]; money in from a shop is that shop's
     * category (a refund, netting off its spending), else a transfer by its words, else [MoneyCategory.INCOME].
     */
    fun categoryOf(text: String, pence: Long): MoneyCategory {
        val t = " " + text.lowercase() + " "
        val hit = RULES.firstOrNull { it.second.containsMatchIn(t) }?.first
        if (pence < 0) return hit ?: MoneyCategory.OTHER
        if (INCOME_WORDS.containsMatchIn(t)) return MoneyCategory.INCOME
        return when (hit) {
            null, MoneyCategory.CASH -> MoneyCategory.INCOME
            else -> hit
        }
    }
}

/** One category's month on the Money screen. [share] of the month's spending (0–1) draws the bar; [over] lights it. */
data class MoneyCategoryRow(
    val key: String, val label: String, val amount: String, val share: Float, val line: String, val over: Boolean, val spoken: String,
)

/** One payment on the Money screen. [amount] signed ("−£23.40", "+£2,500"); [category] its category's key. */
data class MoneyTxnRow(
    val id: String, val day: String, val merchant: String, val amount: String, val moneyIn: Boolean,
    val category: String, val categoryLabel: String, val account: String, val taught: Boolean, val spoken: String,
    /** Slice 13: counted as the side hustle's ([MoneyBusiness]). */
    val business: Boolean = false,
    /** Slice 14: counted in the shared costs with his partner ([MoneyShared]); [sharedAlways] when it's the payer's mark. */
    val shared: Boolean = false,
    val sharedAlways: Boolean = false,
    /** Money at a glance: its receipt or order email ([MoneyReceipts.line]) and the preview's detail, when matched. */
    val receipt: String? = null,
    val receiptDetail: String? = null,
    /** Needs and wants ([MoneyNeeds]): a need (his mark on the payer, else its category's default); [needMarked] when he marked the payer. */
    val need: Boolean = false,
    val needMarked: Boolean = false,
)

data class MoneyAccountRow(
    val id: String, val name: String, val line: String,
    /** Money review, Accounts: how many lines it has, whether it comes from his bank (and needs reconnecting), and "26 Oct" it was last updated or imported. */
    val txns: Int = 0, val bank: Boolean = false, val reconnect: Boolean = false, val updated: String? = null,
    /** Accounts' rest: the name he gave it, shown instead of the short name; null for its own. */
    val nick: String? = null,
)

/**
 * The month on the Money screen: [label] ("October so far"), [spentLine] ("Spent £1,234.50 · in £2,500"), [vsLine]
 * against the 6 months before it (null with no months to compare), each spend category biggest first, and its payments
 * newest first ([more] past [MoneyRules.SHOWN_TXNS]).
 */
data class MoneyMonth(
    val offset: Int, val label: String, val spentLine: String, val vsLine: String?, val vsLit: Boolean,
    val categories: List<MoneyCategoryRow>, val txns: List<MoneyTxnRow>, val more: Int,
    /** Money at a glance: "4 payments this month have a receipt in your email" ([MoneyReceipts.monthLine]); null with none. */
    val receiptsLine: String? = null,
    /** Money redesign (B2): the Spending tab's ring, tiles, category rows and latest payments by day ([MoneySpending]). */
    val spending: MoneySpendingView? = null,
    /** Money review: the month's payments without a category, by payee, for Spending's "Sort them" ([MoneySorter]). */
    val unsorted: MoneyUnsortedView? = null,
)

/**
 * The Money screen. [locked] until his fingerprint opens it (then [MoneyRules.view]); [empty] before anything is
 * imported; [earlier]/[later] whether there are months either side of the one shown.
 */
data class MoneyView(
    val locked: Boolean, val summary: String, val empty: String?, val month: MoneyMonth?, val accounts: List<MoneyAccountRow>,
    val earlier: Boolean, val later: Boolean,
    /** Slice 2: tips from his own numbers ([MoneyTips]), shown with this month only. */
    val tips: List<MoneyTip> = emptyList(),
    /** Slice 2: his regular payments and their monthly total, shown with this month only. */
    val regular: MoneyRegular? = null,
    /** Slice 2: "Hidden: tips on habits" with Show them again; null when nothing is hidden. */
    val hiddenTips: String? = null,
    /** Slice 3: payments worth a second look ([MoneyWatch]), shown with this month only. */
    val alerts: List<MoneyAlert> = emptyList(),
    /** Slice 4: the forecast at his pace with what-ifs ([MoneyForecast]), shown with this month only. */
    val forecast: MoneyForecastView? = null,
    /** Slice 5: refunds on Waiting for checked against his statements ([MoneyRefunds]), shown with this month only. */
    val refunds: MoneyRefundsView? = null,
    /** Slice 6: payday, what's still to go out before it and what's safe to spend ([MoneyPayday]), shown with this month only. */
    val payday: MoneyPaydayView? = null,
    /** Money redesign (B3): the Bills tab's card and the month's bills by date ([MoneyBills]), shown with this month only. */
    val bills: MoneyBillsView? = null,
    /** Slice 7: his cards — due dates, statements, limits, 0% ending, fees ([MoneyCards]), shown with this month only. */
    val cards: MoneyCardsView? = null,
    /** Slice 8: his net worth — balances, cards, what he typed, and the month-by-month line ([MoneyWorth]); shown with this month (and before any statement). */
    val worth: MoneyWorthView? = null,
    /** Bank sync: FROM YOUR BANKS — Lunch Flow's state, Refresh, accounts to reconnect ([MoneyBank]); shown whatever month. */
    val bank: MoneyBankView? = null,
    /** Trading tracker: TRADING · HYPERLIQUID — his public addresses, equity, P&L and open positions ([TradingTracker]); shown whatever month. */
    val trading: TradingView? = null,
    /** Slice 13: SIDE HUSTLE AND TAX — the side hustle's tax year, the share to put aside, Self Assessment dates ([MoneyBusiness]); shown whatever month. */
    val business: MoneyBusinessView? = null,
    /** Slice 14: SHARED WITH <PARTNER> — shared costs and who paid what ([MoneyShared]); shown whatever month. */
    val shared: MoneySharedView? = null,
    /** Slice 15: CHALLENGES — no-spend days, fun money for the week, category caps ([MoneyChallenges]); shown with this month only. */
    val challenges: MoneyChallengesView? = null,
    /** Slice 17: PAYING ABROAD — which accounts charged foreign fees, the one to use, and a banner while he's abroad ([MoneyTravel]); this month only. */
    val travel: MoneyTravelView? = null,
    /** Slice 18: the year in review — last year in January, this year so far after ([MoneyYear]); this month only. */
    val year: MoneyYearView? = null,
    /** Money manager (7): the month in review in plain English — the finished month shown, or last month leading this one for its first days ([MoneyMonthReview]). */
    val review: MoneyMonthReviewView? = null,
    /** Money manager (6): MONEY GOALS — his £ goals and the account each follows ([MoneyGoals]); shown with this month. */
    val goals: MoneyGoalsView? = null,
    /** Speed: while the fresh view is being built after an open, the line under the title ([MoneyOpenRules]); null once it's fresh. */
    val opening: String? = null,
    /** Speed: the first open of a session before any view is built — the title and [opening] only, nothing to tap. */
    val skeleton: Boolean = false,
    /** The payment sheet: every category's key in chip order ([MoneyPaymentSheet.order]); empty before any payment is in. */
    val categoryOrder: List<String> = emptyList(),
    /** His own categories ([MoneyOwnCategories]), oldest first: the sheet's chips, Spending's rows and the New category form. */
    val ownCategories: List<MoneyOwnCategory> = emptyList(),
    /** Slice 3: YOUR CATEGORIES on Accounts, one row per category of his with its payees ([MoneyOwnCategories.rows]). */
    val ownRows: List<MoneyOwnRow> = emptyList(),
    /** Plan's What if I save… ([MoneySave]): what the apps need to work it out on the device; this month only. */
    val save: MoneySaveBase? = null,
    /** Plan's Clear my cards ([MoneyClear]): his credit cards that owe something, for the apps to work it out; this month only. */
    val clear: MoneyClearBase? = null,
    /** Where the shown lines came from (statement files, his banks through Lunch Flow, or both), for the tabs' ⓘ. */
    val source: MoneySource = MoneySource.FILES,
    /** Plan's OBI'S MONEY CHECK: ideas to save from his own payments ([MoneyCheck]); this month only. */
    val check: MoneyCheckView? = null,
) {
    /**
     * Quality gate (Money's shots): TRADING · HYPERLIQUID leads the screen only once an address is in; until then its
     * set-up block waits below the month (and below Import with nothing imported), so the month starts near the top.
     */
    val tradingFirst: Boolean get() = trading?.wallets?.isNotEmpty() == true

    companion object {
        val LOCKED = MoneyView(true, MoneyRules.LOCKED, null, null, emptyList(), earlier = false, later = false)
    }
}

/** What an import, a category change or a removal said: [line], [refused] when nothing changed, [undo] for Undo. */
data class MoneySaved(val line: String, val refused: Boolean = false, val undo: String? = null)

/** A category pick for the change menu. */
data class MoneyCategoryChoice(val key: String, val label: String)

object MoneyRules {
    const val TITLE = "Money"
    const val ROW_LINE = "Spending by category from your statements · behind your fingerprint"
    const val LOCKED = "Your money stays behind your fingerprint · open it to see"
    const val LOCKED_MAC = "Your money stays behind Touch ID · open it to see"
    const val UNLOCK = "Open Money"
    const val UNLOCK_REASON = "Open Money"
    const val EMPTY = "Nothing imported yet · download a statement from your bank or card as CSV (a spreadsheet file) and import it here."
    /** Quality gate: the line under the title with nothing in; how to import is said once, under Import a statement. */
    const val EMPTY_SUMMARY = "Nothing imported yet · your month shows here once a statement is in"
    const val CONNECT_LATER = "Your banks fill this in by themselves (read-only, through Lunch Flow) once Navilife's server has its key; a statement file works for any bank meanwhile."
    const val ACCOUNT_HINT = "Which account? (Current account, Credit card…)"
    const val NO_ACCOUNT = "Name the account first (e.g. Current account), then pick the file."
    const val IMPORT = "Import a statement file"
    const val FOOT = "Read-only: Navilife never moves money or pays anything, and keeps no bank password. Your transactions stay in your own data (synced, encrypted). Not financial advice."
    const val DELETE_ALL = "Delete all money data"
    const val DELETE_ASK = "Delete every account and transaction from Navilife on all your devices? Your bank keeps its own records."
    const val DELETED_ALL = "Deleted all money data"
    const val GONE = "That's no longer there."
    const val SHOWN_TXNS = 40
    const val MAX_ACCOUNTS = 12
    /** Over the 6-month average by this share and at least [OVER_MIN_PENCE] lights the category. */
    const val OVER_SHARE = 0.2
    const val OVER_MIN_PENCE = 2_000L

    /** The account's id: "a-current-account" for "Current account"; null for a name with no letters or digits. */
    fun accountId(name: String): String? = KidsRules.idFor(name)?.let { "a" + it.removePrefix("c") }

    /** A statement line's id: the same line imported twice (the [occurrence]-th identical one in its file) is one entity. */
    fun txnId(account: String, row: MoneyRow, occurrence: Int): String =
        "t" + ActivityRules.fnv64("money:$account:${row.day}:${row.pence}:${row.text.trim().uppercase()}:$occurrence")

    fun ruleId(merchantKey: String): String = "m" + ActivityRules.fnv64("moneyrule:$merchantKey")

    fun fmt(pence: Long): String = RenewalRules.formatPence(pence)

    /** "−£23.40" out, "+£2,500" in. */
    fun signed(pence: Long): String = if (pence < 0) "−" + fmt(-pence) else "+" + fmt(pence)

    /** "£1,240" in credit, "−£50" overdrawn. */
    fun signedPlain(pence: Long): String = if (pence < 0) "−" + fmt(-pence) else fmt(pence)

    /** A month as an index (year × 12 + month − 1) and back. */
    fun monthOf(day: Long): Int = CivilDate.fromEpochDay(day).let { it.year * 12 + it.month - 1 }

    fun monthLabel(month: Int): String = "${CivilDate.MONTH_LONG[month % 12]} ${month / 12}"

    val CHOICES: List<MoneyCategoryChoice> = MoneyCategory.entries.map { MoneyCategoryChoice(it.key, it.label) }

    /** The category a line is in: his correction for its merchant, else what its words point to. */
    fun categoryOf(t: MoneyTxn, rules: Map<String, MoneyCategory>): MoneyCategory =
        rules[MoneyCategoryRules.merchantKey(t.text)] ?: MoneyCategoryRules.categoryOf(t.text, t.pence)

    /** Ask's Money tile: no amounts (it's outside the fingerprint) — "2 accounts · updated Fri 16 Oct", or null. */
    fun tile(accounts: List<MoneyAccount>, cal: LocalCalendar, cards: List<MoneyCard> = emptyList(), today: Long? = null): LifeTileLine? {
        if (accounts.isEmpty()) return null
        // Slice 7: a card payment due within three days lights the tile (still no amounts).
        if (today != null) MoneyCards.tileLine(cards.filter { c -> accounts.any { it.id == c.id } }, today)?.let { return it }
        val last = accounts.mapNotNull { it.importedAtMs }.maxOrNull()
        val n = accounts.size
        val head = if (n == 1) accounts.first().name else "$n accounts"
        return LifeTileLine(head + (last?.let { " · updated ${CivilDate.shortLabel(cal.epochDayOf(it))}" } ?: ""), false)
    }

    /** What an import said: "Added 212 transactions to Current account · 1 Mar – 15 Oct", with what was already there. */
    fun importedLine(account: String, added: Int, already: Int, read: MoneyCsvRead): String {
        val first = read.rows.minOfOrNull { it.day }
        val last = read.rows.maxOfOrNull { it.day }
        val span = if (first != null && last != null) " · ${dayMonth(first)} – ${dayMonth(last)}" else ""
        val parts = mutableListOf<String>()
        parts += if (added == 0) "Nothing new for $account$span" else "Added $added transaction${if (added == 1) "" else "s"} to $account$span"
        if (already > 0) parts += "$already already here"
        if (read.skipped > 0) parts += "${read.skipped} line${if (read.skipped == 1) "" else "s"} without a date or amount skipped"
        if (read.flipped) parts += "read as a card statement (purchases as money out)"
        return parts.joinToString(" · ")
    }

    /**
     * What a month is set against, named by the months it really covers: one month by its name ("September"), else
     * "your 2-month average" … "your 6-month average". [prior] is the month indexes counted (never empty).
     */
    fun averageName(prior: List<Int>): String =
        if (prior.size == 1) CivilDate.MONTH_LONG[prior.single() % 12] else "your ${prior.size}-month average"

    /** "1 Mar 2026". */
    fun dayMonth(day: Long): String = CivilDate.fromEpochDay(day).let { "${it.day} ${Recurrence.MONTH_SHORT[it.month - 1]} ${it.year}" }

    /**
     * The Money screen for the month [offset] months before [today]'s (0 = this month, "October so far"), from every
     * account's lines and his corrections.
     */
    fun view(
        accounts: List<MoneyAccount>, txns: List<MoneyTxn>, rules: Map<String, MoneyCategory>, today: Long, cal: LocalCalendar, offset: Int,
        hiddenTips: Set<String> = emptySet(), fineAlerts: Set<String> = emptySet(), whatIf: MoneyWhatIf = MoneyWhatIf(),
        refundWaits: List<WaitingItem> = emptyList(), bills: List<MoneyBill> = emptyList(), holidays: HolidayCalendar = HolidayCalendar(),
        cards: List<MoneyCard> = emptyList(), worthItems: List<MoneyWorthItem> = emptyList(),
        /** Bank sync: what the server last did, accounts he removed, his Refresh, and the moment (for "updated 06:10"). */
        bankSync: MoneyBankSync? = null, bankRemoved: Int = 0, bankAskedAtMs: Long? = null, nowMs: Long? = null,
        /** Trading tracker, slice 5: his Hyperliquid accounts, counted in net worth in pounds. */
        trading: List<TradingWallet> = emptyList(),
        /** Slice 13: his side hustle's accounts, the payers he marked (true) or kept out (false), and his set-aside share. */
        businessAccounts: Set<String> = emptySet(), businessMerchants: Map<String, Boolean> = emptyMap(), setAsidePct: Int = MoneyBusiness.DEFAULT_PCT,
        /** Slice 14: payments and payers he marked shared, what his partner paid, his share and the last settle-up, and her name. */
        sharedTxns: Map<String, Boolean> = emptyMap(), sharedMerchants: Map<String, Boolean> = emptyMap(),
        sharedEntries: List<MoneySharedEntry> = emptyList(), sharedSetting: MoneySharedSetting = MoneySharedSetting(), partner: String? = null,
        /** Slice 15: the challenges he set. */
        challenges: MoneyChallengeSetting = MoneyChallengeSetting(),
        /** Slice 17: the device is outside UK time, and a trip on or near (its name and first day). */
        abroadNow: Boolean = false, trip: Pair<String, Long>? = null,
        /** Money at a glance: priced receipt and order emails from his tidies ([MoneyReceipts.fromTidies]). */
        receipts: List<MoneyReceiptMail> = emptyList(),
        /** His own categories ([MoneyOwnCategories]) and the payees he put in one (merchant key → its id). */
        own: List<MoneyOwnCategory> = emptyList(), ownRules: Map<String, String> = emptyMap(),
        /** Needs and wants: the payers he marked a need (true) or a want (false), by merchant key ([MoneyNeeds]). */
        needMerchants: Map<String, Boolean> = emptyMap(),
        /** Obi's money check: the ideas he put away (Not for me) or asked to be reminded of. */
        ideaState: MoneyIdeaState = MoneyIdeaState(),
        /** Obi's wording of the money check ([MoneyCheckWords]). */
        checkWords: MoneyCheckWordsState = MoneyCheckWordsState(),
    ): MoneyView {
        val names = accounts.associate { it.id to it.name }
        val bank = nowMs?.let { MoneyBank.view(bankSync, accounts, bankRemoved, bankAskedAtMs, it, cal) }
        // Bank sync: a statement account that is one of his bank accounts counts once (its matched lines and balance).
        val merged = MoneyBank.merge(accounts, txns.filter { it.account in names })
        val counted = accounts.filter { it.id !in merged.sameAs }
        // Each line's merchant and category once per view (the rules are regexes; a statement can be thousands of lines).
        val keys = HashMap<String, String>()
        val cats = HashMap<String, MoneyCategory>()
        fun keyOf(t: MoneyTxn): String = keys.getOrPut(t.id) { MoneyCategoryRules.merchantKey(t.text) }
        val mine = merged.txns
        // Money bugs (A1): card repayments and moves between his own accounts are transfers, out of spent and in.
        val moves = MoneyTransfers.ids(accounts, mine, rules)
        fun cat(t: MoneyTxn): MoneyCategory = cats.getOrPut(t.id) { MoneyTransfers.categoryOf(t, keyOf(t), rules, moves) }
        // His own categories: a payee he put in one counts as Other underneath and shows as his own on Spending.
        val ownById = own.associateBy { it.id }
        fun ownOf(t: MoneyTxn): MoneyOwnCategory? =
            if (ownRules.isEmpty()) null else ownRules[keyOf(t)]?.let { ownById[it] }?.takeIf { cat(t) == MoneyCategory.OTHER }
        fun shownKey(t: MoneyTxn): String = ownOf(t)?.key ?: cat(t).key
        fun isNeed(t: MoneyTxn): Boolean = MoneyNeeds.isNeed(cat(t), needMerchants[keyOf(t)], own = ownOf(t) != null)
        val accountRows = accounts.sortedBy { it.name.lowercase() }.map { a ->
            val ts = mine.filter { it.account == a.id }
            val span = if (ts.isEmpty()) "no transactions" else
                "${ts.size} transaction${if (ts.size == 1) "" else "s"} · ${dayMonth(ts.minOf { it.day })} – ${dayMonth(ts.maxOf { it.day })}"
            val bankLine = if (a.source == MoneyBank.SOURCE) "From your bank" + (if (a.bankStatus == MoneyBank.RECONNECT) " · reconnect in Lunch Flow" else "") + " · " else ""
            val when_ = a.importedAtMs?.let { " · ${if (a.source == MoneyBank.SOURCE) "updated" else "imported"} ${CivilDate.shortLabel(cal.epochDayOf(it))}" } ?: ""
            val same = merged.sameAs[a.id]?.let { b -> " · the same account as ${names[b]} from your bank, so lines in both count once" } ?: ""
            MoneyAccountRow(a.id, a.name, bankLine + span + when_ + same, txns = ts.size, bank = a.source == MoneyBank.SOURCE,
                reconnect = a.bankStatus == MoneyBank.RECONNECT, updated = a.importedAtMs?.let { CivilDate.shortLabel(cal.epochDayOf(it)) }, nick = a.nick)
        }
        // Slice 8: net worth from balances, cards and what he typed (also before any statement is in).
        val liveCards = cards.filter { it.id in names }
        val worth = MoneyWorth.view(counted, liveCards, worthItems, mine.map { Triple(it.account, it.day, it.pence) }, today, trading)
        // Slice 13: the side hustle over every line (whatever month is shown).
        val bizLines = mine.map { MoneyBusinessLine(it.account, it.day, it.pence, keyOf(it), cat(it)) }
        val business = MoneyBusiness.view(accounts, bizLines, businessAccounts, businessMerchants, setAsidePct, today)
        fun isBiz(t: MoneyTxn) = MoneyBusiness.counts(MoneyBusinessLine(t.account, t.day, t.pence, keyOf(t), cat(t)), businessAccounts, businessMerchants)
        // Slice 14: shared costs over every line (whatever month is shown).
        fun sharedLine(t: MoneyTxn) = MoneySharedLine(t.id, t.day, t.pence, keyOf(t), cat(t))
        val shared = MoneyShared.view(accounts.isNotEmpty(), mine.map { sharedLine(it) }, sharedTxns, sharedMerchants, sharedEntries, sharedSetting, partner, today)
        fun isShared(t: MoneyTxn) = MoneyShared.counts(sharedLine(t), sharedTxns, sharedMerchants)
        if (mine.isEmpty()) return MoneyView(false, EMPTY_SUMMARY, EMPTY, null, accountRows, earlier = false, later = false, worth = worth, bank = bank, business = business, shared = shared, ownCategories = own, ownRows = MoneyOwnCategories.rows(own, ownRules))
        val thisMonth = monthOf(today)
        val firstMonth = monthOf(mine.minOf { it.day })
        val shown = (thisMonth - offset.coerceAtLeast(0)).coerceAtLeast(firstMonth)
        val byMonth = mine.groupBy { monthOf(it.day) }
        fun spendOf(ts: List<MoneyTxn>): Map<MoneyCategory, Long> = ts.groupBy { cat(it) }
            .filterKeys { it.spend }.mapValues { (_, l) -> -l.sumOf { it.pence } }
        // Spending's rows: as [spendOf], with his own categories split out of Other.
        fun shownSpendOf(ts: List<MoneyTxn>): Map<String, Long> = ts.filter { cat(it).spend }.groupBy { shownKey(it) }
            .mapValues { (_, l) -> -l.sumOf { it.pence } }
        // Challenges and the caps on his own categories (this month, whatever month is shown).
        val challengeLines = mine.map { MoneyChallengeLine(it.day, it.pence, cat(it), ownOf(it)?.id) }
        val cur = byMonth[shown].orEmpty()
        val spend = spendOf(cur)
        val spent = spend.values.filter { it > 0 }.sum()
        val income = cur.filter { cat(it) == MoneyCategory.INCOME }.sumOf { it.pence }
        // The 6 whole months before the one shown that have statements.
        val prior = (1..6).map { shown - it }.filter { it >= firstMonth }
        val avg: Map<String, Long> = if (prior.isEmpty()) emptyMap() else {
            val sums = prior.map { shownSpendOf(byMonth[it].orEmpty()) }
            (MoneyCategory.entries.filter { it.spend }.map { it.key } + own.map { it.key })
                .associateWith { c -> sums.sumOf { it[c] ?: 0L } / prior.size }
        }
        val avgTotal = if (prior.isEmpty()) null else prior.sumOf { m -> spendOf(byMonth[m].orEmpty()).values.filter { it > 0 }.sum() } / prior.size
        // Quality gate: the average is named by the months it really covers ("your 2-month average", or "September").
        val avgName = averageName(prior)
        val partial = shown == thisMonth
        val label = if (partial) "${CivilDate.MONTH_LONG[shown % 12]} so far" else monthLabel(shown)
        val spendCats = ArrayList<MoneySpendCat>()
        val shownSpend = if (ownRules.isEmpty()) spend.mapKeys { it.key.key } else shownSpendOf(cur)
        val catRows = shownSpend.filter { it.value > 0 }.entries.sortedByDescending { it.value }.mapNotNull { (k, p) ->
            val ownC = MoneyOwnCategories.idOf(k)?.let { ownById[it] }
            val c = MoneyCategory.of(k) ?: if (ownC != null) MoneyCategory.OTHER else return@mapNotNull null
            val label = ownC?.name ?: c.label
            val n = cur.count { shownKey(it) == k && it.pence < 0 }
            val a = avg[k]
            val over = !partial && a != null && p - a >= OVER_MIN_PENCE && p > a * (1 + OVER_SHARE)
            val vs = when {
                a == null || partial -> null
                a == 0L -> if (prior.size == 1) "none in $avgName" else "none in the ${prior.size} months before"
                over -> "${fmt(p - a)} over $avgName"
                a - p >= OVER_MIN_PENCE -> "${fmt(a - p)} under $avgName"
                else -> if (prior.size == 1) "about the same as $avgName" else "about $avgName"
            }
            val line = listOfNotNull("$n payment${if (n == 1) "" else "s"}", vs).joinToString(" · ")
            spendCats += MoneySpendCat(c, p, line, over, under = !partial && a != null && a > 0 && a - p >= OVER_MIN_PENCE, own = ownC)
            MoneyCategoryRow(k, label, fmt(p), if (spent > 0) (p.toFloat() / spent).coerceIn(0f, 1f) else 0f, line, over,
                "$label, ${fmt(p)}, $line")
        }
        val vsLine = when {
            avgTotal == null -> null
            partial -> if (prior.size == 1) "$avgName came to ${fmt(avgTotal)}" else "Your ${prior.size}-month average is ${fmt(avgTotal)} a month"
            spent - avgTotal >= OVER_MIN_PENCE -> "${fmt(spent - avgTotal)} more than $avgName"
            avgTotal - spent >= OVER_MIN_PENCE -> "${fmt(avgTotal - spent)} less than $avgName"
            else -> if (prior.size == 1) "About the same as $avgName" else "About $avgName"
        }
        val sorted = cur.sortedWith(compareByDescending<MoneyTxn> { it.day }.thenBy { it.id })
        // Money at a glance: receipts matched over every line, so one near a month's edge goes to the right payment.
        val receiptOf = if (receipts.isEmpty()) emptyMap() else MoneyReceipts.match(
            mine.map { MoneyReceiptLine(it.id, it.day, it.pence, keyOf(it)) },
            receipts,
        )
        fun rowOf(t: MoneyTxn): MoneyTxnRow {
            val c = cat(t)
            val ownC = ownOf(t)
            val shownCat = ownC?.key ?: c.key
            val shownLabel = ownC?.name ?: c.label
            val key = keyOf(t)
            val merchant = MoneyCategoryRules.merchantLabel(key)
            val r = receiptOf[t.id]
            return MoneyTxnRow(
                id = t.id, day = CivilDate.shortLabel(t.day), merchant = merchant, amount = signed(t.pence), moneyIn = t.pence > 0,
                category = shownCat, categoryLabel = shownLabel, account = names[t.account].orEmpty(), taught = key in rules,
                spoken = "${CivilDate.shortLabel(t.day)}, $merchant, ${if (t.pence < 0) "out" else "in"} ${fmt(kotlin.math.abs(t.pence))}, $shownLabel" +
                    (if (isBiz(t)) ", business" else "") + (if (isShared(t)) ", shared" else "") + (r?.let { MoneyReceipts.spoken(it) } ?: ""),
                business = isBiz(t),
                shared = isShared(t),
                sharedAlways = sharedMerchants[key] == true,
                receipt = r?.let { MoneyReceipts.line(it) },
                receiptDetail = r?.let { MoneyReceipts.detail(it) },
                need = isNeed(t),
                needMarked = needMerchants.containsKey(key),
            )
        }
        val rows = sorted.take(SHOWN_TXNS).map { rowOf(it) }
        // Money review: the month's money out still in Other (not his own pick of Other, not one of his categories), by payee.
        val unsorted = MoneySorter.view(
            sorted.filter { it.pence < 0 && cat(it) == MoneyCategory.OTHER && ownOf(it) == null && keyOf(it) !in rules }
                .groupBy { keyOf(it) }
                .map { (k, ts) -> MoneySortPayee(k, rowOf(ts.first()), ts.size, -ts.sumOf { it.pence }) },
        )
        val month = MoneyMonth(
            offset = thisMonth - shown, label = label,
            spentLine = "Spent ${fmt(spent)}" + (if (income > 0) " · in ${fmt(income)}" else ""),
            vsLine = vsLine, vsLit = !partial && avgTotal != null && spent - avgTotal >= OVER_MIN_PENCE,
            categories = catRows, txns = rows, more = (sorted.size - rows.size).coerceAtLeast(0),
            receiptsLine = MoneyReceipts.monthLine(cur.count { it.id in receiptOf }, CivilDate.MONTH_LONG[shown % 12]),
            unsorted = unsorted,
        )
        val summary = "${accounts.size} account${if (accounts.size == 1) "" else "s"} · ${mine.size} transactions" +
            (if (merged.hidden > 0) " · ${merged.hidden} in a statement and from your bank, counted once" else "")
        // Slice 2: tips and regular payments go with this month (they're about now, not the month shown).
        val lines = if (partial) mine.map { MoneyLine(it.day, it.pence, keyOf(it), cat(it)) } else emptyList()
        val regular = if (partial) MoneyTips.regular(lines, today) else emptyList()
        val yearLines = mine.map { MoneyYearLine(it.day, it.pence, keyOf(it), cat(it)) }
        // Money bugs (A4): the footers say where these lines came from (statement files, his banks through Lunch Flow, or both).
        val source = MoneySource.ofLines(accounts, mine.mapTo(HashSet()) { it.account })
        val watch = if (partial) mine.map { MoneyWatchLine(it.id, it.account, it.day, it.pence, keyOf(it), cat(it), it.text) } else emptyList()
        val cardBills = if (partial) MoneyCards.bills(cards.filter { it.id in names }, today) else emptyList()
        val paydayView = if (partial) MoneyPayday.view(
            mine.map { MoneyPayLine(it.account, it.day, it.pence, keyOf(it), cat(it)) }, today, names,
            counted.mapNotNull { a -> a.balance?.let { a.id to it } }.toMap(),
            // Slice 7: a card's statement due before payday is still to go out (Paid takes it off).
            // Bills due, completeness: a radar bill that is a card's payment counts once, as the card's.
            BillRules.withoutCards(bills, cards.filter { it.id in names }, today) + cardBills, holidays,
            source, cardTitles = cardBills.mapTo(HashSet()) { it.title },
        ) else null
        // Money redesign (B3): the Bills tab's card and list; before a payday is found, the month's bills on every account.
        val billsView = if (!partial) null else paydayView?.bills ?: if (paydayView?.notReady != null) MoneyBills.forMonth(
            regular, BillRules.withoutCards(bills, cards.filter { it.id in names }, today) + cardBills,
            cardBills.mapTo(HashSet()) { it.title }, today,
        ) else null
        // Money redesign (B2): the Spending tab's ring, tiles, category rows and the latest payments by day.
        val shownDate = CivilDate.fromEpochDay(today)
        val spending = MoneySpending.view(
            partial = partial, monthName = CivilDate.MONTH_LONG[shown % 12], spentPence = spent, incomePence = income,
            cats = spendCats, avgTotal = avgTotal, usual = if (prior.size == 1) avgName else "usual",
            dayOfMonth = shownDate.day, daysInMonth = CivilDate.lengthOfMonth(shownDate.year, shownDate.month),
            toGoPence = paydayView?.takeIf { it.notReady == null }?.toGoPence,
            // Money's wrong numbers (3): "Left" is the month's in less out (as the mockup), not the bank balance.
            leftPence = income - spent,
            txns = sorted.take(MoneySpending.LATEST).mapNotNull { t -> rows.firstOrNull { it.id == t.id }?.let { MoneySpendTxn(it, t.day, t.pence) } },
            allTxns = sorted.size, today = today,
        ).copy(
            // Needs and wants: the same month split in two, for the view chips over the ring.
            needs = MoneyNeeds.view(
                cur.filter { cat(it).spend }.map { t -> MoneyNeedLine(ownOf(t)?.name ?: cat(t).label, -t.pence, isNeed(t)) }, spent,
            ),
        )
        val cardLines = if (partial) mine.map { MoneyCardLine(it.account, it.day, it.pence, it.text) } else emptyList()
        val forecastView = if (partial) MoneyForecast.view(lines, today, whatIf, nowMs?.let { MoneyForecast.tradingPace(trading, it, cal) }) else null
        return MoneyView(
            false, summary, null, month.copy(spending = spending), accountRows, earlier = shown > firstMonth, later = shown < thisMonth,
            tips = if (partial) MoneyTips.tips(lines, today, hiddenTips, regular) else emptyList(),
            regular = MoneyTips.regularView(regular),
            hiddenTips = MoneyTips.hiddenLine(hiddenTips),
            alerts = if (partial) MoneyWatch.alerts(watch, today, names, fineAlerts) else emptyList(),
            forecast = forecastView,
            save = if (partial) MoneySave.base(forecastView?.takeIf { it.notReady == null }?.leftPence, today) else null,
            refunds = if (partial) MoneyRefunds.view(refundWaits, watch, today, cal, names) else null,
            payday = paydayView,
            bills = billsView,
            cards = if (partial) MoneyCards.view(cards.filter { it.id in names }, counted, cardLines, today) else null,
            clear = if (partial) MoneyClear.base(
                cards.filter { it.id in names }, counted.mapNotNull { a -> a.balance?.let { a.id to it.pence } }.toMap(), cardLines,
                forecastView?.takeIf { it.notReady == null }?.leftPence, today,
            ) else null,
            worth = if (partial) worth else null,
            bank = bank,
            business = business,
            shared = shared,
            challenges = if (partial) MoneyChallenges.view(challengeLines, challenges, today, own) else null,
            travel = if (partial) MoneyTravel.view(mine.map { MoneyTravelLine(it.account, it.day, it.pence, it.text, cat(it)) }, names, today, abroadNow, trip) else null,
            year = if (partial) MoneyYear.view(yearLines, today, source) else null,
            review = MoneyMonthReview.forScreen(yearLines, shown, today, bizLines.filter { MoneyBusiness.counts(it, businessAccounts, businessMerchants) }, source),
            categoryOrder = MoneyPaymentSheet.order(
                mine.groupingBy { shownKey(it) }.eachCount(),
                rules.entries.mapTo(HashSet()) { (m, c) -> ownRules[m]?.let { MoneyOwnCategories.PREFIX + it } ?: c.key },
                own,
            ),
            ownCategories = own,
            ownRows = MoneyOwnCategories.rows(own, ownRules, MoneyChallenges.ownCapBars(challengeLines, challenges, own, today)),
            source = source,
            check = if (partial) MoneyCheck.view(lines, today, regular, ideaState, hiddenTips, checkWords) else null,
        )
    }

    /**
     * Money manager (16): every account's lines for Ask's answers, a statement line and the same bank line counted once
     * ([MoneyBank.merge]), each with its merchant key and category (his corrections first).
     */
    fun askLines(accounts: List<MoneyAccount>, txns: List<MoneyTxn>, rules: Map<String, MoneyCategory>): List<MoneyLine> {
        val ids = accounts.map { it.id }.toSet()
        val mine = MoneyBank.merge(accounts, txns.filter { it.account in ids }).txns
        val moves = MoneyTransfers.ids(accounts, mine, rules)
        return mine.map { t ->
            val key = MoneyCategoryRules.merchantKey(t.text)
            MoneyLine(t.day, t.pence, key, MoneyTransfers.categoryOf(t, key, rules, moves))
        }
    }

    /** Money manager, nudges: every account's lines as the watchdog sees them, counted once ([MoneyBank.merge]). */
    fun watchLines(accounts: List<MoneyAccount>, txns: List<MoneyTxn>, rules: Map<String, MoneyCategory>): List<MoneyWatchLine> {
        val ids = accounts.map { it.id }.toSet()
        val mine = MoneyBank.merge(accounts, txns.filter { it.account in ids }).txns
        val moves = MoneyTransfers.ids(accounts, mine, rules)
        return mine.map { t ->
            val key = MoneyCategoryRules.merchantKey(t.text)
            MoneyWatchLine(t.id, t.account, t.day, t.pence, key, MoneyTransfers.categoryOf(t, key, rules, moves), t.text)
        }
    }

    /** Money manager (17): every account's lines for PAYING ABROAD in Ask's answer, counted once, with text and category. */
    fun travelLines(accounts: List<MoneyAccount>, txns: List<MoneyTxn>, rules: Map<String, MoneyCategory>): List<MoneyTravelLine> {
        val ids = accounts.map { it.id }.toSet()
        val mine = MoneyBank.merge(accounts, txns.filter { it.account in ids }).txns
        val moves = MoneyTransfers.ids(accounts, mine, rules)
        return mine.map { t ->
            val key = MoneyCategoryRules.merchantKey(t.text)
            MoneyTravelLine(t.account, t.day, t.pence, t.text, MoneyTransfers.categoryOf(t, key, rules, moves))
        }
    }

    fun movedLine(merchant: String, category: MoneyCategory, count: Int): String = MoneyPaymentSheet.movedLine(merchant, category, count)

    fun removedLine(name: String, count: Int): String = "Removed $name · $count transaction${if (count == 1) "" else "s"}"
}

/** Money on a replica (both apps). Keeps the lines in memory between changes, so the screen doesn't re-read them all. */
class Money(private val replica: Replica, private val nowMs: () -> Long) {
    private var cache: List<MoneyTxn>? = null

    private companion object { const val TIPS_UNDO = "tips|"; const val IDEA_UNDO = "idea|"; const val IDEA_PREFIX = "idea-"; const val ALERT_UNDO = "alert|"; const val BALANCE_UNDO = "bal|"; const val CARD_UNDO = "card|"; const val WORTH_UNDO = "worth|"; const val BIZ_UNDO = "biz|"; const val SHARED_UNDO = "shared|"; const val CHALLENGE_UNDO = "chal|"; const val OWN_UNDO = "own|"; const val OWN_EDIT_UNDO = "ownedit|"; const val OWN_HIDE_UNDO = "ownhide|"; const val OWN_MOVE_UNDO = "ownmove|"; const val NEED_UNDO = "need|"; const val NICK_UNDO = "nick|" }

    /** Told of every replica change (the facade's listener, before its refresh): a money line changed, re-read them. */
    fun changed(refs: Set<EntityRef>) {
        if (refs.any { it.entityType == EntityTypes.MONEY_TXN || it.entityType == EntityTypes.MONEY_ACCOUNT }) cache = null
    }

    fun accounts(): List<MoneyAccount> = replica.entities(EntityTypes.MONEY_ACCOUNT).mapNotNull { e ->
        val name = e[MoneyAccountFields.NAME].textOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let(MoneyAccountNames::shown) ?: return@mapNotNull null
        val bp = e[MoneyAccountFields.BALANCE].longOrNull
        val bd = e[MoneyAccountFields.BALANCE_DAY].longOrNull
        MoneyAccount(e.ref.entityId, name, e[MoneyAccountFields.SOURCE].textOrNull ?: "csv", e[MoneyAccountFields.IMPORTED_AT].longOrNull,
            if (bp != null && bd != null) MoneyBalance(bp, bd, e[MoneyAccountFields.BALANCE_TYPED].boolOrNull == true) else null,
            e[MoneyBankAccountFields.STATUS].textOrNull,
            nick = e[MoneyAccountFields.NICK].textOrNull?.let(MoneyAccountNames::cleanNick))
    }

    /**
     * Money review, Accounts' rest: [accountId] goes by [text] on both devices ("Joint bills"); blank gives it back its
     * own name. Refused for a gone account. Undo (by [undoCategory]) puts the name before back.
     */
    fun renameAccount(accountId: String, text: String): MoneySaved {
        val a = accounts().firstOrNull { it.id == accountId } ?: return MoneySaved(MoneyRules.GONE, refused = true)
        val nick = MoneyAccountNames.cleanNick(text)
        if (nick == a.nick) return MoneySaved(MoneyAccountNames.renamedLine(nick ?: a.name))
        replica.commitLocal(EntityTypes.MONEY_ACCOUNT, accountId, mapOf(MoneyAccountFields.NICK to (nick?.fv() ?: FieldValue.Null)))
        return MoneySaved(if (nick == null) MoneyAccountNames.ownNameLine(a.name) else MoneyAccountNames.renamedLine(nick),
            undo = NICK_UNDO + accountId + "|" + a.nick.orEmpty())
    }

    /**
     * Slice 6: [accountId]'s balance as he typed it ([text]: "£1,240", "−£50"; blank clears it), as of [today], on both
     * devices; Undo (by [undoCategory] with the token) puts the one before back.
     */
    fun setBalance(accountId: String, text: String, today: Long): MoneySaved {
        val a = accounts().firstOrNull { it.id == accountId } ?: return MoneySaved(MoneyRules.GONE, refused = true)
        val p = if (text.isBlank()) null else MoneyPayday.parseBalance(text) ?: return MoneySaved(MoneyPayday.BALANCE_BAD, refused = true)
        val b = a.balance
        val undo = BALANCE_UNDO + listOf(accountId, b?.pence?.toString().orEmpty(), b?.day?.toString().orEmpty(), if (b?.typed == true) "1" else "").joinToString("|")
        writeBalance(accountId, p?.let { MoneyBalance(it, today, typed = true) })
        return MoneySaved(if (p == null) MoneyPayday.BALANCE_CLEARED else MoneyPayday.balanceSaid(a.name, p), undo = undo)
    }

    private fun writeBalance(accountId: String, b: MoneyBalance?) {
        replica.commitLocal(EntityTypes.MONEY_ACCOUNT, accountId, mapOf(
            MoneyAccountFields.BALANCE to (b?.pence?.fv() ?: FieldValue.Null),
            MoneyAccountFields.BALANCE_DAY to (b?.day?.fv() ?: FieldValue.Null),
            MoneyAccountFields.BALANCE_TYPED to (b?.typed?.fv() ?: FieldValue.Null),
        ))
    }

    /** Slice 7: the accounts marked as cards, with what he typed off their statements. */
    fun cards(): List<MoneyCard> = replica.entities(EntityTypes.MONEY_ACCOUNT).mapNotNull { e ->
        val kind = e[MoneyCardFields.KIND].textOrNull?.takeIf { it == MoneyCards.CREDIT || it == MoneyCards.CHARGE } ?: return@mapNotNull null
        val name = e[MoneyAccountFields.NAME].textOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let(MoneyAccountNames::shown) ?: return@mapNotNull null
        val nick = e[MoneyAccountFields.NICK].textOrNull?.let(MoneyAccountNames::cleanNick)
        MoneyCard(
            e.ref.entityId, nick ?: name, kind,
            limitPence = e[MoneyCardFields.LIMIT].longOrNull, statementPence = e[MoneyCardFields.STATEMENT].longOrNull,
            minPence = e[MoneyCardFields.MINIMUM].longOrNull, dueDayOfMonth = e[MoneyCardFields.DUE].longOrNull?.toInt()?.takeIf { it in 1..31 },
            promoEndDay = e[MoneyCardFields.PROMO_END].longOrNull, paidFor = e[MoneyCardFields.PAID_FOR].longOrNull,
            aprBp = e[MoneyCardFields.APR].longOrNull?.toInt()?.takeIf { it in 1..9_999 },
        )
    }

    /** The card fields as they stand, for Undo: "field=value;…" (empty values for Null). */
    private fun cardSnapshot(accountId: String): String {
        val e = replica.entity(EntityTypes.MONEY_ACCOUNT, accountId)
        return MoneyCardFields.ALL.joinToString(";") { f ->
            val v = e?.get(f)
            "$f=" + (v?.textOrNull ?: v?.longOrNull?.toString() ?: "")
        }
    }

    /**
     * Slice 7: marks [accountId] as a card ([charge]: a charge card paid in full), or a card as a credit/charge card;
     * [card] false makes it not a card (what was typed goes). Undo (by [undoCategory]) puts the fields back as they were.
     */
    fun setCardKind(accountId: String, card: Boolean, charge: Boolean = false): MoneySaved {
        val a = accounts().firstOrNull { it.id == accountId } ?: return MoneySaved(MoneyRules.GONE, refused = true)
        val token = CARD_UNDO + accountId + "|" + cardSnapshot(accountId)
        if (!card) {
            replica.commitLocal(EntityTypes.MONEY_ACCOUNT, accountId, MoneyCardFields.ALL.associateWith { FieldValue.Null })
            return MoneySaved(MoneyCards.unmarkedSaid(a.name), undo = token)
        }
        val was = cards().any { it.id == accountId }
        replica.commitLocal(EntityTypes.MONEY_ACCOUNT, accountId, mapOf(MoneyCardFields.KIND to (if (charge) MoneyCards.CHARGE else MoneyCards.CREDIT).fv()))
        return MoneySaved(if (was) MoneyCards.kindSaid(a.name, charge) else MoneyCards.markedSaid(a.name), undo = token)
    }

    /** Slice 7: one typed card field ([MoneyCards.FIELDS]; blank clears it), as of [today]; Undo by [undoCategory]. */
    fun setCardField(accountId: String, field: String, text: String, today: Long): MoneySaved {
        val c = cards().firstOrNull { it.id == accountId } ?: return MoneySaved(MoneyCards.NOT_A_CARD, refused = true)
        val blank = text.isBlank()
        val (name, value: FieldValue, said: String?) = when (field) {
            MoneyCards.STATEMENT, MoneyCards.MINIMUM, MoneyCards.LIMIT -> {
                val p = if (blank) null else MoneyCards.parseAmount(text) ?: return MoneySaved(MoneyCards.BAD_AMOUNT, refused = true)
                val f = when (field) { MoneyCards.STATEMENT -> MoneyCardFields.STATEMENT; MoneyCards.MINIMUM -> MoneyCardFields.MINIMUM; else -> MoneyCardFields.LIMIT }
                Triple(f, p?.fv() ?: FieldValue.Null, p?.let { MoneyRules.fmt(it) })
            }
            MoneyCards.DUE -> {
                val d = if (blank) null else MoneyCards.parseDue(text) ?: return MoneySaved(MoneyCards.BAD_DUE, refused = true)
                Triple(MoneyCardFields.DUE, d?.toLong()?.fv() ?: FieldValue.Null, d?.let { "the ${MoneyCards.ordinal(it)}" })
            }
            MoneyCards.PROMO -> {
                val d = if (blank) null else MoneyCards.parsePromo(text, today) ?: return MoneySaved(MoneyCards.BAD_PROMO, refused = true)
                Triple(MoneyCardFields.PROMO_END, d?.fv() ?: FieldValue.Null, d?.let { MoneyRules.dayMonth(it) })
            }
            MoneyCards.APR -> {
                val bp = if (blank) null else MoneyCards.parseApr(text) ?: return MoneySaved(MoneyCards.BAD_APR, refused = true)
                Triple(MoneyCardFields.APR, bp?.toLong()?.fv() ?: FieldValue.Null, bp?.let(MoneyCards::aprText))
            }
            else -> return MoneySaved(MoneyRules.GONE, refused = true)
        }
        val token = CARD_UNDO + accountId + "|" + cardSnapshot(accountId)
        val writes = mutableMapOf(name to value)
        // A new statement starts a new payment: an earlier Paid no longer covers it.
        if (field == MoneyCards.STATEMENT && !blank) {
            val due = MoneyCards.nextDue(c.copy(paidFor = null), today)
            if (c.paidFor != null && due != null && c.paidFor < due) writes[MoneyCardFields.PAID_FOR] = FieldValue.Null
        }
        replica.commitLocal(EntityTypes.MONEY_ACCOUNT, accountId, writes)
        return MoneySaved(MoneyCards.fieldSaid(c.name, field, said), undo = token)
    }

    /** Slice 7: Paid on a card's next payment (its reminder stops until the next one); Undo by [undoCategory]. */
    fun cardPaid(accountId: String, today: Long): MoneySaved {
        val c = cards().firstOrNull { it.id == accountId } ?: return MoneySaved(MoneyCards.NOT_A_CARD, refused = true)
        val due = MoneyCards.nextDue(c, today) ?: return MoneySaved(MoneyCards.NO_DUE, refused = true)
        val token = CARD_UNDO + accountId + "|" + cardSnapshot(accountId)
        replica.commitLocal(EntityTypes.MONEY_ACCOUNT, accountId, mapOf(MoneyCardFields.PAID_FOR to due.fv()))
        return MoneySaved(MoneyCards.paidSaid(c.name, due), undo = token)
    }

    /** Slice 8: the things he typed for his net worth. */
    fun worthItems(): List<MoneyWorthItem> = replica.entities(EntityTypes.MONEY_WORTH).mapNotNull { e ->
        val name = e[MoneyWorthFields.NAME].textOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val p = e[MoneyWorthFields.PENCE].longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
        MoneyWorthItem(e.ref.entityId, name, e[MoneyWorthFields.KIND].textOrNull == "owe", p, e[MoneyWorthFields.DAY].longOrNull ?: 0L)
    }

    private fun worthSnapshot(id: String): String {
        val e = replica.entity(EntityTypes.MONEY_WORTH, id)
        return listOf(MoneyWorthFields.NAME, MoneyWorthFields.KIND, MoneyWorthFields.PENCE, MoneyWorthFields.DAY).joinToString("|") { f ->
            val v = e?.get(f)
            (v?.textOrNull ?: v?.longOrNull?.toString() ?: "").replace("|", " ")
        }
    }

    private fun writeWorth(id: String, name: String?, owes: Boolean?, pence: Long?, day: Long?) {
        replica.commitLocal(EntityTypes.MONEY_WORTH, id, mapOf(
            MoneyWorthFields.NAME to (name?.fv() ?: FieldValue.Null),
            MoneyWorthFields.KIND to (owes?.let { if (it) "owe" else "have" }?.fv() ?: FieldValue.Null),
            MoneyWorthFields.PENCE to (pence?.fv() ?: FieldValue.Null),
            MoneyWorthFields.DAY to (day?.fv() ?: FieldValue.Null),
        ))
    }

    /**
     * Slice 8: one thing for his net worth typed as [text] ("Pension £42,000", "Car loan £3,200"), as of [today], on both
     * devices; the same name again updates it. Undo (by [undoCategory]) puts what was there back.
     */
    fun addWorth(text: String, today: Long): MoneySaved {
        val p = MoneyWorth.parse(text) ?: return MoneySaved(if (text.any { it.isDigit() }) MoneyWorth.NO_NAME else MoneyWorth.BAD, refused = true)
        val id = MoneyWorth.idFor(p.name) ?: return MoneySaved(MoneyWorth.NO_NAME, refused = true)
        val items = worthItems()
        val updated = items.any { it.id == id }
        if (!updated && items.size >= MoneyWorth.MAX_ITEMS) return MoneySaved("Up to ${MoneyWorth.MAX_ITEMS} things here.", refused = true)
        val token = WORTH_UNDO + id + "|" + worthSnapshot(id)
        writeWorth(id, p.name, p.owes, p.pence, today)
        return MoneySaved(MoneyWorth.savedSaid(p, updated), undo = token)
    }

    /** Slice 8: takes a typed thing off his net worth on both devices; Undo (by [undoCategory]) brings it back. */
    fun removeWorth(id: String): MoneySaved {
        val i = worthItems().firstOrNull { it.id == id } ?: return MoneySaved(MoneyRules.GONE, refused = true)
        val token = WORTH_UNDO + id + "|" + worthSnapshot(id)
        writeWorth(id, null, null, null, null)
        return MoneySaved(MoneyWorth.removedSaid(i.name), undo = token)
    }

    fun txns(): List<MoneyTxn> = cache ?: replica.entities(EntityTypes.MONEY_TXN).mapNotNull { e ->
        val account = e[MoneyTxnFields.ACCOUNT].textOrNull ?: return@mapNotNull null
        val day = e[MoneyTxnFields.DAY].longOrNull ?: return@mapNotNull null
        val pence = e[MoneyTxnFields.PENCE].longOrNull ?: return@mapNotNull null
        MoneyTxn(e.ref.entityId, account, day, pence, e[MoneyTxnFields.TEXT].textOrNull.orEmpty())
    }.also { cache = it }

    fun rules(): Map<String, MoneyCategory> = replica.entities(EntityTypes.MONEY_RULE).mapNotNull { e ->
        val m = e[MoneyRuleFields.MERCHANT].textOrNull ?: return@mapNotNull null
        val raw = e[MoneyRuleFields.CATEGORY].textOrNull
        // A payee in one of his own categories is Other underneath ([MoneyOwnCategories]), while that category is there.
        val c = MoneyCategory.of(raw)
            ?: MoneyOwnCategories.idOf(raw)?.takeIf { ownLive(it) }?.let { MoneyCategory.OTHER }
            ?: return@mapNotNull null
        m to c
    }.toMap()

    /** His own categories, oldest first (then by name). */
    fun ownCategories(): List<MoneyOwnCategory> = replica.entities(EntityTypes.MONEY_CATEGORY)
        .mapNotNull { e -> MoneyOwnCategories.read(e.ref.entityId, e.fields) }
        .sortedWith(compareBy<MoneyOwnCategory> { it.createdAtMs }.thenBy { it.name.lowercase() })

    private fun ownLive(id: String): Boolean =
        replica.entity(EntityTypes.MONEY_CATEGORY, id)?.let { MoneyOwnCategories.read(id, it.fields) } != null

    /** The payees he put in one of his own categories: merchant key → the category's id (live ones only). */
    fun ownRules(): Map<String, String> {
        val live = ownCategories().mapTo(HashSet()) { it.id }
        if (live.isEmpty()) return emptyMap()
        return replica.entities(EntityTypes.MONEY_RULE).mapNotNull { e ->
            val m = e[MoneyRuleFields.MERCHANT].textOrNull ?: return@mapNotNull null
            val id = MoneyOwnCategories.idOf(e[MoneyRuleFields.CATEGORY].textOrNull)?.takeIf { it in live } ?: return@mapNotNull null
            m to id
        }.toMap()
    }

    /**
     * Makes one of his own categories ([name], [tone], [icon]), on both devices, and puts [txnId]'s payee in it (past and
     * future) when given. A name that is a built-in category is refused; one he already has is reused (a removed one comes
     * back). Undo by [undoCategory] puts the payee back and takes away a category this made.
     */
    fun addOwnCategory(name: String, tone: Int, icon: MoneyIcon, txnId: String?): MoneySaved {
        val clean = MoneyOwnCategories.clean(name) ?: return MoneySaved(MoneyOwnCategories.NO_NAME, refused = true)
        MoneyOwnCategories.builtIn(clean)?.let { return MoneySaved(MoneyOwnCategories.alreadyLine(it), refused = true) }
        val t = txnId?.let { id -> txns().firstOrNull { it.id == id } ?: return MoneySaved(MoneyRules.GONE, refused = true) }
        // One he renamed keeps its first id, so look for the name among the live ones first.
        val live = ownCategories().firstOrNull { MoneyOwnCategories.sameName(it.name, clean) }
        val id = live?.id ?: MoneyOwnCategories.idFor(clean)
        val existing = live ?: replica.entity(EntityTypes.MONEY_CATEGORY, id)?.let { MoneyOwnCategories.read(id, it.fields) }
        val made = existing == null
        val c = existing ?: MoneyOwnCategory(id, clean, tone.coerceIn(0, MoneySpending.TONES - 1), icon.takeIf { it in MoneyOwnCategories.ICONS } ?: MoneyIcon.DOTS, nowMs())
        var ruleUndo = ""
        var count = 0
        var merchant: String? = null
        replica.batched {
            if (made) replica.commitLocal(EntityTypes.MONEY_CATEGORY, id, mapOf(
                MoneyOwnFields.NAME to c.name.fv(), MoneyOwnFields.TONE to c.tone.toLong().fv(), MoneyOwnFields.ICON to c.icon.name.fv(),
                MoneyOwnFields.CREATED_AT to c.createdAtMs.fv(), MoneyOwnFields.DELETED to false.fv(), MoneyOwnFields.HIDDEN to false.fv(),
            ))
            if (t != null) {
                val key = MoneyCategoryRules.merchantKey(t.text)
                val rid = MoneyRules.ruleId(key)
                val before = replica.entity(EntityTypes.MONEY_RULE, rid)?.get(MoneyRuleFields.CATEGORY)?.textOrNull
                replica.commitLocal(EntityTypes.MONEY_RULE, rid, mapOf(MoneyRuleFields.MERCHANT to key.fv(), MoneyRuleFields.CATEGORY to c.key.fv()))
                ruleUndo = "$rid|${before.orEmpty()}"
                count = txns().count { MoneyCategoryRules.merchantKey(it.text) == key }
                merchant = MoneyCategoryRules.merchantLabel(key)
            }
        }
        val line = if (made) MoneyOwnCategories.madeLine(c.name, merchant, count)
        else merchant?.let { MoneyPaymentSheet.movedLine(it, c.name, count) } ?: "${c.name} is already yours"
        return MoneySaved(line, undo = OWN_UNDO + id + "|" + (if (made) "1" else "0") + "|" + ruleUndo)
    }

    private fun ownLiveOf(id: String): MoneyOwnCategory? =
        replica.entity(EntityTypes.MONEY_CATEGORY, id)?.let { MoneyOwnCategories.read(id, it.fields) }

    /**
     * Slice 3: renames [id] and changes its colour and icon, on both devices. A built-in name, or the name of another of
     * his, is refused. Undo by [undoCategory] puts the old name, colour and icon back.
     */
    fun editOwnCategory(id: String, name: String, tone: Int, icon: MoneyIcon): MoneySaved {
        val c = ownLiveOf(id) ?: return MoneySaved(MoneyOwnCategories.GONE, refused = true)
        val clean = MoneyOwnCategories.clean(name) ?: return MoneySaved(MoneyOwnCategories.NO_NAME, refused = true)
        MoneyOwnCategories.builtIn(clean)?.let { return MoneySaved("${it.label} is already a category", refused = true) }
        ownCategories().firstOrNull { it.id != id && MoneyOwnCategories.sameName(it.name, clean) }
            ?.let { return MoneySaved(MoneyOwnCategories.takenLine(it.name), refused = true) }
        val t = tone.coerceIn(0, MoneySpending.TONES - 1)
        val i = icon.takeIf { it in MoneyOwnCategories.ICONS } ?: MoneyIcon.DOTS
        replica.commitLocal(EntityTypes.MONEY_CATEGORY, id, mapOf(
            MoneyOwnFields.NAME to clean.fv(), MoneyOwnFields.TONE to t.toLong().fv(), MoneyOwnFields.ICON to i.name.fv(),
        ))
        return MoneySaved(MoneyOwnCategories.renamedLine(c.name, clean), undo = "$OWN_EDIT_UNDO$id|${c.tone}|${c.icon.name}|${c.name}")
    }

    /** Slice 3: hides [id] from Spending's ring (its slice drawn quiet, like Other) or shows it again. Undo flips it back. */
    fun hideOwnCategory(id: String, hidden: Boolean): MoneySaved {
        val c = ownLiveOf(id) ?: return MoneySaved(MoneyOwnCategories.GONE, refused = true)
        replica.commitLocal(EntityTypes.MONEY_CATEGORY, id, mapOf(MoneyOwnFields.HIDDEN to hidden.fv()))
        return MoneySaved(MoneyOwnCategories.hiddenLine(c.name, hidden), undo = "$OWN_HIDE_UNDO$id|${if (c.hidden) "1" else "0"}")
    }

    /**
     * Slice 3: merges [id] into [intoKey] (a built-in spending category, or another of his own): its payees move there,
     * past and future, and [id] goes. [MoneyCategory.OTHER] is Delete. Undo brings the category and its payees back.
     */
    fun mergeOwnCategory(id: String, intoKey: String): MoneySaved {
        val c = ownLiveOf(id) ?: return MoneySaved(MoneyOwnCategories.GONE, refused = true)
        val intoLabel = MoneyCategory.of(intoKey)?.takeIf { it.spend }?.label
            ?: MoneyOwnCategories.idOf(intoKey)?.takeIf { it != id }?.let { ownLiveOf(it) }?.name
            ?: return MoneySaved(MoneyOwnCategories.GONE, refused = true)
        val mine = c.key
        val moved = replica.entities(EntityTypes.MONEY_RULE).filter { it[MoneyRuleFields.CATEGORY].textOrNull == mine }.map { it.ref.entityId }
        replica.batched {
            moved.forEach { replica.commitLocal(EntityTypes.MONEY_RULE, it, mapOf(MoneyRuleFields.CATEGORY to intoKey.fv())) }
            replica.commitLocal(EntityTypes.MONEY_CATEGORY, id, mapOf(MoneyOwnFields.DELETED to true.fv()))
        }
        val line = if (intoKey == MoneyCategory.OTHER.key) MoneyOwnCategories.deletedLine(c.name, moved.size)
        else MoneyOwnCategories.mergedLine(c.name, intoLabel, moved.size)
        return MoneySaved(line, undo = "$OWN_MOVE_UNDO$id|${moved.joinToString(",")}")
    }

    /** Slice 3: deletes [id]; its payees go to Other (past and future). Undo brings it back with its payees. */
    fun deleteOwnCategory(id: String): MoneySaved = mergeOwnCategory(id, MoneyCategory.OTHER.key)

    /** Slice 13: the accounts he marked as his side hustle's. */
    fun businessAccounts(): Set<String> = replica.entities(EntityTypes.MONEY_ACCOUNT)
        .filter { it[MoneyBusinessFields.ACCOUNT].boolOrNull == true }.map { it.ref.entityId }.toSet()

    /** Slice 13: payers he marked Business (true) or kept out of it (false), by merchant key. */
    fun businessMerchants(): Map<String, Boolean> = replica.entities(EntityTypes.MONEY_RULE).mapNotNull { e ->
        val m = e[MoneyRuleFields.MERCHANT].textOrNull ?: return@mapNotNull null
        val b = e[MoneyBusinessFields.MERCHANT].boolOrNull ?: return@mapNotNull null
        m to b
    }.toMap()

    /** Slice 13: his share to put aside (percent), from the synced setting. */
    fun setAsidePct(): Int = MoneyBusiness.setAside(replica.entity(EntityTypes.CONTEXT_MODE, MoneyBusiness.ENTITY_ID)?.fields)

    /** Slice 13: his share to put aside ([key] "20"), on both devices. */
    fun setSetAside(key: String): MoneySaved {
        val pct = MoneyBusiness.parseChoice(key) ?: return MoneySaved(MoneyRules.GONE, refused = true)
        val before = setAsidePct()
        replica.commitLocal(EntityTypes.CONTEXT_MODE, MoneyBusiness.ENTITY_ID, MoneyBusiness.setAsideFields(pct, nowMs()))
        return MoneySaved(MoneyBusiness.setAsideSaid(pct), undo = BIZ_UNDO + "p|" + MoneyBusiness.ENTITY_ID + "|" + before)
    }

    /** Slice 13: the whole account [accountId] is (or isn't) the side hustle's, on both devices; Undo by [undoCategory]. */
    fun setBusinessAccount(accountId: String, on: Boolean): MoneySaved {
        val a = accounts().firstOrNull { it.id == accountId } ?: return MoneySaved(MoneyRules.GONE, refused = true)
        val before = replica.entity(EntityTypes.MONEY_ACCOUNT, accountId)?.get(MoneyBusinessFields.ACCOUNT)?.boolOrNull
        replica.commitLocal(EntityTypes.MONEY_ACCOUNT, accountId, mapOf(MoneyBusinessFields.ACCOUNT to on.fv()))
        return MoneySaved(MoneyBusiness.accountSaid(a.name, on), undo = BIZ_UNDO + "a|" + accountId + "|" + (before?.toString() ?: ""))
    }

    /**
     * Slice 13: every line to or from [txnId]'s payer counts as the side hustle's ([on]) or is kept out of it, on every
     * account and both devices; Undo by [undoCategory].
     */
    fun setBusinessMerchant(txnId: String, on: Boolean): MoneySaved {
        val t = txns().firstOrNull { it.id == txnId } ?: return MoneySaved(MoneyRules.GONE, refused = true)
        val key = MoneyCategoryRules.merchantKey(t.text)
        val rid = MoneyRules.ruleId(key)
        val before = replica.entity(EntityTypes.MONEY_RULE, rid)?.get(MoneyBusinessFields.MERCHANT)?.boolOrNull
        replica.commitLocal(EntityTypes.MONEY_RULE, rid, mapOf(MoneyRuleFields.MERCHANT to key.fv(), MoneyBusinessFields.MERCHANT to on.fv()))
        return MoneySaved(MoneyBusiness.merchantSaid(MoneyCategoryRules.merchantLabel(key), on), undo = BIZ_UNDO + "m|" + rid + "|" + (before?.toString() ?: ""))
    }

    /** Needs and wants: payers he marked a need (true) or a want (false), by merchant key. */
    fun needMerchants(): Map<String, Boolean> = replica.entities(EntityTypes.MONEY_RULE).mapNotNull { e ->
        val m = e[MoneyRuleFields.MERCHANT].textOrNull ?: return@mapNotNull null
        val b = e[MoneyNeedFields.MERCHANT].boolOrNull ?: return@mapNotNull null
        m to b
    }.toMap()

    /**
     * Needs and wants: every payment to [txnId]'s payer counts as a need ([on]) or a want, past and future, on every
     * account and both devices; Undo by [undoCategory].
     */
    fun setNeedMerchant(txnId: String, on: Boolean): MoneySaved {
        val t = txns().firstOrNull { it.id == txnId } ?: return MoneySaved(MoneyRules.GONE, refused = true)
        val key = MoneyCategoryRules.merchantKey(t.text)
        val rid = MoneyRules.ruleId(key)
        val before = replica.entity(EntityTypes.MONEY_RULE, rid)?.get(MoneyNeedFields.MERCHANT)?.boolOrNull
        replica.commitLocal(EntityTypes.MONEY_RULE, rid, mapOf(MoneyRuleFields.MERCHANT to key.fv(), MoneyNeedFields.MERCHANT to on.fv()))
        return MoneySaved(MoneyNeeds.said(MoneyCategoryRules.merchantLabel(key), on), undo = NEED_UNDO + rid + "|" + (before?.toString() ?: ""))
    }

    /** Slice 14: payments he marked shared (true) or kept out (false), by line id. */
    fun sharedTxns(): Map<String, Boolean> = replica.entities(EntityTypes.MONEY_TXN).mapNotNull { e ->
        e[MoneySharedFields.TXN].boolOrNull?.let { e.ref.entityId to it }
    }.toMap()

    /** Slice 14: payers he marked Always shared, by merchant key. */
    fun sharedMerchants(): Map<String, Boolean> = replica.entities(EntityTypes.MONEY_RULE).mapNotNull { e ->
        val m = e[MoneyRuleFields.MERCHANT].textOrNull ?: return@mapNotNull null
        val b = e[MoneySharedFields.MERCHANT].boolOrNull ?: return@mapNotNull null
        m to b
    }.toMap()

    /** Slice 14: what his partner paid, as he typed it. */
    fun sharedEntries(): List<MoneySharedEntry> = replica.entities(EntityTypes.MONEY_SHARED).mapNotNull { e ->
        val name = e[MoneySharedFields.NAME].textOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val p = e[MoneySharedFields.PENCE].longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
        val d = e[MoneySharedFields.DAY].longOrNull ?: return@mapNotNull null
        MoneySharedEntry(e.ref.entityId, name, p, d, e[MoneySharedFields.ADDED_AT].longOrNull ?: 0L)
    }

    /** Slice 14: his share and the last settle-up. */
    fun sharedSetting(): MoneySharedSetting = MoneyShared.setting(replica.entity(EntityTypes.CONTEXT_MODE, MoneyShared.ENTITY_ID)?.fields)

    /** Slice 14: Shared on one payment ([on] false keeps it out even of an always-shared payer), on both devices; Undo by [undoCategory]. */
    fun setSharedTxn(txnId: String, on: Boolean): MoneySaved {
        val t = txns().firstOrNull { it.id == txnId } ?: return MoneySaved(MoneyRules.GONE, refused = true)
        val before = replica.entity(EntityTypes.MONEY_TXN, txnId)?.get(MoneySharedFields.TXN)?.boolOrNull
        replica.commitLocal(EntityTypes.MONEY_TXN, txnId, mapOf(MoneySharedFields.TXN to on.fv()))
        val merchant = MoneyCategoryRules.merchantLabel(MoneyCategoryRules.merchantKey(t.text))
        return MoneySaved(MoneyShared.txnSaid(merchant, on), undo = SHARED_UNDO + "t|" + txnId + "|" + (before?.toString() ?: ""))
    }

    /** Slice 14: Always shared on [txnId]'s payer (every payment to them, on any account), on both devices; Undo by [undoCategory]. */
    fun setSharedMerchant(txnId: String, on: Boolean): MoneySaved {
        val t = txns().firstOrNull { it.id == txnId } ?: return MoneySaved(MoneyRules.GONE, refused = true)
        val key = MoneyCategoryRules.merchantKey(t.text)
        val rid = MoneyRules.ruleId(key)
        val before = replica.entity(EntityTypes.MONEY_RULE, rid)?.get(MoneySharedFields.MERCHANT)?.boolOrNull
        val writes = mutableMapOf(MoneyRuleFields.MERCHANT to key.fv(), MoneySharedFields.MERCHANT to (if (on) true.fv() else FieldValue.Null))
        replica.commitLocal(EntityTypes.MONEY_RULE, rid, writes)
        // A payment he'd taken out of it on its own follows the payer again when the payer is marked.
        val tBefore = replica.entity(EntityTypes.MONEY_TXN, txnId)?.get(MoneySharedFields.TXN)?.boolOrNull
        if (on && tBefore == false) replica.commitLocal(EntityTypes.MONEY_TXN, txnId, mapOf(MoneySharedFields.TXN to FieldValue.Null))
        return MoneySaved(MoneyShared.merchantSaid(MoneyCategoryRules.merchantLabel(key), on),
            undo = SHARED_UNDO + "m|" + rid + "|" + (before?.toString() ?: "") + "|" + txnId + "|" + (tBefore?.toString() ?: ""))
    }

    /** Slice 14: his share of the shared costs ([key] "50"), on both devices; Undo by [undoCategory]. */
    fun setSharedShare(key: String): MoneySaved {
        val pct = MoneyShared.parseChoice(key) ?: return MoneySaved(MoneyRules.GONE, refused = true)
        val before = sharedSetting().myPct
        replica.commitLocal(EntityTypes.CONTEXT_MODE, MoneyShared.ENTITY_ID, MoneyShared.shareFields(pct, nowMs()))
        return MoneySaved(MoneyShared.shareSaid(pct), undo = SHARED_UNDO + "p|" + before)
    }

    /** Slice 14: what his partner paid ([text] "Tesco £42.50"), on [today], on both devices; Undo by [undoCategory]. */
    fun addShared(text: String, today: Long, partner: String): MoneySaved {
        val (name, pence) = MoneyShared.parse(text) ?: return MoneySaved(MoneyShared.BAD, refused = true)
        if (sharedEntries().size >= MoneyShared.MAX_ENTRIES) return MoneySaved("Up to ${MoneyShared.MAX_ENTRIES} payments here · Settled up and remove old ones first.", refused = true)
        val now = nowMs()
        val id = MoneyShared.entryId(name, today, now)
        writeShared(id, name, pence, today, now)
        return MoneySaved(MoneyShared.addedSaid(partner, name, pence), undo = SHARED_UNDO + "e|" + id + "|||||")
    }

    /** Slice 14: takes one of her payments off, on both devices; Undo by [undoCategory]. */
    fun removeShared(id: String): MoneySaved {
        val e = sharedEntries().firstOrNull { it.id == id } ?: return MoneySaved(MoneyRules.GONE, refused = true)
        writeShared(id, null, null, null, null)
        return MoneySaved(MoneyShared.removedSaid(e.name),
            undo = SHARED_UNDO + "e|" + id + "|" + listOf(e.name.replace("|", " "), e.pence, e.day, e.addedAtMs).joinToString("|"))
    }

    /** Slice 14: Settled up — from now on only what's paid after today counts; Undo by [undoCategory]. */
    fun settleShared(today: Long, partner: String): MoneySaved {
        val s = sharedSetting()
        replica.commitLocal(EntityTypes.CONTEXT_MODE, MoneyShared.ENTITY_ID, MoneyShared.settleFields(today, nowMs(), nowMs()))
        return MoneySaved(MoneyShared.settledSaid(partner), undo = SHARED_UNDO + "s|" + (s.settledDay?.toString() ?: "") + "|" + (s.settledAtMs?.toString() ?: ""))
    }

    private fun writeShared(id: String, name: String?, pence: Long?, day: Long?, addedAt: Long?) {
        replica.commitLocal(EntityTypes.MONEY_SHARED, id, mapOf(
            MoneySharedFields.NAME to (name?.fv() ?: FieldValue.Null),
            MoneySharedFields.PENCE to (pence?.fv() ?: FieldValue.Null),
            MoneySharedFields.DAY to (day?.fv() ?: FieldValue.Null),
            MoneySharedFields.ADDED_AT to (addedAt?.fv() ?: FieldValue.Null),
        ))
    }

    /** Slice 15: the challenges he set. */
    fun challenges(): MoneyChallengeSetting = MoneyChallenges.setting(replica.entity(EntityTypes.CONTEXT_MODE, MoneyChallenges.ENTITY_ID)?.fields)

    private fun challengeField(name: String): FieldValue? = replica.entity(EntityTypes.CONTEXT_MODE, MoneyChallenges.ENTITY_ID)?.get(name)

    /** Slice 15: counting no-spend days on or off, on both devices; Undo by [undoCategory]. */
    fun setNoSpend(on: Boolean): MoneySaved {
        val before = challenges().noSpend
        replica.commitLocal(EntityTypes.CONTEXT_MODE, MoneyChallenges.ENTITY_ID, MoneyChallenges.noSpendFields(on, nowMs()))
        return MoneySaved(MoneyChallenges.noSpendSaid(on), undo = CHALLENGE_UNDO + "n|" + before)
    }

    /** Slice 15: fun money a week ([text] "£50"; blank or null stops it), on both devices; Undo by [undoCategory]. */
    fun setFunMoney(text: String?): MoneySaved {
        val pence = if (text.isNullOrBlank()) null else MoneyChallenges.parseFun(text) ?: return MoneySaved(MoneyChallenges.BAD_FUN, refused = true)
        val before = challengeField(MoneyChallengeFields.FUN)?.longOrNull
        if (pence == null && before == null) return MoneySaved(MoneyRules.GONE, refused = true)
        replica.commitLocal(EntityTypes.CONTEXT_MODE, MoneyChallenges.ENTITY_ID, MoneyChallenges.funFields(pence, nowMs()))
        return MoneySaved(MoneyChallenges.funSaid(pence), undo = CHALLENGE_UNDO + "f|" + (before?.toString() ?: ""))
    }

    /** Slice 15: a category's cap a month ([text] "Eating out £150"), on both devices; Undo by [undoCategory]. */
    fun setCap(text: String): MoneySaved {
        // His own categories first ("Vape £60"); none of his has a built-in name.
        MoneyChallenges.parseOwnCap(text, ownCategories())?.let { (o, pence) -> return setOwnCap(o.id, pence) }
        val (c, pence) = MoneyChallenges.parseCap(text) ?: return MoneySaved(MoneyChallenges.BAD_CAP, refused = true)
        val before = challengeField(MoneyChallengeFields.cap(c))?.longOrNull
        replica.commitLocal(EntityTypes.CONTEXT_MODE, MoneyChallenges.ENTITY_ID, MoneyChallenges.capFields(c, pence, nowMs()))
        return MoneySaved(MoneyChallenges.capSaid(c, pence), undo = CHALLENGE_UNDO + "c|" + c.key + "|" + (before?.toString() ?: ""))
    }

    /**
     * His own categories: a monthly cap on [id] ([text] "£60"; blank or null takes it off), counting the payees he put in
     * it, on both devices. Undo by [undoCategory].
     */
    fun capOwnCategory(id: String, text: String?): MoneySaved {
        ownLiveOf(id) ?: return MoneySaved(MoneyOwnCategories.GONE, refused = true)
        val pence = if (text.isNullOrBlank()) null else MoneyChallenges.parseAmount(text) ?: return MoneySaved(MoneyChallenges.BAD_AMOUNT, refused = true)
        return setOwnCap(id, pence)
    }

    private fun setOwnCap(id: String, pence: Long?): MoneySaved {
        val c = ownLiveOf(id) ?: return MoneySaved(MoneyOwnCategories.GONE, refused = true)
        val before = challengeField(MoneyChallengeFields.ownCap(id))?.longOrNull
        if (pence == null && before == null) return MoneySaved(MoneyRules.GONE, refused = true)
        replica.commitLocal(EntityTypes.CONTEXT_MODE, MoneyChallenges.ENTITY_ID, MoneyChallenges.ownCapFields(id, pence, nowMs()))
        return MoneySaved(MoneyChallenges.capSaid(c.name, pence), undo = CHALLENGE_UNDO + "o|" + id + "|" + (before?.toString() ?: ""))
    }

    /** Slice 15: takes a cap ([key] the category's, or `own:<id>`) or fun money ([MoneyChallenges.FUN_KEY]) off; Undo by [undoCategory]. */
    fun removeChallenge(key: String): MoneySaved {
        if (key == MoneyChallenges.FUN_KEY) return setFunMoney(null)
        MoneyOwnCategories.idOf(key)?.let { return setOwnCap(it, null) }
        val c = MoneyCategory.of(key)?.takeIf { it.spend } ?: return MoneySaved(MoneyRules.GONE, refused = true)
        val before = challengeField(MoneyChallengeFields.cap(c))?.longOrNull ?: return MoneySaved(MoneyRules.GONE, refused = true)
        replica.commitLocal(EntityTypes.CONTEXT_MODE, MoneyChallenges.ENTITY_ID, MoneyChallenges.capFields(c, null, nowMs()))
        return MoneySaved(MoneyChallenges.capSaid(c, null), undo = CHALLENGE_UNDO + "c|" + c.key + "|" + before)
    }

    /** Slice 2: the kinds of tip he hid ([MoneyTipKind.key]). */
    fun hiddenTips(): Set<String> = replica.entities(EntityTypes.MONEY_TIPS)
        .filter { !it.ref.entityId.startsWith(IDEA_PREFIX) && it[MoneyTipsFields.HIDDEN].boolOrNull == true }.map { it.ref.entityId }.toSet()

    /** Obi's money check: the ideas he said Not for me to, and the ones Remind me put away until a day. */
    fun ideaState(): MoneyIdeaState {
        val ideas = replica.entities(EntityTypes.MONEY_TIPS).filter { it.ref.entityId.startsWith(IDEA_PREFIX) }
        return MoneyIdeaState(
            notFor = ideas.filter { it[MoneyTipsFields.HIDDEN].boolOrNull == true }.map { it.ref.entityId.removePrefix(IDEA_PREFIX) }.toSet(),
            until = ideas.mapNotNull { e -> e[MoneyTipsFields.UNTIL].longOrNull?.let { e.ref.entityId.removePrefix(IDEA_PREFIX) to it } }.toMap(),
        )
    }

    /** Not for me on one idea: put away for good on both devices; Undo (by [undoCategory] with the token) brings it back. */
    fun ideaNotFor(id: String): MoneySaved {
        if (!MoneyCheck.validId(id)) return MoneySaved(MoneyRules.GONE, refused = true)
        replica.commitLocal(EntityTypes.MONEY_TIPS, IDEA_PREFIX + id, mapOf(MoneyTipsFields.HIDDEN to true.fv()))
        return MoneySaved(MoneyCheck.NOT_FOR_SAID, undo = IDEA_UNDO + id)
    }

    /** Remind me on one idea: put away until [MoneyCheck.REMIND_DAYS] days after [today], on both devices; Undo brings it back now. */
    fun ideaRemind(id: String, today: Long): MoneySaved {
        if (!MoneyCheck.validId(id)) return MoneySaved(MoneyRules.GONE, refused = true)
        val day = today + MoneyCheck.REMIND_DAYS
        replica.commitLocal(EntityTypes.MONEY_TIPS, IDEA_PREFIX + id, mapOf(MoneyTipsFields.UNTIL to day.fv()))
        return MoneySaved(MoneyCheck.remindSaid(day), undo = IDEA_UNDO + id)
    }

    /** Show them again on the money check: every idea put away, and every kind of tip hidden, back. */
    fun showIdeas(): MoneySaved {
        val ideas = replica.entities(EntityTypes.MONEY_TIPS).filter {
            it.ref.entityId.startsWith(IDEA_PREFIX) && (it[MoneyTipsFields.HIDDEN].boolOrNull == true || it[MoneyTipsFields.UNTIL].longOrNull != null)
        }.map { it.ref.entityId }
        val kinds = hiddenTips()
        if (ideas.isEmpty() && kinds.isEmpty()) return MoneySaved(MoneyCheck.SHOWN_AGAIN)
        replica.batched {
            ideas.forEach { replica.commitLocal(EntityTypes.MONEY_TIPS, it, mapOf(MoneyTipsFields.HIDDEN to false.fv(), MoneyTipsFields.UNTIL to FieldValue.Null)) }
            kinds.forEach { replica.commitLocal(EntityTypes.MONEY_TIPS, it, mapOf(MoneyTipsFields.HIDDEN to false.fv())) }
        }
        return MoneySaved(MoneyCheck.SHOWN_AGAIN)
    }

    /** Obi's wording of the money check as last synced ([MoneyCheckWords]). */
    fun checkWords(): MoneyCheckWordsState {
        val es = replica.entities(EntityTypes.MONEY_TIPS)
        val words = es.filter { it.ref.entityId.startsWith(IDEA_PREFIX) }.mapNotNull { e ->
            val t = e[MoneyTipsFields.WORD_TITLE].textOrNull ?: return@mapNotNull null
            val w = e[MoneyTipsFields.WORD_WHY].textOrNull ?: return@mapNotNull null
            val r = e[MoneyTipsFields.RANK].longOrNull ?: return@mapNotNull null
            e.ref.entityId.removePrefix(IDEA_PREFIX) to MoneyIdeaWords(t, w, r.toInt())
        }.toMap()
        val at = es.firstOrNull { it.ref.entityId == MoneyCheckWords.ENTITY }
        return MoneyCheckWordsState(words, at?.get(MoneyTipsFields.AT)?.longOrNull, at?.get(MoneyTipsFields.DAY)?.longOrNull)
    }

    /**
     * Keeps Obi's [words] (id → title and why, in his order) on both devices, taking his older words off every idea he
     * didn't word this time, and notes the run at [atMs] on [day] (a run that worded nothing still counts as the week's).
     */
    fun saveCheckWords(words: List<Pair<String, Pair<String, String>>>, atMs: Long, day: Long) {
        val keep = words.filter { MoneyCheck.validId(it.first) }.distinctBy { it.first }
        val ids = keep.map { it.first }.toSet()
        replica.batched {
            replica.entities(EntityTypes.MONEY_TIPS).filter {
                it.ref.entityId.startsWith(IDEA_PREFIX) && it.ref.entityId.removePrefix(IDEA_PREFIX) !in ids && it[MoneyTipsFields.WORD_TITLE].textOrNull != null
            }.forEach { replica.commitLocal(EntityTypes.MONEY_TIPS, it.ref.entityId, wordFields(null, null, null)) }
            keep.forEachIndexed { n, (id, w) -> replica.commitLocal(EntityTypes.MONEY_TIPS, IDEA_PREFIX + id, wordFields(w.first, w.second, n)) }
            replica.commitLocal(EntityTypes.MONEY_TIPS, MoneyCheckWords.ENTITY, mapOf(MoneyTipsFields.AT to atMs.fv(), MoneyTipsFields.DAY to day.fv()))
        }
    }

    private fun wordFields(title: String?, why: String?, rank: Int?): Map<String, FieldValue> = mapOf(
        MoneyTipsFields.WORD_TITLE to (title?.fv() ?: FieldValue.Null),
        MoneyTipsFields.WORD_WHY to (why?.fv() ?: FieldValue.Null),
        MoneyTipsFields.RANK to (rank?.toLong()?.fv() ?: FieldValue.Null),
    )

    /** Hides every tip of [kindKey] on both devices; Undo (by [undoCategory] with the token) shows them again. */
    fun hideTips(kindKey: String): MoneySaved {
        val kind = MoneyTipKind.of(kindKey) ?: return MoneySaved(MoneyRules.GONE, refused = true)
        replica.commitLocal(EntityTypes.MONEY_TIPS, kind.key, mapOf(MoneyTipsFields.HIDDEN to true.fv()))
        return MoneySaved(MoneyTips.hiddenSaid(kind), undo = TIPS_UNDO + kind.key)
    }

    /** Slice 3: the watchdog alerts he put away. */
    fun fineAlerts(): Set<String> = replica.entities(EntityTypes.MONEY_ALERT)
        .filter { it[MoneyAlertFields.FINE].boolOrNull == true }.map { it.ref.entityId }.toSet()

    /** "That's fine" on one alert: put away on both devices; Undo (by [undoCategory] with the token) brings it back. */
    fun alertFine(alertId: String): MoneySaved {
        if (!alertId.startsWith("w") || alertId.length > 40) return MoneySaved(MoneyRules.GONE, refused = true)
        replica.commitLocal(EntityTypes.MONEY_ALERT, alertId, mapOf(MoneyAlertFields.FINE to true.fv()))
        return MoneySaved(MoneyWatch.FINE_SAID, undo = ALERT_UNDO + alertId)
    }

    /** Shows every hidden kind of tip again. */
    fun showTips(): MoneySaved {
        val hidden = hiddenTips()
        if (hidden.isEmpty()) return MoneySaved(MoneyTips.SHOWN_AGAIN)
        replica.batched { hidden.forEach { replica.commitLocal(EntityTypes.MONEY_TIPS, it, mapOf(MoneyTipsFields.HIDDEN to false.fv())) } }
        return MoneySaved(MoneyTips.SHOWN_AGAIN)
    }

    /** Imports a statement file's [text] into [accountName] (made if new); lines already there are left alone. */
    fun import(accountName: String, text: String): MoneySaved {
        val name = accountName.trim().take(40)
        val existing = accounts()
        // A2: names are shown with any account number masked, so a chip's name finds its statement account by that.
        val id = existing.firstOrNull { it.source == "csv" && it.name.equals(MoneyAccountNames.shown(name), ignoreCase = true) }?.id
            ?: MoneyRules.accountId(name) ?: return MoneySaved(MoneyRules.NO_ACCOUNT, refused = true)
        if (existing.none { it.id == id } && existing.size >= MoneyRules.MAX_ACCOUNTS) return MoneySaved("Up to ${MoneyRules.MAX_ACCOUNTS} accounts.", refused = true)
        val read = MoneyCsv.read(text)
        read.problem?.let { return MoneySaved(it, refused = true) }
        val have = txns().filter { it.account == id }.map { it.id }.toSet()
        val seen = HashMap<String, Int>()
        var added = 0
        var already = 0
        replica.batched {
            replica.commitLocal(EntityTypes.MONEY_ACCOUNT, id, mapOf(
                MoneyAccountFields.NAME to name.fv(), MoneyAccountFields.SOURCE to "csv".fv(), MoneyAccountFields.IMPORTED_AT to nowMs().fv(),
                os.meka.core.sync.DELETED_FIELD to false.fv(),
            ))
            for (row in read.rows) {
                val k = "${row.day}:${row.pence}:${row.text.trim().uppercase()}"
                val n = seen.getOrElse(k) { 0 }
                seen[k] = n + 1
                val tid = MoneyRules.txnId(id, row, n)
                if (tid in have) { already++; continue }
                replica.commitLocal(EntityTypes.MONEY_TXN, tid, mapOf(
                    MoneyTxnFields.ACCOUNT to id.fv(), MoneyTxnFields.DAY to row.day.fv(), MoneyTxnFields.PENCE to row.pence.fv(),
                    MoneyTxnFields.TEXT to row.text.fv(), os.meka.core.sync.DELETED_FIELD to false.fv(),
                ))
                added++
            }
        }
        // Slice 6: the file's balance, unless the account already holds a newer one.
        val bp = read.balancePence
        val bd = read.balanceDay
        if (bp != null && bd != null) {
            val had = accounts().firstOrNull { it.id == id }?.balance
            if (had == null || bd >= had.day) writeBalance(id, MoneyBalance(bp, bd, typed = false))
        }
        cache = null
        return MoneySaved(MoneyRules.importedLine(name, added, already, read))
    }

    /** Puts every payment to [txnId]'s merchant in [categoryKey] from now on; Undo hands back the old choice. */
    fun setCategory(txnId: String, categoryKey: String): MoneySaved {
        val t = txns().firstOrNull { it.id == txnId } ?: return MoneySaved(MoneyRules.GONE, refused = true)
        // A built-in category, or one of his own that is still there.
        val label = MoneyCategory.of(categoryKey)?.label
            ?: MoneyOwnCategories.idOf(categoryKey)?.let { id -> replica.entity(EntityTypes.MONEY_CATEGORY, id)?.let { MoneyOwnCategories.read(id, it.fields) } }?.name
            ?: return MoneySaved(MoneyRules.GONE, refused = true)
        val key = MoneyCategoryRules.merchantKey(t.text)
        val rid = MoneyRules.ruleId(key)
        val before = replica.entity(EntityTypes.MONEY_RULE, rid)?.get(MoneyRuleFields.CATEGORY)?.textOrNull
        replica.commitLocal(EntityTypes.MONEY_RULE, rid, mapOf(MoneyRuleFields.MERCHANT to key.fv(), MoneyRuleFields.CATEGORY to categoryKey.fv()))
        val count = txns().count { MoneyCategoryRules.merchantKey(it.text) == key }
        return MoneySaved(MoneyPaymentSheet.movedLine(MoneyCategoryRules.merchantLabel(key), label, count), undo = "$rid|${before.orEmpty()}")
    }

    /** Undo for [setCategory]: the merchant goes back to what it was (or to its words' own category). */
    fun undoCategory(token: String): Boolean {
        if (token.startsWith(OWN_EDIT_UNDO)) {
            val parts = token.removePrefix(OWN_EDIT_UNDO).split('|', limit = 4)
            val id = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return false
            if (replica.entity(EntityTypes.MONEY_CATEGORY, id) == null) return false
            val name = parts.getOrNull(3)?.let { MoneyOwnCategories.clean(it) } ?: return false
            replica.commitLocal(EntityTypes.MONEY_CATEGORY, id, mapOf(
                MoneyOwnFields.NAME to name.fv(), MoneyOwnFields.TONE to (parts.getOrNull(1)?.toLongOrNull() ?: 0L).fv(),
                MoneyOwnFields.ICON to (parts.getOrNull(2) ?: MoneyIcon.DOTS.name).fv(),
            ))
            return true
        }
        if (token.startsWith(OWN_HIDE_UNDO)) {
            val parts = token.removePrefix(OWN_HIDE_UNDO).split('|')
            val id = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return false
            if (replica.entity(EntityTypes.MONEY_CATEGORY, id) == null) return false
            replica.commitLocal(EntityTypes.MONEY_CATEGORY, id, mapOf(MoneyOwnFields.HIDDEN to (parts.getOrNull(1) == "1").fv()))
            return true
        }
        if (token.startsWith(OWN_MOVE_UNDO)) {
            val parts = token.removePrefix(OWN_MOVE_UNDO).split('|')
            val id = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return false
            if (replica.entity(EntityTypes.MONEY_CATEGORY, id) == null) return false
            val key = MoneyOwnCategories.PREFIX + id
            replica.batched {
                replica.commitLocal(EntityTypes.MONEY_CATEGORY, id, mapOf(MoneyOwnFields.DELETED to false.fv()))
                parts.getOrNull(1).orEmpty().split(',').filter { it.isNotEmpty() }.forEach { rid ->
                    if (replica.entity(EntityTypes.MONEY_RULE, rid) != null) {
                        replica.commitLocal(EntityTypes.MONEY_RULE, rid, mapOf(MoneyRuleFields.CATEGORY to key.fv()))
                    }
                }
            }
            return true
        }
        if (token.startsWith(OWN_UNDO)) {
            val parts = token.removePrefix(OWN_UNDO).split('|')
            val id = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return false
            val made = parts.getOrNull(1) == "1"
            val rid = parts.getOrNull(2).orEmpty()
            replica.batched {
                if (rid.isNotEmpty() && replica.entity(EntityTypes.MONEY_RULE, rid) != null) {
                    replica.commitLocal(EntityTypes.MONEY_RULE, rid, mapOf(MoneyRuleFields.CATEGORY to parts.getOrNull(3).orEmpty().ifEmpty { null }.fv()))
                }
                if (made && replica.entity(EntityTypes.MONEY_CATEGORY, id) != null) {
                    replica.commitLocal(EntityTypes.MONEY_CATEGORY, id, mapOf(MoneyOwnFields.DELETED to true.fv()))
                }
            }
            return true
        }
        if (token.startsWith(CHALLENGE_UNDO)) {
            val parts = token.removePrefix(CHALLENGE_UNDO).split('|')
            val fields = when (parts.getOrNull(0)) {
                "n" -> MoneyChallenges.noSpendFields(parts.getOrNull(1) == "true", nowMs())
                "f" -> MoneyChallenges.funFields(parts.getOrNull(1)?.toLongOrNull(), nowMs())
                "c" -> {
                    val c = MoneyCategory.of(parts.getOrNull(1))?.takeIf { it.spend } ?: return false
                    MoneyChallenges.capFields(c, parts.getOrNull(2)?.toLongOrNull(), nowMs())
                }
                "o" -> {
                    val id = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return false
                    MoneyChallenges.ownCapFields(id, parts.getOrNull(2)?.toLongOrNull(), nowMs())
                }
                else -> return false
            }
            replica.commitLocal(EntityTypes.CONTEXT_MODE, MoneyChallenges.ENTITY_ID, fields)
            return true
        }
        if (token.startsWith(SHARED_UNDO)) {
            val parts = token.removePrefix(SHARED_UNDO).split('|')
            fun bool(v: String?): FieldValue = when (v) { "true" -> true.fv(); "false" -> false.fv(); else -> FieldValue.Null }
            when (parts.getOrNull(0)) {
                "t" -> {
                    val id = parts.getOrNull(1) ?: return false
                    if (replica.entity(EntityTypes.MONEY_TXN, id) == null) return false
                    replica.commitLocal(EntityTypes.MONEY_TXN, id, mapOf(MoneySharedFields.TXN to bool(parts.getOrNull(2))))
                }
                "m" -> {
                    val id = parts.getOrNull(1) ?: return false
                    if (replica.entity(EntityTypes.MONEY_RULE, id) == null) return false
                    replica.commitLocal(EntityTypes.MONEY_RULE, id, mapOf(MoneySharedFields.MERCHANT to bool(parts.getOrNull(2))))
                    val tid = parts.getOrNull(3).orEmpty()
                    if (tid.isNotEmpty() && replica.entity(EntityTypes.MONEY_TXN, tid) != null) {
                        replica.commitLocal(EntityTypes.MONEY_TXN, tid, mapOf(MoneySharedFields.TXN to bool(parts.getOrNull(4))))
                    }
                }
                "p" -> {
                    val pct = parts.getOrNull(1)?.toIntOrNull() ?: return false
                    replica.commitLocal(EntityTypes.CONTEXT_MODE, MoneyShared.ENTITY_ID, MoneyShared.shareFields(pct, nowMs()))
                }
                "s" -> replica.commitLocal(EntityTypes.CONTEXT_MODE, MoneyShared.ENTITY_ID,
                    MoneyShared.settleFields(parts.getOrNull(1)?.toLongOrNull(), parts.getOrNull(2)?.toLongOrNull(), nowMs()))
                "e" -> {
                    val id = parts.getOrNull(1)?.takeIf { it.startsWith("s") } ?: return false
                    writeShared(id, parts.getOrNull(2)?.ifEmpty { null }, parts.getOrNull(3)?.toLongOrNull(),
                        parts.getOrNull(4)?.toLongOrNull(), parts.getOrNull(5)?.toLongOrNull())
                }
                else -> return false
            }
            return true
        }
        if (token.startsWith(NEED_UNDO)) {
            val parts = token.removePrefix(NEED_UNDO).split('|')
            val id = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return false
            if (replica.entity(EntityTypes.MONEY_RULE, id) == null) return false
            val bool: FieldValue = when (parts.getOrNull(1)) { "true" -> true.fv(); "false" -> false.fv(); else -> FieldValue.Null }
            replica.commitLocal(EntityTypes.MONEY_RULE, id, mapOf(MoneyNeedFields.MERCHANT to bool))
            return true
        }
        if (token.startsWith(BIZ_UNDO)) {
            val parts = token.removePrefix(BIZ_UNDO).split('|')
            val id = parts.getOrNull(1) ?: return false
            val v = parts.getOrNull(2).orEmpty()
            val bool: FieldValue = when (v) { "true" -> true.fv(); "false" -> false.fv(); else -> FieldValue.Null }
            when (parts.getOrNull(0)) {
                "a" -> { if (accounts().none { it.id == id }) return false; replica.commitLocal(EntityTypes.MONEY_ACCOUNT, id, mapOf(MoneyBusinessFields.ACCOUNT to bool)) }
                "m" -> { if (replica.entity(EntityTypes.MONEY_RULE, id) == null) return false; replica.commitLocal(EntityTypes.MONEY_RULE, id, mapOf(MoneyBusinessFields.MERCHANT to bool)) }
                "p" -> { val pct = v.toIntOrNull() ?: return false; replica.commitLocal(EntityTypes.CONTEXT_MODE, MoneyBusiness.ENTITY_ID, MoneyBusiness.setAsideFields(pct, nowMs())) }
                else -> return false
            }
            return true
        }
        if (token.startsWith(WORTH_UNDO)) {
            val parts = token.removePrefix(WORTH_UNDO).split('|')
            val id = parts.getOrNull(0)?.takeIf { it.startsWith("n-") } ?: return false
            val name = parts.getOrNull(1)?.ifEmpty { null }
            writeWorth(id, name, parts.getOrNull(2)?.ifEmpty { null }?.let { it == "owe" },
                parts.getOrNull(3)?.toLongOrNull(), parts.getOrNull(4)?.toLongOrNull())
            return true
        }
        if (token.startsWith(CARD_UNDO)) {
            val rest = token.removePrefix(CARD_UNDO)
            val id = rest.substringBefore('|')
            if (accounts().none { it.id == id }) return false
            val fields = rest.substringAfter('|', "").split(';').filter { '=' in it }.associate { kv ->
                val f = kv.substringBefore('=')
                val v = kv.substringAfter('=')
                f to when {
                    v.isEmpty() -> FieldValue.Null
                    f == MoneyCardFields.KIND -> v.fv()
                    else -> v.toLongOrNull()?.fv() ?: FieldValue.Null
                }
            }.filterKeys { it in MoneyCardFields.ALL }
            if (fields.isEmpty()) return false
            replica.commitLocal(EntityTypes.MONEY_ACCOUNT, id, fields)
            return true
        }
        if (token.startsWith(NICK_UNDO)) {
            val parts = token.removePrefix(NICK_UNDO).split('|', limit = 2)
            val id = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return false
            if (accounts().none { it.id == id }) return false
            val before = parts.getOrNull(1)?.let(MoneyAccountNames::cleanNick)
            replica.commitLocal(EntityTypes.MONEY_ACCOUNT, id, mapOf(MoneyAccountFields.NICK to (before?.fv() ?: FieldValue.Null)))
            return true
        }
        if (token.startsWith(BALANCE_UNDO)) {
            val parts = token.removePrefix(BALANCE_UNDO).split('|')
            val id = parts.getOrNull(0) ?: return false
            if (accounts().none { it.id == id }) return false
            val p = parts.getOrNull(1)?.toLongOrNull()
            val d = parts.getOrNull(2)?.toLongOrNull()
            writeBalance(id, if (p != null && d != null) MoneyBalance(p, d, parts.getOrNull(3) == "1") else null)
            return true
        }
        if (token.startsWith(ALERT_UNDO)) {
            val id = token.removePrefix(ALERT_UNDO)
            if (replica.entity(EntityTypes.MONEY_ALERT, id) == null) return false
            replica.commitLocal(EntityTypes.MONEY_ALERT, id, mapOf(MoneyAlertFields.FINE to false.fv()))
            return true
        }
        if (token.startsWith(IDEA_UNDO)) {
            val id = token.removePrefix(IDEA_UNDO)
            if (replica.entity(EntityTypes.MONEY_TIPS, IDEA_PREFIX + id) == null) return false
            replica.commitLocal(EntityTypes.MONEY_TIPS, IDEA_PREFIX + id, mapOf(MoneyTipsFields.HIDDEN to false.fv(), MoneyTipsFields.UNTIL to FieldValue.Null))
            return true
        }
        if (token.startsWith(TIPS_UNDO)) {
            val kind = MoneyTipKind.of(token.removePrefix(TIPS_UNDO)) ?: return false
            replica.commitLocal(EntityTypes.MONEY_TIPS, kind.key, mapOf(MoneyTipsFields.HIDDEN to false.fv()))
            return true
        }
        val rid = token.substringBefore('|')
        val before = token.substringAfter('|', "").ifEmpty { null }
        if (replica.entity(EntityTypes.MONEY_RULE, rid) == null) return false
        replica.commitLocal(EntityTypes.MONEY_RULE, rid, mapOf(MoneyRuleFields.CATEGORY to before.fv()))
        return true
    }

    /** Takes [accountId] and its lines off every device (their words and amounts are cleared too). */
    fun removeAccount(accountId: String): MoneySaved {
        val a = accounts().firstOrNull { it.id == accountId } ?: return MoneySaved(MoneyRules.GONE, refused = true)
        val ts = txns().filter { it.account == accountId }
        replica.batched {
            ts.forEach { clear(it.id) }
            replica.commitLocal(EntityTypes.MONEY_ACCOUNT, accountId, mapOf(
                os.meka.core.sync.DELETED_FIELD to true.fv(), MoneyAccountFields.IMPORTED_AT to FieldValue.Null, MoneyAccountFields.NICK to FieldValue.Null,
                MoneyAccountFields.BALANCE to FieldValue.Null, MoneyAccountFields.BALANCE_DAY to FieldValue.Null, MoneyAccountFields.BALANCE_TYPED to FieldValue.Null,
                MoneyBusinessFields.ACCOUNT to FieldValue.Null,
            ) + MoneyCardFields.ALL.associateWith { FieldValue.Null })
        }
        cache = null
        return MoneySaved(MoneyRules.removedLine(a.name, ts.size))
    }

    /** Every account, line and correction gone, on every device. */
    fun deleteAll(): MoneySaved {
        val ts = txns()
        val accounts = accounts()
        val rules = replica.entities(EntityTypes.MONEY_RULE)
        replica.batched {
            ts.forEach { clear(it.id) }
            accounts.forEach {
                replica.commitLocal(EntityTypes.MONEY_ACCOUNT, it.id, mapOf(
                    os.meka.core.sync.DELETED_FIELD to true.fv(), MoneyAccountFields.NAME to FieldValue.Null, MoneyAccountFields.NICK to FieldValue.Null, MoneyAccountFields.IMPORTED_AT to FieldValue.Null,
                    MoneyAccountFields.BALANCE to FieldValue.Null, MoneyAccountFields.BALANCE_DAY to FieldValue.Null, MoneyAccountFields.BALANCE_TYPED to FieldValue.Null,
                    MoneyBusinessFields.ACCOUNT to FieldValue.Null,
                ) + MoneyCardFields.ALL.associateWith { FieldValue.Null })
            }
            worthItems().forEach { writeWorth(it.id, null, null, null, null) }
            sharedEntries().forEach { writeShared(it.id, null, null, null, null) }
            if (replica.entity(EntityTypes.CONTEXT_MODE, MoneyShared.ENTITY_ID) != null) {
                replica.commitLocal(EntityTypes.CONTEXT_MODE, MoneyShared.ENTITY_ID, mapOf(MoneySharedFields.MY_PCT to FieldValue.Null) + MoneyShared.settleFields(null, null, nowMs()))
            }
            if (replica.entity(EntityTypes.CONTEXT_MODE, MoneyChallenges.ENTITY_ID) != null) {
                replica.commitLocal(EntityTypes.CONTEXT_MODE, MoneyChallenges.ENTITY_ID, MoneyChallenges.clearFields(nowMs(), challenges().ownCaps.keys))
            }
            if (replica.entity(EntityTypes.CONTEXT_MODE, MoneyBusiness.ENTITY_ID) != null) {
                replica.commitLocal(EntityTypes.CONTEXT_MODE, MoneyBusiness.ENTITY_ID, mapOf(MoneyBusinessFields.SET_ASIDE to FieldValue.Null))
            }
            hiddenTips().forEach { replica.commitLocal(EntityTypes.MONEY_TIPS, it, mapOf(MoneyTipsFields.HIDDEN to FieldValue.Null)) }
            replica.entities(EntityTypes.MONEY_TIPS).map { it.ref.entityId }.filter { it.startsWith(IDEA_PREFIX) }.forEach {
                replica.commitLocal(EntityTypes.MONEY_TIPS, it, mapOf(MoneyTipsFields.HIDDEN to FieldValue.Null, MoneyTipsFields.UNTIL to FieldValue.Null) + wordFields(null, null, null))
            }
            if (replica.entity(EntityTypes.MONEY_TIPS, MoneyCheckWords.ENTITY) != null) {
                replica.commitLocal(EntityTypes.MONEY_TIPS, MoneyCheckWords.ENTITY, mapOf(MoneyTipsFields.AT to FieldValue.Null, MoneyTipsFields.DAY to FieldValue.Null))
            }
            replica.entities(EntityTypes.MONEY_ALERT).forEach { replica.commitLocal(EntityTypes.MONEY_ALERT, it.ref.entityId, mapOf(MoneyAlertFields.FINE to FieldValue.Null)) }
            replica.entities(EntityTypes.MONEY_CATEGORY, includeDeleted = true).forEach {
                replica.commitLocal(EntityTypes.MONEY_CATEGORY, it.ref.entityId, mapOf(
                    MoneyOwnFields.DELETED to true.fv(), MoneyOwnFields.NAME to FieldValue.Null, MoneyOwnFields.TONE to FieldValue.Null,
                    MoneyOwnFields.ICON to FieldValue.Null, MoneyOwnFields.CREATED_AT to FieldValue.Null, MoneyOwnFields.HIDDEN to FieldValue.Null,
                ))
            }
            rules.forEach {
                replica.commitLocal(EntityTypes.MONEY_RULE, it.ref.entityId, mapOf(
                    MoneyRuleFields.CATEGORY to FieldValue.Null, MoneyRuleFields.MERCHANT to FieldValue.Null, MoneyBusinessFields.MERCHANT to FieldValue.Null,
                    MoneySharedFields.MERCHANT to FieldValue.Null, MoneyNeedFields.MERCHANT to FieldValue.Null,
                ))
            }
        }
        cache = null
        return MoneySaved(MoneyRules.DELETED_ALL)
    }

    private fun clear(id: String) {
        replica.commitLocal(EntityTypes.MONEY_TXN, id, mapOf(
            os.meka.core.sync.DELETED_FIELD to true.fv(), MoneyTxnFields.TEXT to FieldValue.Null, MoneyTxnFields.PENCE to FieldValue.Null,
            MoneyTxnFields.DAY to FieldValue.Null, MoneySharedFields.TXN to FieldValue.Null,
        ))
    }
}
