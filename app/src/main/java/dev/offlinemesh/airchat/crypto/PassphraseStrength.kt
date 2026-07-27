package dev.offlinemesh.airchat.crypto

enum class PassphraseStrength(val label: String) {
    Weak("weak"),
    Fair("fair"),
    Strong("strong")
}

object PassphraseStrengthMeter {
    fun estimate(passphrase: String): PassphraseStrength {
        val value = passphrase.trim()
        val length = value.length
        val classes = listOf(
            value.any(Char::isLowerCase),
            value.any(Char::isUpperCase),
            value.any(Char::isDigit),
            value.any { !it.isLetterOrDigit() }
        ).count { it }
        val uniqueChars = value.toSet().size

        return when {
            length < 12 || uniqueChars < 6 -> PassphraseStrength.Weak
            length >= 24 && classes >= 2 -> PassphraseStrength.Strong
            length >= 16 && classes >= 3 && uniqueChars >= 10 -> PassphraseStrength.Strong
            else -> PassphraseStrength.Fair
        }
    }
}
