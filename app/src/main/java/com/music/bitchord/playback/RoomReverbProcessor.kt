package com.music.bitchord.playback

import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.FloatAudioProcessor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Warm stereo Schroeder/Freeverb room reverb operating purely in Float32 inside
 * [com.music.bitchord.playback.audio.DspChain].
 *
 * Designed specifically for real-time *Slowed + Reverb* and *Sped Up + Reverb*
 * playback:
 * - **Pre-delay (~18 ms)** keeps vocal transients forward and crisp ahead of the wash.
 * - **Wet-send high-pass (~130 Hz)** keeps slowed basslines tight instead of muddy.
 * - **6 parallel damped comb filters + 3 series allpass diffusers per channel**
 *   scaled to any input sample rate (44.1 kHz through 192 kHz) with stereo
 *   spread offsets so the acoustic tail blooms wide without metallic ringing.
 * - **Zero-cost bit-exact bypass** when [wetMix] is 0 and the wet glide has
 *   settled, returning before reading a single sample so [DspChain]'s bit-exact
 *   contract holds.
 */
class RoomReverbProcessor : FloatAudioProcessor {

    @Volatile
    var wetMix: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    val isEnabled: Boolean
        get() = wetMix > BYPASS_EPSILON || currentWet > BYPASS_EPSILON

    private var sampleRate: Int = 0
    private var channelCount: Int = 0
    private var currentWet: Float = 0f
    private var glideAlpha: Float = 0.01f

    // Wet input high-pass (~130 Hz) to keep low-end clean on slowed tracks.
    private var hpAlpha: Float = 0.98f
    private var hpPrevInL: Float = 0f
    private var hpPrevOutL: Float = 0f
    private var hpPrevInR: Float = 0f
    private var hpPrevOutR: Float = 0f

    // 18 ms stereo pre-delay lines.
    private var preDelayL = FloatArray(0)
    private var preDelayR = FloatArray(0)
    private var preDelayIdxL = 0
    private var preDelayIdxR = 0

    // Left / Right comb and allpass networks.
    private var combsL: Array<CombFilter> = emptyArray()
    private var combsR: Array<CombFilter> = emptyArray()
    private var allpassesL: Array<AllpassFilter> = emptyArray()
    private var allpassesR: Array<AllpassFilter> = emptyArray()

    override fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount

        if (sampleRate <= 0 || channelCount <= 0) {
            resetBuffers()
            return
        }

        val sr = sampleRate.toDouble()
        val rateScale = sr / REFERENCE_SAMPLE_RATE

        glideAlpha = (1.0 - exp(-1.0 / (GLIDE_TIME_SEC * sr))).toFloat().coerceIn(0.0001f, 1f)
        hpAlpha = exp(-2.0 * PI * WET_HPF_HZ / sr).toFloat().coerceIn(0f, 0.9999f)

        val preDelayLen = (sr * PRE_DELAY_SEC).roundToInt().coerceAtLeast(1)
        preDelayL = FloatArray(preDelayLen)
        preDelayR = FloatArray(preDelayLen)
        preDelayIdxL = 0
        preDelayIdxR = 0

        val spread = (STEREO_SPREAD_44K * rateScale).roundToInt().coerceAtLeast(1)
        combsL = Array(COMB_TUNINGS_44K.size) { i ->
            CombFilter((COMB_TUNINGS_44K[i] * rateScale).roundToInt().coerceAtLeast(8))
        }
        combsR = Array(COMB_TUNINGS_44K.size) { i ->
            CombFilter(((COMB_TUNINGS_44K[i] * rateScale).roundToInt() + spread).coerceAtLeast(8))
        }
        allpassesL = Array(ALLPASS_TUNINGS_44K.size) { i ->
            AllpassFilter((ALLPASS_TUNINGS_44K[i] * rateScale).roundToInt().coerceAtLeast(4))
        }
        allpassesR = Array(ALLPASS_TUNINGS_44K.size) { i ->
            AllpassFilter(((ALLPASS_TUNINGS_44K[i] * rateScale).roundToInt() + spread).coerceAtLeast(4))
        }

        currentWet = wetMix
        clearState()
    }

    override fun process(block: AudioBlock) {
        val frames = block.frameCount
        if (frames == 0 || sampleRate <= 0 || channelCount <= 0) return
        val target = wetMix
        if (target <= BYPASS_EPSILON && currentWet <= BYPASS_EPSILON) {
            return
        }
        if (combsL.isEmpty()) return

        val samples = block.samples
        val ch = block.channelCount
        val gAlpha = glideAlpha
        val hAlpha = hpAlpha
        val preLen = preDelayL.size

        if (ch == 1) {
            var wet = currentWet
            var prevIn = hpPrevInL
            var prevOut = hpPrevOutL
            var preIdx = preDelayIdxL

            for (f in 0 until frames) {
                wet += gAlpha * (target - wet)
                if (abs(target - wet) < GLIDE_SNAP_EPS) wet = target

                val dry = samples[f]
                val hp = hAlpha * (prevOut + dry - prevIn)
                prevIn = dry
                prevOut = if (abs(hp) < DENORMAL_FLOOR) 0f else hp

                val delayed = preDelayL[preIdx]
                preDelayL[preIdx] = prevOut
                preIdx++
                if (preIdx >= preLen) preIdx = 0

                var acc = 0f
                for (c in combsL.indices) {
                    acc += combsL[c].process(delayed, ROOM_FEEDBACK, ROOM_DAMPING)
                }
                for (a in allpassesL.indices) {
                    acc = allpassesL[a].process(acc)
                }

                val dryGain = 1f - DRY_ATTENUATION * wet
                samples[f] = dry * dryGain + acc * WET_SCALE * wet
            }

            currentWet = wet
            hpPrevInL = prevIn
            hpPrevOutL = prevOut
            preDelayIdxL = preIdx
        } else {
            // Stereo or multichannel (processes front L/R channels 0 and 1).
            var wet = currentWet
            var prevInL = hpPrevInL
            var prevOutL = hpPrevOutL
            var prevInR = hpPrevInR
            var prevOutR = hpPrevOutR
            var preIdxL = preDelayIdxL
            var preIdxR = preDelayIdxR
            var idx = 0

            for (f in 0 until frames) {
                wet += gAlpha * (target - wet)
                if (abs(target - wet) < GLIDE_SNAP_EPS) wet = target

                val dryL = samples[idx]
                val dryR = samples[idx + 1]

                // Slight cross-feed into the wet send (85% own + 15% opposite) fills mono-panned vocals naturally.
                val sendL = dryL * 0.85f + dryR * 0.15f
                val sendR = dryR * 0.85f + dryL * 0.15f

                val hpL = hAlpha * (prevOutL + sendL - prevInL)
                prevInL = sendL
                prevOutL = if (abs(hpL) < DENORMAL_FLOOR) 0f else hpL

                val hpR = hAlpha * (prevOutR + sendR - prevInR)
                prevInR = sendR
                prevOutR = if (abs(hpR) < DENORMAL_FLOOR) 0f else hpR

                val delayedL = preDelayL[preIdxL]
                preDelayL[preIdxL] = prevOutL
                preIdxL++
                if (preIdxL >= preLen) preIdxL = 0

                val delayedR = preDelayR[preIdxR]
                preDelayR[preIdxR] = prevOutR
                preIdxR++
                if (preIdxR >= preLen) preIdxR = 0

                var accL = 0f
                var accR = 0f
                for (c in combsL.indices) {
                    accL += combsL[c].process(delayedL, ROOM_FEEDBACK, ROOM_DAMPING)
                    accR += combsR[c].process(delayedR, ROOM_FEEDBACK, ROOM_DAMPING)
                }
                for (a in allpassesL.indices) {
                    accL = allpassesL[a].process(accL)
                    accR = allpassesR[a].process(accR)
                }

                val dryGain = 1f - DRY_ATTENUATION * wet
                val wGain = WET_SCALE * wet
                samples[idx] = dryL * dryGain + accL * wGain
                samples[idx + 1] = dryR * dryGain + accR * wGain
                idx += ch
            }

            currentWet = wet
            hpPrevInL = prevInL
            hpPrevOutL = prevOutL
            hpPrevInR = prevInR
            hpPrevOutR = prevOutR
            preDelayIdxL = preIdxL
            preDelayIdxR = preIdxR
        }

        // If we finished ramping down to zero, clear filter tails so re-enabling starts fresh.
        if (target <= BYPASS_EPSILON && currentWet <= BYPASS_EPSILON) {
            currentWet = 0f
            clearState()
        }
    }

    override fun flush() {
        currentWet = wetMix
        clearState()
    }

    override fun reset() {
        currentWet = 0f
        sampleRate = 0
        channelCount = 0
        resetBuffers()
    }

    private fun clearState() {
        hpPrevInL = 0f
        hpPrevOutL = 0f
        hpPrevInR = 0f
        hpPrevOutR = 0f
        preDelayL.fill(0f)
        preDelayR.fill(0f)
        preDelayIdxL = 0
        preDelayIdxR = 0
        combsL.forEach { it.clear() }
        combsR.forEach { it.clear() }
        allpassesL.forEach { it.clear() }
        allpassesR.forEach { it.clear() }
    }

    private fun resetBuffers() {
        preDelayL = FloatArray(0)
        preDelayR = FloatArray(0)
        combsL = emptyArray()
        combsR = emptyArray()
        allpassesL = emptyArray()
        allpassesR = emptyArray()
        hpPrevInL = 0f
        hpPrevOutL = 0f
        hpPrevInR = 0f
        hpPrevOutR = 0f
    }

    private class CombFilter(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0
        private var filterStore = 0f

        fun process(input: Float, feedback: Float, damp: Float): Float {
            val output = buffer[index]
            val damped = output * (1f - damp) + filterStore * damp
            filterStore = if (abs(damped) < DENORMAL_FLOOR) 0f else damped
            val next = input + filterStore * feedback
            buffer[index] = if (abs(next) < DENORMAL_FLOOR) 0f else next
            index++
            if (index >= buffer.size) index = 0
            return output
        }

        fun clear() {
            buffer.fill(0f)
            index = 0
            filterStore = 0f
        }
    }

    private class AllpassFilter(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0

        fun process(input: Float): Float {
            val bufOut = buffer[index]
            // True unity-gain Schroeder allpass: H(z) = (-g + z^-N) / (1 - g z^-N),
            // giving flat all-frequency magnitude |H(e^jw)| == 1 without Freeverb's +4 dB HF boost.
            val next = input + bufOut * ALLPASS_FEEDBACK
            val output = bufOut - next * ALLPASS_FEEDBACK
            buffer[index] = if (abs(next) < DENORMAL_FLOOR) 0f else next
            index++
            if (index >= buffer.size) index = 0
            return output
        }

        fun clear() {
            buffer.fill(0f)
            index = 0
        }
    }

    private companion object {
        const val REFERENCE_SAMPLE_RATE = 44_100.0
        const val PRE_DELAY_SEC = 0.018
        const val GLIDE_TIME_SEC = 0.025
        const val WET_HPF_HZ = 130.0

        // Six well-spaced Freeverb-inspired comb lengths + three allpass diffusers.
        val COMB_TUNINGS_44K = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491)
        val ALLPASS_TUNINGS_44K = intArrayOf(556, 441, 341)
        const val STEREO_SPREAD_44K = 23

        const val ROOM_FEEDBACK = 0.84f
        const val ROOM_DAMPING = 0.44f
        const val ALLPASS_FEEDBACK = 0.50f

        // Calibrates 6-comb sum (~-13.5 dB wet/dry RMS at 35% Slowed + Reverb).
        const val WET_SCALE = 0.135f
        const val DRY_ATTENUATION = 0.16f

        const val BYPASS_EPSILON = 0.0001f
        const val GLIDE_SNAP_EPS = 0.00002f
        const val DENORMAL_FLOOR = 1e-18f
    }
}
