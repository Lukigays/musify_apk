package com.music.bitchord.playback

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bounds and slider increments for the real-time Varispeed + Room Reverb
 * processor ([RoomReverbProcessor] + Media3 pitch-linked `PlaybackParameters`).
 */
object VarispeedLayout {
    const val MIN_SPEED = 0.50f
    const val MAX_SPEED = 1.50f
    const val SPEED_STEP = 0.05f
    const val MIN_REVERB = 0.00f
    const val MAX_REVERB = 1.00f
    const val REVERB_STEP = 0.05f

    fun clampSpeed(speed: Float): Float =
        ((speed / SPEED_STEP).roundToInt() * SPEED_STEP)
            .takeIf { it.isFinite() }
            ?.coerceIn(MIN_SPEED, MAX_SPEED)
            ?: 1.0f

    fun clampRawSpeed(speed: Float): Float =
        speed.takeIf { it.isFinite() }?.coerceIn(MIN_SPEED, MAX_SPEED) ?: 1.0f

    fun clampReverb(reverb: Float): Float =
        reverb.takeIf { it.isFinite() }?.coerceIn(MIN_REVERB, MAX_REVERB) ?: 0f
}

/**
 * Curated starting points for Slowed + Reverb, Nightcore, and Sped Up listening.
 *
 * [CUSTOM] is derived automatically when the user adjusts speed, pitch lock, or
 * reverb away from a named preset — never stored separately so resetting the
 * sliders back to a preset's values cleanly re-selects that preset.
 */
enum class VarispeedPreset(
    val speed: Float,
    val pitchLinked: Boolean,
    val reverb: Float,
) {
    OFF(speed = 1.00f, pitchLinked = false, reverb = 0.00f),
    SLOWED_REVERB(speed = 0.85f, pitchLinked = true, reverb = 0.35f),
    NIGHTCORE(speed = 1.22f, pitchLinked = true, reverb = 0.00f),
    SPED_UP_REVERB(speed = 1.20f, pitchLinked = true, reverb = 0.22f),
    CUSTOM(speed = 1.00f, pitchLinked = false, reverb = 0.00f),
    ;

    companion object {
        private const val EPS = 0.005f

        /** Named presets presented to the user (excludes synthetic [CUSTOM]). */
        val SELECTABLE: List<VarispeedPreset> = listOf(
            OFF,
            SLOWED_REVERB,
            NIGHTCORE,
            SPED_UP_REVERB,
        )

        fun matching(
            speed: Float,
            pitchLinked: Boolean,
            reverb: Float,
        ): VarispeedPreset = SELECTABLE.firstOrNull { preset ->
            abs(preset.speed - speed) < EPS &&
                preset.pitchLinked == pitchLinked &&
                abs(preset.reverb - reverb) < EPS
        } ?: CUSTOM
    }
}
