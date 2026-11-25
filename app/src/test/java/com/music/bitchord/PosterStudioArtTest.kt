package com.music.bitchord

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.music.bitchord.ui.poster.DepthStroke
import com.music.bitchord.ui.poster.POSTER_CARD_BASE_WIDTH_DP
import com.music.bitchord.ui.poster.POSTER_CARD_DEFAULT_SCALE
import com.music.bitchord.ui.poster.POSTER_CARD_MAX_SCALE
import com.music.bitchord.ui.poster.POSTER_CARD_MIN_SCALE
import com.music.bitchord.ui.poster.POSTER_LINE_PALETTE
import com.music.bitchord.ui.poster.POSTER_TINT_PALETTE
import com.music.bitchord.ui.poster.Poster3dPreset
import com.music.bitchord.ui.poster.PosterAspectRatio
import com.music.bitchord.ui.poster.PosterBackgroundMode
import com.music.bitchord.ui.poster.PosterCardStyle
import com.music.bitchord.ui.poster.PosterColorFilter
import com.music.bitchord.ui.poster.PosterFontStyle
import com.music.bitchord.ui.poster.PosterStickerKind
import com.music.bitchord.ui.poster.composeColorToHex
import com.music.bitchord.ui.poster.composeColorToHsv
import com.music.bitchord.ui.poster.hsvToComposeColor
import com.music.bitchord.ui.poster.posterCardEffectiveScale
import com.music.bitchord.ui.poster.posterCardViewportScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterStudioArtTest {

    @Test
    fun `poster studio exposes all 7 card styles and 13 sticker kinds`() {
        assertEquals(7, PosterCardStyle.entries.size)
        assertTrue(PosterCardStyle.DARK_PLAYER in PosterCardStyle.entries)
        assertTrue(PosterCardStyle.LIQUID_GLASS in PosterCardStyle.entries)
        assertTrue(PosterCardStyle.APPLE_LYRIC in PosterCardStyle.entries)
        assertTrue(PosterCardStyle.VINYL in PosterCardStyle.entries)
        assertTrue(PosterCardStyle.CASSETTE in PosterCardStyle.entries)
        assertTrue(PosterCardStyle.POLAROID in PosterCardStyle.entries)
        assertTrue(PosterCardStyle.MINIMAL in PosterCardStyle.entries)

        assertEquals(13, PosterStickerKind.entries.size)
        assertTrue(PosterStickerKind.KILAU in PosterStickerKind.entries)
        assertTrue(PosterStickerKind.CUSTOM_IMAGE in PosterStickerKind.entries)
    }

    @Test
    fun `color filter matrices match 4x5 RGBA dimensions`() {
        assertNull(PosterColorFilter.ORIGINAL.matrixValues)
        for (filter in PosterColorFilter.entries) {
            if (filter == PosterColorFilter.ORIGINAL) continue
            val m = filter.matrixValues
            assertEquals("Expected 20-float ColorMatrix for ${filter.name}", 20, m?.size)
        }
    }

    @Test
    fun `aspect ratios tint palette and background modes are valid`() {
        assertEquals(9f / 16f, PosterAspectRatio.STORY_9_16.ratio, 0.001f)
        assertEquals(4f / 5f, PosterAspectRatio.PORTRAIT_4_5.ratio, 0.001f)
        assertEquals(1f, PosterAspectRatio.SQUARE_1_1.ratio, 0.001f)
        assertEquals(3, PosterBackgroundMode.entries.size)
        assertEquals(4, PosterFontStyle.entries.size)
        assertTrue(POSTER_TINT_PALETTE.size >= 8)

        val stroke = DepthStroke(
            points = listOf(Offset(0.2f, 0.3f), Offset(0.5f, 0.6f)),
            radiusNorm = 0.06f,
            erase = false,
        )
        assertEquals(2, stroke.points.size)
        assertEquals(0.06f, stroke.radiusNorm, 0.0001f)
    }

    @Test
    fun `poster 3d presets and free HSV color helpers work cleanly`() {
        assertEquals(5, Poster3dPreset.entries.size)
        assertEquals(0f, Poster3dPreset.FLAT.depth3d, 0.0001f)
        assertTrue(Poster3dPreset.TILT_LEFT.depth3d >= 0.8f)
        assertTrue(Poster3dPreset.DRAMATIC.depth3d == 1.0f)
        assertTrue(POSTER_LINE_PALETTE.size >= 8)

        val cyan = Color(0xFF38BDF8)
        val hsv = composeColorToHsv(cyan)
        val rebuilt = hsvToComposeColor(hsv[0], hsv[1], hsv[2])
        assertEquals(composeColorToHex(cyan), composeColorToHex(rebuilt))
        assertEquals("#FFFFFF", composeColorToHex(Color.White))
    }

    @Test
    fun `poster card sizing scales proportionally and supports tiny shrink range`() {
        assertTrue(POSTER_CARD_BASE_WIDTH_DP < 192f)
        assertTrue(POSTER_CARD_DEFAULT_SCALE < 1f)
        assertTrue(POSTER_CARD_MIN_SCALE <= 0.20f)
        assertTrue(POSTER_CARD_MAX_SCALE >= 2.00f)

        val phoneStageScale = posterCardViewportScale(240f, 426f)
        val tabletStageScale = posterCardViewportScale(480f, 480f)
        assertTrue(phoneStageScale < tabletStageScale)
        assertTrue(phoneStageScale in 0.68f..0.85f)

        val minRendered = posterCardEffectiveScale(POSTER_CARD_MIN_SCALE, phoneStageScale)
        val defaultRendered = posterCardEffectiveScale(POSTER_CARD_DEFAULT_SCALE, phoneStageScale)
        assertTrue(minRendered < 0.25f)
        assertTrue(defaultRendered in 0.50f..0.75f)
    }
}
