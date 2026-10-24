package os.meka.core.domain

import os.meka.core.testing.InventedRoute
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Setup checklist's rules (Meka approved 2026-10-09). */
class SetupTest {
    // Rule 13, release 2 slice 6: the Train lines step names About you's route (an invented one here).
    @BeforeTest
    fun route() { Household.useRoute(InventedRoute.TYPED) }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    @Test
    fun trainLinesNameTheRouteOrAskForIt() {
        assertEquals("South Rail, North Rail and Metro line from TfL",
            SetupRules.view(facts()).steps.first { it.key == "trains.status" }.line)
        Household.set(HouseholdNames.EMPTY)
        val none = SetupRules.view(facts()).steps.first { it.key == "trains.status" }
        assertEquals(RouteRules.NO_ROUTE, none.line)
        assertEquals(SetupState.LATER, none.state)
    }

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
    private val family = PeopleLists(family = setOf("Jordan"), numbers = mapOf("Jordan" to setOf("+447700900123")))
    private val fold = SetupDevice(
        mac = false, notificationsAllowed = true, batteryExempt = true, notificationAccess = true, callRoleHeld = true,
        people = family, watch = RequestWatch(people = setOf("Jordan")),
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
    ) = SetupFacts(device, connected, server, accounts, signIns, ai, callAssistantOn, voiceMessageSeen, voiceChosen,
        homePlace = "Oakfield", workPlace = "Riverside", nowMs = now)

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
        assertEquals("Allowed on Google · add and move events from Navilife", v.step("calendar.editing").line)
        assertEquals("Jordan always ring", v.step("calls.family").line)
        assertEquals("Speaking as Amy", v.step("voice.picked").line)
        assertEquals("Home Oakfield · work Riverside · change in Calendars", v.step("weather").line)
        assertEquals("Home not set, showing London · work not set · add them in About you", SetupRules.weatherLine(null, null))
        assertEquals("Connected", v.step("mac").line)
        assertEquals(
            listOf("Calendars", "Call assistant", "Messages", "Obi's voice", "Weather and travel", "Mail", "This phone", "Your devices"),
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
        check("device.battery", SetupFix.BATTERY, "Keep Navilife awake")
        check("mac", null, null)
        // The number is About you's (rule 13); with none typed the step names it generically.
        assertEquals("Set your network's “forward when busy” to Navilife's number, then ring yourself and decline", v.step("calls.forwarding").line)
        Household.usePlaces("", "", "01632 960000")
        try {
            assertEquals("Set your network's “forward when busy” to 01632 960000, then ring yourself and decline",
                SetupRules.view(facts(voiceMessageSeen = false)).step("calls.forwarding").line)
        } finally { Household.set(HouseholdNames.EMPTY) }
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
        val v = SetupRules.view(facts(device = fold.copy(people = PeopleLists(family = setOf("Jordan", "Mum"), numbers = mapOf("Mum" to setOf("+447700900999"))))))
        assertEquals("Jordan has no number · their calls won't be recognised", v.step("calls.family").line)
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

    @Test
    fun theRecoveryKeyStepFollowsTheServer() {
        fun step(status: RecoveryKeyRules.Status?, connected: Boolean = true) =
            SetupRules.view(facts(connected = connected), SetupRecovery(status)).step(SetupRules.RECOVERY_KEY)
        assertEquals(SetupState.TODO, step(RecoveryKeyRules.Status(RecoveryKeyRules.Status.NONE)).state)
        assertEquals(SetupFix.RECOVERY, step(RecoveryKeyRules.Status(RecoveryKeyRules.Status.NONE)).fix)
        assertEquals(SetupState.DONE, step(RecoveryKeyRules.Status(RecoveryKeyRules.Status.READY)).state)
        assertEquals("Used to connect a device · make a new one", step(RecoveryKeyRules.Status(RecoveryKeyRules.Status.USED)).line)
        assertEquals(SetupState.UNKNOWN, step(null).state)
        assertEquals(SetupState.LATER, step(null, connected = false).state)
        // Without the server's answer wrapper the step isn't listed (the old one-argument view).
        assertTrue(SetupRules.view(facts()).steps.none { it.key == SetupRules.RECOVERY_KEY })
        // Replacing a step works the counts out again.
        val v = SetupRules.view(facts(), SetupRecovery(RecoveryKeyRules.Status(RecoveryKeyRules.Status.NONE)))
        assertEquals(1, v.toDo)
        val ticked = SetupRules.replaceStep(v, SetupRules.recoveryStep(true, RecoveryKeyRules.Status(RecoveryKeyRules.Status.READY)))
        assertEquals(0, ticked.toDo)
        assertEquals(v.done + 1, ticked.done)
        assertNull(ticked.todayLine)
    }

    @Test
    fun theScreenSaverStepSitsUnderThisPhoneOnlyOnTheFold() {
        val off = SetupRules.view(facts(), null, SetupScreenSaver(false))
        val phone = off.sections.first { it.title == "This phone" }.steps
        assertEquals(BedsideDreamRules.SETUP_KEY, phone.last().key)
        assertEquals(1, off.toDo)
        assertEquals("1 step left · Bedside screen saver", off.todayLine)
        assertEquals(SetupState.DONE, SetupRules.view(facts(), null, SetupScreenSaver(true)).step(BedsideDreamRules.SETUP_KEY).state)
        // Unreadable: "Couldn't check", never counted.
        val unknown = SetupRules.view(facts(), null, SetupScreenSaver(null))
        assertEquals(0, unknown.toDo)
        assertEquals(SetupRules.view(facts()).total, unknown.total)
        // No answer passed, or the Mac: no step.
        assertNull(SetupRules.view(facts()).steps.firstOrNull { it.key == BedsideDreamRules.SETUP_KEY })
        assertNull(SetupRules.view(facts(device = mac), null, SetupScreenSaver(false)).steps.firstOrNull { it.key == BedsideDreamRules.SETUP_KEY })
    }

    @Test
    fun theCommuteStepShowsMekasGuessUntilHeSetsHisOwn() {
        val guess = SetupRules.view(facts(), null, null, CommuteSetting.DEFAULT)
        val travel = guess.sections.first { it.title == "Weather and travel" }.steps
        assertEquals(SetupRules.COMMUTE_KEY, travel.first().key)
        val step = guess.step(SetupRules.COMMUTE_KEY)
        assertEquals(SetupState.TODO, step.state)
        assertEquals(SetupFix.WORK, step.fix)
        assertEquals("Set", step.fixLabel)
        assertTrue(step.line.startsWith("About 1 h 20 each way, Navilife's guess"))
        assertEquals("1 step left · Commute", guess.todayLine)
        // Set on either app (the default's own 1 h 20 tapped counts): ticked with his times.
        val same = SetupRules.view(facts(), null, null, CommuteSetting(80, 80, chosen = true)).step(SetupRules.COMMUTE_KEY)
        assertEquals(SetupState.DONE, same.state)
        assertEquals("1 h 20 each way · the wake alarm counts it", same.line)
        val own = SetupRules.view(facts(), null, null, CommuteSetting(90, 70, chosen = true))
        assertEquals("1 h 30 there · 1 h 10 home · the wake alarm counts it", own.step(SetupRules.COMMUTE_KEY).line)
        assertEquals(guess.done + 1, own.done)
        assertNull(own.todayLine)
        // No commute passed: no step (the older callers).
        assertNull(SetupRules.view(facts()).steps.firstOrNull { it.key == SetupRules.COMMUTE_KEY })
    }

    @Test
    fun naturalVoiceNamesTheVoicesThatSpeak() {
        // Quality gate slice 58: Setup said "On · Amazon Polly" while Google's voices spoke.
        assertEquals("On · Amazon Polly", SetupRules.view(facts()).step("voice.natural").line)
        val google = server.copy(speechGoogle = ServerHealth.GOOGLE_ON)
        assertEquals("On · Google Chirp 3 HD, Amazon Polly standing by", SetupRules.view(facts(server = google)).step("voice.natural").line)
        val down = server.copy(speechGoogle = ServerHealth.GOOGLE_FAILED)
        assertEquals("On · Amazon Polly · Google's voices can't be reached", SetupRules.view(facts(server = down)).step("voice.natural").line)
    }

    @Test
    fun aButtonOnlyWhereThereIsSomethingToOpen() {
        val v = SetupRules.view(facts(device = fold.copy(batteryExempt = false), voiceChosen = null, server = null))
        assertTrue(SetupRules.hasButton(v.step("device.battery")))
        assertTrue(SetupRules.hasButton(v.step("voice.picked")))
        assertFalse(SetupRules.hasButton(v.step("calls.number"))) // couldn't check, nothing to open
        assertFalse(SetupRules.hasButton(v.step("calendar.google"))) // done
    }

    @Test
    fun everyVariantReadsSensibly() {
        val fresh = SetupDevice(mac = false, notificationsAllowed = false, batteryExempt = false, notificationAccess = false, callRoleHeld = false,
            people = PeopleLists(), watch = RequestWatch())
        val views = listOf(
            SetupRules.view(facts(device = SetupDevice(mac = false), server = null, accounts = null, ai = null, connected = false), SetupRecovery(null), SetupScreenSaver(null), null),
            SetupRules.view(facts(device = fresh, accounts = emptyList(), signIns = emptyList(), voiceChosen = null, callAssistantOn = false, voiceMessageSeen = false), SetupRecovery(null), SetupScreenSaver(false), CommuteSetting(80, 80)),
            SetupRules.view(facts(server = server.copy(speechGoogle = ServerHealth.GOOGLE_ON)), SetupRecovery(null), SetupScreenSaver(true), CommuteSetting(80, 75, chosen = true)),
        )
        views.forEach { v ->
            val texts = listOf(v.summary) + v.steps.flatMap { listOfNotNull(it.title, it.line, it.fixLabel) }
            assertEquals(emptyList(), ScreenSenseRules.problems(texts), v.summary)
        }
    }
}
