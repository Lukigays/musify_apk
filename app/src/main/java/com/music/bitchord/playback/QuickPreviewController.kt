package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class QuickPreviewStatus {
    IDLE,
    LOADING,
    PLAYING,
    PAUSED,
    ERROR,
}

enum class QuickPreviewStartSource {
    BEGINNING,
    BEST_PART,
}

data class QuickPreviewState(
    val song: Song? = null,
    val status: QuickPreviewStatus = QuickPreviewStatus.IDLE,
    val startMs: Long = 0L,
    val elapsedMs: Long = 0L,
    val durationMs: Long = QuickPreviewController.DEFAULT_DURATION_SEC * 1000L,
    val startSource: QuickPreviewStartSource = QuickPreviewStartSource.BEGINNING,
    val errorMessage: String? = null,
) {
    val isActive: Boolean
        get() = song != null && status != QuickPreviewStatus.IDLE

    val progress: Float
        get() = if (durationMs <= 0L) 0f else (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * Shared runtime state and pure math helpers for **Quick Preview (Audio Ducking)**.
 *
 * The isolated preview player lives in [PlaybackService] so it never touches the active
 * queue, playback history, Last.fm/ListenBrainz scrobbling, Discord RPC, notification
 * metadata, or system audio focus. While active, [duckGain] scales the main and
 * crossfade players smoothly down to [AppSettings.quickPreviewDuckVolume] and eases back
 * to `1.0f` the instant the preview ends or is dismissed.
 */
object QuickPreviewController {
    const val MIN_DURATION_SEC = 5
    const val DEFAULT_DURATION_SEC = 15
    const val MAX_DURATION_SEC = 45
    val DURATION_OPTIONS_SEC = listOf(10, 15, 20, 30)

    const val MIN_DUCK_VOLUME = 0f
    const val DEFAULT_DUCK_VOLUME = 0.15f
    const val MAX_DUCK_VOLUME = 0.40f
    val DUCK_VOLUME_STEPS = listOf(0.05f, 0.10f, 0.15f, 0.25f, 0.35f)

    @Volatile
    var duckGain: Float = 1f

    private val _state = MutableStateFlow(QuickPreviewState())
    val state: StateFlow<QuickPreviewState> = _state.asStateFlow()

    fun clampDurationSec(seconds: Int): Int =
        seconds.coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC)

    fun clampDuckVolume(volume: Float): Float =
        if (!volume.isFinite()) DEFAULT_DUCK_VOLUME
        else volume.coerceIn(MIN_DUCK_VOLUME, MAX_DUCK_VOLUME)

    /**
     * Picks the preview start offset, preferring [bestPartMs] (heatmap chorus / lyric hook)
     * when it leaves enough runway before the end of the track.
     */
    fun previewWindowStart(
        trackDurationMs: Long,
        bestPartMs: Long?,
        previewDurationMs: Long,
    ): Pair<Long, QuickPreviewStartSource> {
        if (bestPartMs == null || bestPartMs <= 0L) {
            return 0L to QuickPreviewStartSource.BEGINNING
        }
        if (trackDurationMs <= 0L) {
            return bestPartMs to QuickPreviewStartSource.BEST_PART
        }
        val minRunwayMs = (previewDurationMs / 2L).coerceAtLeast(3_000L)
        if (trackDurationMs <= minRunwayMs) {
            return 0L to QuickPreviewStartSource.BEGINNING
        }
        val maxStart = (trackDurationMs - minRunwayMs).coerceAtLeast(0L)
        return bestPartMs.coerceAtMost(maxStart) to QuickPreviewStartSource.BEST_PART
    }

    /** Smoothstep interpolation in `0f..1f` for non-jarring volume ducking ramps. */
    fun lerpGain(from: Float, to: Float, fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t)
        return (from + (to - from) * eased).coerceIn(0f, 1f)
    }

    fun formatDuration(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return "%d:%02d".format(minutes, seconds)
    }

    internal fun update(transform: (QuickPreviewState) -> QuickPreviewState) {
        _state.value = transform(_state.value)
    }

    internal fun reset() {
        duckGain = 1f
        _state.value = QuickPreviewState()
    }
}
