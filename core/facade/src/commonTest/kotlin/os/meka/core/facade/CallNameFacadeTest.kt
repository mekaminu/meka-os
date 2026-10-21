package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AboutField
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.AskProposal
import os.meka.core.domain.AskUndo
import os.meka.core.domain.CallNameRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * One name everywhere (Fix first, 2026-10-21) through the facade: with Your name empty the orb's hello asks once (on
 * either app), the answer comes back as a card, Save writes Your name, and Today, the hello and the brief all say it.
 */
class CallNameFacadeTest {
    private companion object { val SLOW_RUNNER = 5.minutes }

    private var now = 1_791_795_600_000L // Mon 12 Oct 2026, 10:00 in London
    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun anEmptyNameIsAskedOnceAndTheAnswerIsSavedOnHisTap() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android")
        val mac = core("mac")
        fold.setAboutYou(AboutField.PARTNER_NAME, "Sam") // a profile without his name
        // The hello asks, out loud, before anything else.
        val ask = fold.hello()
        assertEquals(CallNameRules.ASK, ask.closing)
        assertEquals("Good morning.", ask.opening)
        // His answer becomes the card; nothing is saved until he taps Save.
        val out = assertIs<AskOutcome.Answered>(fold.askMeka("It's alex"))
        val card = out.answer.cards.single()
        assertEquals(AskProposal.SetName("Alex"), card.proposal)
        assertNull(fold.aboutYouView.value.callName)
        val done = fold.doAsk(card)
        assertEquals("Alex", fold.aboutYouView.value.callName)
        // Every opener has it now, and Today says the same name.
        val hello = fold.hello()
        assertTrue("Alex" in hello.opening, hello.opening)
        assertEquals("Good morning, Alex", CallNameRules.todayGreeting(10, fold.aboutYouView.value.callName))
        assertTrue(fold.briefScriptNow().startsWith("Good morning, Alex."))
        // Undo puts it back to empty, and the hello doesn't ask again (it asked once).
        assertTrue(fold.undoAsk(assertIs<AskUndo.PutBackName>(done.undo)))
        assertNull(fold.aboutYouView.value.callName)
        assertTrue("Alex" !in fold.hello().opening)
        assertNotEquals(CallNameRules.ASK, fold.hello().closing)
        assertTrue(fold.briefScriptNow().startsWith("Good morning. "))
        // The Mac knows it was asked: it doesn't ask again either.
        fold.syncNow(); mac.syncNow()
        assertNotEquals(CallNameRules.ASK, mac.hello().closing)
    }

    @Test
    fun callMeWorksAnyTimeAndNoThanksIsKind() = runTest(timeout = SLOW_RUNNER) {
        val c = core("android")
        c.setAboutYou(AboutField.SHOWN_NAME, "Alex")
        // A name set: no ask.
        assertNotEquals(CallNameRules.ASK, c.hello().closing)
        // "Call me Sam" any time offers the card; a bare "Sam" (no ask open) is just a question.
        val card = assertIs<AskOutcome.Answered>(c.askMeka("call me Sam")).answer.cards.single()
        assertEquals(AskProposal.SetName("Sam"), card.proposal)
        assertIs<AskOutcome.Unavailable>(c.askMeka("Sam"))
        c.doAsk(card)
        assertEquals("Sam", c.aboutYouView.value.callName)

        val d = core("other")
        assertEquals(CallNameRules.ASK, d.hello().closing) // no profile yet: still asks, once per run
        assertEquals(CallNameRules.DECLINED, assertIs<AskOutcome.Answered>(d.askMeka("no thanks")).answer.text)
        assertNotEquals(CallNameRules.ASK, d.hello().closing)
        // The ask is over: a later bare word is a question again.
        now += CallNameRules.PENDING_MS + 1
        assertIs<AskOutcome.Unavailable>(d.askMeka("Alex"))
    }
}
