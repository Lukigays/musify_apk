package com.music.bitchord.data.lyrics

/**
 * A YouTube title, as a lyrics database would have indexed it.
 *
 * Every source here but [SimpMusicLyrics] is asked for a name, and the name
 * this app has is YouTube's, which is not the name anyone catalogued. "Dracula
 * (feat. JENNIE)" is filed by Apple, LRCLIB and everyone else as "Dracula", and
 * asked for verbatim it misses all of them — which is exactly how a track ends
 * up answered by whichever source happened to clean the title on its own
 * ([PaxSenix] was the only one that did) regardless of the order the user put
 * their sources in.
 *
 * ### What is taken off, and what is deliberately left on
 *
 * Only credits and packaging: who else is on the record, and how the upload was
 * labelled. Those are not part of the recording's name anywhere.
 *
 * Anything that names a *different recording* stays — "(Remix)", "(Acoustic)",
 * "(Live)", "(Remastered 2011)", "(Sped Up)". Those look like the same kind of
 * bracket and are the opposite: stripping them turns a search for one recording
 * into a search for another, and the lyrics that come back are confidently
 * wrong rather than merely missing. A miss is recoverable; the wrong words
 * scrolling in time with the right song is not.
 */
fun String.forLyricsSearch(): String {
    var name = this
    CREDITS.forEach { pattern -> name = pattern.replace(name, " ") }
    return name.replace(TRAILING_PIPE_NOISE, "").replace(WHITESPACE, " ").trim()
        .trimEnd(',', '-', '–', '—').trim()
        // A title that was *only* packaging is no title at all; better to ask
        // with what we were given than with nothing.
        .ifBlank { trim() }
}

/**
 * Trims " - Topic" off an auto-generated channel name.
 *
 * YouTube's own artist channels for licensed music are named this way, and it
 * reaches the player as the artist on anything played from one.
 */
fun String.artistForLyricsSearch(): String =
    removeSuffix(" - Topic").trim().ifBlank { trim() }

/**
 * Splits composite YouTube upload titles such as
 * `"Sheila On 7 - Bila Kau Tak Disampingku"` or
 * `"Bila Kau Tak Disampingku - Sheila On 7"` into `(songTitle, songArtist)`
 * when one side matches the known artist or the artist field looks like an
 * uploader channel rather than the recording artist.
 */
fun splitCompositeTitle(title: String, artist: String): Pair<String, String>? {
    val cleanTitle = title.forLyricsSearch()
    val cleanArtist = artist.artistForLyricsSearch()
    val match = COMPOSITE_SEPARATOR.find(cleanTitle) ?: return null
    val left = cleanTitle.substring(0, match.range.first).trim()
    val right = cleanTitle.substring(match.range.last + 1).trim()
    if (left.isBlank() || right.isBlank()) return null

    fun sameArtist(a: String, b: String): Boolean {
        val na = a.lowercase().filter { it.isLetterOrDigit() }
        val nb = b.lowercase().filter { it.isLetterOrDigit() }
        return na.isNotEmpty() && nb.isNotEmpty() && (na == nb || na in nb || nb in na)
    }

    return when {
        cleanArtist.isNotBlank() && sameArtist(left, cleanArtist) -> right to left
        cleanArtist.isNotBlank() && sameArtist(right, cleanArtist) -> left to right
        cleanArtist.isBlank() || looksLikeUploaderChannel(cleanArtist) -> right to left
        else -> left to cleanArtist
    }.takeIf { (altTitle, altArtist) ->
        !altTitle.equals(cleanTitle, ignoreCase = true) ||
            !altArtist.equals(cleanArtist, ignoreCase = true)
    }
}

private fun looksLikeUploaderChannel(artist: String): Boolean {
    val lower = artist.lowercase()
    return UPLOADER_HINTS.any { it in lower } || !artist.contains(' ') && lower.endsWith("vevo")
}

private val WHITESPACE = Regex("""\s+""")
private val COMPOSITE_SEPARATOR = Regex("""\s+[-–—|:]\s+""")
private val TRAILING_PIPE_NOISE = Regex(
    """\s*\|\s*(official|music\s*video|lyrics?|lirik|audio|mv|hd|4k).*$""",
    RegexOption.IGNORE_CASE,
)
private val UPLOADER_HINTS = listOf(
    "official", "records", "music", "entertainment", "musik", "indo",
    "indonesia", "vevo", "channel", "studio", "tv", "lyrics", "lirik",
)

private val CREDITS = listOf(
    // Bracketed credits: (feat. X), [ft. X], (with X).
    Regex("""\s*[(\[]\s*(feat|ft|featuring|with)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
    // The same, unbracketed and running to the end of the title.
    Regex("""\s+(feat|ft|featuring)\.?\s+.*$""", RegexOption.IGNORE_CASE),
    // How the upload was labelled, not what was recorded.
    Regex(
        """\s*[(\[]\s*(official\s*)?(music\s*|musik\s*)?""" +
            """(video|audio(\s*resmi)?|visuali[sz]er|lyrics?\s*video|lyrics?|video\s*lirik|lirik(\s*lagu)?|video\s*klip|musik\s*video|m/?v|hd|hq|4k|full\s*song|clip\s*official)""" +
            """\s*[)\]]""",
        RegexOption.IGNORE_CASE,
    ),
    Regex("""\s*[(\[]\s*official\s*[)\]]""", RegexOption.IGNORE_CASE),
)
