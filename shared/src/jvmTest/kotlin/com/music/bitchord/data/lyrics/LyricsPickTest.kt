package com.music.bitchord.data.lyrics

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsPickTest {

    private fun result(source: LyricsSource, timeMs: Long) =
        LyricsRepository.Result(source, listOf(LyricLine(timeMs = timeMs, text = "line")))

    private fun wordResult(source: LyricsSource, timeMs: Long = 1_000L) =
        LyricsRepository.Result(
            source,
            listOf(
                LyricLine(
                    timeMs = timeMs,
                    text = "hello world",
                    words = listOf(
                        LyricWord(timeMs, timeMs + 400L, "hello"),
                        LyricWord(timeMs + 400L, timeMs + 900L, "world"),
                    ),
                ),
            ),
        )

    @Test
    fun `plain lyrics found first do not shadow line-synced ones when syllable sync is preferred`() = runBlocking {
        val picked = LyricsRepository.pick(
            listOf(
                LyricsSource.YOUTUBE_MUSIC to suspend { result(LyricsSource.YOUTUBE_MUSIC, 0L) },
                LyricsSource.LRCLIB to suspend { result(LyricsSource.LRCLIB, 1_000L) },
            ),
            prioritizeSyllableSync = true,
        )

        assertEquals(LyricsSource.LRCLIB, picked?.source)
    }

    @Test
    fun `plain lyrics are still the answer when nothing is timed`() = runBlocking {
        val picked = LyricsRepository.pick(
            listOf(LyricsSource.YOUTUBE_MUSIC to suspend { result(LyricsSource.YOUTUBE_MUSIC, 0L) }),
            prioritizeSyllableSync = true,
        )

        assertEquals(LyricsSource.YOUTUBE_MUSIC, picked?.source)
    }

    @Test
    fun `fast race emits line-synced immediately then upgrades to word-synced without waiting on stalled tail`() = runBlocking {
        val preliminaries = mutableListOf<LyricsSource>()
        val picked = LyricsRepository.race(
            listOf(
                LyricsSource.LRC_RED to suspend {
                    delay(1_500L) // hangs beyond short word grace
                    null
                },
                LyricsSource.LYRICS_PLUS to suspend {
                    delay(120L)
                    wordResult(LyricsSource.LYRICS_PLUS)
                },
                LyricsSource.LRCLIB to suspend {
                    delay(20L)
                    result(LyricsSource.LRCLIB, 1_000L)
                },
            ),
            prioritizeSyllableSync = true,
            wordGraceMs = 150L,
            lineUpgradeGraceMs = 400L,
            onPreliminaryResult = { preliminaries += it.source },
        )

        assertEquals(listOf(LyricsSource.LRCLIB, LyricsSource.LYRICS_PLUS), preliminaries)
        assertEquals(LyricsSource.LYRICS_PLUS, picked?.source)
        assertTrue(picked?.lines?.any { it.isWordSynced } == true)
    }

    @Test
    fun `fast race prefers higher-priority word-synced hit when it finishes within grace`() = runBlocking {
        val preliminaries = mutableListOf<LyricsSource>()
        val picked = LyricsRepository.race(
            listOf(
                LyricsSource.LRC_RED to suspend {
                    delay(100L)
                    wordResult(LyricsSource.LRC_RED)
                },
                LyricsSource.UNISON to suspend {
                    delay(20L)
                    wordResult(LyricsSource.UNISON)
                },
            ),
            prioritizeSyllableSync = true,
            wordGraceMs = 300L,
            onPreliminaryResult = { preliminaries += it.source },
        )

        assertEquals(listOf(LyricsSource.UNISON, LyricsSource.LRC_RED), preliminaries)
        assertEquals(LyricsSource.LRC_RED, picked?.source)
    }

    @Test
    fun `LyricsPlus request URL prioritizes Apple word-synced source`() {
        val url = LyricsPlus.buildUrl(
            host = "https://lyricsplus.prjktla.my.id",
            title = "Ain't In LA",
            artist = "ADÉLA",
            durationMs = 185_000L,
            album = null,
            isrc = null,
        )
        assertTrue(url.contains("source=apple%2Clyricsplus%2Cmusixmatch%2Cqq"))
    }
}
