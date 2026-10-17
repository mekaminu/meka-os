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

data class TradingAlertSetting(val liquidation: TradingLiqAlert = TradingLiqAlert.TEN, val drop: TradingDropAlert = TradingDropAlert.TWENTY) {
    companion object {
        val DEFAULT = TradingAlertSetting()
    }
}

object TradingAlertFields {
    const val LIQ_PCT = "liqPct"
    const val DROP_PCT = "dropPct"
    const val CHANGED_AT = "changedAtMs"
}

/** One choice under ALERTS: "10%", lit when it's his. */
data class TradingAlertChip(val kind: String, val key: String, val label: String, val on: Boolean, val spoken: String)

/** ALERTS under TRADING: what he's told and when, with the two rows of choices. */
data class TradingAlertsView(
    val head: String, val line: String, val liqLabel: String, val liqChips: List<TradingAlertChip>,
    val dropLabel: String, val dropChips: List<TradingAlertChip>,
)

object TradingAlerts {
    const val ENTITY_ID = "trading_alerts"
    const val HEAD = "ALERTS"
    /** Kinds for [TradingAlertChip.kind] and the facade's setter. */
    const val LIQ = "liq"
    const val DROP = "drop"
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
    )

    fun settingFields(s: TradingAlertSetting, nowMs: Long): Map<String, FieldValue> = mapOf(
        TradingAlertFields.LIQ_PCT to s.liquidation.percent.fv(), TradingAlertFields.DROP_PCT to s.drop.percent.fv(),
        TradingAlertFields.CHANGED_AT to nowMs.fv(),
    )

    /** The setting with one choice changed ([kind] [LIQ] or [DROP], [key] a chip's key); null for one that isn't. */
    fun with(s: TradingAlertSetting, kind: String, key: String): TradingAlertSetting? = when (kind) {
        LIQ -> TradingLiqAlert.of(key)?.let { s.copy(liquidation = it) }
        DROP -> TradingDropAlert.of(key)?.let { s.copy(drop = it) }
        else -> null
    }

    /** What ALERTS says under its head. */
    fun line(s: TradingAlertSetting): String {
        val liq = if (s.liquidation == TradingLiqAlert.OFF) "No alert near liquidation"
        else "An alert when a position is within ${s.liquidation.label} of liquidation, even in quiet hours"
        val drop = if (s.drop == TradingDropAlert.OFF) "none for a drop"
        else "a heads-up when an account is ${s.drop.label} below this month's high"
        return "$liq · $drop · no amounts on the lock screen"
    }

    /** What the pane says after a change. */
    fun switchedLine(kind: String, s: TradingAlertSetting): String = when (kind) {
        LIQ -> if (s.liquidation == TradingLiqAlert.OFF) "Near-liquidation alert off" else "Alert within ${s.liquidation.label} of liquidation · on both devices"
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
    )

    /** The notices from [wallets] as the server last read them, with his [setting]. */
    fun notices(wallets: List<TradingWallet>, setting: TradingAlertSetting, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val day = cal.epochDayOf(nowMs)
        val out = mutableListOf<Notice>()
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
        }
        return out
    }
}
