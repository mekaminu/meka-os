package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AboutField
import os.meka.core.domain.GovernorResult
import os.meka.core.domain.GovernorState
import os.meka.core.domain.Notice
import os.meka.core.domain.NoticePrecision
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.NoticeTarget
import os.meka.core.domain.NoticeTier
import os.meka.core.domain.SpeakMoment
import os.meka.core.domain.SpeakPlace
import os.meka.core.domain.SpeakUpRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailFollowUpRules
import os.meka.core.domain.MailSentFacts
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Op
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Speaking up, slice 1, through the facade: one pick, logged once, the switch and Mute. */
class SpeakUpFacadeTest {
    private var now = 1_792_570_800_000L // Wed 21 Oct 2026, 09:20 in London
    private val ops = InMemoryServerOpStore()

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val train = Notice(
        key = "trainlive:1", source = NoticeSource.TRAINS, tier = NoticeTier.HEADS_UP, title = "South Rail 08:42 cancelled",
        text = "", atMs = now, target = NoticeTarget.TODAY, speak = SpeakMoment.TRAIN, spoken = "your 08:42 is cancelled.",
    )
    private val result = GovernorResult(listOf(train), null, GovernorState(), null, NoticePrecision.SOFT)

    @Test
    fun saidOnceAndInActivity() = runTest {
        val c = core()
        try {
            c.setAboutYou(AboutField.SHOWN_NAME, "Alex Smith")
            // The phone's speaker never says it; earbuds do, with his name, and Activity keeps it as Said aloud.
            assertNull(c.speakUp(result, listOf(train.key), SpeakPlace.SPEAKER, onCall = false, talking = false))
            val pick = c.speakUp(result, listOf(train.key), SpeakPlace.PRIVATE, onCall = false, talking = false)
            assertEquals("Alex, your 08:42 is cancelled.", pick?.line)
            // Counted from Activity's Said aloud entries (every device's).
            assertTrue(c.speakUpView().sections.any { "Said today: 1 of 6" in it.steps })
            // Once per notice, on any device.
            assertNull(c.speakUp(result, listOf(train.key), SpeakPlace.PRIVATE, onCall = false, talking = false))
        } finally { c.close() }
    }

    @Test
    fun focusHoldsTheTrainButNotMoney() = runTest {
        val c = core()
        try {
            // Obi slice 4: the phone's Do Not Disturb is focus; the train stays a card, money that can't wait is said.
            assertNull(c.speakUp(result, listOf(train.key), SpeakPlace.PRIVATE, onCall = false, talking = false, deviceFocus = true))
            val money = train.copy(key = "approval:a1:ending", source = NoticeSource.APPROVAL, tier = NoticeTier.CRITICAL,
                speak = SpeakMoment.MONEY, spoken = "a payment approval from Kestrel runs out at 09:30. It's in Needs you.")
            val r = GovernorResult(listOf(money), null, GovernorState(), null, NoticePrecision.SOFT)
            assertEquals("A payment approval from Kestrel runs out at 09:30. It's in Needs you.",
                c.speakUp(r, listOf(money.key), SpeakPlace.PRIVATE, onCall = false, talking = false, deviceFocus = true)?.line)
            // Out of focus the train is said as before.
            assertEquals("Your 08:42 is cancelled.", c.speakUp(result, listOf(train.key), SpeakPlace.PRIVATE, onCall = false, talking = false)?.line)
        } finally { c.close() }
    }

    @Test
    fun switchOffAndMute() = runTest {
        val c = core()
        try {
            val off = c.speakUpAction(SpeakMoment.TRAIN.label)
            assertEquals("Off", off.sections.first { it.label == SpeakMoment.TRAIN.label }.status)
            assertNull(c.speakUp(result, listOf(train.key), SpeakPlace.PRIVATE, onCall = false, talking = false))
            c.speakUpAction(SpeakMoment.TRAIN.label)
            val muted = c.speakUpAction(SpeakUpRules.MUTE_LABEL)
            assertEquals("Muted till 11:20", muted.sections.last().status)
            assertNull(c.speakUp(result, listOf(train.key), SpeakPlace.PRIVATE, onCall = false, talking = false))
            now += SpeakUpRules.MUTE_MS
            assertEquals("Your 08:42 is cancelled.", c.speakUp(result, listOf(train.key), SpeakPlace.PRIVATE, onCall = false, talking = false)?.line)
        } finally { c.close() }
    }

    @Test
    fun urgentFromFamilySaidOnce() = runTest {
        val c = core()
        try {
            c.setAboutYou(AboutField.SHOWN_NAME, "Alex Smith")
            val item = os.meka.core.domain.CapturedItem(
                id = "w1", app = os.meka.core.domain.CaptureApp.WHATSAPP, kind = os.meka.core.domain.CaptureKind.MESSAGE,
                personName = "Sam", text = "Emergency, ring me", conversation = null, atMs = now - 30_000,
            )
            val lists = os.meka.core.domain.PeopleLists(family = setOf("Sam"))
            assertNull(c.speakUpUrgent(item, os.meka.core.domain.PeopleLists(), SpeakPlace.PRIVATE, onCall = false, talking = false))
            assertNull(c.speakUpUrgent(item, lists, SpeakPlace.PRIVATE, onCall = true, talking = false))
            assertEquals("Alex, Sam says it's urgent on WhatsApp: “Emergency, ring me.”",
                c.speakUpUrgent(item, lists, SpeakPlace.PRIVATE, onCall = false, talking = false)?.line)
            assertTrue(c.speakUpView().sections.any { "Said today: 1 of 6" in it.steps })
            // The alert re-posted (▶ Listen arriving) isn't said again; "shh" in Talk mutes the next one.
            assertNull(c.speakUpUrgent(item, lists, SpeakPlace.PRIVATE, onCall = false, talking = false))
            c.muteSpeakUp()
            assertNull(c.speakUpUrgent(item.copy(id = "w2"), lists, SpeakPlace.PRIVATE, onCall = false, talking = false))
        } finally { c.close() }
    }

    /** Slice 3: tomorrow stacking up is a heads-up at 19:30 the evening before, said once with his name. */
    @Test
    fun tomorrowStackingUpIsSaidOnceInTheEvening() = runTest {
        now = 1_792_605_600_000L // Wed 21 Oct 2026, 19:00 in London
        val c = core()
        try {
            c.setAboutYou(AboutField.SHOWN_NAME, "Alex Smith")
            c.tick()
            val tomorrow = c.weekBoard.value.todayEpochDay + 1
            listOf("Call the garage" to 19, "Wrap the present" to 20, "Pack the kit" to 21).forEach { (title, hour) ->
                c.addOnBoard(title, tomorrow)!!.also { c.setWhen(it, tomorrow, hour * 60) }
            }
            // 19:00: the evening digest and the day's own heads-ups go first; nothing about tomorrow yet.
            val seven = c.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
            assertTrue(seven.post.none { it.source == NoticeSource.PRESSURE })
            now += 35 * 60_000L // 19:35
            val r = c.governNotifications(seven.stateEncoded, os.meka.core.domain.DeviceAlerts.ALL)
            val n = r.post.single { it.source == NoticeSource.PRESSURE }
            assertEquals("Tomorrow evening is stacking up", n.title)
            assertEquals(SpeakMoment.PRESSURE, n.speak)
            val pick = c.speakUp(r, listOf(n.key), SpeakPlace.BEDSIDE, onCall = false, talking = false)
            assertTrue(pick!!.line.startsWith("Alex, tomorrow evening is stacking up: Call the garage, Wrap the present and Pack the kit."), pick.line)
            assertNull(c.speakUp(r, listOf(n.key), SpeakPlace.BEDSIDE, onCall = false, talking = false))
        } finally { c.close() }
    }

    /** Slice 4: a reply to an email he put on Waiting for posts a heads-up; Obi says it once he turns that moment on. */
    @Test
    fun aReplyHeWasWaitingForIsSaidOnlyOnceTurnedOn() = runTest {
        val clock = HlcClock("server", { now })
        var k = 0
        fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
            ops.append(Op("srvsu${k++}", "hh", EntityTypes.MAIL_FIND, id, f, v, clock.now(), emptyList(), "server"))
        }
        val sentDay = os.meka.core.domain.CivilDate.toEpochDay(2026, 10, 14)
        val fu = MailFollowUpRules.read(MailSentFacts("Sam Taylor", "sam@example.com", 1, "Quote for the kitchen", "Could you send the quote?", now - 7 * 86_400_000L), setOf("alex@example.com"), false)!!
        val id = MailFollowUpRules.findId("acc1", "s1")
        serverWrite(id, MailFollowUpRules.serverFields(fu, "Personal", "Quote for the kitchen", sentDay))
        val c = core()
        try {
            c.setAboutYou(AboutField.SHOWN_NAME, "Alex Smith")
            assertTrue(c.syncNow())
            c.addMailFind(id)
            // Sam answers: the server marks the find and the heads-up posts.
            serverWrite(id, MailFollowUpRules.answeredFields(now - 5 * 60_000L, MailFollowUpRules.ANSWERED_REPLIED))
            assertTrue(c.syncNow())
            c.tick()
            val r = c.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
            val n = r.post.single { it.source == NoticeSource.REPLIED }
            assertEquals("Sam Taylor replied", n.title)
            // Off to start: nothing said.
            assertNull(c.speakUp(r, listOf(n.key), SpeakPlace.PRIVATE, onCall = false, talking = false))
            c.speakUpAction(SpeakMoment.REPLY.label)
            val pick = c.speakUp(r, listOf(n.key), SpeakPlace.PRIVATE, onCall = false, talking = false)
            assertEquals("Alex, Sam Taylor replied to your email about Quote for the kitchen.", pick?.line)
            assertNull(c.speakUp(r, listOf(n.key), SpeakPlace.PRIVATE, onCall = false, talking = false))
        } finally { c.close() }
    }
}
