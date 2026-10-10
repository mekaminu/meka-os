package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Setup checklist's rules (Meka approved 2026-10-09). */
class SetupTest {
    private val now = 1_791_552_300_000L
    private val day = 20_735L
    private val ai = AiStatusView("On · $1.20 of $20 this month", lit = false, canAsk = true)
    private val server = ServerHealth(ServerHealth.PUSH_ON, calls = true, speech = true, atMs = now, macs = 1)
    private val accounts = listOf(
        HealthAccount("google", "meka@gmail.com", "Personal", "ok", now),
        HealthAccount("microsoft", "meka@outlook.com", "Work", "ok", now),
        HealthAccount("weather", "home", "Weather", "ok", now),
        HealthAccount("lines", "tfl", "Train lines", "ok", now),
        HealthAccount("travel", "Google Routes · drive times to football", "Travel times", "ok", now),
    )
    private val editing = listOf(SignIn("google", "meka@gmail.com", SignInState.OK, null, canEdit = true))
    private val family = PeopleLists(family = setOf("Jeanette"), numbers = mapOf("Jeanette" to setOf("+447700900123")))
    private val fold = SetupDevice(
        mac = false, notificationsAllowed = true, batteryExempt = true, notificationAccess = true, callRoleHeld = true,
        people = family, watch = RequestWatch(people = setOf("Jeanette")),
    )
    private val mac = SetupDevice(mac = true, notificationsAllowed = true)

    private fun facts(
        device: SetupDevice = fold,
        server: ServerHealth? = this.server,
        accounts: List<HealthAccount>? = this.accounts,
        signIns: List<SignIn> = editing,
        ai: AiStatusView? = this.ai,
        callAssistantOn: Boolean = true,
        voiceMessageSeen: Boolean = true,
        voiceChosen: String? = "Amy",
        connected: Boolean = true,
    ) = SetupFacts(device, connected, server, accounts, signIns, ai, callAssistantOn, voiceMessageSeen, voiceChosen, nowMs = now)

    private fun SetupView.step(key: String) = steps.first { it.key == key }

    @Test
    fun allSetOnTheFoldSaysWhatComesLaterAndShowsNoCard() {
        val v = SetupRules.view(facts())
        assertEquals(0, v.toDo, v.steps.filter { it.toDo }.toString())
        // Mail and live departures come later; they aren't counted.
        assertEquals("All set for now · ${v.done} of ${v.done} done · 2 come later", v.summary)
        assertEquals(SetupState.LATER, v.step("mail").state)
        assertEquals(SetupState.LATER, v.step("trains.live").state)
        assertNull(v.todayLine)
        assertFalse(SetupRules.cardShown(v, hiddenOnDay = null, today = day))
        assertEquals("Connected · meka@gmail.com", v.step("calendar.google").line)
        assertEquals("Allowed on Google · add and move events from MEKA", v.step("calendar.editing").line)
        assertEquals("Jeanette always ring", v.step("calls.family").line)
        assertEquals("Speaking as Amy", v.step("voice.picked").line)
        assertEquals("Home Biggleswade · work Canary Wharf · change in Calendars", v.step("weather").line)
        assertEquals("Connected", v.step("mac").line)
        assertEquals(
            listOf("Calendars", "Call assistant", "Messages", "MEKA's voice", "Weather and travel", "Mail", "This phone", "Your devices"),
            v.sections.map { it.title },
        )
    }

    @Test
    fun whatIsLeftHasItsNextStepAndTodaysCardNamesIt() {
        val v = SetupRules.view(facts(
            accounts = accounts.filter { it.provider != "microsoft" }, signIns = emptyList(), callAssistantOn = false,
            voiceMessageSeen = false, voiceChosen = null,
            device = fold.copy(callRoleHeld = false, people = PeopleLists(), watch = RequestWatch(), batteryExempt = false, notificationsAllowed = false),
            server = server.copy(macs = 0),
        ))
        fun check(key: String, fix: SetupFix?, label: String?) {
            val s = v.step(key)
            assertEquals(SetupState.TODO, s.state, key)
            assertEquals(fix, s.fix, key)
            assertEquals(label, s.fixLabel, key)
        }
        check("calendar.outlook", SetupFix.CALENDARS, "Connect")
        check("calendar.editing", SetupFix.CALENDARS, "Allow")
        check("calls.on", SetupFix.WORK, "Work mode")
        check("calls.role", SetupFix.CALL_ROLE, "Allow")
        check("calls.forwarding", null, null)
        check("calls.family", SetupFix.WORK, "Pick")
        check("messages.watch", SetupFix.WORK, "Pick")
        check("voice.picked", SetupFix.VOICE, "Choose")
        check("device.notifications", SetupFix.NOTIFICATIONS, "Allow")
        check("device.battery", SetupFix.BATTERY, "Keep MEKA awake")
        check("mac", null, null)
        assertEquals("Set your network's “forward when busy” to 01767 667246, then ring yourself and decline", v.step("calls.forwarding").line)
        assertEquals(11, v.toDo)
        assertEquals("11 steps left · ${v.done} of ${v.total} done", v.summary)
        assertEquals("11 steps left · Outlook calendar, Calendar editing, Call assistant…", v.todayLine)
        assertTrue(SetupRules.cardShown(v, hiddenOnDay = null, today = day))
        // "Not today" hides the card for the rest of the day only.
        assertFalse(SetupRules.cardShown(v, hiddenOnDay = day, today = day))
        assertTrue(SetupRules.cardShown(v, hiddenOnDay = day, today = day + 1))
        // One left names it.
        assertEquals("1 step left · Pick a voice", SetupRules.todayLine(listOf(v.step("voice.picked"))))
    }

    @Test
    fun aFamilyNameWithoutANumberIsLeftToDo() {
        val v = SetupRules.view(facts(device = fold.copy(people = PeopleLists(family = setOf("Jeanette", "Mum"), numbers = mapOf("Mum" to setOf("+447700900999"))))))
        assertEquals("Jeanette has no number · their calls won't be recognised", v.step("calls.family").line)
        assertEquals(SetupFix.WORK, v.step("calls.family").fix)
        val many = SetupRules.view(facts(device = fold.copy(people = PeopleLists(family = setOf("A", "B", "C"), numbers = mapOf("A" to setOf("1"), "B" to setOf("2"), "C" to setOf("3"))))))
        assertEquals("A and B and 1 more always ring", many.step("calls.family").line)
    }

    @Test
    fun theMacLeavesThePhonesStepsToThePhone() {
        val v = SetupRules.view(facts(device = mac))
        listOf("calls.role", "calls.family", "messages.access", "messages.watch").forEach { k ->
            assertEquals(SetupState.ELSEWHERE, v.step(k).state, k)
            assertEquals(SetupRules.ON_THE_FOLD, v.step(k).line, k)
        }
        assertTrue(v.steps.none { it.key == "device.battery" })
        assertEquals("This Mac", v.step("mac").line)
        assertEquals("This Mac", v.sections.first { it.steps.any { s -> s.key == "device.connected" } }.title)
        assertEquals(0, v.toDo)
    }

    @Test
    fun whatCouldntBeReadIsNeverTicked() {
        val v = SetupRules.view(facts(server = null, accounts = null, ai = null, connected = false, device = fold.copy(notificationsAllowed = null)))
        listOf("calendar.google", "calendar.outlook", "calendar.editing", "calls.number", "messages.ai", "voice.natural", "weather", "trains.status", "mac", "device.notifications")
            .forEach { k -> assertEquals(SetupState.UNKNOWN, v.step(k).state, k) }
        assertEquals(SetupState.TODO, v.step("device.connected").state)
        assertEquals(SetupState.UNKNOWN, SetupRules.view(facts(ai = AskRules.STATUS_UNKNOWN)).step("messages.ai").state)
        // An older server that doesn't count Macs.
        assertEquals(SetupState.UNKNOWN, SetupRules.view(facts(server = server.copy(macs = null))).step("mac").state)
        // Nothing connected yet: editing waits for a calendar.
        val none = SetupRules.view(facts(accounts = emptyList()))
        assertEquals(SetupState.LATER, none.step("calendar.editing").state)
        assertEquals(SetupState.TODO, none.step("calendar.google").state)
        // The AI switched off by its budget is MEKA's to sort, not a step for Meka.
        val off = SetupRules.view(facts(ai = AiStatusView("Off until 1 Nov · this month's $20 is used", lit = true, canAsk = false)))
        assertEquals(SetupState.LATER, off.step("messages.ai").state)
        // The device's own voice counts as chosen.
        assertEquals("The device's own voice", SetupRules.view(facts(voiceChosen = MekaVoiceRules.DEVICE)).step("voice.picked").line)
    }
}
