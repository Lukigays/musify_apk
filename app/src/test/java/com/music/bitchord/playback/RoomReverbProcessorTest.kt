package com.music.bitchord.playback

import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.DspChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class RoomReverbProcessorTest {

    @Test
    fun `bypass at zero mix leaves audio samples bit-identical`() {
        val reverb = RoomReverbProcessor()
        reverb.wetMix = 0f
        reverb.configure(44_100, 2)
        reverb.flush()

        val frames = 256
        val block = AudioBlock(channelCount = 2, capacityFrames = frames)
        val original = FloatArray(frames * 2)
        for (f in 0 until frames) {
            val l = (0.3 * sin(2.0 * PI * 440.0 * f / 44_100.0)).toFloat()
            val r = (0.3 * sin(2.0 * PI * 660.0 * f / 44_100.0)).toFloat()
            block.samples[f * 2] = l
            block.samples[f * 2 + 1] = r
            original[f * 2] = l
            original[f * 2 + 1] = r
        }
        block.reset(frames)

        assertFalse(reverb.isEnabled)
        reverb.process(block)

        for (i in original.indices) {
            assertEquals("Sample $i mutated during bypass", original[i], block.samples[i], 0f)
        }
    }

    @Test
    fun `impulse produces smooth stereo reverb tail that decays over time`() {
        val sampleRate = 48_000
        val reverb = RoomReverbProcessor()
        reverb.wetMix = 0.35f
        reverb.configure(sampleRate, 2)
        reverb.flush()
        assertTrue(reverb.isEnabled)

        // Pre-delay is 18 ms (~864 frames at 48 kHz), followed by dense comb/allpass decay.
        val totalFrames = sampleRate // 1.0 s
        val block = AudioBlock(channelCount = 2, capacityFrames = totalFrames)
        block.samples[0] = 0.8f
        block.samples[1] = 0.8f
        block.reset(totalFrames)

        reverb.process(block)

        var earlyPeak = 0f
        var midTailRms = 0.0
        var lateTailRms = 0.0
        var stereoDiffSum = 0.0

        val preDelayFrames = (0.018 * sampleRate).toInt()
        for (f in 0 until totalFrames) {
            val l = block.samples[f * 2]
            val r = block.samples[f * 2 + 1]
            assertTrue("NaN or Infinite at frame $f", l.isFinite() && r.isFinite())
            assertTrue("Extreme output amplitude at frame $f", abs(l) < 1.5f && abs(r) < 1.5f)
            if (f > preDelayFrames) {
                earlyPeak = maxOf(earlyPeak, abs(l), abs(r))
                stereoDiffSum += abs(l - r).toDouble()
            }
            if (f in (sampleRate / 10)..(sampleRate / 5)) {
                midTailRms += l * l + r * r
            }
            if (f in (sampleRate * 4 / 5) until totalFrames) {
                lateTailRms += l * l + r * r
            }
        }

        val midRms = sqrt(midTailRms / (sampleRate / 10 * 2))
        val lateRms = sqrt(lateTailRms / (sampleRate / 5 * 2))
        assertTrue("Reverb tail should emerge after pre-delay (peak=$earlyPeak)", earlyPeak > 0.01f)
        assertTrue("Reverb tail should carry stereo decorrelation ($stereoDiffSum)", stereoDiffSum > 0.1)
        assertTrue("Late tail ($lateRms) should decay below mid tail ($midRms)", lateRms < midRms * 0.5)
    }

    @Test
    fun `flush clears lingering reverb tail cleanly`() {
        val reverb = RoomReverbProcessor()
        reverb.wetMix = 0.5f
        reverb.configure(44_100, 2)
        reverb.flush()

        val frames = 2048
        val block = AudioBlock(channelCount = 2, capacityFrames = frames)
        for (f in 0 until 512) {
            block.samples[f * 2] = 0.5f
            block.samples[f * 2 + 1] = 0.5f
        }
        block.reset(frames)
        reverb.process(block)

        reverb.flush()
        block.samples.fill(0f)
        block.reset(frames)
        reverb.process(block)

        for (f in 0 until frames) {
            assertEquals(0f, block.samples[f * 2], 1e-7f)
            assertEquals(0f, block.samples[f * 2 + 1], 1e-7f)
        }
    }

    @Test
    fun `supports mono input and high sample rates without clipping or NaN`() {
        for (sr in intArrayOf(44_100, 48_000, 96_000, 192_000)) {
            val reverb = RoomReverbProcessor()
            reverb.wetMix = 1.0f
            reverb.configure(sr, 1)
            reverb.flush()

            val frames = 4096
            val block = AudioBlock(channelCount = 1, capacityFrames = frames)
            for (f in 0 until frames) {
                block.samples[f] = (0.4 * sin(2.0 * PI * 880.0 * f / sr)).toFloat()
            }
            block.reset(frames)
            reverb.process(block)

            for (f in 0 until frames) {
                assertTrue("Non-finite sample at sr=$sr frame=$f", block.samples[f].isFinite())
                assertTrue("Sample clipped excessively at sr=$sr", abs(block.samples[f]) < 1.5f)
            }
        }
    }

    @Test
    fun `dsp chain activates loudness limiter when reverb is active`() {
        val chain = DspChain()
        chain.configure(44_100, 2)
        chain.reverb.wetMix = 0.35f
        chain.flush()

        val frames = 512
        val block = AudioBlock(channelCount = 2, capacityFrames = frames)
        for (f in 0 until frames) {
            block.samples[f * 2] = 0.2f
            block.samples[f * 2 + 1] = 0.2f
        }
        block.reset(frames)
        chain.process(block)

        assertTrue(chain.loudness.holdLimiter)

        chain.reverb.wetMix = 0f
        chain.flush()
        block.reset(frames)
        chain.process(block)
        assertFalse(chain.loudness.holdLimiter)
    }
}
