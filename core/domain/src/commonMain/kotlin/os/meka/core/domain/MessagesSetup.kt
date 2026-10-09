package os.meka.core.domain

/**
 * Work mode → Messages (build plan V1, messages assistant slice 5): the setup screen. Non-AI and pure: the status
 * line (notification access, MEKA's AI), the WhatsApp settings that let MEKA handle groups quietly, what goes to the
 * AI and what it costs, the limits of reading notifications, and the people and groups Meka can keep from the AI.
 *
 * The "never send to MEKA's AI" list and each group's mode stay on the Fold (sealed, never synced); the Mac shows the
 * explanation only.
 */
object MessagesSetupRules {
    /** How far back the people and groups offered on the screen reach (the phone keeps messages a week). */
    const val RECENT_MS = 7 * 24 * 60 * 60_000L
    const val MAX_PEOPLE = 12
    const val MAX_GROUPS = 8
    const val MAX_NAME = 80

    const val TITLE = "Messages"

    /**
     * The line under the title. [listening]: notification access (null on the Mac, which never reads messages);
     * [aiOn]: MEKA's AI can be asked (null while unknown).
     */
    fun statusLine(listening: Boolean?, aiOn: Boolean?): String = when {
        listening == null -> "Your Fold reads WhatsApp and texts as they arrive; set it up there: Work mode → Messages."
        !listening -> "Give MEKA notification access so it can read WhatsApp and texts as they arrive."
        aiOn == false -> "MEKA is reading WhatsApp and texts. MEKA's AI is off, so groups still go to the digest but no replies are drafted."
        else -> "MEKA is reading WhatsApp and texts: replies drafted for you to send, actions as cards, groups in the digest."
    }

    /** Whether the status line needs a look (lit in the accent). */
    fun statusLit(listening: Boolean?, aiOn: Boolean?): Boolean = listening == false || (listening == true && aiOn == false)

    /** WhatsApp → Settings → Notifications, so groups reach MEKA but stop buzzing. */
    val WHATSAPP_STEPS = listOf(
        "WhatsApp → Settings → Notifications: keep \"Show notifications\" on for messages and groups.",
        "Groups: Notification tone \"None\", Vibrate \"Off\", High-priority notifications \"Off\". MEKA then gathers them quietly.",
        "Don't mute groups in WhatsApp: a muted chat sends no notification, so MEKA can't see it.",
        "Android Settings → Notifications → WhatsApp: allowed.",
    )

    /** What goes to MEKA's AI, and what stays on the phone. */
    val PRIVACY = listOf(
        "Only a message's text, the sender's name and the time go to MEKA's AI: one message at a time for chats, one call per digest for busy groups.",
        "Messages stay sealed on this phone and are dropped after 7 days. The server keeps only the cards: who, the gist and the draft, never the message.",
        "Nothing is ever sent without your tap.",
    )

    /** What it costs, inside MEKA's AI's monthly cap. */
    const val COST = "A few pounds a month at most, inside MEKA's AI's monthly cap (Ask → More shows this month's spend)."

    /** What reading notifications can't do. */
    val LIMITS = listOf(
        "MEKA sees only what a notification shows: long messages are cut short.",
        "Voice notes and photos can't be read; a voice note becomes a \"listen later\" card.",
        "A muted chat sends no notification, and a message read first on another device may not notify.",
        "A reply to you in a group can't be seen in a notification; only your name (Meka, Chukwuemeka, @Meka) counts as a mention.",
    )

    /**
     * The people and groups offered on the screen: everyone who wrote in the last week (people from 1:1 chats, groups by
     * name), newest first, plus everyone already kept from the AI even when quiet. People and groups each capped.
     */
    fun rows(recent: List<CapturedItem>, settings: TriageSettings, nowMs: Long): MessagesSetupList {
        val since = nowMs - RECENT_MS
        val fresh = recent.filter { it.kind != CaptureKind.MISSED_CALL && it.atMs >= since }.sortedByDescending { it.atMs }
        val people = LinkedHashMap<String, String>()
        val groups = LinkedHashMap<String, String>()
        fresh.forEach { i ->
            val group = i.conversation?.trim()?.takeIf { it.isNotEmpty() }
            if (group != null) groups.getOrPut(People.key(group)) { group }
            else i.personName.trim().takeIf { it.isNotEmpty() }?.let { people.getOrPut(People.key(it)) { it } }
        }
        val neverToAi = settings.neverToAi
        val groupKeys = groups.keys.toSet() + settings.groupModes.keys.map(People::key)
        // Names kept from the AI that didn't write lately still show, so they can be switched back.
        neverToAi.forEach { n ->
            val k = People.key(n)
            if (k in groupKeys) groups.getOrPut(k) { n } else people.getOrPut(k) { n }
        }
        settings.groupModes.keys.forEach { g -> groups.getOrPut(People.key(g)) { g } }
        val privateKeys = neverToAi.map(People::key).toSet()
        val pRows = people.entries.take(MAX_PEOPLE).map { (k, n) ->
            MessagesSetupRow(n, group = false, private = k in privateKeys, mode = null)
        }
        val gRows = groups.entries.take(MAX_GROUPS).map { (k, n) ->
            MessagesSetupRow(n, group = true, private = k in privateKeys, mode = settings.modeOf(n))
        }
        return MessagesSetupList(pRows, gRows)
    }

    /** Adds or removes [name] from the never-to-AI list (by [People.key], so "Tunde " and "tunde" are one). */
    fun setNeverToAi(current: Set<String>, name: String, on: Boolean): Set<String> {
        val n = name.trim().take(MAX_NAME)
        if (n.isEmpty()) return current
        val rest = current.filterTo(LinkedHashSet()) { People.key(it) != People.key(n) }
        return if (on) rest + n else rest
    }

    /** The row's line under the name. */
    fun rowLine(row: MessagesSetupRow): String = when {
        row.private && row.group -> "Kept from MEKA's AI · ${row.mode?.label ?: GroupMode.DIGEST.label}, no gist"
        row.private -> "Kept from MEKA's AI · shown as FYI, nothing drafted"
        row.group -> GroupDigestRules.modeHint(row.mode ?: GroupMode.DIGEST)
        else -> "Sent to MEKA's AI for a lane and a draft"
    }

    /** For the undo bar: "Tunde · kept from MEKA's AI" / "Tunde · back to MEKA's AI". */
    fun privateLine(name: String, on: Boolean): String =
        if (on) "${name.trim()} · kept from MEKA's AI" else "${name.trim()} · back to MEKA's AI"

    const val EMPTY = "People and groups show here once they've messaged you."
}

data class MessagesSetupRow(val name: String, val group: Boolean, val private: Boolean, val mode: GroupMode?) {
    val line: String get() = MessagesSetupRules.rowLine(this)
}

data class MessagesSetupList(val people: List<MessagesSetupRow>, val groups: List<MessagesSetupRow>) {
    val isEmpty: Boolean get() = people.isEmpty() && groups.isEmpty()
}
