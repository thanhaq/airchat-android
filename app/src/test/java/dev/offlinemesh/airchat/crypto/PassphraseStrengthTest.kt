package dev.offlinemesh.airchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class PassphraseStrengthTest {
    @Test
    fun estimatesWeakShortPassphrases() {
        assertEquals(PassphraseStrength.Weak, PassphraseStrengthMeter.estimate("radio"))
        assertEquals(PassphraseStrength.Weak, PassphraseStrengthMeter.estimate("aaaaaaaaaaaaaaaa"))
    }

    @Test
    fun estimatesFairMemorablePhrases() {
        assertEquals(PassphraseStrength.Fair, PassphraseStrengthMeter.estimate("shared field key"))
    }

    @Test
    fun estimatesStrongLongMixedPassphrases() {
        assertEquals(PassphraseStrength.Strong, PassphraseStrengthMeter.estimate("Correct-Horse-72-Field-Radio"))
    }
}
