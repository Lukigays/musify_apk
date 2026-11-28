package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPreviewControllerTest {

    @After
    fun tearDown() {
        QuickPreviewController.reset()
        AppSettings.setQuickPreviewOnHold(true)
        AppSettings.setQuickPreviewHoldOnly(false)
        AppSettings.setQuickPreviewBestPart(true)
        AppSettings.setQuickPreviewDurationSec(QuickPreviewController.DEFAULT_DURATION_SEC)
        AppSettings.setQuickPreviewDuckVolume(QuickPreviewController.DEFAULT_DUCK_VOLUME)
    }

    @Test
    fun `clamping duration and duck volume preserves safe bounds`() {
        assertEquals(QuickPreviewController.MIN_DURATION_SEC, QuickPreviewController.clampDurationSec(0))
        assertEquals(QuickPreviewController.MIN_DURATION_SEC, QuickPreviewController.clampDurationSec(-10))
        assertEquals(15, QuickPreviewController.clampDurationSec(15))
        assertEquals(QuickPreviewController.MAX_DURATION_SEC, QuickPreviewController.clampDurationSec(120))

        assertEquals(0f, QuickPreviewController.clampDuckVolume(-0.2f), 0.0001f)
        assertEquals(0.15f, QuickPreviewController.clampDuckVolume(0.15f), 0.0001f)
        assertEquals(QuickPreviewController.MAX_DUCK_VOLUME, QuickPreviewController.clampDuckVolume(0.95f), 0.0001f)
        assertEquals(QuickPreviewController.DEFAULT_DUCK_VOLUME, QuickPreviewController.clampDuckVolume(Float.NaN), 0.0001f)
        assertEquals(QuickPreviewController.DEFAULT_DUCK_VOLUME, QuickPreviewController.clampDuckVolume(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun `previewWindowStart selects best part when runway permits and falls back safely`() {
        assertEquals(
            0L to QuickPreviewStartSource.BEGINNING,
            QuickPreviewController.previewWindowStart(trackDurationMs = 200_000L, bestPartMs = null, previewDurationMs = 15_000L),
        )
        assertEquals(
            0L to QuickPreviewStartSource.BEGINNING,
            QuickPreviewController.previewWindowStart(trackDurationMs = 200_000L, bestPartMs = 0L, previewDurationMs = 15_000L),
        )
        assertEquals(
            58_000L to QuickPreviewStartSource.BEST_PART,
            QuickPreviewController.previewWindowStart(trackDurationMs = 200_000L, bestPartMs = 58_000L, previewDurationMs = 15_000L),
        )
        // Best part very close to end clamps back to leave at least half-preview runway.
        assertEquals(
            192_500L to QuickPreviewStartSource.BEST_PART,
            QuickPreviewController.previewWindowStart(trackDurationMs = 200_000L, bestPartMs = 199_000L, previewDurationMs = 15_000L),
        )
        // Track shorter than minimum runway starts from 0ms.
        assertEquals(
            0L to QuickPreviewStartSource.BEGINNING,
            QuickPreviewController.previewWindowStart(trackDurationMs = 5_000L, bestPartMs = 3_000L, previewDurationMs = 15_000L),
        )
        // Unknown duration keeps bestPartMs directly.
        assertEquals(
            42_000L to QuickPreviewStartSource.BEST_PART,
            QuickPreviewController.previewWindowStart(trackDurationMs = 0L, bestPartMs = 42_000L, previewDurationMs = 15_000L),
        )
    }

    @Test
    fun `lerpGain eases smoothly between endpoints and stays clamped`() {
        assertEquals(1.0f, QuickPreviewController.lerpGain(1.0f, 0.15f, -0.5f), 0.0001f)
        assertEquals(1.0f, QuickPreviewController.lerpGain(1.0f, 0.15f, 0f), 0.0001f)
        val mid = QuickPreviewController.lerpGain(1.0f, 0.15f, 0.5f)
        assertEquals(0.575f, mid, 0.0001f)
        assertEquals(0.15f, QuickPreviewController.lerpGain(1.0f, 0.15f, 1f), 0.0001f)
        assertEquals(0.15f, QuickPreviewController.lerpGain(1.0f, 0.15f, 2f), 0.0001f)

        val quarter = QuickPreviewController.lerpGain(0f, 1f, 0.25f)
        assertTrue("smoothstep at 0.25 should be below linear 0.25", quarter < 0.25f && quarter > 0f)
    }

    @Test
    fun `formatDuration and QuickPreviewState helpers report accurate state`() {
        assertEquals("0:00", QuickPreviewController.formatDuration(-500L))
        assertEquals("0:00", QuickPreviewController.formatDuration(0L))
        assertEquals("0:15", QuickPreviewController.formatDuration(15_000L))
        assertEquals("1:05", QuickPreviewController.formatDuration(65_400L))

        val idle = QuickPreviewState()
        assertFalse(idle.isActive)
        assertEquals(0f, idle.progress, 0.0001f)

        val sampleSong = Song(
            videoId = "abc12345678",
            title = "Night Drive",
            artist = "Neon Echo",
            thumbnailUrl = null,
        )
        val active = QuickPreviewState(
            song = sampleSong,
            status = QuickPreviewStatus.PLAYING,
            startMs = 45_000L,
            elapsedMs = 7_500L,
            durationMs = 15_000L,
            startSource = QuickPreviewStartSource.BEST_PART,
        )
        assertTrue(active.isActive)
        assertEquals(0.5f, active.progress, 0.0001f)
    }

    @Test
    fun `AppSettings quick preview defaults and clamped updates behave consistently`() {
        assertTrue(AppSettings.quickPreviewOnHold.value)
        assertFalse(AppSettings.quickPreviewHoldOnly.value)
        assertTrue(AppSettings.quickPreviewBestPart.value)
        assertEquals(15, AppSettings.quickPreviewDurationSec.value)
        assertEquals(0.15f, AppSettings.quickPreviewDuckVolume.value, 0.0001f)

        AppSettings.setQuickPreviewOnHold(false)
        AppSettings.setQuickPreviewHoldOnly(true)
        AppSettings.setQuickPreviewBestPart(false)
        AppSettings.setQuickPreviewDurationSec(99)
        AppSettings.setQuickPreviewDuckVolume(0.8f)

        assertFalse(AppSettings.quickPreviewOnHold.value)
        assertTrue(AppSettings.quickPreviewHoldOnly.value)
        assertFalse(AppSettings.quickPreviewBestPart.value)
        assertEquals(QuickPreviewController.MAX_DURATION_SEC, AppSettings.quickPreviewDurationSec.value)
        assertEquals(QuickPreviewController.MAX_DUCK_VOLUME, AppSettings.quickPreviewDuckVolume.value, 0.0001f)
    }
}
