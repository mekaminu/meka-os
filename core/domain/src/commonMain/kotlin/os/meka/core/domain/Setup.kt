package os.meka.core.domain

/**
 * The Setup checklist (Meka approved 2026-10-09): one page, Ask → More → Setup on both apps, listing every capability
 * MEKA has with a tick or the next step, and a card on Today while something is left to do. Health asks "is it still
 * working?"; Setup asks "is it set up at all?". Non-AI, pure, unit-tested.
 *
 * A step is [SetupState.DONE] or [SetupState.TODO] (with the next step and, where it can be done from MEKA, a button);
 * a step only the other device can do or see is [SetupState.ELSEWHERE] ("On the Fold"); one that waits on something
 * not built yet or on Meka's own account work is [SetupState.LATER]; one that couldn't be read is
 * [SetupState.UNKNOWN] ("Couldn't check"), never a tick. Only DONE and TODO count towards "12 of 15 done".
 */
enum class SetupState { DONE, TODO, ELSEWHERE, LATER, UNKNOWN }

/** Where a step's button goes; the apps map each to their own screen or the system's. */
enum class SetupFix { CALENDARS, WORK, VOICE, NOTIFICATION_ACCESS, CALL_ROLE, BATTERY, NOTIFICATIONS }

data class SetupStep(
    /** Stable: "calendar.google", "calls.role", "mac"… */
    val key: String,
    val title: String,
    val line: String,
    val state: SetupState,
    val fix: SetupFix? = null,
    val fixLabel: String? = null,
) {
    val toDo: Boolean get() = state == SetupState.TODO
}

data class SetupSection(val title: String, val steps: List<SetupStep>)

data class SetupView(
    val sections: List<SetupSection>,
    val done: Int,
    /** Steps this device can answer for (done + to do). */
    val total: Int,
    val toDo: Int,
    /** "All set · 15 of 15 done" · "3 steps left · 12 of 15 done". */
    val summary: String,
    /** Today's card while something is left: "3 steps left · Calendar editing, Family, Pick a voice"; null when all set. */
    val todayLine: String?,
    val checkedAtMs: Long,
) {
    val steps: List<SetupStep> get() = sections.flatMap { it.steps }
}

/** What only this device can tell Setup. Null = not on this device (the Mac has no battery, listener or call role). */
data class SetupDevice(
    val mac: Boolean,
    /** MEKA may post notifications here (Android's permission, macOS's Notifications setting). */
    val notificationsAllowed: Boolean? = null,
    val batteryExempt: Boolean? = null,
    val notificationAccess: Boolean? = null,
    val callRoleHeld: Boolean? = null,
    /** Family and always-notify, which stay on the phone. */
    val people: PeopleLists? = null,
    /** Who MEKA reads for requests, which stays on the phone. */
    val watch: RequestWatch? = null,
)

data class SetupFacts(
    val device: SetupDevice,
    val connected: Boolean,
    /** Null: the server couldn't be reached. */
    val server: ServerHealth?,
    /** Null: the list couldn't be fetched. */
    val accounts: List<HealthAccount>?,
    val signIns: List<SignIn> = emptyList(),
    val ai: AiStatusView?,
    val callAssistantOn: Boolean,
    /** A caller's voice message has reached MEKA at least once (so the carrier's forwarding works). */
    val voiceMessageSeen: Boolean,
    /** MEKA's voice as chosen in Ask → More → MEKA's voice; null while never chosen. */
    val voiceChosen: String?,
    val homePlace: String = WeatherPlaceRules.HOME,
    val workPlace: String = PlacesRules.WORK,
    val nowMs: Long,
)

object SetupRules {
    const val ON_THE_FOLD = "On the Fold"
    private const val COULDNT = "Couldn't check"

    fun view(f: SetupFacts): SetupView {
        val sections = listOf(
            SetupSection("Calendars", calendars(f)),
            SetupSection("Call assistant", calls(f)),
            SetupSection("Messages", messages(f)),
            SetupSection("MEKA's voice", voice(f)),
            SetupSection("Weather and trains", places(f)),
            SetupSection("Mail", listOf(SetupStep("mail", "Mail", "Comes with email triage: you'll reconnect Google and Outlook with mail access then", SetupState.LATER))),
            SetupSection(if (f.device.mac) "This Mac" else "This phone", device(f)),
            SetupSection("Your devices", listOf(mac(f))),
        )
        val all = sections.flatMap { it.steps }
        val done = all.count { it.state == SetupState.DONE }
        val left = all.filter { it.toDo }
        val total = done + left.size
        val later = all.count { it.state == SetupState.LATER }
        val summary = when {
            left.isEmpty() && later > 0 -> "All set for now · $done of $total done · $later come later"
            left.isEmpty() -> "All set · $done of $total done"
            else -> "${steps(left.size)} left · $done of $total done"
        }
        return SetupView(sections, done, total, left.size, summary, todayLine(left), f.nowMs)
    }

    private fun steps(n: Int) = if (n == 1) "1 step" else "$n steps"

    /** "1 step left · Pick a voice" · "3 steps left · Calendar editing, Family, Pick a voice" (at most three named). */
    fun todayLine(left: List<SetupStep>): String? {
        if (left.isEmpty()) return null
        val named = left.take(3).joinToString(", ") { it.title } + if (left.size > 3) "…" else ""
        return "${steps(left.size)} left · $named"
    }

    /** Today's card: while something is left, unless Meka said "Not today" today ([hiddenOnDay] is that day). */
    fun cardShown(view: SetupView?, hiddenOnDay: Long?, today: Long): Boolean =
        view?.todayLine != null && hiddenOnDay != today

    private fun isCalendar(p: String) = p == "google" || p == "microsoft"

    private fun calendars(f: SetupFacts): List<SetupStep> {
        val accounts = f.accounts
            ?: return listOf(
                SetupStep("calendar.google", "Google calendar", COULDNT, SetupState.UNKNOWN),
                SetupStep("calendar.outlook", "Outlook calendar", COULDNT, SetupState.UNKNOWN),
                SetupStep("calendar.editing", "Calendar editing", COULDNT, SetupState.UNKNOWN),
            )
        fun account(provider: String, key: String, title: String): SetupStep {
            val signed = accounts.filter { it.provider == provider }
            return if (signed.isNotEmpty()) SetupStep(key, title, "Connected · " + signed.joinToString(", ") { it.email }, SetupState.DONE)
            else SetupStep(key, title, "Connect it so its events show in Today and the Calendar", SetupState.TODO, SetupFix.CALENDARS, "Connect")
        }
        val calendars = accounts.filter { isCalendar(it.provider) }
        val editing = when {
            calendars.isEmpty() -> SetupStep("calendar.editing", "Calendar editing", "After connecting a calendar", SetupState.LATER)
            f.signIns.any { isCalendar(it.provider) && it.canEdit } -> {
                val who = f.signIns.filter { isCalendar(it.provider) && it.canEdit }.map { CalendarAccountRules.providerLabel(it.provider) }.distinct()
                SetupStep("calendar.editing", "Calendar editing", "Allowed on ${who.joinToString(" and ")} · add and move events from MEKA", SetupState.DONE)
            }
            else -> SetupStep("calendar.editing", "Calendar editing", "Let MEKA add, move and delete events for you", SetupState.TODO, SetupFix.CALENDARS, "Allow")
        }
        return listOf(account("google", "calendar.google", "Google calendar"), account("microsoft", "calendar.outlook", "Outlook calendar"), editing)
    }

    private fun calls(f: SetupFacts): List<SetupStep> {
        val number = CallScreeningRules.ASSISTANT_NUMBER
        val service = when (f.server?.calls) {
            null -> SetupStep("calls.number", "MEKA's number", COULDNT, SetupState.UNKNOWN)
            true -> SetupStep("calls.number", "MEKA's number", "$number takes messages for you", SetupState.DONE)
            false -> SetupStep("calls.number", "MEKA's number", "The phone service isn't set up on MEKA's server yet", SetupState.LATER)
        }
        val on = if (f.callAssistantOn) SetupStep("calls.on", "Call assistant", "On · it answers calls you decline", SetupState.DONE)
        else SetupStep("calls.on", "Call assistant", "Switch it on in Work mode", SetupState.TODO, SetupFix.WORK, "Work mode")
        val role = when (f.device.callRoleHeld) {
            null -> SetupStep("calls.role", "Call screening", ON_THE_FOLD, SetupState.ELSEWHERE)
            true -> SetupStep("calls.role", "Call screening", "MEKA screens calls on this phone", SetupState.DONE)
            false -> SetupStep("calls.role", "Call screening", "Let MEKA screen calls (Android allows one app)", SetupState.TODO, SetupFix.CALL_ROLE, "Allow")
        }
        val forwarding = if (f.voiceMessageSeen) SetupStep("calls.forwarding", "Forwarding", "Working · a caller's message has reached MEKA", SetupState.DONE)
        else SetupStep("calls.forwarding", "Forwarding", "Set your network's “forward when busy” to $number, then ring yourself and decline", SetupState.TODO)
        return listOf(service, on, role, forwarding, family(f))
    }

    private fun family(f: SetupFacts): SetupStep {
        val title = "Family"
        val people = f.device.people ?: return SetupStep("calls.family", title, ON_THE_FOLD, SetupState.ELSEWHERE)
        val names = people.family.sorted()
        if (names.isEmpty()) return SetupStep("calls.family", title, "Pick family so their calls always ring", SetupState.TODO, SetupFix.WORK, "Pick")
        val missing = names.filterNot(people::hasNumber)
        if (missing.isNotEmpty()) {
            val who = if (missing.size == 1) "${missing[0]} has no number" else "${missing.size} have no number"
            return SetupStep("calls.family", title, "$who · their calls won't be recognised", SetupState.TODO, SetupFix.WORK, "Fix")
        }
        return SetupStep("calls.family", title, names.take(2).joinToString(" and ") + (if (names.size > 2) " and ${names.size - 2} more" else "") + " always ring", SetupState.DONE)
    }

    private fun messages(f: SetupFacts): List<SetupStep> {
        val access = when (f.device.notificationAccess) {
            null -> SetupStep("messages.access", "WhatsApp and texts", ON_THE_FOLD, SetupState.ELSEWHERE)
            true -> SetupStep("messages.access", "WhatsApp and texts", "MEKA reads them as they arrive", SetupState.DONE)
            false -> SetupStep("messages.access", "WhatsApp and texts", "Give MEKA notification access so it can read them", SetupState.TODO, SetupFix.NOTIFICATION_ACCESS, "Allow")
        }
        val ai = when {
            f.ai == null || f.ai == AskRules.STATUS_UNKNOWN -> SetupStep("messages.ai", "MEKA's AI", COULDNT, SetupState.UNKNOWN)
            f.ai.canAsk -> SetupStep("messages.ai", "MEKA's AI", "On · drafts replies and spots requests", SetupState.DONE)
            else -> SetupStep("messages.ai", "MEKA's AI", f.ai.line, SetupState.LATER)
        }
        val watch = f.device.watch
        val watched = when {
            watch == null -> SetupStep("messages.watch", "Requests from", ON_THE_FOLD, SetupState.ELSEWHERE)
            watch.people.isEmpty() && watch.groups.isEmpty() ->
                SetupStep("messages.watch", "Requests from", "Pick who MEKA reads for requests (Work mode → People)", SetupState.TODO, SetupFix.WORK, "Pick")
            else -> {
                val names = (watch.people.sorted() + watch.groups.sorted())
                SetupStep("messages.watch", "Requests from", "Watching " + names.take(2).joinToString(" and ") + if (names.size > 2) " and ${names.size - 2} more" else "", SetupState.DONE)
            }
        }
        return listOf(access, ai, watched)
    }

    private fun voice(f: SetupFacts): List<SetupStep> {
        val natural = when (f.server?.speech) {
            null -> SetupStep("voice.natural", "Natural voice", COULDNT, SetupState.UNKNOWN)
            true -> SetupStep("voice.natural", "Natural voice", "On · Amazon Polly", SetupState.DONE)
            false -> SetupStep("voice.natural", "Natural voice", "Not set up on MEKA's server · the device's own voice speaks", SetupState.LATER)
        }
        val chosen = f.voiceChosen
        val picked = when {
            chosen == MekaVoiceRules.DEVICE -> SetupStep("voice.picked", "Pick a voice", "The device's own voice", SetupState.DONE)
            chosen != null -> SetupStep("voice.picked", "Pick a voice", "Speaking as $chosen", SetupState.DONE)
            else -> SetupStep("voice.picked", "Pick a voice", "MEKA's default for now · hear the others and pick one", SetupState.TODO, SetupFix.VOICE, "Choose")
        }
        return listOf(natural, picked)
    }

    private fun places(f: SetupFacts): List<SetupStep> {
        val accounts = f.accounts
        val weather = when {
            accounts == null -> SetupStep("weather", "Weather", COULDNT, SetupState.UNKNOWN)
            accounts.any { it.provider == "weather" } -> SetupStep("weather", "Weather", "Home ${f.homePlace} · work ${f.workPlace} · change in Calendars", SetupState.DONE)
            else -> SetupStep("weather", "Weather", "Not fetched yet · it starts with MEKA's server", SetupState.LATER)
        }
        val lines = when {
            accounts == null -> SetupStep("trains.status", "Train lines", COULDNT, SetupState.UNKNOWN)
            accounts.any { it.provider == "lines" } -> SetupStep("trains.status", "Train lines", "Thameslink, Great Northern and the Elizabeth line from TfL", SetupState.DONE)
            else -> SetupStep("trains.status", "Train lines", "Not fetched yet · it starts with MEKA's server", SetupState.LATER)
        }
        val live = SetupStep("trains.live", "Live departures", "Need a free National Rail data key (on the build board for you)", SetupState.LATER)
        return listOf(weather, lines, live)
    }

    private fun device(f: SetupFacts): List<SetupStep> = buildList {
        add(
            if (f.connected) SetupStep("device.connected", "Connected", "Syncing with MEKA's server", SetupState.DONE)
            else SetupStep("device.connected", "Connected", "Connect this device to MEKA's server", SetupState.TODO),
        )
        when (f.device.notificationsAllowed) {
            null -> add(SetupStep("device.notifications", "Notifications", COULDNT, SetupState.UNKNOWN))
            true -> add(SetupStep("device.notifications", "Notifications", "Allowed", SetupState.DONE))
            false -> add(SetupStep("device.notifications", "Notifications", "Let MEKA tell you when something needs you", SetupState.TODO, SetupFix.NOTIFICATIONS, "Allow"))
        }
        if (!f.device.mac) when (f.device.batteryExempt) {
            null -> Unit
            true -> add(SetupStep("device.battery", "Battery", "Allowed to run in the background", SetupState.DONE))
            false -> add(SetupStep("device.battery", "Battery", "Samsung may put MEKA to sleep", SetupState.TODO, SetupFix.BATTERY, "Keep MEKA awake"))
        }
    }

    private fun mac(f: SetupFacts): SetupStep {
        if (f.device.mac) return SetupStep("mac", "Mac", "This Mac", SetupState.DONE)
        return when (val macs = f.server?.macs) {
            null -> SetupStep("mac", "Mac", COULDNT, SetupState.UNKNOWN)
            0 -> SetupStep("mac", "Mac", "Install MEKA on your Mac (tools/install-mac.sh) and connect it", SetupState.TODO)
            else -> SetupStep("mac", "Mac", if (macs == 1) "Connected" else "$macs Macs connected", SetupState.DONE)
        }
    }
}
