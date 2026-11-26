package com.music.bitchord

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import com.music.bitchord.ui.player.activeLyricRows
import com.music.bitchord.ui.player.withAppleAnimationWords
import com.music.bitchord.ui.player.withAppleTranslationTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleAnimationWordsTest {

    @Test
    fun `line-synced lyrics gain monotonically ordered proportional word timings`() {
        val raw = listOf(
            LyricLine(0L, ""),
            LyricLine(5_000L, "Bila kau tak di sampingku"),
            LyricLine(9_000L, "Ku rasa sepi selalu"),
            LyricLine(13_500L, "Oh"),
        )
        val animated = raw.withAppleAnimationWords(durationMs = 18_000L)

        assertTrue(animated[0].isGap)
        val line1 = animated[1]
        assertTrue(line1.isWordSynced)
        assertEquals(listOf("Bila", "kau", "tak", "di", "sampingku"), line1.words.map { it.text })
        assertEquals(5_000L, line1.words.first().startMs)
        assertTrue(line1.words.last().endMs <= 9_000L)
        // Longer final word receives a larger sweep span than short connector words.
        val shortWordSpan = line1.words[3].endMs - line1.words[3].startMs
        val longWordSpan = line1.words[4].endMs - line1.words[4].startMs
        assertTrue(longWordSpan > shortWordSpan)

        // Monotonic non-overlapping words within the line
        for (i in 1 until line1.words.size) {
            assertTrue(line1.words[i].startMs >= line1.words[i - 1].endMs)
            assertTrue(line1.words[i].endMs > line1.words[i].startMs)
        }

        // Single-word line also gets Apple sweep timing
        assertEquals(1, animated[3].words.size)
        assertEquals("Oh", animated[3].words.single().text)
        assertTrue(animated[3].words.single().endMs > 13_500L)
    }

    @Test
    fun `existing word-synced lines stay untouched`() {
        val realWords = listOf(
            LyricWord(1_000L, 1_400L, "real"),
            LyricWord(1_400L, 2_100L, "timing"),
        )
        val raw = listOf(LyricLine(1_000L, "real timing", words = realWords))
        val animated = raw.withAppleAnimationWords()
        assertSame(realWords, animated.single().words)
    }

    @Test
    fun `plain unsynced lyrics remain untouched`() {
        val raw = listOf(
            LyricLine(0L, "[Verse 1]"),
            LyricLine(0L, "Plain lyric line"),
        )
        val animated = raw.withAppleAnimationWords(durationMs = 200_000L)
        assertTrue(animated.none { it.isWordSynced })
        assertEquals(raw, animated)
    }

    @Test
    fun `synced line starting at 0ms still receives sweep timings`() {
        val raw = listOf(
            LyricLine(0L, "First sung line"),
            LyricLine(4_000L, "Second sung line"),
        )
        val animated = raw.withAppleAnimationWords(durationMs = 8_000L)
        assertTrue(animated[0].isWordSynced)
        assertEquals(0L, animated[0].words.first().startMs)
        assertTrue(animated[0].words.last().endMs <= 4_000L)
    }

    @Test
    fun `long instrumental gap caps line sweep and activeLyricRows transitions cleanly`() {
        val raw = listOf(
            LyricLine(10_000L, "Short line"),
            LyricLine(40_000L, "After solo"),
        )
        val animated = raw.withAppleAnimationWords(durationMs = 50_000L)
        val firstEnd = animated[0].words.last().endMs
        assertTrue("Expected sweep cap <= 19_000ms, was $firstEnd", firstEnd <= 19_000L)
        assertEquals(listOf(0), activeLyricRows(animated, 12_000L))
        assertEquals(listOf(0), activeLyricRows(animated, 25_000L))
        assertEquals(listOf(1), activeLyricRows(animated, 40_000L))
    }

    @Test
    fun `translation lines follow synthesized lead word timings`() {
        val rawLead = listOf(LyricLine(2_000L, "Bila kau tak di sampingku"))
        val rawSub = listOf(LyricLine(2_000L, "When you are not beside me"))
        val animatedLead = rawLead.withAppleAnimationWords(durationMs = 6_000L)
        val animatedSub = rawSub.withAppleTranslationTiming(animatedLead)

        assertTrue(animatedSub.single().isWordSynced)
        assertEquals(animatedLead.single(), animatedSub.single().timingSource)
        assertTrue(animatedSub.single().revealedChars(4_000L) > 0f)
        assertFalse(animatedSub.single().revealedChars(1_500L) > 0f)
    }
}
