package com.music.bitchord.data.listentogether

/**
 * Social text filter for Musify Dengar Bersama: masks Indonesian/English
 * profanity and 18+/NSFW content (including leetspeak, repeated letters, and
 * spaced characters) in room nicknames and live room chat.
 */
object SafeTextFilter {

    val bannedWords: Set<String> = setOf(
        // Indonesian / regional profanity
        "anjing", "anjir", "ajg", "bangsat", "bgst", "bajingan", "kontol", "kntl",
        "memek", "mmk", "pepek", "pukimak", "kimak", "jancok", "jancuk", "cok",
        "asw", "asu", "babi", "kampret", "keparat", "tolol", "goblok", "gblk",
        "idiot", "bodoh", "setan", "sialan", "bejat", "brengsek", "bencong",
        // English profanity
        "fuck", "fuk", "fck", "shit", "bitch", "bastard", "asshole", "dick",
        "cunt", "slut", "whore", "retard", "nigga", "nigger", "faggot", "pussy",
        // 18+ / NSFW
        "bokep", "bkp", "porno", "porn", "sex", "seks", "seksi", "telanjang",
        "bugil", "nude", "nudes", "hentai", "milf", "colmek", "coli", "crot",
        "openbo", "vcs", "cybersex", "sexting", "sange", "sangean", "masturbasi",
        "onani", "orgasme", "kondom", "perawan", "gadis18", "esek", "ewe",
        "telolet", "sakitjiwa",
    )

    val bannedPhrases: List<String> = listOf(
        "open bo",
        "openbo",
        "bo terbuka",
        "vcs 18",
        "18 plus",
        "18+",
        "chat sex",
        "video sex",
        "video bokep",
        "sex chat",
        "no sensor",
        "tanpa sensor",
    )

    private val LEET_MAP: Map<Char, Char> = mapOf(
        '0' to 'o',
        '1' to 'i',
        '3' to 'e',
        '4' to 'a',
        '5' to 's',
        '7' to 't',
        '8' to 'b',
        '9' to 'g',
        '@' to 'a',
        '$' to 's',
        '!' to 'i',
        '|' to 'i',
    )

    private val NON_ALNUM_PLUS = Regex("[^a-z0-9+]")
    private val NON_ALPHA = Regex("[^a-z]+")
    private val NON_ALPHA_CHAR = Regex("[^a-z]")
    private val MULTI_CHAR = Regex("(.)\\1+")
    private val WHITESPACE = Regex("\\s+")

    private fun leet(input: String): String {
        val out = StringBuilder(input.length)
        for (ch in input) out.append(LEET_MAP[ch] ?: ch)
        return out.toString()
    }

    private fun squeeze(word: String): String =
        MULTI_CHAR.replace(word) { it.groupValues[1] }

    private fun tokens(text: String): List<String> =
        NON_ALPHA.split(leet(text.lowercase())).filter { it.isNotEmpty() }

    /** Returns true if [text] contains profanity or 18+ phrasing. */
    fun isUnsafeText(text: String): Boolean {
        val lower = leet(text.lowercase())
        val flat = NON_ALNUM_PLUS.replace(lower, "")
        for (phrase in bannedPhrases) {
            val cleanPhrase = NON_ALNUM_PLUS.replace(leet(phrase), "")
            if (flat.contains(cleanPhrase)) return true
        }
        val words = tokens(text)
        for (token in words) {
            if (token in bannedWords || squeeze(token) in bannedWords) return true
        }
        val spacedSingles = words.filter { it.length == 1 }.joinToString("")
        if (spacedSingles.length >= 4) {
            for (word in bannedWords) {
                if (word.length >= 4 && spacedSingles.contains(word)) return true
            }
        }
        return false
    }

    /**
     * Masks unsafe words cleanly while preserving clean text.
     * Falls back to `"***"` if an obfuscated phrase cannot be isolated per word.
     */
    fun maskUnsafeText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (!isUnsafeText(trimmed)) return trimmed
        val words = WHITESPACE.split(trimmed)
        var changed = false
        val masked = ArrayList<String>(words.size)
        for (word in words) {
            val clean = NON_ALPHA_CHAR.replace(leet(word.lowercase()), "")
            val unsafe = clean.isNotEmpty() && (clean in bannedWords || squeeze(clean) in bannedWords)
            if (unsafe) {
                val stars = "*".repeat((clean.length - 1).coerceIn(2, 6))
                masked.add("${word.take(1)}$stars")
                changed = true
            } else {
                masked.add(word)
            }
        }
        val joined = masked.joinToString(" ")
        return if (!changed || isUnsafeText(joined)) "***" else joined
    }
}
