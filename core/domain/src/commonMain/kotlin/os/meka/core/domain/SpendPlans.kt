package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Spending plans (V1, approved 2026-11-11, `docs/design/money/SpendPlan.dc.html`), slice 1: under Money's Plan tab,
 * plans for things he wants to buy or send ("Christmas gifts", "Holiday", money sent home to family abroad), each a list
 * of people or things with an amount and the month he'll buy it, added up into a total with a bought/sent bar.
 *
 * **Can I afford it?** comes from what Money already knows: his average month in and out ([MoneyForecastView], from the
 * bank feed and statements), what's left over, minus what's still to buy on this plan, this month or spread over two or
 * three months: "Fits · £x left" or "£x short · spread it or trim it". Three month tiles show each month's still-to-buy
 * total against what's left. A plan in another currency has a "Same for everyone" amount (₦50,000 each) with a stepper
 * and the pound figure at today's rate (slice 3: looked up once a day on the server from a free public rate service,
 * nothing about him sent; written onto the plan as [SpendPlanFields.DAY_RATE]), or at the rate he types, which wins.
 *
 * **A calculator only** (hard rule): Navilife never sends or moves money, and nothing here is an instruction to pay.
 * Plain, non-AI rules; nothing is sent anywhere. Synced entities `spend_plan` and `spend_plan_item` (ADR-008 addendum
 * 2026-11-12).
 */
object SpendPlanFields {
    /** "Christmas gifts" (at most [SpendPlanRules.MAX_NAME]). */
    const val NAME = "name"
    /** The day it's needed by (epoch day); absent or -1 for none. */
    const val BY_DAY = "byDay"
    /** "GBP", or the other currency the plan is in ("NGN"). */
    const val CURRENCY = "currency"
    /** The same-for-everyone amount in the plan's currency, in hundredths (₦50,000 = 5,000,000); 0 for none. */
    const val EACH = "eachMinor"
    /** His rate: units of the plan's currency to £1, × 1,000 (₦2,000 to £1 = 2,000,000); 0 = not typed yet. */
    const val RATE = "rateMilli"
    /** Count in my forecast: what's still to buy comes off Money's forecast in the month he'll buy it (absent = off). */
    const val COUNTED = "counted"
    /** Slice 3, written by the server: today's rate for [DAY_RATE_CODE], units to £1 × 1,000; absent = not looked up. */
    const val DAY_RATE = "dayRateMilli"
    /** The epoch day today's rate is for. */
    const val DAY_RATE_DAY = "dayRateDay"
    /** The currency today's rate was looked up for (used only while it's still the plan's currency). */
    const val DAY_RATE_CODE = "dayRateCode"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
}

object SpendItemFields {
    const val PLAN = "planId"
    /** "Football boots", "Mum" (at most [SpendPlanRules.MAX_NAME]). */
    const val NAME = "name"
    /** "For Sam", "4 people" (at most [SpendPlanRules.MAX_NOTE]); "" for none. */
    const val NOTE = "note"
    /** Its own amount in the plan's currency, in hundredths; -1 = the plan's same-for-everyone amount. */
    const val AMOUNT = "amountMinor"
    /** The month he'll buy it: year × 12 + month − 1. */
    const val MONTH = "month"
    /** Bought, or sent. */
    const val DONE = "done"
    /** Slice 4: the shop's page for it, a plain https link ([SpendPlanRules.link]); absent or "" for none. */
    const val LINK = "link"
    /**
     * Slice 5: its photo, a sealed copy like the Vault's ([VaultFileRules.encode]: `1|id|type|bytes|keyId|atMs|by`,
     * a JPEG or PNG only); absent or Null for none. The picture itself never syncs as an op.
     */
    const val PHOTO = "photo"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
}

/** A currency a plan can be in: [code] "NGN", [symbol] "₦", the stepper's [step] in whole units. */
data class SpendCurrency(val code: String, val symbol: String, val name: String, val step: Long)

data class SpendPlanItem(
    val id: String, val name: String, val byDay: Long, val currency: String, val eachMinor: Long, val rateMilli: Long,
    val addedAtMs: Long, val counted: Boolean = false,
    /** Today's rate as the server looked it up (0 = none yet) and the day it's for (-1 = none). */
    val dayRateMilli: Long = 0L, val dayRateDay: Long = -1L,
) {
    val foreign: Boolean get() = currency != SpendPlanRules.GBP
    /** His typed rate when he has one, else today's; 0 when neither. */
    val rateInUse: Long get() = if (rateMilli > 0) rateMilli else dayRateMilli
    /** Using the looked-up rate (he hasn't typed his own). */
    val onTodaysRate: Boolean get() = rateMilli <= 0 && dayRateMilli > 0
}

data class SpendThing(
    val id: String, val planId: String, val name: String, val note: String, val amountMinor: Long, val month: Int,
    val done: Boolean, val addedAtMs: Long,
    /** Slice 4: the shop's page, a checked https link; "" for none. */
    val link: String = "",
    /** Slice 5: its photo's sealed copy (a JPEG or PNG); null for none. */
    val photo: VaultFileRef? = null,
) {
    val sameForEveryone: Boolean get() = amountMinor < 0
}

/** A plan's chip at the top: [on] for the one shown. */
data class SpendPlanChip(val id: String, val name: String)

/** One line of Can I afford it?: "Comes in each month" · "£3,200"; [strong] for Left over and the plan. */
data class SpendAffordRow(val label: String, val value: String, val strong: Boolean, val ruled: Boolean)

/** Can I afford it? over [months] (1, 2 or 3): its rows and the verdict, green when [fits]. */
data class SpendAfford(val months: Int, val label: String, val rows: List<SpendAffordRow>, val verdict: String, val fits: Boolean)

enum class SpendTone { FITS, SHORT, NONE }

/** A month tile: "Dec" · "£145" · "fits" / "£20 short" / "nothing". */
data class SpendMonthTile(val label: String, val total: String, val verdict: String, val tone: SpendTone)

/** A month to buy a thing in, for the add and edit sheets: [key] year × 12 + month − 1, [label] "Dec". */
data class SpendMonthChoice(val key: Int, val label: String)

/**
 * One person or thing: "Football boots" · "For Sam · buy in Dec" · [gbp] "£45" · [local] "₦50,000" for a plan in another
 * currency ("" otherwise); [done] bought or sent. [amountText] is what the edit sheet starts with ("" for the
 * same-for-everyone amount).
 */
data class SpendRow(
    val id: String, val name: String, val note: String, val gbp: String, val local: String, val done: Boolean,
    val month: Int, val amountText: String, val noteText: String,
    /** Slice 4: the link to open ("" for none) and the shop it goes to, "argos.co.uk" ("" for none). */
    val link: String = "", val linkLabel: String = "",
    /** Slice 5: it has a photo (the tile is lit; a tap shows it), and the tile's spoken label. */
    val hasPhoto: Boolean = false, val photoLabel: String = SpendPlanRules.PHOTO_TAKE,
)

/**
 * A plan in another currency: [each] "₦50,000", [rateLine] "₦50,000 ≈ £25 each · today's rate, ₦2,000 to £1" (or "your
 * rate"). Slice 3: [todayLine] for the rate sheet ("Today's rate · ₦2,050 to £1, 12 Nov", or that it's looked up once
 * a day), [canUseToday] when he has typed his own and a looked-up one is there to go back to.
 */
data class SpendForeign(
    val code: String, val symbol: String, val each: String, val rateLine: String, val rateSet: Boolean, val rateText: String,
    val todayLine: String = SpendPlanRules.TODAY_NONE, val canUseToday: Boolean = false, val onToday: Boolean = false,
)

/**
 * One plan in full: the THIS PLAN card ([total], [totalSub] "₦600,000 · 12 people" or "6 things", [by] "needed by 20
 * Dec", the [bar] 0..1 and [spentLine] "£75 bought · £525 to go"), Can I afford it? for 1, 2 and 3 months ([afford],
 * empty with [affordNote] saying why when Money hasn't an average month yet), the three [months] tiles, [foreign], and
 * the people or things ([listTitle] "People" or "Things").
 */
data class SpendPlanDetail(
    val id: String, val name: String, val total: String, val totalSub: String, val by: String?, val bar: Float,
    val spentLine: String, val afford: List<SpendAfford>, val affordNote: String?, val months: List<SpendMonthTile>,
    val foreign: SpendForeign?, val listTitle: String, val rows: List<SpendRow>, val canAdd: Boolean, val byText: String,
    /** Count in my forecast: [counted] the switch, [countLine] what it does now. */
    val counted: Boolean = false, val countLine: String = SpendPlanRules.COUNT_OFF,
)

/**
 * Spending plans: the [chips], every plan in full ([plans], same order), the Plan tab's row line ([rowLine]), the month
 * choices for a thing ([monthChoices]) and whether another plan may be added ([canAdd]).
 */
data class SpendPlansView(
    val chips: List<SpendPlanChip>, val plans: List<SpendPlanDetail>, val rowLine: String, val rowLit: Boolean,
    val monthChoices: List<SpendMonthChoice>, val canAdd: Boolean,
) {
    fun plan(id: String?): SpendPlanDetail? = plans.firstOrNull { it.id == id } ?: plans.firstOrNull()

    companion object { val EMPTY = SpendPlansView(emptyList(), emptyList(), SpendPlanRules.ROW_EMPTY, false, emptyList(), true) }
}

/** What a change said: [line] for the toast, [id] what was added (null when refused or not an add). */
data class SpendSaid(val line: String, val refused: Boolean, val id: String? = null)

/** His average month from Money: what comes in and what goes out (pence, rounded as Money shows them). */
data class SpendMonthMoney(val inPence: Long, val outPence: Long)

object SpendPlanRules {
    const val TITLE = "Spending plans"
    const val ROW_EMPTY = "Plan for gifts, a holiday or money sent home"
    const val GBP = "GBP"
    const val MAX_NAME = 40
    const val MAX_NOTE = 40
    const val MAX_PLANS = 12
    const val MAX_ITEMS = 60
    /** Five people or things a page. */
    const val PAGE = 5
    /** The month tiles: this month and the next two. */
    const val TILES = 3
    /** The months a thing can be bought in: this month and the next eleven. */
    const val CHOICES = 12
    const val MAX_MINOR = 100_000_000_000L

    const val NEW_PLAN = "+ New plan"
    const val NEW_SHEET = "New plan"
    const val NEW_HINT = "Christmas gifts 20 Dec"
    const val NEW_DESCRIPTION = "Name the plan, and the day it's needed by if there is one, for example Christmas gifts 20 Dec"
    const val ADD = "+ Add"
    const val ADD_SHEET = "Add to this plan"
    const val EDIT_SHEET = "Change"
    const val AFFORD_HEAD = "Can I afford it?"
    const val SAME_HEAD = "Same for everyone"
    const val CHANGE_RATE = "Change rate"
    const val RATE_SHEET = "Today's rate"
    const val FOOT = "A calculator only: Navilife never sends or moves money."
    const val DONE = "Done"
    const val REMOVE_PLAN = "Remove this plan"
    const val AFFORD_NOT_READY = "Can I afford it? needs Money's average month · import two whole months of statements, or connect your banks."
    const val NO_NAME = "Give it a name, like \"Christmas gifts 20 Dec\"."
    const val NO_AMOUNT = "Type how much, like 45 or 12.50."
    const val BAD_AMOUNT = "That isn't an amount Navilife can use. Type it like 45 or 12.50."
    const val BAD_RATE = "Type how many to £1, like 2,000."
    const val TOO_MANY_PLANS = "That's as many plans as Navilife keeps. Remove one first."
    const val TOO_MANY_ITEMS = "That's as many as one plan holds. Remove one first."
    const val GONE = "That plan isn't there any more."
    const val COUNT_LABEL = "Count in my forecast"
    const val COUNT_OFF = "Off · Money's forecast leaves this plan out"
    const val COUNT_ON = "On · what's still to buy comes off Money's forecast in the month you'll buy it"
    const val COUNT_UNPRICED = "On · once there's a rate it comes off Money's forecast"
    /** Slice 3: the rate sheet. */
    const val RATE_HELP = "Today's rate is looked up once a day. Type your own, from your bank or a transfer service, to use it instead."
    const val TODAY_NONE = "Today's rate · looked up within the hour, or type your own"
    const val USE_TODAY = "Use today's rate"
    const val RATE_SOURCE = "Rates by Exchange Rate API"
    const val NO_RATE = "Type a rate to see it in pounds"
    /** Slice 4: a link on a thing. */
    const val MAX_LINK = 500
    const val LINK_HINT = "Link to the shop (optional)"
    const val BAD_LINK = "That isn't a web link Navilife can open. Paste the shop's link, starting https://"
    const val OPEN_LINK = "Open link"

    /** Slice 5: a photo on a thing, sealed with the Vault key like the Vault's copies and kept on his devices. */
    const val PHOTO_TAKE = "Take a photo"
    const val PHOTO_PICK = "Choose a photo"
    const val PHOTO_SEE = "Photo saved, tap to see"
    const val PHOTO_REPLACE = "Change photo"
    const val PHOTO_REMOVE = "Remove photo"
    const val PHOTO_REMOVED = "Photo removed from every device"
    const val PHOTO_NOT_PICTURE = "A thing's photo is a picture (JPEG or PNG)."
    const val PHOTO_NONE = "No photo on this one"
    const val NO_CAMERA = "No camera app to take the photo"
    const val PHOTO_NO_KEY = "Photos are locked with your Vault key, and this device doesn't hold it yet: set it up in Ask → Settings → Vault."
    /** A Vault line about a copy, said the Spending plans way (the Vault's own key line points at a section not here). */
    fun photoProblem(line: String): String = when (line) {
        VaultFileRules.NO_KEY -> PHOTO_NO_KEY
        VaultFileRules.TOO_BIG -> "That photo is too big (1.4 MB at most). Take it again or pick a smaller one."
        else -> line
    }
    const val PHOTO_NOTE = "Photos are locked with your Vault key on this device before they leave it; only your devices can open them."

    /** A synced photo field as a sealed copy; null for none, a PDF or anything unreadable. */
    fun photo(text: String?): VaultFileRef? = VaultFileRules.decode(text)?.takeIf { it.type == VaultFileRules.JPEG || it.type == VaultFileRules.PNG }

    /** After saving: "Photo saved · only your devices can open it" (or that it goes up when this device is online). */
    fun photoSavedLine(r: VaultFileRef, uploaded: Boolean, replaced: Boolean): String =
        (if (replaced) "Photo changed" else "Photo saved") + " · " + (if (uploaded) "only your devices can open it" else "it goes up when this ${r.by} is next online")
    /** A looked-up rate older than this many days is called by its day ("rate from 3 Nov"). */
    const val STALE_DAYS = 3
    /** The highest rate believed (a million to £1). */
    const val MAX_RATE_MILLI = 1_000_000_000L

    val CURRENCIES: List<SpendCurrency> = listOf(
        SpendCurrency(GBP, "£", "Pounds", 5),
        SpendCurrency("NGN", "₦", "Naira", 5_000),
        SpendCurrency("GHS", "GH₵", "Cedis", 50),
        SpendCurrency("KES", "KSh", "Shillings", 500),
        SpendCurrency("ZAR", "R", "Rand", 50),
        SpendCurrency("INR", "₹", "Rupees", 500),
        SpendCurrency("EUR", "€", "Euros", 5),
        SpendCurrency("USD", "$", "Dollars", 5),
    )

    fun currency(code: String): SpendCurrency = CURRENCIES.firstOrNull { it.code == code } ?: CURRENCIES.first()

    private fun grouped(n: Long): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

    /** Pence to whole pounds, rounded: "£1,240". */
    fun pounds(pence: Long): String = (if (pence < 0) "−£" else "£") + grouped((kotlin.math.abs(pence) + 50) / 100)

    /** Hundredths in [code] to "₦50,000" (pence shown when not whole). */
    fun local(minor: Long, code: String): String {
        val c = currency(code)
        val whole = grouped(minor / 100)
        val rest = minor % 100
        return c.symbol + whole + if (rest == 0L) "" else "." + rest.toString().padStart(2, '0')
    }

    /** What the edit field starts with: 45, 12.50, 50000. */
    fun amountText(minor: Long): String = if (minor % 100 == 0L) (minor / 100).toString() else "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"

    /** "45", "£45", "12.50", "50,000", "₦50,000", "50k" → hundredths; null when it isn't an amount. */
    fun amount(text: String): Long? {
        var t = text.trim().lowercase().replace(" ", "")
        CURRENCIES.forEach { t = t.removePrefix(it.symbol.lowercase()) }
        t = t.removeSuffix("each")
        val k = t.endsWith("k")
        if (k) t = t.removeSuffix("k")
        val m = Regex("""^(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?$""").matchEntire(t) ?: return null
        val whole = m.groupValues[1].replace(",", "").toLongOrNull() ?: return null
        val frac = m.groupValues[2].padEnd(2, '0').toLong()
        val minor = (whole * 100 + frac) * (if (k) 1_000 else 1)
        return minor.takeIf { it in 1..MAX_MINOR }
    }

    /** "2,000", "2000", "1.17" → units to £1 × 1,000; null when it isn't a rate. */
    fun rate(text: String): Long? {
        val t = text.trim().replace(",", "").replace(" ", "")
        val m = Regex("""^(\d{1,9})(?:\.(\d{1,3}))?$""").matchEntire(t) ?: return null
        val milli = m.groupValues[1].toLong() * 1_000 + m.groupValues[2].padEnd(3, '0').toLong()
        return milli.takeIf { it > 0 }
    }

    fun rateText(milli: Long): String {
        if (milli <= 0) return ""
        val rest = milli % 1_000
        return grouped(milli / 1_000) + if (rest == 0L) "" else "." + rest.toString().padStart(3, '0').trimEnd('0')
    }

    /**
     * Slice 4: a pasted or typed link as Navilife keeps it: a plain https address with a real host ("argos.co.uk/boots"
     * gains https://); "" for none; null when it isn't one it will open (http://, another scheme, a user name or port in
     * the address, spaces, longer than [MAX_LINK]).
     */
    fun link(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return ""
        val u = when {
            t.startsWith("https://", ignoreCase = true) -> "https://" + t.substring(8)
            t.contains("://") || t.startsWith("http:", ignoreCase = true) -> return null
            else -> "https://$t"
        }
        if (u.length > MAX_LINK || u.any { it.isWhitespace() || it < ' ' }) return null
        return u.takeIf { linkHost(it) != null }
    }

    /** The shop a link goes to: its host, lower case, without "www." ("argos.co.uk"); null when it has none we trust. */
    fun linkHost(url: String): String? {
        if (!url.startsWith("https://")) return null
        val authority = url.substring(8).substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || '@' in authority || ':' in authority) return null
        val host = authority.lowercase().trimEnd('.')
        if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' } || '.' !in host) return null
        if (host.split('.').any { it.isEmpty() }) return null
        return host.removePrefix("www.")
    }

    /** The plan's amount for [t] in hundredths of its currency. */
    fun minorOf(t: SpendThing, p: SpendPlanItem): Long = if (t.sameForEveryone) p.eachMinor else t.amountMinor

    /** In pence: the amount itself for a pound plan, else at his rate or today's; null for another currency without one. */
    fun penceOf(minor: Long, p: SpendPlanItem): Long? = when {
        !p.foreign -> minor
        p.rateInUse <= 0 -> null
        else -> (minor * 1_000 + p.rateInUse / 2) / p.rateInUse
    }

    /** "today's rate" / "rate from 3 Nov" (looked up), or "your rate" (typed). */
    private fun rateWho(p: SpendPlanItem, today: Long): String = when {
        !p.onTodaysRate -> "your rate"
        p.dayRateDay < 0 || today - p.dayRateDay <= STALE_DAYS -> "today's rate"
        else -> "rate from ${dayLabel(p.dayRateDay)}"
    }

    fun monthKey(day: Long): Int = CivilDate.fromEpochDay(day).let { it.year * 12 + it.month - 1 }

    fun monthLabel(key: Int): String = Recurrence.MONTH_SHORT[key % 12]

    private fun dayLabel(day: Long): String = CivilDate.fromEpochDay(day).let { "${it.day} ${Recurrence.MONTH_SHORT[it.month - 1]}" }

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    fun plans(entities: Map<String, Map<String, FieldValue>>): List<SpendPlanItem> = entities.mapNotNull { (id, s) ->
        if (s.f(SpendPlanFields.DELETED).boolOrNull == true) return@mapNotNull null
        val name = s.f(SpendPlanFields.NAME).textOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        SpendPlanItem(
            id, name.take(MAX_NAME), s.f(SpendPlanFields.BY_DAY).longOrNull ?: -1L,
            currency(s.f(SpendPlanFields.CURRENCY).textOrNull ?: GBP).code,
            (s.f(SpendPlanFields.EACH).longOrNull ?: 0L).coerceIn(0L, MAX_MINOR),
            (s.f(SpendPlanFields.RATE).longOrNull ?: 0L).coerceAtLeast(0L),
            s.f(SpendPlanFields.ADDED_AT).longOrNull ?: 0L,
            s.f(SpendPlanFields.COUNTED).boolOrNull ?: false,
            dayRate(s), s.f(SpendPlanFields.DAY_RATE_DAY).longOrNull ?: -1L,
        )
    }.sortedWith(compareBy<SpendPlanItem> { it.addedAtMs }.thenBy { it.id })

    /** Today's rate, only while it was looked up for the plan's own currency and is believable. */
    private fun dayRate(s: Map<String, FieldValue>): Long {
        val code = currency(s.f(SpendPlanFields.CURRENCY).textOrNull ?: GBP).code
        if (code == GBP || s.f(SpendPlanFields.DAY_RATE_CODE).textOrNull != code) return 0L
        return (s.f(SpendPlanFields.DAY_RATE).longOrNull ?: 0L).takeIf { it in 1..MAX_RATE_MILLI } ?: 0L
    }

    fun things(entities: Map<String, Map<String, FieldValue>>): List<SpendThing> = entities.mapNotNull { (id, s) ->
        if (s.f(SpendItemFields.DELETED).boolOrNull == true) return@mapNotNull null
        val plan = s.f(SpendItemFields.PLAN).textOrNull?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val name = s.f(SpendItemFields.NAME).textOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        SpendThing(
            id, plan, name.take(MAX_NAME), (s.f(SpendItemFields.NOTE).textOrNull ?: "").trim().take(MAX_NOTE),
            (s.f(SpendItemFields.AMOUNT).longOrNull ?: -1L).coerceAtMost(MAX_MINOR),
            (s.f(SpendItemFields.MONTH).longOrNull ?: 0L).toInt(),
            s.f(SpendItemFields.DONE).boolOrNull ?: false, s.f(SpendItemFields.ADDED_AT).longOrNull ?: 0L,
            // A link that doesn't pass the check now (written elsewhere, or by an older rule) is never offered.
            (s.f(SpendItemFields.LINK).textOrNull ?: "").let { l -> link(l)?.takeIf { it == l.trim() } ?: "" },
            photo(s.f(SpendItemFields.PHOTO).textOrNull),
        )
    }.sortedWith(compareBy<SpendThing> { it.addedAtMs }.thenBy { it.id })

    /**
     * What one New plan line says: the name and, when it holds one, the day it's needed by ("Christmas gifts 20 Dec";
     * with no year, the next 20 Dec). Null name when there's nothing but a date.
     */
    fun readPlan(text: String, today: Long): Pair<String, Long> {
        val e = CountdownRules.read(text, today)
        if (e == null) return text.trim().take(MAX_NAME).replaceFirstChar { it.uppercase() } to -1L
        val day = if (e.year == 0) {
            val y = CivilDate.fromEpochDay(today).year
            fun inYear(yy: Int) = CivilDate.toEpochDay(yy, e.month, minOf(e.day, CivilDate.lengthOfMonth(yy, e.month)))
            inYear(y).let { if (it >= today) it else inYear(y + 1) }
        } else CivilDate.toEpochDay(e.year, e.month, minOf(e.day, CivilDate.lengthOfMonth(e.year, e.month)))
        return e.name to day
    }

    /** The months a thing can be bought in, from [today]'s. */
    fun monthChoices(today: Long): List<SpendMonthChoice> {
        val now = monthKey(today)
        return (0 until CHOICES).map { SpendMonthChoice(now + it, monthLabel(now + it)) }
    }

    private fun afford(left: Long, money: SpendMonthMoney, toGo: Long, months: Int, sent: Boolean): SpendAfford {
        val perMonth = (toGo + months - 1) / months
        val after = left - perMonth
        val each = if (months > 1) " each month" else " this month"
        val rows = listOf(
            SpendAffordRow("Comes in each month", pounds(money.inPence), strong = false, ruled = false),
            SpendAffordRow("Regular bills and spending", "− " + pounds(money.outPence), strong = false, ruled = false),
            SpendAffordRow("Left over", pounds(left), strong = true, ruled = true),
            SpendAffordRow("Still to " + (if (sent) "send" else "buy") + (if (months > 1) " ÷ $months months" else ""), "− " + pounds(perMonth), strong = true, ruled = false),
        )
        val verdict = when {
            toGo == 0L -> if (sent) "All sent · nothing left to find" else "All bought · nothing left to find"
            after >= 0 -> "Fits · ${pounds(after)} left$each"
            else -> "${pounds(-after)} short$each · spread it or trim it"
        }
        val label = if (months == 1) "This month" else "Over $months months"
        return SpendAfford(months, label, rows, verdict, fits = toGo == 0L || after >= 0)
    }

    /** One plan in full for [today], with Can I afford it? from [money] (null: Money has no average month yet). */
    fun detail(p: SpendPlanItem, all: List<SpendThing>, money: SpendMonthMoney?, today: Long): SpendPlanDetail {
        val things = all.filter { it.planId == p.id }
        val sent = p.foreign
        val pence = things.associate { it.id to penceOf(minorOf(it, p), p) }
        val priced = pence.values.all { it != null }
        val total = things.sumOf { pence[it.id] ?: 0L }
        val spent = things.filter { it.done }.sumOf { pence[it.id] ?: 0L }
        val toGo = total - spent
        val noun = if (sent) "people" else "things"
        val one = if (sent) "person" else "thing"
        val count = "${things.size} ${if (things.size == 1) one else noun}"
        val totalMinor = things.sumOf { minorOf(it, p) }
        val totalSub = if (p.foreign) "${local(totalMinor, p.currency)} · $count" else count
        val spentLine = if (!priced) NO_RATE
            else "${pounds(spent)} ${if (sent) "sent" else "bought"} · ${pounds(toGo)} to go"
        val left = money?.let { it.inPence - it.outPence }
        val affords = if (money == null || !priced) emptyList() else (1..3).map { afford(left!!, money, toGo, it, sent) }
        val note = when {
            !priced -> null
            money == null -> AFFORD_NOT_READY
            else -> null
        }
        val now = monthKey(today)
        val tiles = (0 until TILES).map { i ->
            val key = now + i
            val t = things.filter { !it.done && (if (i == 0) it.month <= key else it.month == key) }.sumOf { pence[it.id] ?: 0L }
            when {
                t == 0L -> SpendMonthTile(monthLabel(key), pounds(0), "nothing", SpendTone.NONE)
                left == null -> SpendMonthTile(monthLabel(key), pounds(t), "", SpendTone.NONE)
                left - t >= 0 -> SpendMonthTile(monthLabel(key), pounds(t), "fits", SpendTone.FITS)
                else -> SpendMonthTile(monthLabel(key), pounds(t), "${pounds(t - left)} short", SpendTone.SHORT)
            }
        }
        val foreign = if (!p.foreign) null else {
            val c = currency(p.currency)
            val eachPence = penceOf(p.eachMinor, p)
            val rateLine = when {
                p.eachMinor <= 0 -> "Set an amount for everyone, or give each their own"
                eachPence == null -> "${local(p.eachMinor, p.currency)} each · type a rate to see it in pounds"
                else -> "${local(p.eachMinor, p.currency)} ≈ ${pounds(eachPence)} each · ${rateWho(p, today)}, ${c.symbol}${rateText(p.rateInUse)} to £1"
            }
            val todayLine = if (p.dayRateMilli <= 0) TODAY_NONE
                else "Today's rate · ${c.symbol}${rateText(p.dayRateMilli)} to £1" + (p.dayRateDay.takeIf { it >= 0 }?.let { ", ${dayLabel(it)}" } ?: "")
            SpendForeign(
                c.code, c.symbol, local(p.eachMinor, p.currency), rateLine, p.rateInUse > 0, rateText(p.rateMilli),
                todayLine, canUseToday = p.rateMilli > 0 && p.dayRateMilli > 0, onToday = p.onTodaysRate,
            )
        }
        val rows = things.map { t ->
            val minor = minorOf(t, p)
            val pn = pence[t.id]
            val base = t.note.ifEmpty { if (t.done) (if (sent) "Sent" else "Bought") else if (p.foreign && t.sameForEveryone) "Same as everyone" else "" }
            val noteLine = if (sent) base.ifEmpty { "Own amount" } else listOf(base, "buy in ${monthLabel(t.month)}").filter { it.isNotEmpty() }.joinToString(" · ")
            SpendRow(
                t.id, t.name, noteLine, pn?.let { pounds(it) } ?: "—", if (p.foreign) local(minor, p.currency) else "", t.done, t.month,
                if (t.sameForEveryone) "" else amountText(t.amountMinor), t.note,
                t.link, if (t.link.isEmpty()) "" else linkHost(t.link) ?: "",
                t.photo != null, if (t.photo != null) PHOTO_SEE else PHOTO_TAKE,
            )
        }
        return SpendPlanDetail(
            p.id, p.name, if (priced) pounds(total) else local(totalMinor, p.currency), totalSub,
            p.byDay.takeIf { it >= 0 }?.let { "needed by ${dayLabel(it)}" },
            if (total > 0) (spent.toFloat() / total).coerceIn(0f, 1f) else 0f, spentLine, affords, note, tiles, foreign,
            if (sent) "People" else "Things", rows, things.size < MAX_ITEMS,
            p.byDay.takeIf { it >= 0 }?.let { "${p.name} ${dayLabel(it)}" } ?: p.name,
            p.counted, when { !p.counted -> COUNT_OFF; !priced -> COUNT_UNPRICED; else -> COUNT_ON },
        )
    }

    /** Every plan for [today]; [money] his average month from Money (null before it has one, or while Money is locked). */
    fun view(plans: List<SpendPlanItem>, things: List<SpendThing>, money: SpendMonthMoney?, today: Long): SpendPlansView {
        val details = plans.map { detail(it, things, money, today) }
        val soonest = plans.filter { it.byDay >= today }.minByOrNull { it.byDay } ?: plans.firstOrNull()
        val row = soonest?.let { s ->
            val d = details.first { it.id == s.id }
            val toGo = d.spentLine.substringAfter(" · ", "").takeIf { d.spentLine.contains(" to go") }
            listOfNotNull(s.name, toGo, s.byDay.takeIf { it >= 0 }?.let { dayLabel(it) }).joinToString(" · ") +
                if (plans.size > 1) " · ${plans.size - 1} more" else ""
        } ?: ROW_EMPTY
        return SpendPlansView(
            plans.map { SpendPlanChip(it.id, it.name) }, details, row, rowLit = false,
            monthChoices(today), plans.size < MAX_PLANS,
        )
    }

    /**
     * The plans as Countdowns sees them ([CountdownRules.planFor]): name, needed-by day and "£185 to go" ("all bought" /
     * "all sent" once nothing is left; null with nothing priced). Totals only: no Money figures go with it.
     */
    fun countdownLinks(plans: List<SpendPlanItem>, things: List<SpendThing>, today: Long): List<CountdownPlanLink> = plans.map { p ->
        val d = detail(p, things, null, today)
        val mine = things.filter { it.planId == p.id }
        val priced = mine.isNotEmpty() && mine.all { penceOf(minorOf(it, p), p) != null }
        val toGo = when {
            !priced -> null
            mine.all { it.done } -> if (p.foreign) "all sent" else "all bought"
            else -> d.spentLine.substringAfter(" · ", "").takeIf { it.endsWith(" to go") }
        }
        CountdownPlanLink(p.name, p.byDay, toGo)
    }

    /**
     * Count in my forecast: the plans he switched on, each with what's still to buy in each month (pence; a month gone
     * by counts as this one). A thing without a pound figure (another currency, no rate yet) is left out.
     */
    fun forecastPlans(plans: List<SpendPlanItem>, things: List<SpendThing>, today: Long): List<MoneyPlanned> {
        val now = monthKey(today)
        return plans.filter { it.counted }.map { p ->
            val byMonth = HashMap<Int, Long>()
            things.filter { it.planId == p.id && !it.done }.forEach { t ->
                val pence = penceOf(minorOf(t, p), p) ?: return@forEach
                val m = t.month.coerceAtLeast(now)
                byMonth[m] = (byMonth[m] ?: 0L) + pence
            }
            MoneyPlanned(p.name, byMonth)
        }
    }

    /** His average month from Money's forecast, or null when it has none ready (no pay in, or not two months yet). */
    fun money(f: MoneyForecastView?): SpendMonthMoney? =
        f?.takeIf { it.notReady == null && it.inRounded > 0 }?.let { SpendMonthMoney(it.inRounded, it.outRounded) }
}

/** The spending plans on a replica. */
class SpendPlans(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    private fun today() = calendar.epochDayOf(nowMs())

    fun plans(): List<SpendPlanItem> = SpendPlanRules.plans(replica.entities(EntityTypes.SPEND_PLAN).associate { it.ref.entityId to it.fields })

    fun things(): List<SpendThing> {
        val live = plans().map { it.id }.toSet()
        return SpendPlanRules.things(replica.entities(EntityTypes.SPEND_PLAN_ITEM).associate { it.ref.entityId to it.fields })
            .filter { it.planId in live }
    }

    private fun plan(id: String) = plans().firstOrNull { it.id == id }

    /** A new plan from "Christmas gifts 20 Dec" in [currency] ("GBP" or another of [SpendPlanRules.CURRENCIES]). */
    fun add(text: String, currency: String): SpendSaid {
        val (name, by) = SpendPlanRules.readPlan(text, today())
        if (name.isBlank()) return SpendSaid(SpendPlanRules.NO_NAME, refused = true)
        if (plans().size >= SpendPlanRules.MAX_PLANS) return SpendSaid(SpendPlanRules.TOO_MANY_PLANS, refused = true)
        val id = newId()
        replica.commitLocal(EntityTypes.SPEND_PLAN, id, mapOf(
            SpendPlanFields.NAME to name.fv(),
            SpendPlanFields.BY_DAY to by.fv(),
            SpendPlanFields.CURRENCY to SpendPlanRules.currency(currency).code.fv(),
            SpendPlanFields.EACH to 0L.fv(),
            SpendPlanFields.RATE to 0L.fv(),
            SpendPlanFields.ADDED_AT to nowMs().fv(),
            SpendPlanFields.DELETED to false.fv(),
        ))
        return SpendSaid("Added $name", refused = false, id = id)
    }

    /** Renames the plan and its needed-by day from one line, as [add] reads it. */
    fun rename(id: String, text: String): SpendSaid {
        plan(id) ?: return SpendSaid(SpendPlanRules.GONE, refused = true)
        val (name, by) = SpendPlanRules.readPlan(text, today())
        if (name.isBlank()) return SpendSaid(SpendPlanRules.NO_NAME, refused = true)
        replica.commitLocal(EntityTypes.SPEND_PLAN, id, mapOf(SpendPlanFields.NAME to name.fv(), SpendPlanFields.BY_DAY to by.fv()))
        return SpendSaid("Saved", refused = false)
    }

    /** Removes the plan and everything on it, for good. */
    fun remove(id: String): Boolean {
        plan(id) ?: return false
        replica.commitLocal(EntityTypes.SPEND_PLAN, id, mapOf(SpendPlanFields.DELETED to true.fv()))
        return true
    }

    /** Count in my forecast, on or off for the plan. */
    fun setCounted(id: String, on: Boolean): Boolean {
        val p = plan(id) ?: return false
        if (p.counted == on) return false
        replica.commitLocal(EntityTypes.SPEND_PLAN, id, mapOf(SpendPlanFields.COUNTED to on.fv()))
        return true
    }

    /** The same-for-everyone amount ("50,000"; "" clears it). */
    fun setEach(id: String, text: String): SpendSaid {
        val p = plan(id) ?: return SpendSaid(SpendPlanRules.GONE, refused = true)
        val minor = if (text.isBlank()) 0L else SpendPlanRules.amount(text) ?: return SpendSaid(SpendPlanRules.BAD_AMOUNT, refused = true)
        replica.commitLocal(EntityTypes.SPEND_PLAN, id, mapOf(SpendPlanFields.EACH to minor.fv()))
        return SpendSaid(if (minor == 0L) "Cleared" else "${SpendPlanRules.local(minor, p.currency)} each", refused = false)
    }

    /** The − / + stepper: one [SpendCurrency.step] down (never under one step) or up. */
    fun stepEach(id: String, up: Boolean): Boolean {
        val p = plan(id) ?: return false
        val step = SpendPlanRules.currency(p.currency).step * 100
        val next = if (up) (p.eachMinor + step).coerceAtMost(SpendPlanRules.MAX_MINOR) else (p.eachMinor - step).coerceAtLeast(step)
        if (next == p.eachMinor) return false
        replica.commitLocal(EntityTypes.SPEND_PLAN, id, mapOf(SpendPlanFields.EACH to next.fv()))
        return true
    }

    /** His rate for a plan in another currency: how many to £1 ("2,000"). */
    fun setRate(id: String, text: String): SpendSaid {
        val p = plan(id) ?: return SpendSaid(SpendPlanRules.GONE, refused = true)
        if (!p.foreign) return SpendSaid(SpendPlanRules.GONE, refused = true)
        // Slice 3: a blank rate goes back to today's looked-up one.
        if (text.isBlank()) return useTodaysRate(id)
        val milli = SpendPlanRules.rate(text)?.takeIf { it <= SpendPlanRules.MAX_RATE_MILLI } ?: return SpendSaid(SpendPlanRules.BAD_RATE, refused = true)
        replica.commitLocal(EntityTypes.SPEND_PLAN, id, mapOf(SpendPlanFields.RATE to milli.fv()))
        return SpendSaid("${SpendPlanRules.currency(p.currency).symbol}${SpendPlanRules.rateText(milli)} to £1", refused = false)
    }

    /** Slice 3: drops his typed rate so the plan uses today's looked-up one. */
    fun useTodaysRate(id: String): SpendSaid {
        val p = plan(id) ?: return SpendSaid(SpendPlanRules.GONE, refused = true)
        if (!p.foreign) return SpendSaid(SpendPlanRules.GONE, refused = true)
        if (p.rateMilli > 0) replica.commitLocal(EntityTypes.SPEND_PLAN, id, mapOf(SpendPlanFields.RATE to 0L.fv()))
        val c = SpendPlanRules.currency(p.currency)
        return SpendSaid(
            if (p.dayRateMilli > 0) "Using today's rate, ${c.symbol}${SpendPlanRules.rateText(p.dayRateMilli)} to £1"
            else "Today's rate is used once it's looked up", refused = false,
        )
    }

    /**
     * Adds a person or thing: its [name], an optional [note] ("For Sam"), its [amountText] (blank on a plan in another
     * currency = the same-for-everyone amount) and the [month] he'll buy it.
     */
    fun addThing(planId: String, name: String, note: String, amountText: String, month: Int): SpendSaid {
        val p = plan(planId) ?: return SpendSaid(SpendPlanRules.GONE, refused = true)
        val n = name.trim().take(SpendPlanRules.MAX_NAME).replaceFirstChar { it.uppercase() }
        if (n.isEmpty()) return SpendSaid("Give it a name.", refused = true)
        val amount = amountOf(p, amountText) ?: return SpendSaid(if (amountText.isBlank()) SpendPlanRules.NO_AMOUNT else SpendPlanRules.BAD_AMOUNT, refused = true)
        if (things().count { it.planId == planId } >= SpendPlanRules.MAX_ITEMS) return SpendSaid(SpendPlanRules.TOO_MANY_ITEMS, refused = true)
        val id = newId()
        replica.commitLocal(EntityTypes.SPEND_PLAN_ITEM, id, mapOf(
            SpendItemFields.PLAN to planId.fv(),
            SpendItemFields.NAME to n.fv(),
            SpendItemFields.NOTE to note.trim().take(SpendPlanRules.MAX_NOTE).fv(),
            SpendItemFields.AMOUNT to amount.fv(),
            SpendItemFields.MONTH to monthOr(month, p).toLong().fv(),
            SpendItemFields.DONE to false.fv(),
            SpendItemFields.ADDED_AT to nowMs().fv(),
            SpendItemFields.DELETED to false.fv(),
        ))
        return SpendSaid("Added $n", refused = false, id = id)
    }

    /** Changes a person or thing (same fields as [addThing]). */
    fun editThing(id: String, name: String, note: String, amountText: String, month: Int): SpendSaid {
        val t = things().firstOrNull { it.id == id } ?: return SpendSaid(SpendPlanRules.GONE, refused = true)
        val p = plan(t.planId) ?: return SpendSaid(SpendPlanRules.GONE, refused = true)
        val n = name.trim().take(SpendPlanRules.MAX_NAME).replaceFirstChar { it.uppercase() }
        if (n.isEmpty()) return SpendSaid("Give it a name.", refused = true)
        val amount = amountOf(p, amountText) ?: return SpendSaid(if (amountText.isBlank()) SpendPlanRules.NO_AMOUNT else SpendPlanRules.BAD_AMOUNT, refused = true)
        replica.commitLocal(EntityTypes.SPEND_PLAN_ITEM, id, mapOf(
            SpendItemFields.NAME to n.fv(),
            SpendItemFields.NOTE to note.trim().take(SpendPlanRules.MAX_NOTE).fv(),
            SpendItemFields.AMOUNT to amount.fv(),
            SpendItemFields.MONTH to monthOr(month, p).toLong().fv(),
        ))
        return SpendSaid("Saved", refused = false)
    }

    /** Ticks a person or thing as sent or bought, or back. */
    fun setDone(id: String, done: Boolean): Boolean {
        val t = things().firstOrNull { it.id == id } ?: return false
        if (t.done == done) return false
        replica.commitLocal(EntityTypes.SPEND_PLAN_ITEM, id, mapOf(SpendItemFields.DONE to done.fv()))
        return true
    }

    /** Slice 4: the shop's link on a person or thing ("" removes it). */
    fun setLink(id: String, text: String): SpendSaid {
        val t = things().firstOrNull { it.id == id } ?: return SpendSaid(SpendPlanRules.GONE, refused = true)
        val l = SpendPlanRules.link(text) ?: return SpendSaid(SpendPlanRules.BAD_LINK, refused = true)
        if (l == t.link) return SpendSaid("Saved", refused = false)
        replica.commitLocal(EntityTypes.SPEND_PLAN_ITEM, id, mapOf(SpendItemFields.LINK to l.fv()))
        return SpendSaid(if (l.isEmpty()) "Link removed" else "Link saved · ${SpendPlanRules.linkHost(l)}", refused = false)
    }

    /**
     * Slice 5: puts [ref] (a sealed photo already kept on this device) on a person or thing, or takes it off with null.
     * Returns the photo it had before (its copy is then let go), or null; [GONE] when there's no such thing.
     */
    fun setPhoto(id: String, ref: VaultFileRef?): Result<VaultFileRef?> {
        val t = things().firstOrNull { it.id == id } ?: return Result.failure(NoSuchElementException(SpendPlanRules.GONE))
        replica.commitLocal(EntityTypes.SPEND_PLAN_ITEM, id, mapOf(SpendItemFields.PHOTO to (ref?.let { VaultFileRules.encode(it).fv() } ?: FieldValue.Null)))
        return Result.success(t.photo)
    }

    /** Slice 5: every photo a live person or thing points at (their sealed copies are kept; the rest are let go). */
    fun photoIds(): Set<String> = things().mapNotNull { it.photo?.id }.toSet()

    fun removeThing(id: String): Boolean {
        things().firstOrNull { it.id == id } ?: return false
        replica.commitLocal(EntityTypes.SPEND_PLAN_ITEM, id, mapOf(SpendItemFields.DELETED to true.fv()))
        return true
    }

    private fun amountOf(p: SpendPlanItem, text: String): Long? =
        if (text.isBlank()) (if (p.foreign) -1L else null) else SpendPlanRules.amount(text)

    /** A month before this one reads as this one; none given reads as the needed-by month, else this one. */
    private fun monthOr(month: Int, p: SpendPlanItem): Int {
        val now = SpendPlanRules.monthKey(today())
        if (month > 0) return month.coerceAtLeast(now)
        return p.byDay.takeIf { it >= 0 }?.let { SpendPlanRules.monthKey(it) }?.coerceAtLeast(now) ?: now
    }
}
