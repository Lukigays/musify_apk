package com.music.bitchord.playback

import com.music.bitchord.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VarispeedPresetTest {

    @Test
    fun `matching resolves named presets and falls back to custom`() {
        assertEquals(
            VarispeedPreset.OFF,
            VarispeedPreset.matching(speed = 1.00f, pitchLinked = false, reverb = 0.00f),
        )
        assertEquals(
            VarispeedPreset.SLOWED_REVERB,
            VarispeedPreset.matching(speed = 0.85f, pitchLinked = true, reverb = 0.35f),
        )
        assertEquals(
            VarispeedPreset.NIGHTCORE,
            VarispeedPreset.matching(speed = 1.22f, pitchLinked = true, reverb = 0.00f),
        )
        assertEquals(
            VarispeedPreset.SPED_UP_REVERB,
            VarispeedPreset.matching(speed = 1.20f, pitchLinked = true, reverb = 0.22f),
        )
        assertEquals(
            VarispeedPreset.CUSTOM,
            VarispeedPreset.matching(speed = 0.85f, pitchLinked = false, reverb = 0.35f),
        )
        assertEquals(
            VarispeedPreset.CUSTOM,
            VarispeedPreset.matching(speed = 0.90f, pitchLinked = true, reverb = 0.35f),
        )
    }

    @Test
    fun `varispeed layout clamps speed and reverb cleanly`() {
        assertEquals(VarispeedLayout.MIN_SPEED, VarispeedLayout.clampRawSpeed(0.10f), 0.0001f)
        assertEquals(VarispeedLayout.MAX_SPEED, VarispeedLayout.clampRawSpeed(3.00f), 0.0001f)
        assertEquals(1.0f, VarispeedLayout.clampRawSpeed(Float.NaN), 0.0001f)
        assertEquals(0.85f, VarispeedLayout.clampSpeed(0.84f), 0.0001f)
        assertEquals(0f, VarispeedLayout.clampReverb(-0.5f), 0.0001f)
        assertEquals(1f, VarispeedLayout.clampReverb(2.5f), 0.0001f)
        assertEquals(0f, VarispeedLayout.clampReverb(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun `playback parameters match speed to pitch when varispeed is linked`() {
        val linkedSlow = AppSettings.playbackParametersFor(speed = 0.85f, pitchLinked = true)
        assertEquals(0.85f, linkedSlow.speed, 0.0001f)
        assertEquals(0.85f, linkedSlow.pitch, 0.0001f)

        val linkedFast = AppSettings.playbackParametersFor(speed = 1.22f, pitchLinked = true)
        assertEquals(1.22f, linkedFast.speed, 0.0001f)
        assertEquals(1.22f, linkedFast.pitch, 0.0001f)

        val tempoOnly = AppSettings.playbackParametersFor(speed = 0.85f, pitchLinked = false)
        assertEquals(0.85f, tempoOnly.speed, 0.0001f)
        assertEquals(1.00f, tempoOnly.pitch, 0.0001f)
    }

    @Test
    fun `applying preset and resetting updates AppSettings flows coherently`() {
        AppSettings.applyVarispeedPreset(VarispeedPreset.SLOWED_REVERB)
        assertEquals(0.85f, AppSettings.playbackSpeed.value, 0.001f)
        assertTrue(AppSettings.pitchLinkedSpeed.value)
        assertEquals(0.35f, AppSettings.roomReverb.value, 0.001f)
        assertEquals(VarispeedPreset.SLOWED_REVERB, AppSettings.varispeedPreset.value)

        AppSettings.setRoomReverb(0.55f)
        assertEquals(VarispeedPreset.CUSTOM, AppSettings.varispeedPreset.value)

        AppSettings.resetAudioEffects()
        assertEquals(1.00f, AppSettings.playbackSpeed.value, 0.001f)
        assertFalse(AppSettings.pitchLinkedSpeed.value)
        assertEquals(0.00f, AppSettings.roomReverb.value, 0.001f)
        assertEquals(VarispeedPreset.OFF, AppSettings.varispeedPreset.value)
    }
}
