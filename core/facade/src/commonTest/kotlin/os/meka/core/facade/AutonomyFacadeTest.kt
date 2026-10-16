package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.AskRawAction
import os.meka.core.domain.AutonomyRules
import os.meka.core.domain.TalkTurn
import os.meka.core.policy.ActionRequest
import os.meka.core.policy.ActionType
import os.meka.core.policy.AutonomyLevel
import os.meka.core.policy.PolicyConfig
import os.meka.core.policy.PolicyDecision
import os.meka.core.policy.PolicyDomain
import os.meka.core.policy.PolicyEngine
import os.meka.core.policy.Provenance
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "What MEKA may do" through the facade (policy engine UI, slice 1): the kill switch, Ask first, and the locked rows. */
class AutonomyFacadeTest {
    private var now = 1_791_476_100_000L // Thu 8 Oct 2026, 17:15 in London

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        var reply: AskReply = AskReply.Answered("Nothing yet.", emptyList())
        override suspend fun aiStatus(): AiStatusReply? = AiStatusReply("on", null, 120, 2000, "ok")
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply = reply
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core(device: String = "android") = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(if (device == "android") 1 else 2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun addTask(title: String) { server.reply = AskReply.Answered("Sure.", listOf(AskRawAction("add_task", title = title))) }

    private fun MekaCore.row(key: String) = autonomyView.value.sections.flatMap { it.rows }.single { it.key == key }

    @Test
    fun pauseHoldsATrustedKindAtAskingFirstOnBothDevicesAndResumeBringsItBack() = runTest {
        val fold = core()
        val mac = core("mac")
        fold.answerTrustOffer("add_task", yes = true)
        assertEquals("Navilife does 1 thing on its own · each with Undo and in Activity", fold.autonomyView.value.summary)
        assertTrue(fold.row("add_task").onItsOwn)
        assertEquals(AutonomyRules.ASK_FIRST, fold.row("add_task").action)

        assertEquals("Paused · Navilife asks before everything", fold.setAutonomyPaused(true))
        assertNull(fold.setAutonomyPaused(true))
        assertEquals("Paused since 17:15 · Navilife asks before everything", fold.autonomyView.value.summary)
        assertEquals(AutonomyRules.RESUME, fold.autonomyView.value.pauseButton)
        assertEquals(AutonomyRules.PAUSED_LINE, fold.row("add_task").line)

        // Paused: the card waits for a tap.
        addTask("Milk")
        val paused = assertIs<AskOutcome.Answered>(fold.askMeka("add milk")).answer
        assertEquals(1, paused.cards.size)
        assertTrue(paused.did.isEmpty())

        // The pause reaches the Mac.
        fold.syncNow(); mac.syncNow()
        assertTrue(mac.autonomyView.value.paused)
        assertEquals("Resumed · Navilife does what you've trusted it with again", mac.setAutonomyPaused(false))
        mac.syncNow(); fold.syncNow()
        assertFalse(fold.autonomyView.value.paused)

        // Resumed: trust was kept, so MEKA just does it again.
        addTask("Bread")
        val resumed = assertIs<AskOutcome.Answered>(fold.askMeka("add bread")).answer
        assertTrue(resumed.cards.isEmpty())
        assertEquals("Added “Bread” · done for you", resumed.did.single().line)
    }

    @Test
    fun askFirstSendsATrustedKindBackToAskingAndOfferAgainUndoesKeepAsking() = runTest {
        val c = core()
        c.answerTrustOffer("add_task", yes = true)
        c.answerTrustOffer("shopping", yes = false)
        assertEquals(AutonomyRules.DECLINED_LINE, c.row("shopping").line)
        assertEquals(AutonomyRules.OFFER_AGAIN, c.row("shopping").action)

        assertEquals("Navilife will ask before it adds tasks again.", c.resetTrust("add_task"))
        assertEquals("Asks first · offers after 5 yeses in a row", c.row("add_task").line)
        assertNull(c.row("add_task").action)
        assertNull(c.resetTrust("add_task"))
        assertEquals("Navilife may offer again after 5 yeses in a row.", c.resetTrust("shopping"))
        assertNull(c.row("shopping").action)
        assertNull(c.resetTrust("nonsense"))
        assertEquals(AutonomyRules.NONE_LINE, c.autonomyView.value.summary)

        addTask("Milk")
        val answer = assertIs<AskOutcome.Answered>(c.askMeka("add milk")).answer
        assertEquals(1, answer.cards.size)
        assertTrue(answer.did.isEmpty())
    }

    /** The locked rows say what the policy engine enforces, even with every level set to "on its own". */
    @Test
    fun theLockedRowsAreWhatThePolicyEngineEnforces() {
        val allAuto = PolicyConfig(
            levels = ActionType.entries.flatMap { t -> PolicyDomain.entries.map { (it to t) to AutonomyLevel.AUTO_UNDER_RULE } }.toMap(),
            autoRules = ActionType.entries.associateBy { "rule.${it.name}" },
        )
        fun decide(type: ActionType, domain: PolicyDomain, provenance: Provenance = Provenance.USER) =
            PolicyEngine(allAuto).decide(ActionRequest(type, domain, provenance, reversible = true, ruleId = "rule.${type.name}"))

        // Messages and email: asked, never sent on their own (no fingerprint needed).
        assertEquals(PolicyDecision.RequireApproval("Needs your approval", biometric = false), decide(ActionType.SEND_PERSONAL_MESSAGE, PolicyDomain.MESSAGING))
        assertEquals(PolicyDecision.RequireApproval("Needs your approval", biometric = false), decide(ActionType.SEND_EMAIL, PolicyDomain.EMAIL))
        // Money, trading and sharing documents: asked, with a fingerprint.
        for ((t, d) in listOf(ActionType.FINANCIAL_TRANSACTION to PolicyDomain.FINANCE, ActionType.TRADING_APPROVAL to PolicyDomain.TRADING, ActionType.SHARE_DOCUMENT to PolicyDomain.DOCUMENTS)) {
            assertEquals(PolicyDecision.RequireApproval("Needs your approval", biometric = true), decide(t, d))
        }
        // Other people's words never run on their own, even for MEKA's own lists.
        assertIs<PolicyDecision.RequireApproval>(decide(ActionType.CREATE_TASK, PolicyDomain.TASKS, Provenance.MODEL_FROM_UNTRUSTED))
        // The kill switch holds everything at asking.
        assertIs<PolicyDecision.RequireApproval>(PolicyEngine(allAuto.copy(killSwitch = true)).decide(
            ActionRequest(ActionType.CREATE_TASK, PolicyDomain.TASKS, Provenance.USER, reversible = true, ruleId = "rule.CREATE_TASK"),
        ))
        // And the page lists every one of them as locked.
        assertEquals(
            listOf(AutonomyRules.MESSAGES, AutonomyRules.EMAIL, AutonomyRules.MONEY, AutonomyRules.TRADING, AutonomyRules.DOCUMENTS, AutonomyRules.OTHERS, AutonomyRules.THE_REST),
            AutonomyRules.LOCKED.map { it.key },
        )
        assertTrue(AutonomyRules.LOCKED.all { it.locked && it.action == null && !it.onItsOwn })
    }

    @Test
    fun inboxAutopilotIsOnByDefaultAndItsRowSwitchesItOnBothDevices() = runTest {
        val fold = core()
        val mac = core("mac")
        fold.syncNow()
        assertEquals(AutonomyRules.MAIL_ON_LINE, fold.row(AutonomyRules.MAIL_AUTOPILOT).line)
        assertEquals("Turn off", fold.row(AutonomyRules.MAIL_AUTOPILOT).action)
        // It doesn't count as busy on a fresh install (nothing connected yet).
        assertEquals(AutonomyRules.NONE_LINE, fold.autonomyView.value.summary)
        assertTrue(fold.resetTrust(AutonomyRules.MAIL_AUTOPILOT)!!.startsWith("Autopilot is off"))
        assertEquals(AutonomyRules.MAIL_OFF_LINE, fold.row(AutonomyRules.MAIL_AUTOPILOT).line)
        fold.syncNow(); mac.syncNow()
        assertEquals("Turn on", mac.row(AutonomyRules.MAIL_AUTOPILOT).action)
        // Mail's switch: the accounts were never listed on this device, so the pane reads them again (null view).
        val on = mac.setMailAutopilot(true)
        assertTrue(on.line.startsWith("Autopilot is on"))
        assertNull(on.view)
        assertEquals("Turn off", mac.row(AutonomyRules.MAIL_AUTOPILOT).action)
        // Undo on a tidy that isn't there says so.
        assertEquals("This can't be undone", mac.undoMailTidy("nothing").line)
        assertEquals(os.meka.core.domain.MailSenderPanelRules.UNKNOWN, mac.unsubscribeMailSender("nothing").line)
        assertEquals(os.meka.core.domain.MailSenderPanelRules.UNKNOWN, mac.setMailSenderRule("nothing", "KEEP").line)
        // Slice 4: nothing tidied, no "Archived for you"; Got it and its Undo still answer, and it syncs.
        assertNull(mac.archivedForYou.value)
        assertEquals(os.meka.core.domain.ArchivedForYouRules.GOT_IT_LINE, mac.seenArchivedForYou())
        mac.undoSeenArchivedForYou()
        mac.syncNow(); fold.syncNow()
        assertNull(fold.archivedForYou.value)
        // Slice 3: All tidied mail reads empty, and a sender nobody sent from shows the whole (empty) list.
        val all = fold.archivedAll("nobody")
        assertEquals(os.meka.core.domain.ArchivedAllRules.EMPTY, all.empty)
        assertTrue(all.allSelected)
        assertTrue(all.days.isEmpty())
        // Slice 4: Open in Gmail/Outlook on an email that isn't there says so, with no link.
        val open = fold.archivedEmailLink("nothing", "te1")
        assertNull(open.link)
        assertEquals(os.meka.core.domain.MailTidiedAskRules.UNKNOWN, open.problem)
        assertNull(fold.archivedEmail("nothing", "te1"))
    }
}
