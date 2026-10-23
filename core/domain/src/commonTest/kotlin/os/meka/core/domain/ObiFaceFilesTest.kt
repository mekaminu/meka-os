package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObiFaceFilesTest {
    private val r = FaceFileRules
    private val all = setOf("face-loop.mp4", "mouth-rest.png", "mouth-m.png", "mouth-f.png", "mouth-ee.png", "mouth-oh.png", "mouth-ah.png")
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun aFirstLookIsDueAtOnceThenWeeklyAndSoonerWhileIncomplete() {
        assertTrue(r.due(FaceFileRules.Index(), 1_000))
        val full = FaceFileRules.Index(all.associateWith { "t1" }, checkedAtMs = 10 * day, complete = true)
        assertFalse(r.due(full, 16 * day))
        assertTrue(r.due(full, 17 * day))
        // Nothing there yet (the orb): looks again after six hours, not a week.
        val empty = FaceFileRules.Index(emptyMap(), checkedAtMs = 10 * day, complete = false)
        assertFalse(r.due(empty, 10 * day + r.RETRY_MS - 1))
        assertTrue(r.due(empty, 10 * day + r.RETRY_MS))
        // A clock set back asks again.
        assertTrue(r.due(full, 9 * day))
    }

    @Test
    fun onlyChangedFilesAreFetchedAndGoneOnesDropped() {
        val kept = FaceFileRules.Index(mapOf("face-loop.mp4" to "a", "mouth-m.png" to "b", "mouth-old.png" to "x"), 1, false)
        val offered = all.associateWith { "t" } + mapOf("face-loop.mp4" to "a", "mouth-m.png" to "c", "secret.txt" to "z")
        val p = r.plan(kept, offered, all)
        assertEquals((all - "face-loop.mp4").sorted(), p.fetch)
        assertEquals(listOf("mouth-old.png"), p.drop)
        val next = r.after(kept, p, p.fetch.associateWith { offered.getValue(it) }, all, 500)
        assertTrue(next.complete)
        assertEquals(all, next.kept.keys)
        assertEquals(500, next.checkedAtMs)
    }

    @Test
    fun theFaceShowsOnlyWithAllSevenAndFaceOn() {
        assertFalse(r.ready(all - "mouth-ah.png", all))
        assertTrue(r.ready(all, all))
        assertFalse(r.ready(emptySet(), emptySet()))
        assertTrue(r.shows(ready = true, faceOn = true))
        assertFalse(r.shows(ready = true, faceOn = false))
        assertFalse(r.shows(ready = false, faceOn = true))
        // A half-finished fetch is not complete, so it's looked at again soon.
        val half = r.after(FaceFileRules.Index(), FaceFileRules.Plan(all.sorted(), emptyList()), mapOf("face-loop.mp4" to "t"), all, 9)
        assertFalse(half.complete)
        assertTrue(r.failed(FaceFileRules.Index(all.associateWith { "t" }, 1, true), 9).let { !it.complete && it.checkedAtMs == 9L && it.kept.size == 7 })
    }

    @Test
    fun theIndexRoundTripsAndIgnoresJunk() {
        val i = FaceFileRules.Index(all.associateWith { "e$it".take(10) }, 42, true)
        assertEquals(i, r.decode(r.encode(i), all))
        assertEquals(FaceFileRules.Index(), r.decode(null, all))
        assertEquals(FaceFileRules.Index(), r.decode("not a time\nface-loop.mp4\tt", all))
        val d = r.decode("7\nface-loop.mp4\tt\n../etc/passwd\tt\nmouth-m.png\t\nmouth-f.png\ta\tb\n", all)
        assertEquals(mapOf("face-loop.mp4" to "t"), d.kept)
        assertFalse(d.complete)
    }

    @Test
    fun theFaceDissolvesInBrightensListeningAndGlintsThinking() {
        val l = FaceLook
        assertEquals(0f, l.faceIn(-1, false))
        assertEquals(0.5f, l.faceIn(300, false))
        assertEquals(1f, l.faceIn(600, false))
        assertEquals(1f, l.faceIn(5_000, false))
        assertEquals(1f, l.faceIn(HelloEntrance.FADE_MS, true))
        assertEquals(1.08f, l.brightness(OrbState.LISTENING))
        assertEquals(1f, l.brightness(OrbState.SPEAKING))
        assertNull(l.glint(OrbState.SPEAKING, 100, false))
        assertNull(l.glint(OrbState.THINKING, 100, true))
        assertEquals(0.5f, l.glint(OrbState.THINKING, 1_200 + l.GLINT_MS, false))
    }

    @Test
    fun withNoTimingsTheMouthFollowsTheLoudnessOnlyWhileSpeaking() {
        assertNull(FaceLook.mouthFromLevel(OrbState.LISTENING, 0.9f, 0))
        assertNull(FaceLook.mouthFromLevel(OrbState.SPEAKING, 0.01f, 0))
        assertEquals(MouthShape.AH, FaceLook.mouthFromLevel(OrbState.SPEAKING, 0.9f, 0))
        assertEquals(MouthShape.EE, FaceLook.mouthFromLevel(OrbState.SPEAKING, 0.3f, 0))
        assertEquals(MouthShape.REST, FaceLook.mouthFromLevel(OrbState.SPEAKING, 0.3f, LipSync.BEAT_MS))
    }

    @Test
    fun theLiveMouthHoldsEachShapeThenCrossFades() {
        val m = MouthFollower()
        assertEquals(LipSync.Frame(null, MouthShape.AH, 0.0), m.step(MouthShape.AH, 1_000))
        assertEquals(LipSync.Frame(null, MouthShape.AH, 0.5), m.step(MouthShape.AH, 1_045))
        // Too soon after the last change: AH stays.
        assertEquals(LipSync.Frame(MouthShape.AH, MouthShape.AH, 1.0), m.step(MouthShape.EE, 1_100))
        assertEquals(LipSync.Frame(MouthShape.AH, MouthShape.EE, 0.0), m.step(MouthShape.EE, 1_120))
        assertEquals(LipSync.Frame(MouthShape.EE, MouthShape.EE, 1.0), m.step(MouthShape.EE, 1_210))
        // Silence: the patch fades away to the film's own mouth.
        assertEquals(LipSync.Frame(MouthShape.EE, null, 0.0), m.step(null, 1_300))
        assertEquals(LipSync.Frame(null, null, 1.0), m.step(null, 1_400))
    }
}

