package os.meka.core.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObiFaceTest {
    private fun near(a: Double, b: Double, eps: Double = 1e-6) = assertTrue(abs(a - b) < eps, "$a vs $b")

    @Test
    fun theMouthSitsWhereTheMockupPutIt() {
        // The mockup drew the crop at 624×900 (×1.5): the patch at 180, 570, 270×247.5.
        val m = FaceGeometry.mouthIn(624.0, 900.0)
        near(m.x, 180.0); near(m.y, 570.0); near(m.w, 270.0); near(m.h, 247.5)
        // A wider view centres the crop: same size, shifted right.
        val wide = FaceGeometry.mouthIn(824.0, 900.0)
        near(wide.x, 280.0); near(wide.y, 570.0)
        // The patch's source is 360×330: half scale in crop pixels.
        near(FaceGeometry.MOUTH.w, 360 * 0.5); near(FaceGeometry.MOUTH.h, 330 * 0.5)
        // The crop stays inside the 540×720 film.
        val c = FaceGeometry.CROP
        assertTrue(c.x + c.w <= FaceGeometry.FILM_W && c.y + c.h <= FaceGeometry.FILM_H)
    }

    @Test
    fun theEdgeFadesToBlack() {
        near(FaceGeometry.fadeAlpha(0.5, 0.43), 1.0)
        near(FaceGeometry.fadeAlpha(0.0, 0.43), 0.0)    // the side edge is the ellipse's edge
        near(FaceGeometry.fadeAlpha(0.5 - 0.5 * 0.775, 0.43), 0.5) // halfway between 55 % and 100 %
        assertTrue(FaceGeometry.fadeAlpha(0.5, 0.9) > 0.0) // the chin still shows: the ellipse is 1.5× tall
        near(FaceGeometry.fadeAlpha(0.02, 0.98), 0.0)
    }

    @Test
    fun goldIsGoldAndTheMatrixMatchesTheExactChainInTheShadows() {
        assertTrue((FaceGold.lut[0] shr 16 and 0xFF) < 40) // black stays dark
        val mid = FaceGold.lut[128]
        val r = mid shr 16 and 0xFF; val g = mid shr 8 and 0xFF; val b = mid and 0xFF
        assertTrue(r > g && g > b, "gold: red over green over blue, got $r $g $b")
        val top = FaceGold.lut[255]
        assertEquals(0xFF, top shr 16 and 0xFF) // highlights burn to bright gold
        // In the darker tones no step clips, so the one-matrix path gives the same colour.
        val m = FaceGold.matrix
        for (i in listOf(60, 90, 110)) {
            val y = i / 255.0
            val exact = FaceGold.tint(y, y, y)
            for (row in 0..2) {
                val viaMatrix = ((m[row * 5] + m[row * 5 + 1] + m[row * 5 + 2]) * i + m[row * 5 + 4]) / 255.0
                near(viaMatrix.coerceIn(0.0, 1.0), exact[row], 1e-4)
            }
        }
        assertEquals(1f, m[18])
    }

    @Test
    fun pollyVisemesMapToTheSixShapes() {
        assertEquals(MouthShape.M, LipSync.shapeForPolly("p"))
        assertEquals(MouthShape.F, LipSync.shapeForPolly("f"))
        assertEquals(MouthShape.AH, LipSync.shapeForPolly("a"))
        assertEquals(MouthShape.AH, LipSync.shapeForPolly("@"))
        assertEquals(MouthShape.OH, LipSync.shapeForPolly("u"))
        assertEquals(MouthShape.EE, LipSync.shapeForPolly("E"))
        assertEquals(MouthShape.REST, LipSync.shapeForPolly("sil"))
        assertEquals(MouthShape.REST, LipSync.shapeForPolly("k"))
        assertEquals(MouthShape.M, LipSync.shapeForLetter('B'))
        assertEquals(MouthShape.REST, LipSync.shapeForLetter(' '))
        assertEquals(listOf(0L to MouthShape.M, 120L to MouthShape.OH), LipSync.marksFromCharacters(listOf("m", "o"), listOf(0.0, 0.12)))
    }

    @Test
    fun shapesAreHeldAtLeast120msAndTheNewestQuickSoundWins() {
        val marks = listOf(
            LipSync.Mark(0, "p"),     // m
            LipSync.Mark(40, "a"),    // too soon
            LipSync.Mark(90, "i"),    // too soon, newest before 120: ee wins at 120
            LipSync.Mark(300, "i"),   // same as showing: no change
            LipSync.Mark(400, "o"),
            LipSync.Mark(410, "sil"), // too soon: rest wins at 520
        )
        val cues = LipSync.fromPolly(marks, clipMs = 800)
        assertEquals(
            listOf(
                LipSync.Cue(0, MouthShape.M), LipSync.Cue(120, MouthShape.EE), LipSync.Cue(400, MouthShape.OH),
                LipSync.Cue(520, MouthShape.REST), LipSync.Cue(670, null),
            ),
            cues,
        )
        for ((a, b) in cues.zipWithNext()) assertTrue(b.atMs - a.atMs >= LipSync.HOLD_MS)
    }

    @Test
    fun theMouthClosesAtTheEndThenTheFilmsOwnFaceIsBack() {
        val cues = LipSync.fromPolly(listOf(LipSync.Mark(0, "a"), LipSync.Mark(200, "o")), clipMs = 900)
        assertEquals(listOf(LipSync.Cue(0, MouthShape.AH), LipSync.Cue(200, MouthShape.OH), LipSync.Cue(900, MouthShape.REST), LipSync.Cue(1050, null)), cues)
        assertEquals(emptyList(), LipSync.fromPolly(emptyList(), 500))
    }

    @Test
    fun framesCrossFadeOver90ms() {
        val cues = listOf(LipSync.Cue(100, MouthShape.M), LipSync.Cue(300, MouthShape.AH), LipSync.Cue(600, null))
        assertEquals(LipSync.Frame(null, null, 1.0), LipSync.at(cues, 50))
        val f = LipSync.at(cues, 345)
        assertEquals(MouthShape.M, f.from); assertEquals(MouthShape.AH, f.to); near(f.mix, 0.5)
        assertEquals(LipSync.Frame(MouthShape.AH, MouthShape.AH, 1.0), LipSync.at(cues, 500))
        val gone = LipSync.at(cues, 700)
        assertNull(gone.to); assertNull(gone.from)
    }

    @Test
    fun aVoiceWithNoTimingsMovesWithItsLoudness() {
        assertNull(LipSync.shapeForLoudness(0.01, true))
        assertEquals(MouthShape.REST, LipSync.shapeForLoudness(0.1, true))
        assertEquals(MouthShape.EE, LipSync.shapeForLoudness(0.3, LipSync.beat(0)))
        assertEquals(MouthShape.REST, LipSync.shapeForLoudness(0.3, LipSync.beat(160)))
        assertEquals(MouthShape.AH, LipSync.shapeForLoudness(0.8, false))
    }

    // Slice 3d: the points gather into the face.
    private fun gathered(g: Float, state: OrbState = OrbState.SPEAKING, angle: Float = 0.3f): Pair<ConstellationGeometry, ConstellationFrame> {
        val geo = Constellation.geometry(Constellation.POINTS)
        val f = ConstellationFrame(geo.n, Constellation.DUST)
        Constellation.frame(geo, f, state, 0.5f, angle, 1_000L, reduced = false)
        FaceGather.apply(geo, f, g)
        return geo to f
    }

    @Test
    fun thePointsGatherOntoTheFaceWhereItShowsClearly() {
        val (geo, f) = gathered(1f)
        for (i in 0 until geo.n) {
            val (tx, ty) = FaceGather.target(geo, i)
            assertTrue(abs(f.px[i] - tx) < 1e-5f && abs(f.py[i] - ty) < 1e-5f, "point $i not on its spot")
            // In the face's crop (u, v 0…1), where the edge fade still shows most of the face.
            val u = (f.px[i] / (2 * FaceGather.HALF_W) + 0.5f).toDouble()
            val v = (f.py[i] / (2 * Constellation.EXTENT) + 0.5f).toDouble()
            assertTrue(FaceGeometry.fadeAlpha(u, v) >= 0.6, "point $i at $u, $v is in the faded edge")
            assertEquals(FaceGather.NEAR, f.depth[i])
        }
        // Gathered, the face is still once the sphere's turn no longer moves the points.
        val (_, turned) = gathered(1f, angle = 2.1f)
        assertTrue((0 until geo.n).all { f.px[it] == turned.px[it] && f.py[it] == turned.py[it] })
        // The lines dim to a faint mesh; the core and the dust go.
        assertEquals(0f, f.core)
        assertTrue((0 until f.dustN).all { f.da[it] == 0f })
        val (_, none) = gathered(0f)
        assertTrue(abs(f.linkBoost - none.linkBoost * FaceGather.LINKS_KEPT) < 1e-5f)
    }

    @Test
    fun theGatheringIsStaggeredAndNothingMovesAtZero() {
        val (geo, start) = gathered(0f)
        val fresh = ConstellationFrame(geo.n, Constellation.DUST)
        Constellation.frame(geo, fresh, OrbState.SPEAKING, 0.5f, 0.3f, 1_000L, reduced = false)
        assertTrue((0 until geo.n).all { start.px[it] == fresh.px[it] && start.py[it] == fresh.py[it] })
        // Half way, some points have arrived and some have barely left: a swirl, not in step.
        val (_, half) = gathered(0.5f)
        val shares = (0 until geo.n).map { i ->
            val (tx, ty) = FaceGather.target(geo, i)
            val whole = kotlin.math.hypot(tx - fresh.px[i], ty - fresh.py[i])
            if (whole < 1e-3f) 1f else 1f - kotlin.math.hypot(tx - half.px[i], ty - half.py[i]) / whole
        }
        assertTrue(shares.maxOrNull()!! - shares.minOrNull()!! > 0.3f, "every point moved in step")
    }

    @Test
    fun thePointsHoldWhileTheyGatherThenTheFaceComesUp() {
        // The points gather over the first 70 % of the dissolve, still lit; the face shows through after.
        assertEquals(0f, FaceGather.gather(0f, false))
        assertEquals(1f, FaceGather.gather(FaceGather.GATHER_SHARE, false))
        assertEquals(1f, FaceGather.orbAlpha(0.5f, false))
        assertEquals(0f, FaceGather.faceAlpha(0.3f, false))
        assertEquals(0f, FaceGather.orbAlpha(1f, false))
        assertEquals(1f, FaceGather.faceAlpha(1f, false))
        // Both are somewhere in between near the end, so the points dissolve into the face rather than vanish first.
        assertTrue(FaceGather.orbAlpha(0.8f, false) in 0.05f..0.95f && FaceGather.faceAlpha(0.8f, false) in 0.05f..0.95f)
        // Reduced motion: no gathering, the plain cross-fade.
        assertEquals(0f, FaceGather.gather(1f, true))
        assertTrue(abs(FaceGather.orbAlpha(0.4f, true) - 0.6f) < 1e-6f)
        assertTrue(abs(FaceGather.faceAlpha(0.4f, true) - 0.4f) < 1e-6f)
    }
}
