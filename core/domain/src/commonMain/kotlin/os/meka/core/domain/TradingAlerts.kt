package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv
import kotlin.math.roundToLong

/**
 * Trading tracker, slice 3: the alerts. Read from what Navilife's server already writes for each public address
 * ([TradingWallet]), so nothing new is sent anywhere and nothing here can trade.
 *
 * - **Near liquidation**: an open position within his chosen share of the price from liquidation ([TradingLiqAlert],
 *   10% unless changed) is a [NoticeTier.CRITICAL] notice: the one trading alert that comes in quiet hours (the build
 *   plan's completeness line). Once a day per position, and again (in the same place) when it's within half that.
 * - **A drop from the month's high**: an account whose P&L has fallen his chosen share of the account below this
 *   month's high ([TradingDropAlert], 20% unless changed) is a heads-up, once a day, and again at each further step.
 *
 * Only from a fresh read ([FRESH_MS]): an old figure never alerts late. No amounts on the notifications (the figures stay
 * behind Money's fingerprint), only the coin, the side, the share and the account's name; a tap opens Money. Never
 * advice. His two choices are one synced `context_mode` entity, id [ENTITY_ID] (ADR-008 addendum, 2026-10-17): `liqPct`
 * and `dropPct` (Int64, 0 = off) and `changedAtMs`, LWW, either app writes; absent = the defaults.
 *
 * Slice 7 adds two heads-ups, both from what the server read in the last day ([TradingWallet.recentFills],
 * [TradingWallet.fundingDayCents]) and both measured against the account's own value, so no amount is ever needed:
 * - **A big order** ([TradingFillAlert], 5× the account unless changed): an order filled in the last [FILL_FRESH_MS]
 *   worth at least that many times the account — "Big ETH order · Main" · "Open long worth 6× the account · 14:02 · …".
 *   Once per order.
 * - **High funding** ([TradingFundingAlert], 1% of the account a day unless changed): funding paid over the last 24
 *   hours at least that share of the account — once a day per account.
 * Stored on the same entity as `fillX` (Int64, times the account; 0 = off) and `fundingBp` (Int64, basis points of the
 * account a day; 0 = off); absent = the defaults.
 */
enum class TradingLiqAlert(val key: String, val label: String, val share: Double) {
    OFF("off", "Off", 0.0),
    FIVE("5", "5%", 0.05),
    TEN("10", "10%", 0.10),
    TWENTY("20", "20%", 0.20),
    ;

    val percent: Long get() = (share * 100).roundToLong()

    companion object {
        fun of(key: String?): TradingLiqAlert? = entries.firstOrNull { it.key == key }
        /** A stored percent back to its chip: 0 or less is Off, else the nearest chip at or above it. */
        fun ofPercent(p: Long): TradingLiqAlert = if (p <= 0) OFF else entries.filter { it != OFF && it.percent >= p }.minByOrNull { it.percent } ?: TWENTY
    }
}

enum class TradingDropAlert(val key: String, val label: String, val share: Double) {
    OFF("off", "Off", 0.0),
    TEN("10", "10%", 0.10),
    TWENTY("20", "20%", 0.20),
    THIRTY("30", "30%", 0.30),
    ;

    val percent: Long get() = (share * 100).roundToLong()

    companion object {
        fun of(key: String?): TradingDropAlert? = entries.firstOrNull { it.key == key }
        fun ofPercent(p: Long): TradingDropAlert = if (p <= 0) OFF else entries.filter { it != OFF && it.percent >= p }.minByOrNull { it.percent } ?: THIRTY
    }
}

/** Slice 7: how big an order (times the account's value) gets a heads-up. */
enum class TradingFillAlert(val key: String, val label: String, val times: Long) {
    OFF("off", "Off", 0),
    TWO("2", "2×", 2),
    FIVE("5", "5×", 5),
    TEN("10", "10×", 10),
    ;

    companion object {
        fun of(key: String?): TradingFillAlert? = entries.firstOrNull { it.key == key }
        fun ofTimes(x: Long): TradingFillAlert = if (x <= 0) OFF else entries.filter { it != OFF && it.times >= x }.minByOrNull { it.times } ?: TEN
    }
}

/** Slice 7: how much funding paid in a day (basis points of the account) gets a heads-up. */
enum class TradingFundingAlert(val key: String, val label: String, val bp: Long) {
    OFF("off", "Off", 0),
    HALF("0.5", "0.5%", 50),
    ONE("1", "1%", 100),
    TWO("2", "2%", 200),
    ;

    val share: Double get() = bp / 10_000.0

    companion object {
        fun of(key: String?): TradingFundingAlert? = entries.firstOrNull { it.key == key }
        fun ofBp(b: Long): TradingFundingAlert = if (b <= 0) OFF else entries.filter { it != OFF && it.bp >= b }.minByOrNull { it.bp } ?: TWO
    }
}

data class TradingAlertSetting(
    val liquidation: TradingLiqAlert = TradingLiqAlert.TEN, val drop: TradingDropAlert = TradingDropAlert.TWENTY,
    val fill: TradingFillAlert = TradingFillAlert.FIVE, val funding: TradingFundingAlert = TradingFundingAlert.ONE,
    /** Slice 12: TRADING read in pounds rather than US dollars (a display choice, kept on the same entity). */
    val pounds: Boolean = false,
) {
    companion object {
        val DEFAULT = TradingAlertSetting()
    }
}

object TradingAlertFields {
    const val LIQ_PCT = "liqPct"
    const val DROP_PCT = "dropPct"
    const val FILL_X = "fillX"
    const val FUNDING_BP = "fundingBp"
    /** Slice 12: "gbp" or "usd"; absent = dollars. */
    const val CURRENCY = "currency"
    const val CHANGED_AT = "changedAtMs"
}

/** One choice under ALERTS: "10%", lit when it's his. */
data class TradingAlertChip(val kind: String, val key: String, val label: String, val on: Boolean, val spoken: String)

/** ALERTS under TRADING: what he's told and when, with the two rows of choices. */
data class TradingAlertsView(
    val head: String, val line: String, val liqLabel: String, val liqChips: List<TradingAlertChip>,
    val dropLabel: String, val dropChips: List<TradingAlertChip>,
    /** Slice 7: a big order, and high funding. */
    val fillLabel: String = FILL_LABEL_DEFAULT, val fillChips: List<TradingAlertChip> = emptyList(),
    val fundingLabel: String = FUNDING_LABEL_DEFAULT, val fundingChips: List<TradingAlertChip> = emptyList(),
)

private const val FILL_LABEL_DEFAULT = "A big order"
private const val FUNDING_LABEL_DEFAULT = "Funding paid in a day"

object TradingAlerts {
    const val ENTITY_ID = "trading_alerts"
    const val HEAD = "ALERTS"
    /** Kinds for [TradingAlertChip.kind] and the facade's setter. */
    const val LIQ = "liq"
    const val DROP = "drop"
    const val FILL = "fill"
    const val FUNDING = "funding"
    const val FILL_LABEL = FILL_LABEL_DEFAULT
    const val FUNDING_LABEL = FUNDING_LABEL_DEFAULT
    /** Slice 7: an order alerts only while it's this recent (the server reads every 5 minutes with a position open). */
    const val FILL_FRESH_MS = 2 * 60 * 60_000L
    const val LIQ_LABEL = "Near liquidation"
    const val DROP_LABEL = "Below this month's high"
    /** A read older than this never alerts (the server reads every 5 minutes while a position is open). */
    const val FRESH_MS = 30 * 60_000L
    /** An account this far below its month's high says so on its row (lit at his drop alert's level). */
    const val DROP_SHOWN = 0.05
    /** Drop alerts come again at each further step of his level, up to this many. */
    const val MAX_STEPS = 5
    const val NOTICE_PREFIX = "trading:"

    fun read(fields: Map<String, FieldValue>?): TradingAlertSetting = TradingAlertSetting(
        fields?.get(TradingAlertFields.LIQ_PCT)?.longOrNull?.let { TradingLiqAlert.ofPercent(it) } ?: TradingAlertSetting.DEFAULT.liquidation,
        fields?.get(TradingAlertFields.DROP_PCT)?.longOrNull?.let { TradingDropAlert.ofPercent(it) } ?: TradingAlertSetting.DEFAULT.drop,
        fields?.get(TradingAlertFields.FILL_X)?.longOrNull?.let { TradingFillAlert.ofTimes(it) } ?: TradingAlertSetting.DEFAULT.fill,
        fields?.get(TradingAlertFields.FUNDING_BP)?.longOrNull?.let { TradingFundingAlert.ofBp(it) } ?: TradingAlertSetting.DEFAULT.funding,
        fields?.get(TradingAlertFields.CURRENCY)?.textOrNull?.trim()?.lowercase() == TradingCurrencyRules.GBP,
    )

    fun settingFields(s: TradingAlertSetting, nowMs: Long): Map<String, FieldValue> = mapOf(
        TradingAlertFields.LIQ_PCT to s.liquidation.percent.fv(), TradingAlertFields.DROP_PCT to s.drop.percent.fv(),
        TradingAlertFields.FILL_X to s.fill.times.fv(), TradingAlertFields.FUNDING_BP to s.funding.bp.fv(),
        TradingAlertFields.CURRENCY to (if (s.pounds) TradingCurrencyRules.GBP else TradingCurrencyRules.USD).fv(),
        TradingAlertFields.CHANGED_AT to nowMs.fv(),
    )

    /** The setting with one choice changed ([kind] [LIQ], [DROP], [FILL] or [FUNDING], [key] a chip's key); null for one that isn't. */
    fun with(s: TradingAlertSetting, kind: String, key: String): TradingAlertSetting? = when (kind) {
        LIQ -> TradingLiqAlert.of(key)?.let { s.copy(liquidation = it) }
        DROP -> TradingDropAlert.of(key)?.let { s.copy(drop = it) }
        FILL -> TradingFillAlert.of(key)?.let { s.copy(fill = it) }
        FUNDING -> TradingFundingAlert.of(key)?.let { s.copy(funding = it) }
        TradingCurrencyRules.KIND -> when (key) { TradingCurrencyRules.GBP -> s.copy(pounds = true); TradingCurrencyRules.USD -> s.copy(pounds = false); else -> null }
        else -> null
    }

    /** What ALERTS says under its head. */
    fun line(s: TradingAlertSetting): String {
        val liq = if (s.liquidation == TradingLiqAlert.OFF) "No alert near liquidation"
        else "An alert when a position is within ${s.liquidation.label} of liquidation, even in quiet hours"
        val drop = if (s.drop == TradingDropAlert.OFF) "none for a drop"
        else "a heads-up when an account is ${s.drop.label} below this month's high"
        val fill = if (s.fill == TradingFillAlert.OFF) "none for a big order" else "one for an order worth ${s.fill.label} the account"
        val funding = if (s.funding == TradingFundingAlert.OFF) "none for funding" else "one when a day's funding costs ${s.funding.label} of it"
        return "$liq · $drop · $fill · $funding · no amounts on the lock screen"
    }

    /** What the pane says after a change. */
    fun switchedLine(kind: String, s: TradingAlertSetting): String = when (kind) {
        TradingCurrencyRules.KIND -> TradingCurrencyRules.switched(s.pounds)
        LIQ -> if (s.liquidation == TradingLiqAlert.OFF) "Near-liquidation alert off" else "Alert within ${s.liquidation.label} of liquidation · on both devices"
        FILL -> if (s.fill == TradingFillAlert.OFF) "Big-order alert off" else "Heads-up for an order worth ${s.fill.label} the account · on both devices"
        FUNDING -> if (s.funding == TradingFundingAlert.OFF) "Funding alert off" else "Heads-up when a day's funding costs ${s.funding.label} of the account · on both devices"
        else -> if (s.drop == TradingDropAlert.OFF) "Drop alert off" else "Heads-up at ${s.drop.label} below this month's high · on both devices"
    }

    fun view(s: TradingAlertSetting): TradingAlertsView = TradingAlertsView(
        HEAD, line(s),
        LIQ_LABEL, TradingLiqAlert.entries.map { a ->
            TradingAlertChip(LIQ, a.key, a.label, a == s.liquidation,
                (if (a == TradingLiqAlert.OFF) "No near-liquidation alert" else "Alert within ${a.label} of liquidation") + if (a == s.liquidation) ", chosen" else "")
        },
        DROP_LABEL, TradingDropAlert.entries.map { a ->
            TradingAlertChip(DROP, a.key, a.label, a == s.drop,
                (if (a == TradingDropAlert.OFF) "No drop alert" else "Heads-up at ${a.label} below this month's high") + if (a == s.drop) ", chosen" else "")
        },
        FILL_LABEL, TradingFillAlert.entries.map { a ->
            TradingAlertChip(FILL, a.key, a.label, a == s.fill,
                (if (a == TradingFillAlert.OFF) "No big-order alert" else "Heads-up for an order worth ${a.times} times the account") + if (a == s.fill) ", chosen" else "")
        },
        FUNDING_LABEL, TradingFundingAlert.entries.map { a ->
            TradingAlertChip(FUNDING, a.key, a.label, a == s.funding,
                (if (a == TradingFundingAlert.OFF) "No funding alert" else "Heads-up when a day's funding costs ${a.label} of the account") + if (a == s.funding) ", chosen" else "")
        },
    )

    /** The notices from [wallets] as the server last read them, with his [setting]. */
    fun notices(wallets: List<TradingWallet>, setting: TradingAlertSetting, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val day = cal.epochDayOf(nowMs)
        val out = mutableListOf<Notice>()
        // Slice 9: the monthly review is ready (no amounts).
        TradingReviewRules.notice(wallets, nowMs, cal)?.let { out += it }
        for (w in wallets) {
            val readAt = w.readAtMs ?: continue
            if (w.equityCents == null || nowMs - readAt > FRESH_MS || readAt > nowMs + FRESH_MS) continue
            val expires = readAt + FRESH_MS
            val liq = setting.liquidation.share
            if (liq > 0) for (p in w.positions) {
                val d = p.liquidationDistance ?: continue
                if (d >= liq) continue
                val side = if (p.long) "long" else "short"
                val very = d < liq / 2
                val slot = "${NOTICE_PREFIX}liq:${w.id}:${p.coin}:${if (p.long) "L" else "S"}"
                out += Notice(
                    key = "$slot:$day:${if (very) "very" else "near"}", source = NoticeSource.TRADING, tier = NoticeTier.CRITICAL,
                    title = "${p.coin} $side ${if (very) "very near" else "near"} liquidation",
                    text = "${TradingTracker.pct(d)} from liquidation · ${w.label} · the figures are in Money · Navilife can't trade",
                    atMs = readAt, target = NoticeTarget.MONEY, expiresAtMs = expires, postAs = slot,
                    // Obi slice 4: money that can't wait, said out loud when he can hear it privately (no amounts).
                    speak = SpeakMoment.MONEY, spoken = ObiInterruptRules.liquidationWords(p.coin, p.long, very),
                )
            }
            val drop = setting.drop.share
            val share = w.drawdownShare
            if (drop > 0 && share != null && share >= drop) {
                val step = (share / drop).toInt().coerceIn(1, MAX_STEPS)
                val slot = "${NOTICE_PREFIX}drop:${w.id}"
                out += Notice(
                    key = "$slot:$day:$step", source = NoticeSource.TRADING, tier = NoticeTier.HEADS_UP,
                    title = "${w.label} ${TradingTracker.pct(share)} below this month's high",
                    text = "Its profit and loss since the month's best · the figures are in Money",
                    atMs = readAt, target = NoticeTarget.MONEY, expiresAtMs = expires, postAs = slot,
                )
            }
            val equity = w.equityCents.takeIf { it > 0 } ?: continue
            if (setting.fill != TradingFillAlert.OFF) for (f in w.recentFills) {
                if (f.atMs > nowMs + FRESH_MS || nowMs - f.atMs > FILL_FRESH_MS) continue
                if (f.valueCents < setting.fill.times * equity) continue
                val slot = "${NOTICE_PREFIX}fill:${w.id}"
                out += Notice(
                    key = "$slot:${f.atMs}:${f.coin}", source = NoticeSource.TRADING, tier = NoticeTier.HEADS_UP,
                    title = "Big ${f.coin} order · ${w.label}",
                    text = listOfNotNull(
                        direction(f.direction)?.let { "$it worth ${times(f.valueCents, equity)} the account" } ?: "Worth ${times(f.valueCents, equity)} the account",
                        LocalClock.formatMinute(cal.minuteOfDay(f.atMs)), "the figures are in Money · Navilife can't trade",
                    ).joinToString(" · "),
                    atMs = f.atMs, target = NoticeTarget.MONEY, expiresAtMs = f.atMs + FILL_FRESH_MS, postAs = slot,
                )
            }
            val paid = w.fundingDayCents?.let { -it }?.takeIf { it > 0 }
            if (setting.funding != TradingFundingAlert.OFF && paid != null && paid * 10_000 >= setting.funding.bp * equity) {
                val slot = "${NOTICE_PREFIX}funding:${w.id}"
                out += Notice(
                    key = "$slot:$day", source = NoticeSource.TRADING, tier = NoticeTier.HEADS_UP,
                    title = "Funding costs high · ${w.label}",
                    text = "Paid ${share(paid.toDouble() / equity)} of the account in funding over the last day · the figures are in Money",
                    atMs = readAt, target = NoticeTarget.MONEY, expiresAtMs = expires, postAs = slot,
                )
            }
        }
        return out
    }

    /** "Open long" from Hyperliquid's "Open Long"; null for one it doesn't name. */
    internal fun direction(d: String): String? = d.trim().takeIf { it.isNotEmpty() && it.length <= 30 }?.lowercase()?.replaceFirstChar { it.uppercase() }

    /** "6×", "2.5×" — the order's value over the account's, to a half. */
    internal fun times(valueCents: Long, equityCents: Long): String {
        val halves = (valueCents * 2 + equityCents / 2) / equityCents
        return if (halves % 2 == 0L) "${halves / 2}×" else "${halves / 2}.5×"
    }

    /** "0.7%", "1.2%", "3%": a share to one place (whole from 10%). */
    internal fun share(x: Double): String {
        val tenths = (x * 1000).roundToLong()
        return when {
            tenths >= 100 -> "${(x * 100).roundToLong()}%"
            tenths % 10 == 0L -> "${tenths / 10}%"
            else -> "${tenths / 10}.${tenths % 10}%"
        }
    }
}
