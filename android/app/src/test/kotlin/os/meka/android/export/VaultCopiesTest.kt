package os.meka.android.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Vault's copies on the Fold (slice 2b): a picked photo is decoded no bigger than needed and scaled to fit. */
class VaultCopiesTest {
    @Test
    fun aBigPhotoIsSampledDownButNeverBelowTheTarget() {
        assertEquals(1, VaultCopies.sampleSize(1_600, 1_200, 2_000))
        assertEquals(1, VaultCopies.sampleSize(3_999, 3_000, 2_000))
        assertEquals(2, VaultCopies.sampleSize(4_000, 3_000, 2_000))
        assertEquals(4, VaultCopies.sampleSize(3_000, 8_160, 2_000))
        // The sampled picture's long side is still at least the target.
        assertTrue(8_160 / VaultCopies.sampleSize(3_000, 8_160, 2_000) >= 2_000)
    }

    @Test
    fun theLongSideIsScaledToTheTarget() {
        assertEquals(1f, VaultCopies.scaleFor(1_600, 1_200, 2_000))
        assertEquals(0.5f, VaultCopies.scaleFor(4_000, 3_000, 2_000))
        assertEquals(0.5f, VaultCopies.scaleFor(3_000, 4_000, 2_000))
        assertEquals(listOf(85, 72, 60, 48), VaultCopies.QUALITIES)
    }
}
