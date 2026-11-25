package com.music.bitchord.data.listentogether

/**
 * The public invite URL for a Musify Dengar Bersama room, and the only shape
 * accepted back for canonical web invites.
 */
object JamInvite {

    const val ORIGIN = "https://musify.app"

    /** How many characters a party code carries. */
    const val CODE_LENGTH = 6

    /** The normalized party code, for the public invite URL shape only. */
    fun parse(value: String?): String? {
        val text = value?.trim() ?: return null
        val normalizedCode = listOf("$ORIGIN/invite/", "$ORIGIN/jam/").firstNotNullOfOrNull { prefix ->
            if (!text.startsWith(prefix, ignoreCase = true)) null
            else text.substring(prefix.length).substringBefore('?').substringBefore('#')
        } ?: return null
        if (normalizedCode.length != CODE_LENGTH || !normalizedCode.all(Char::isLetterOrDigit)) return null
        return normalizedCode.uppercase()
    }

    fun url(code: String): String = "$ORIGIN/invite/${code.uppercase()}"
}
