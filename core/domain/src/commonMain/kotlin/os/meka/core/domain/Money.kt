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
    /** "csv" (a statement file); "bank" once Enable Banking lands. */
    const val SOURCE = "source"
    const val IMPORTED_AT = "importedAtMs"
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
data class MoneyCsvRead(val rows: List<MoneyRow>, val skipped: Int = 0, val flipped: Boolean = false, val problem: String? = null)

/** One stored statement line. */
data class MoneyTxn(val id: String, val account: String, val day: Long, val pence: Long, val text: String)

data class MoneyAccount(val id: String, val name: String, val source: String, val importedAtMs: Long?)

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
        }
        if (rows.isEmpty()) return MoneyCsvRead(emptyList(), skipped, problem = NO_ROWS)
        // A card statement: purchases positive, its payments negative and called a payment.
        val positive = rows.count { it.pence > 0 }
        val payments = rows.filter { it.pence < 0 }
        val flip = amountCol != null && positive * 10 >= rows.size * 7 && payments.isNotEmpty() &&
            payments.all { PAYMENT.containsMatchIn(it.text) || REFUND.containsMatchIn(it.text) } && payments.any { PAYMENT.containsMatchIn(it.text) }
        val kept = if (flip) rows.map { it.copy(pence = -it.pence) } else rows
        return MoneyCsvRead(kept, skipped, flipped = flip)
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
        MoneyCategory.TRANSFERS to w("transfer", "to savings", "from savings", "savings pot", "pot transfer", "own account", "american express", "amex", "barclaycard", "credit card payment", "card repayment", "payment received", "payment thank you", "thank you for your payment", "monzo", "revolut", "starling", "top up", "topup"),
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
)

data class MoneyAccountRow(val id: String, val name: String, val line: String)

/**
 * The month on the Money screen: [label] ("October so far"), [spentLine] ("Spent £1,234.50 · in £2,500"), [vsLine]
 * against the 6 months before it (null with no months to compare), each spend category biggest first, and its payments
 * newest first ([more] past [MoneyRules.SHOWN_TXNS]).
 */
data class MoneyMonth(
    val offset: Int, val label: String, val spentLine: String, val vsLine: String?, val vsLit: Boolean,
    val categories: List<MoneyCategoryRow>, val txns: List<MoneyTxnRow>, val more: Int,
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
) {
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
    const val CONNECT_LATER = "Connecting your banks directly (read-only, through Open Banking) comes once Enable Banking is set up; a statement file works for any bank meanwhile."
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

    /** A month as an index (year × 12 + month − 1) and back. */
    fun monthOf(day: Long): Int = CivilDate.fromEpochDay(day).let { it.year * 12 + it.month - 1 }

    fun monthLabel(month: Int): String = "${CivilDate.MONTH_LONG[month % 12]} ${month / 12}"

    val CHOICES: List<MoneyCategoryChoice> = MoneyCategory.entries.map { MoneyCategoryChoice(it.key, it.label) }

    /** The category a line is in: his correction for its merchant, else what its words point to. */
    fun categoryOf(t: MoneyTxn, rules: Map<String, MoneyCategory>): MoneyCategory =
        rules[MoneyCategoryRules.merchantKey(t.text)] ?: MoneyCategoryRules.categoryOf(t.text, t.pence)

    /** Ask's Money tile: no amounts (it's outside the fingerprint) — "2 accounts · updated Fri 16 Oct", or null. */
    fun tile(accounts: List<MoneyAccount>, cal: LocalCalendar): LifeTileLine? {
        if (accounts.isEmpty()) return null
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

    /** "1 Mar 2026". */
    fun dayMonth(day: Long): String = CivilDate.fromEpochDay(day).let { "${it.day} ${Recurrence.MONTH_SHORT[it.month - 1]} ${it.year}" }

    /**
     * The Money screen for the month [offset] months before [today]'s (0 = this month, "October so far"), from every
     * account's lines and his corrections.
     */
    fun view(
        accounts: List<MoneyAccount>, txns: List<MoneyTxn>, rules: Map<String, MoneyCategory>, today: Long, cal: LocalCalendar, offset: Int,
        hiddenTips: Set<String> = emptySet(), fineAlerts: Set<String> = emptySet(), whatIf: MoneyWhatIf = MoneyWhatIf(),
    ): MoneyView {
        val names = accounts.associate { it.id to it.name }
        // Each line's merchant and category once per view (the rules are regexes; a statement can be thousands of lines).
        val keys = HashMap<String, String>()
        val cats = HashMap<String, MoneyCategory>()
        fun keyOf(t: MoneyTxn): String = keys.getOrPut(t.id) { MoneyCategoryRules.merchantKey(t.text) }
        fun cat(t: MoneyTxn): MoneyCategory = cats.getOrPut(t.id) { rules[keyOf(t)] ?: MoneyCategoryRules.categoryOf(t.text, t.pence) }
        val mine = txns.filter { it.account in names }
        val accountRows = accounts.sortedBy { it.name.lowercase() }.map { a ->
            val ts = mine.filter { it.account == a.id }
            val span = if (ts.isEmpty()) "no transactions" else
                "${ts.size} transaction${if (ts.size == 1) "" else "s"} · ${dayMonth(ts.minOf { it.day })} – ${dayMonth(ts.maxOf { it.day })}"
            MoneyAccountRow(a.id, a.name, span + (a.importedAtMs?.let { " · imported ${CivilDate.shortLabel(cal.epochDayOf(it))}" } ?: ""))
        }
        if (mine.isEmpty()) return MoneyView(false, EMPTY, EMPTY, null, accountRows, earlier = false, later = false)
        val thisMonth = monthOf(today)
        val firstMonth = monthOf(mine.minOf { it.day })
        val shown = (thisMonth - offset.coerceAtLeast(0)).coerceAtLeast(firstMonth)
        val byMonth = mine.groupBy { monthOf(it.day) }
        fun spendOf(ts: List<MoneyTxn>): Map<MoneyCategory, Long> = ts.groupBy { cat(it) }
            .filterKeys { it.spend }.mapValues { (_, l) -> -l.sumOf { it.pence } }
        val cur = byMonth[shown].orEmpty()
        val spend = spendOf(cur)
        val spent = spend.values.filter { it > 0 }.sum()
        val income = cur.filter { cat(it) == MoneyCategory.INCOME }.sumOf { it.pence }
        // The 6 whole months before the one shown that have statements.
        val prior = (1..6).map { shown - it }.filter { it >= firstMonth }
        val avg: Map<MoneyCategory, Long> = if (prior.isEmpty()) emptyMap() else {
            val sums = prior.map { spendOf(byMonth[it].orEmpty()) }
            MoneyCategory.entries.filter { it.spend }.associateWith { c -> sums.sumOf { it[c] ?: 0L } / prior.size }
        }
        val avgTotal = if (prior.isEmpty()) null else prior.sumOf { m -> spendOf(byMonth[m].orEmpty()).values.filter { it > 0 }.sum() } / prior.size
        val partial = shown == thisMonth
        val label = if (partial) "${CivilDate.MONTH_LONG[shown % 12]} so far" else monthLabel(shown)
        val catRows = spend.filter { it.value > 0 }.entries.sortedByDescending { it.value }.map { (c, p) ->
            val n = cur.count { cat(it) == c && it.pence < 0 }
            val a = avg[c]
            val over = !partial && a != null && p - a >= OVER_MIN_PENCE && p > a * (1 + OVER_SHARE)
            val vs = when {
                a == null || partial -> null
                a == 0L -> "none in the 6 months before"
                over -> "${fmt(p - a)} over your 6-month average"
                a - p >= OVER_MIN_PENCE -> "${fmt(a - p)} under your 6-month average"
                else -> "about your 6-month average"
            }
            val line = listOfNotNull("$n payment${if (n == 1) "" else "s"}", vs).joinToString(" · ")
            MoneyCategoryRow(c.key, c.label, fmt(p), if (spent > 0) (p.toFloat() / spent).coerceIn(0f, 1f) else 0f, line, over,
                "${c.label}, ${fmt(p)}, $line")
        }
        val vsLine = when {
            avgTotal == null -> null
            partial -> "Your 6-month average is ${fmt(avgTotal)} a month"
            spent - avgTotal >= OVER_MIN_PENCE -> "${fmt(spent - avgTotal)} more than your 6-month average"
            avgTotal - spent >= OVER_MIN_PENCE -> "${fmt(avgTotal - spent)} less than your 6-month average"
            else -> "About your 6-month average"
        }
        val sorted = cur.sortedWith(compareByDescending<MoneyTxn> { it.day }.thenBy { it.id })
        val rows = sorted.take(SHOWN_TXNS).map { t ->
            val c = cat(t)
            val key = keyOf(t)
            val merchant = MoneyCategoryRules.merchantLabel(key)
            MoneyTxnRow(
                id = t.id, day = CivilDate.shortLabel(t.day), merchant = merchant, amount = signed(t.pence), moneyIn = t.pence > 0,
                category = c.key, categoryLabel = c.label, account = names[t.account].orEmpty(), taught = key in rules,
                spoken = "${CivilDate.shortLabel(t.day)}, $merchant, ${if (t.pence < 0) "out" else "in"} ${fmt(kotlin.math.abs(t.pence))}, ${c.label}",
            )
        }
        val month = MoneyMonth(
            offset = thisMonth - shown, label = label,
            spentLine = "Spent ${fmt(spent)}" + (if (income > 0) " · in ${fmt(income)}" else ""),
            vsLine = vsLine, vsLit = !partial && avgTotal != null && spent - avgTotal >= OVER_MIN_PENCE,
            categories = catRows, txns = rows, more = (sorted.size - rows.size).coerceAtLeast(0),
        )
        val summary = "${accounts.size} account${if (accounts.size == 1) "" else "s"} · ${mine.size} transactions"
        // Slice 2: tips and regular payments go with this month (they're about now, not the month shown).
        val lines = if (partial) mine.map { MoneyLine(it.day, it.pence, keyOf(it), cat(it)) } else emptyList()
        val regular = if (partial) MoneyTips.regular(lines, today) else emptyList()
        return MoneyView(
            false, summary, null, month, accountRows, earlier = shown > firstMonth, later = shown < thisMonth,
            tips = if (partial) MoneyTips.tips(lines, today, hiddenTips, regular) else emptyList(),
            regular = MoneyTips.regularView(regular),
            hiddenTips = MoneyTips.hiddenLine(hiddenTips),
            alerts = if (partial) MoneyWatch.alerts(
                mine.map { MoneyWatchLine(it.id, it.account, it.day, it.pence, keyOf(it), cat(it), it.text) }, today, names, fineAlerts,
            ) else emptyList(),
            forecast = if (partial) MoneyForecast.view(lines, today, whatIf) else null,
        )
    }

    fun movedLine(merchant: String, category: MoneyCategory, count: Int): String =
        "$merchant is ${category.label} from now on" + (if (count > 1) " · $count payments" else "")

    fun removedLine(name: String, count: Int): String = "Removed $name · $count transaction${if (count == 1) "" else "s"}"
}

/** Money on a replica (both apps). Keeps the lines in memory between changes, so the screen doesn't re-read them all. */
class Money(private val replica: Replica, private val nowMs: () -> Long) {
    private var cache: List<MoneyTxn>? = null

    private companion object { const val TIPS_UNDO = "tips|"; const val ALERT_UNDO = "alert|" }

    /** Told of every replica change (the facade's listener, before its refresh): a money line changed, re-read them. */
    fun changed(refs: Set<EntityRef>) {
        if (refs.any { it.entityType == EntityTypes.MONEY_TXN || it.entityType == EntityTypes.MONEY_ACCOUNT }) cache = null
    }

    fun accounts(): List<MoneyAccount> = replica.entities(EntityTypes.MONEY_ACCOUNT).mapNotNull { e ->
        val name = e[MoneyAccountFields.NAME].textOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        MoneyAccount(e.ref.entityId, name, e[MoneyAccountFields.SOURCE].textOrNull ?: "csv", e[MoneyAccountFields.IMPORTED_AT].longOrNull)
    }

    fun txns(): List<MoneyTxn> = cache ?: replica.entities(EntityTypes.MONEY_TXN).mapNotNull { e ->
        val account = e[MoneyTxnFields.ACCOUNT].textOrNull ?: return@mapNotNull null
        val day = e[MoneyTxnFields.DAY].longOrNull ?: return@mapNotNull null
        val pence = e[MoneyTxnFields.PENCE].longOrNull ?: return@mapNotNull null
        MoneyTxn(e.ref.entityId, account, day, pence, e[MoneyTxnFields.TEXT].textOrNull.orEmpty())
    }.also { cache = it }

    fun rules(): Map<String, MoneyCategory> = replica.entities(EntityTypes.MONEY_RULE).mapNotNull { e ->
        val m = e[MoneyRuleFields.MERCHANT].textOrNull ?: return@mapNotNull null
        val c = MoneyCategory.of(e[MoneyRuleFields.CATEGORY].textOrNull) ?: return@mapNotNull null
        m to c
    }.toMap()

    /** Slice 2: the kinds of tip he hid ([MoneyTipKind.key]). */
    fun hiddenTips(): Set<String> = replica.entities(EntityTypes.MONEY_TIPS)
        .filter { it[MoneyTipsFields.HIDDEN].boolOrNull == true }.map { it.ref.entityId }.toSet()

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
        val id = MoneyRules.accountId(name) ?: return MoneySaved(MoneyRules.NO_ACCOUNT, refused = true)
        val existing = accounts()
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
        cache = null
        return MoneySaved(MoneyRules.importedLine(name, added, already, read))
    }

    /** Puts every payment to [txnId]'s merchant in [categoryKey] from now on; Undo hands back the old choice. */
    fun setCategory(txnId: String, categoryKey: String): MoneySaved {
        val t = txns().firstOrNull { it.id == txnId } ?: return MoneySaved(MoneyRules.GONE, refused = true)
        val c = MoneyCategory.of(categoryKey) ?: return MoneySaved(MoneyRules.GONE, refused = true)
        val key = MoneyCategoryRules.merchantKey(t.text)
        val rid = MoneyRules.ruleId(key)
        val before = replica.entity(EntityTypes.MONEY_RULE, rid)?.get(MoneyRuleFields.CATEGORY)?.textOrNull
        replica.commitLocal(EntityTypes.MONEY_RULE, rid, mapOf(MoneyRuleFields.MERCHANT to key.fv(), MoneyRuleFields.CATEGORY to c.key.fv()))
        val count = txns().count { MoneyCategoryRules.merchantKey(it.text) == key }
        return MoneySaved(MoneyRules.movedLine(MoneyCategoryRules.merchantLabel(key), c, count), undo = "$rid|${before.orEmpty()}")
    }

    /** Undo for [setCategory]: the merchant goes back to what it was (or to its words' own category). */
    fun undoCategory(token: String): Boolean {
        if (token.startsWith(ALERT_UNDO)) {
            val id = token.removePrefix(ALERT_UNDO)
            if (replica.entity(EntityTypes.MONEY_ALERT, id) == null) return false
            replica.commitLocal(EntityTypes.MONEY_ALERT, id, mapOf(MoneyAlertFields.FINE to false.fv()))
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
                os.meka.core.sync.DELETED_FIELD to true.fv(), MoneyAccountFields.IMPORTED_AT to FieldValue.Null,
            ))
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
                    os.meka.core.sync.DELETED_FIELD to true.fv(), MoneyAccountFields.NAME to FieldValue.Null, MoneyAccountFields.IMPORTED_AT to FieldValue.Null,
                ))
            }
            hiddenTips().forEach { replica.commitLocal(EntityTypes.MONEY_TIPS, it, mapOf(MoneyTipsFields.HIDDEN to FieldValue.Null)) }
            replica.entities(EntityTypes.MONEY_ALERT).forEach { replica.commitLocal(EntityTypes.MONEY_ALERT, it.ref.entityId, mapOf(MoneyAlertFields.FINE to FieldValue.Null)) }
            rules.forEach {
                replica.commitLocal(EntityTypes.MONEY_RULE, it.ref.entityId, mapOf(
                    MoneyRuleFields.CATEGORY to FieldValue.Null, MoneyRuleFields.MERCHANT to FieldValue.Null,
                ))
            }
        }
        cache = null
        return MoneySaved(MoneyRules.DELETED_ALL)
    }

    private fun clear(id: String) {
        replica.commitLocal(EntityTypes.MONEY_TXN, id, mapOf(
            os.meka.core.sync.DELETED_FIELD to true.fv(), MoneyTxnFields.TEXT to FieldValue.Null, MoneyTxnFields.PENCE to FieldValue.Null,
            MoneyTxnFields.DAY to FieldValue.Null,
        ))
    }
}
