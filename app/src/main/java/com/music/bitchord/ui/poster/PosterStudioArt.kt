package com.music.bitchord.ui.poster

import android.graphics.BlurMaskFilter
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCameraBack
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.Song
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

enum class PosterCardStyle(val label: String, val icon: ImageVector) {
    DARK_PLAYER("Dark Player", Icons.Rounded.SmartDisplay),
    LIQUID_GLASS("Liquid Glass", Icons.Rounded.BlurOn),
    APPLE_LYRIC("Kartu Lirik", Icons.Rounded.FormatQuote),
    VINYL("Vinyl", Icons.Rounded.Album),
    CASSETTE("Kaset Retro", Icons.Rounded.Voicemail),
    POLAROID("Polaroid", Icons.Rounded.PhotoCameraBack),
    MINIMAL("Minimalis", Icons.Rounded.Title),
}

enum class Poster3dPreset(
    val label: String,
    val tiltX: Float,
    val tiltY: Float,
    val depth3d: Float,
) {
    FLAT("2D Datar", 0f, 0f, 0f),
    TILT_LEFT("3D Kiri", 9f, -18f, 0.85f),
    TILT_RIGHT("3D Kanan", 9f, 18f, 0.85f),
    TILT_UP("3D Atas", -16f, 10f, 0.90f),
    DRAMATIC("3D Dramatis", 16f, -26f, 1.0f),
}

enum class PosterStickerKind(val label: String) {
    KILAU("Kilau"),
    BURUNG("Burung"),
    BINTANG("Bintang"),
    HATI("Hati"),
    PITA("Pita"),
    NOT_MUSIK("Not Musik"),
    KUPU_KUPU("Kupu-kupu"),
    AWAN("Awan"),
    VINYL("Vinyl"),
    MAHKOTA("Mahkota"),
    PETIR("Petir"),
    TEKS("Teks"),
    CUSTOM_IMAGE("Gambar"),
}

enum class PosterColorFilter(val label: String, val matrixValues: FloatArray?) {
    ORIGINAL("Asli", null),
    WARM(
        "Hangat",
        floatArrayOf(
            1.12f, 0.04f, 0.0f, 0f, 8f,
            0.0f, 1.03f, 0.0f, 0f, 2f,
            0.0f, 0.0f, 0.88f, 0f, -6f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    COOL(
        "Dingin",
        floatArrayOf(
            0.90f, 0.0f, 0.04f, 0f, -4f,
            0.0f, 1.00f, 0.05f, 0f, 0f,
            0.04f, 0.04f, 1.15f, 0f, 8f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    VINTAGE(
        "Vintage",
        floatArrayOf(
            0.95f, 0.18f, 0.05f, 0f, 10f,
            0.08f, 0.88f, 0.05f, 0f, 6f,
            0.02f, 0.08f, 0.72f, 0f, 12f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    MONO(
        "Monokrom",
        floatArrayOf(
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    CINEMATIC(
        "Sinematik",
        floatArrayOf(
            1.12f, -0.04f, -0.02f, 0f, -8f,
            -0.02f, 1.06f, 0.02f, 0f, -4f,
            0.02f, 0.04f, 1.14f, 0f, 4f,
            0f, 0f, 0f, 1f, 0f,
        ),
    );

    fun toColorFilter(): ColorFilter? =
        matrixValues?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }
}

enum class PosterAspectRatio(val label: String, val ratio: Float) {
    STORY_9_16("9:16 Story", 9f / 16f),
    PORTRAIT_4_5("4:5 Feed", 4f / 5f),
    SQUARE_1_1("1:1 Square", 1f),
}

enum class PosterBackgroundMode(val label: String) {
    PHOTO("Foto Galeri"),
    ALBUM_BLUR("Album Blur"),
    GRADIENT("Gradien"),
}

enum class PosterFontStyle(val label: String, val family: FontFamily) {
    CLEAN("Clean Sans", FontFamily.SansSerif),
    EDITORIAL("Editorial", FontFamily.Serif),
    ROUNDED("Modern Bold", FontFamily.Default),
    MONO("Typewriter", FontFamily.Monospace),
}

data class DepthStroke(
    val points: List<Offset>,
    val radiusNorm: Float,
    val erase: Boolean = false,
)

fun buildStrokePath(stroke: DepthStroke, width: Float, height: Float): Path {
    val p = Path()
    if (stroke.points.isEmpty()) return p
    val r = stroke.radiusNorm * min(width, height)
    for (i in stroke.points.indices) {
        val pt = Offset(stroke.points[i].dxNorm(width), stroke.points[i].dyNorm(height))
        p.addOval(Rect(pt.x - r, pt.y - r, pt.x + r, pt.y + r))
        if (i > 0) {
            val prev = Offset(stroke.points[i - 1].dxNorm(width), stroke.points[i - 1].dyNorm(height))
            val dist = hypot((pt.x - prev.x).toDouble(), (pt.y - prev.y).toDouble()).toFloat()
            val segs = (dist / max(r * 0.45f, 1f)).roundToInt().coerceIn(1, 16)
            for (k in 1 until segs) {
                val t = k.toFloat() / segs.toFloat()
                val ix = prev.x + (pt.x - prev.x) * t
                val iy = prev.y + (pt.y - prev.y) * t
                p.addOval(Rect(ix - r, iy - r, ix + r, iy + r))
            }
        }
    }
    return p
}

private fun Offset.dxNorm(width: Float): Float = (x * width)
private fun Offset.dyNorm(height: Float): Float = (y * height)

/**
 * Combines chronological depth strokes into either an additive manual foreground mask
 * (`startFull = false`) or a subtractive auto-cutout mask (`startFull = true`).
 */
fun buildDepthMaskPath(
    strokes: List<DepthStroke>,
    width: Float,
    height: Float,
    startFull: Boolean,
): Path {
    var path = Path()
    if (startFull) {
        path.addRect(Rect(0f, 0f, width, height))
    }
    for (s in strokes) {
        if (s.points.isEmpty()) continue
        val sp = buildStrokePath(s, width, height)
        val combined = Path()
        combined.op(
            path1 = path,
            path2 = sp,
            operation = if (s.erase) PathOperation.Difference else PathOperation.Union,
        )
        path = combined
    }
    return path
}

val POSTER_TINT_PALETTE = listOf(
    Color(0xFFFA233B), // Musify Crimson
    Color(0xFF7C3AED), // Electric Violet
    Color(0xFF2563EB), // Cobalt Sky
    Color(0xFF0EA5E9), // Ocean Cyan
    Color(0xFF10B981), // Emerald
    Color(0xFFF59E0B), // Warm Amber
    Color(0xFFEC4899), // Rose Pink
    Color(0xFF1E293B), // Midnight Slate
)

val POSTER_LINE_PALETTE = listOf(
    Color.White,
    Color(0xFFFA233B),
    Color(0xFF38BDF8),
    Color(0xFFFACC15),
    Color(0xFF34D399),
    Color(0xFFA78BFA),
    Color(0xFFF472B6),
    Color(0xFF0F172A),
)

fun hsvToComposeColor(hue: Float, saturation: Float, value: Float, alpha: Float = 1f): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val c = v * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

fun composeColorToHsv(color: Color): FloatArray {
    val r = color.red.coerceIn(0f, 1f)
    val g = color.green.coerceIn(0f, 1f)
    val b = color.blue.coerceIn(0f, 1f)
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC
    val hue = when {
        delta < 1e-5f -> 0f
        maxC == r -> 60f * (((g - b) / delta) % 6f)
        maxC == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val sat = if (maxC < 1e-5f) 0f else (delta / maxC)
    return floatArrayOf(hue, sat, maxC)
}

fun composeColorToHex(color: Color): String {
    val r = (color.red * 255f).roundToInt().coerceIn(0, 255)
    val g = (color.green * 255f).roundToInt().coerceIn(0, 255)
    val b = (color.blue * 255f).roundToInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(r, g, b)
}

/**
 * Resolves card line color:
 * - Default pure white uses crisp subtle alpha for borders so glass cards stay translucent.
 * - Any user-selected custom color renders vividly so the chosen border/line color stands out clearly.
 */
private fun resolvedBorderLineColor(lineColor: Color, subtleWhiteAlpha: Float): Color {
    val isPureWhite = lineColor.red > 0.98f && lineColor.green > 0.98f && lineColor.blue > 0.98f
    return if (isPureWhite) {
        lineColor.copy(alpha = subtleWhiteAlpha)
    } else {
        lineColor.copy(alpha = 0.94f)
    }
}

private fun resolvedAccentLineColor(lineColor: Color, alpha: Float = 0.95f): Color =
    lineColor.copy(alpha = alpha)

@Composable
fun PosterSongCard(
    song: Song,
    style: PosterCardStyle,
    tint: Color,
    fontStyle: PosterFontStyle,
    showControls: Boolean,
    showHeart: Boolean,
    lines: List<String>,
    cardWidth: Dp = 192.dp,
    lineColor: Color = Color.White,
    lineWidthDp: Float = 1.4f,
    tiltX: Float = 0f,
    tiltY: Float = 0f,
    depth3d: Float = 0.85f,
) {
    val textMaker: (Float, FontWeight, Color) -> TextStyle = { sizeSp, weight, color ->
        TextStyle(
            fontFamily = fontStyle.family,
            fontSize = sizeSp.sp,
            fontWeight = weight,
            color = color,
        )
    }

    when (style) {
        PosterCardStyle.DARK_PLAYER -> DarkPlayerCard(
            song, tint, showControls, showHeart, lines, cardWidth,
            lineColor, lineWidthDp, tiltX, tiltY, depth3d, textMaker,
        )
        PosterCardStyle.LIQUID_GLASS -> LiquidGlassCard(
            song, tint, showControls, showHeart, lines, cardWidth,
            lineColor, lineWidthDp, tiltX, tiltY, depth3d, textMaker,
        )
        PosterCardStyle.APPLE_LYRIC -> AppleLyricCard(
            song, tint, lines, cardWidth,
            lineColor, lineWidthDp, tiltX, tiltY, depth3d, textMaker,
        )
        PosterCardStyle.VINYL -> VinylCard(
            song, tint, cardWidth,
            lineColor, lineWidthDp, tiltX, tiltY, depth3d, textMaker,
        )
        PosterCardStyle.CASSETTE -> CassetteCard(
            song, tint, cardWidth,
            lineColor, lineWidthDp, tiltX, tiltY, depth3d, textMaker,
        )
        PosterCardStyle.POLAROID -> PolaroidCard(
            song, tint, cardWidth,
            lineColor, lineWidthDp, tiltX, tiltY, depth3d, textMaker,
        )
        PosterCardStyle.MINIMAL -> MinimalCard(
            song, tint, cardWidth,
            lineColor, lineWidthDp, tiltX, tiltY, depth3d, textMaker,
        )
    }
}

/**
 * Adds unmistakable physical 3D depth to poster cards:
 * 1) Directional blurred 3D cast drop shadow offset opposite the card tilt.
 * 2) 12-layer extruded volumetric bevel sidewall shaded between deep shadow & metallic tint/line rim.
 * 3) Dynamic angled specular light sheen across the card surface matching tilt angles.
 */
@Composable
private fun Extruded3DCardWrapper(
    tiltX: Float,
    tiltY: Float,
    depth3d: Float,
    tint: Color,
    lineColor: Color,
    shape: Shape,
    cornerRadiusDp: Float = 18f,
    isCircle: Boolean = false,
    content: @Composable () -> Unit,
) {
    val d = depth3d.coerceIn(0f, 1f)
    val radX = tiltX * PI / 180.0
    val radY = tiltY * PI / 180.0
    val tiltIntensity = ((abs(tiltX) + abs(tiltY)) / 50f).coerceIn(0f, 1f)

    Box {
        if (d > 0.02f) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas

                val cornerPx = cornerRadiusDp.dp.toPx()
                val shadowSpreadPx = (14f + 16f * d).dp.toPx()
                val shadowDx = (-sin(radY).toFloat() * 34f * d).dp.toPx()
                val shadowDy = ((14f + sin(radX).toFloat() * 28f) * d).dp.toPx()

                // 1. Directional blurred 3D floating cast shadow
                drawIntoCanvas { canvas ->
                    val shadowPaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = Color.Black.copy(alpha = 0.42f + 0.24f * d).toArgb()
                        maskFilter = BlurMaskFilter(shadowSpreadPx.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
                    }
                    if (isCircle) {
                        val r = min(w, h) / 2f
                        canvas.nativeCanvas.drawCircle(w / 2f + shadowDx, h / 2f + shadowDy, r * 1.02f, shadowPaint)
                    } else {
                        val rect = android.graphics.RectF(
                            shadowDx - 2f,
                            shadowDy - 2f,
                            w + shadowDx + 2f,
                            h + shadowDy + 2f,
                        )
                        canvas.nativeCanvas.drawRoundRect(rect, cornerPx, cornerPx, shadowPaint)
                    }
                }

                // 2. 12-layer volumetric 3D extruded bevel sidewall
                val thicknessPx = (22f * d).dp.toPx()
                val baseLipPx = (3.5f * d).dp.toPx()
                val extrudeDx = -sin(radY).toFloat() * thicknessPx
                val extrudeDy = sin(radX).toFloat() * thicknessPx + baseLipPx
                val farEdgeColor = Color(0xFF07080D)
                val rimBaseColor = lerp(Color(0xFF1F2430), tint.copy(alpha = 1f), 0.34f)
                val litRimColor = lerp(rimBaseColor, lineColor.copy(alpha = 1f), 0.28f)

                val steps = 12
                for (i in steps downTo 1) {
                    val t = i.toFloat() / steps.toFloat()
                    val sx = extrudeDx * t
                    val sy = extrudeDy * t
                    val shrink = 1f - t * 0.012f
                    val insetW = w * (1f - shrink) * 0.5f
                    val insetH = h * (1f - shrink) * 0.5f
                    val sliceColor = lerp(litRimColor, farEdgeColor, t)

                    if (isCircle) {
                        val r = (min(w, h) / 2f) * shrink
                        drawCircle(
                            color = sliceColor,
                            radius = r,
                            center = Offset(w / 2f + sx, h / 2f + sy),
                        )
                    } else {
                        drawRoundRect(
                            color = sliceColor,
                            topLeft = Offset(sx + insetW, sy + insetH),
                            size = Size((w * shrink).coerceAtLeast(1f), (h * shrink).coerceAtLeast(1f)),
                            cornerRadius = CornerRadius(cornerPx * shrink),
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.clip(shape)) {
            content()
            if (d > 0.02f) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val w = size.width
                    val h = size.height
                    if (w <= 0f || h <= 0f) return@Canvas
                    val shiftX = (tiltY / 45f).coerceIn(-0.6f, 0.6f)
                    val shiftY = (-tiltX / 45f).coerceIn(-0.6f, 0.6f)
                    val sheenAlpha = (0.04f + 0.18f * tiltIntensity) * d
                    val shadeAlpha = (0.03f + 0.14f * tiltIntensity) * d
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = sheenAlpha),
                                Color.Transparent,
                                Color.Black.copy(alpha = shadeAlpha),
                            ),
                            start = Offset(w * (0.15f - shiftX * 0.6f), h * (0.10f - shiftY * 0.6f)),
                            end = Offset(w * (0.85f - shiftX * 0.6f), h * (0.90f - shiftY * 0.6f)),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterCoverArt(
    url: String?,
    size: Dp,
    cornerRadius: Dp,
    circle: Boolean = false,
    tiltX: Float = 0f,
    tiltY: Float = 0f,
    depth3d: Float = 0f,
    frameLineColor: Color? = null,
    frameLineWidthDp: Float = 0f,
) {
    val shape = if (circle) CircleShape else RoundedCornerShape(cornerRadius)
    val d = depth3d.coerceIn(0f, 1f)
    val density = LocalDensity.current
    val popX = with(density) { (tiltY * 0.18f * d).dp.toPx() }
    val popY = with(density) { (-tiltX * 0.15f * d).dp.toPx() }

    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                translationX = popX
                translationY = popY
            }
            .shadow(elevation = (4f + 10f * d).dp, shape = shape)
            .clip(shape)
            .background(Color(0xFF1F222A))
            .then(
                if (frameLineColor != null && frameLineWidthDp > 0.1f) {
                    Modifier.border(frameLineWidthDp.dp, frameLineColor, shape)
                } else {
                    Modifier
                },
            ),
    )
}

@Composable
private fun DarkPlayerCard(
    song: Song,
    tint: Color,
    showControls: Boolean,
    showHeart: Boolean,
    lines: List<String>,
    width: Dp,
    lineColor: Color,
    lineWidthDp: Float,
    tiltX: Float,
    tiltY: Float,
    depth3d: Float,
    ts: (Float, FontWeight, Color) -> TextStyle,
) {
    val artSize = width - 20.dp
    val shape = RoundedCornerShape(18.dp)
    val borderCol = resolvedBorderLineColor(lineColor, 0.28f)
    val accentLine = resolvedAccentLineColor(lineColor, 0.95f)

    Extruded3DCardWrapper(
        tiltX = tiltX,
        tiltY = tiltY,
        depth3d = depth3d,
        tint = tint,
        lineColor = lineColor,
        shape = shape,
        cornerRadiusDp = 18f,
    ) {
        Column(
            modifier = Modifier
                .width(width)
                .background(Color(0xE812141A))
                .border(lineWidthDp.dp, borderCol, shape)
                .padding(10.dp),
        ) {
            PosterCoverArt(
                url = song.thumbnailUrl,
                size = artSize,
                cornerRadius = 12.dp,
                tiltX = tiltX,
                tiltY = tiltY,
                depth3d = depth3d,
                frameLineColor = resolvedBorderLineColor(lineColor, 0.12f),
                frameLineWidthDp = (lineWidthDp * 0.55f).coerceAtLeast(0.5f),
            )
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = ts(12.5f, FontWeight.ExtraBold, Color.White),
                    )
                    Text(
                        text = song.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = ts(10f, FontWeight.Medium, Color.White.copy(alpha = 0.72f)),
                    )
                }
                if (showHeart) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            if (lines.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((lineWidthDp * 0.65f).coerceIn(0.6f, 2.2f).dp)
                        .background(accentLine.copy(alpha = 0.35f)),
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = lines.take(2).joinToString("\n"),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = ts(9.5f, FontWeight.SemiBold, Color.White.copy(alpha = 0.80f)).copy(lineHeight = 12.sp),
                )
            }
            if (showControls) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((lineWidthDp + 1.8f).coerceIn(2.6f, 5.2f).dp)
                        .clip(RoundedCornerShape(3.dp)),
                ) {
                    Box(Modifier.weight(0.42f).fillMaxHeight().background(accentLine))
                    Box(Modifier.weight(0.58f).fillMaxHeight().background(lineColor.copy(alpha = 0.24f)))
                }
                Spacer(Modifier.height(7.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(17.dp))
                    Icon(Icons.Rounded.Pause, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Icon(Icons.Rounded.SkipNext, null, tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

@Composable
private fun LiquidGlassCard(
    song: Song,
    tint: Color,
    showControls: Boolean,
    showHeart: Boolean,
    lines: List<String>,
    width: Dp,
    lineColor: Color,
    lineWidthDp: Float,
    tiltX: Float,
    tiltY: Float,
    depth3d: Float,
    ts: (Float, FontWeight, Color) -> TextStyle,
) {
    val shape = RoundedCornerShape(20.dp)
    val borderCol = resolvedBorderLineColor(lineColor, 0.38f)
    val accentLine = resolvedAccentLineColor(lineColor, 0.95f)

    Extruded3DCardWrapper(
        tiltX = tiltX,
        tiltY = tiltY,
        depth3d = depth3d,
        tint = tint,
        lineColor = lineColor,
        shape = shape,
        cornerRadiusDp = 20f,
    ) {
        Column(
            modifier = Modifier
                .width(width)
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            tint.copy(alpha = 0.36f),
                            Color.Black.copy(alpha = 0.48f),
                        ),
                    ),
                )
                .border(lineWidthDp.dp, borderCol, shape)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PosterCoverArt(
                    url = song.thumbnailUrl,
                    size = 46.dp,
                    cornerRadius = 10.dp,
                    tiltX = tiltX,
                    tiltY = tiltY,
                    depth3d = depth3d,
                    frameLineColor = borderCol.copy(alpha = 0.65f),
                    frameLineWidthDp = (lineWidthDp * 0.55f).coerceAtLeast(0.5f),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(12.5f, FontWeight.ExtraBold, Color.White))
                    Spacer(Modifier.height(2.dp))
                    Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(10.5f, FontWeight.Medium, Color.White.copy(alpha = 0.78f)))
                }
                if (showHeart) {
                    Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
            if (lines.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((lineWidthDp * 0.65f).coerceIn(0.6f, 2.2f).dp)
                        .background(accentLine.copy(alpha = 0.45f)),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = lines.take(3).joinToString("\n"),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = ts(11.5f, FontWeight.Bold, Color.White).copy(lineHeight = 15.sp),
                )
            }
            if (showControls) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = accentLine.copy(alpha = 0.85f), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height((lineWidthDp + 1.8f).coerceIn(2.6f, 5.2f).dp)
                            .clip(RoundedCornerShape(3.dp)),
                    ) {
                        Box(Modifier.weight(0.56f).fillMaxHeight().background(accentLine))
                        Box(Modifier.weight(0.44f).fillMaxHeight().background(lineColor.copy(alpha = 0.24f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun AppleLyricCard(
    song: Song,
    tint: Color,
    lines: List<String>,
    width: Dp,
    lineColor: Color,
    lineWidthDp: Float,
    tiltX: Float,
    tiltY: Float,
    depth3d: Float,
    ts: (Float, FontWeight, Color) -> TextStyle,
) {
    val shown = if (lines.isNotEmpty()) lines.take(4) else listOf(song.title, song.artist)
    val darkTint = Color(
        red = tint.red * 0.42f,
        green = tint.green * 0.42f,
        blue = tint.blue * 0.42f,
        alpha = 0.96f,
    )
    val shape = RoundedCornerShape(20.dp)
    val borderCol = resolvedBorderLineColor(lineColor, 0.28f)
    val accentLine = resolvedAccentLineColor(lineColor, 0.92f)

    Extruded3DCardWrapper(
        tiltX = tiltX,
        tiltY = tiltY,
        depth3d = depth3d,
        tint = tint,
        lineColor = lineColor,
        shape = shape,
        cornerRadiusDp = 20f,
    ) {
        Column(
            modifier = Modifier
                .width(width + 20.dp)
                .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.90f), darkTint)))
                .border(lineWidthDp.dp, borderCol, shape)
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PosterCoverArt(
                    url = song.thumbnailUrl,
                    size = 34.dp,
                    cornerRadius = 8.dp,
                    tiltX = tiltX,
                    tiltY = tiltY,
                    depth3d = depth3d,
                    frameLineColor = borderCol.copy(alpha = 0.6f),
                    frameLineWidthDp = (lineWidthDp * 0.5f).coerceAtLeast(0.5f),
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(11.5f, FontWeight.ExtraBold, Color.White))
                    Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(9.5f, FontWeight.Medium, Color.White.copy(alpha = 0.78f)))
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((lineWidthDp * 0.75f).coerceIn(0.8f, 2.4f).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentLine.copy(alpha = 0.55f)),
            )
            Spacer(Modifier.height(8.dp))
            shown.forEach { line ->
                Text(
                    text = line,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = ts(13.5f, FontWeight.ExtraBold, Color.White).copy(lineHeight = 17.sp),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun VinylCard(
    song: Song,
    tint: Color,
    width: Dp,
    lineColor: Color,
    lineWidthDp: Float,
    tiltX: Float,
    tiltY: Float,
    depth3d: Float,
    ts: (Float, FontWeight, Color) -> TextStyle,
) {
    val discSize = width * 0.90f
    val borderCol = resolvedBorderLineColor(lineColor, 0.30f)
    val grooveBaseAlpha = if (lineColor.red > 0.98f && lineColor.green > 0.98f && lineColor.blue > 0.98f) 0.16f else 0.52f

    Column(
        modifier = Modifier.width(width),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Extruded3DCardWrapper(
            tiltX = tiltX,
            tiltY = tiltY,
            depth3d = depth3d,
            tint = tint,
            lineColor = lineColor,
            shape = CircleShape,
            isCircle = true,
        ) {
            Box(
                modifier = Modifier
                    .size(discSize)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                Color(0xFF282B33),
                                Color(0xFF0A0B0E),
                                Color(0xFF1F222A),
                                Color(0xFF08090C),
                            ),
                        ),
                    )
                    .border(lineWidthDp.dp, borderCol, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val rBase = size.minDimension / 2f
                    val grooveWidthPx = (lineWidthDp * 0.72f).coerceIn(0.8f, 2.4f).dp.toPx()
                    for (f in listOf(0.58f, 0.68f, 0.78f, 0.88f)) {
                        drawCircle(
                            color = lineColor.copy(alpha = grooveBaseAlpha),
                            radius = rBase * f,
                            style = Stroke(width = grooveWidthPx),
                        )
                    }
                }
                PosterCoverArt(
                    url = song.thumbnailUrl,
                    size = discSize * 0.48f,
                    cornerRadius = 0.dp,
                    circle = true,
                    tiltX = tiltX,
                    tiltY = tiltY,
                    depth3d = depth3d * 0.75f,
                    frameLineColor = borderCol,
                    frameLineWidthDp = (lineWidthDp * 0.75f).coerceAtLeast(0.7f),
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .border(1.dp, lineColor.copy(alpha = 0.75f), CircleShape),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Extruded3DCardWrapper(
            tiltX = tiltX,
            tiltY = tiltY,
            depth3d = depth3d * 0.75f,
            tint = tint,
            lineColor = lineColor,
            shape = RoundedCornerShape(12.dp),
            cornerRadiusDp = 12f,
        ) {
            Column(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.78f))
                    .border((lineWidthDp * 0.75f).coerceAtLeast(0.7f).dp, borderCol, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(11f, FontWeight.ExtraBold, Color.White))
                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(9.5f, FontWeight.Medium, Color.White.copy(alpha = 0.78f)))
            }
        }
    }
}

@Composable
private fun CassetteCard(
    song: Song,
    tint: Color,
    width: Dp,
    lineColor: Color,
    lineWidthDp: Float,
    tiltX: Float,
    tiltY: Float,
    depth3d: Float,
    ts: (Float, FontWeight, Color) -> TextStyle,
) {
    val shape = RoundedCornerShape(14.dp)
    val borderCol = resolvedBorderLineColor(lineColor, 0.34f)
    val accentLine = resolvedAccentLineColor(lineColor, 0.88f)

    Extruded3DCardWrapper(
        tiltX = tiltX,
        tiltY = tiltY,
        depth3d = depth3d,
        tint = tint,
        lineColor = lineColor,
        shape = shape,
        cornerRadiusDp = 14f,
    ) {
        Column(
            modifier = Modifier
                .width(width + 16.dp)
                .background(Color(0xEE161922))
                .border(lineWidthDp.dp, borderCol, shape)
                .padding(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF5F1E6))
                    .border((lineWidthDp * 0.65f).coerceAtLeast(0.6f).dp, accentLine.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PosterCoverArt(song.thumbnailUrl, 30.dp, 5.dp, tiltX = tiltX, tiltY = tiltY, depth3d = depth3d * 0.4f)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(10.5f, FontWeight.ExtraBold, Color(0xDD000000)))
                    Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(9f, FontWeight.SemiBold, Color(0x88000000)))
                }
                Text("SIDE A", style = ts(8f, FontWeight.ExtraBold, Color(0x88000000)))
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border((lineWidthDp * 0.7f).coerceAtLeast(0.7f).dp, borderCol, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CassetteReel(accentLine, lineWidthDp)
                Box(
                    Modifier
                        .weight(1f)
                        .height(10.dp)
                        .padding(horizontal = 10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(tint.copy(alpha = 0.55f))
                        .border((lineWidthDp * 0.5f).coerceAtLeast(0.5f).dp, accentLine.copy(alpha = 0.55f), RoundedCornerShape(3.dp)),
                )
                CassetteReel(accentLine, lineWidthDp)
            }
        }
    }
}

@Composable
private fun CassetteReel(lineColor: Color, lineWidthDp: Float) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .border((lineWidthDp + 0.4f).coerceIn(1.2f, 3.0f).dp, lineColor.copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(lineColor))
    }
}

@Composable
private fun PolaroidCard(
    song: Song,
    tint: Color,
    width: Dp,
    lineColor: Color,
    lineWidthDp: Float,
    tiltX: Float,
    tiltY: Float,
    depth3d: Float,
    ts: (Float, FontWeight, Color) -> TextStyle,
) {
    val artSize = width - 18.dp
    val shape = RoundedCornerShape(6.dp)
    val isPureWhite = lineColor.red > 0.98f && lineColor.green > 0.98f && lineColor.blue > 0.98f
    val borderCol = if (isPureWhite) Color(0x28000000) else lineColor.copy(alpha = 0.95f)
    val accentLine = if (isPureWhite) Color(0x33000000) else lineColor.copy(alpha = 0.85f)

    Extruded3DCardWrapper(
        tiltX = tiltX,
        tiltY = tiltY,
        depth3d = depth3d,
        tint = tint,
        lineColor = lineColor,
        shape = shape,
        cornerRadiusDp = 6f,
    ) {
        Column(
            modifier = Modifier
                .width(width)
                .background(Color(0xFFF6F2E9))
                .border(lineWidthDp.dp, borderCol, shape)
                .padding(start = 9.dp, end = 9.dp, top = 9.dp, bottom = 14.dp),
        ) {
            PosterCoverArt(
                url = song.thumbnailUrl,
                size = artSize,
                cornerRadius = 3.dp,
                tiltX = tiltX,
                tiltY = tiltY,
                depth3d = depth3d * 0.55f,
                frameLineColor = accentLine,
                frameLineWidthDp = (lineWidthDp * 0.65f).coerceAtLeast(0.6f),
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((lineWidthDp * 0.6f).coerceIn(0.6f, 2.2f).dp)
                    .background(accentLine),
            )
            Spacer(Modifier.height(6.dp))
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(12f, FontWeight.ExtraBold, Color(0xFF15161A)))
            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(9.5f, FontWeight.SemiBold, Color(0xFF5A5D66)))
        }
    }
}

@Composable
private fun MinimalCard(
    song: Song,
    tint: Color,
    width: Dp,
    lineColor: Color,
    lineWidthDp: Float,
    tiltX: Float,
    tiltY: Float,
    depth3d: Float,
    ts: (Float, FontWeight, Color) -> TextStyle,
) {
    val accentLine = resolvedAccentLineColor(lineColor, 0.92f)
    Column(
        modifier = Modifier
            .width(width)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width((lineWidthDp + 1.4f).coerceIn(2f, 4.8f).dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentLine),
            )
            Spacer(Modifier.width(8.dp))
            Extruded3DCardWrapper(
                tiltX = tiltX,
                tiltY = tiltY,
                depth3d = depth3d,
                tint = tint,
                lineColor = lineColor,
                shape = RoundedCornerShape(10.dp),
                cornerRadiusDp = 10f,
            ) {
                PosterCoverArt(
                    url = song.thumbnailUrl,
                    size = 46.dp,
                    cornerRadius = 10.dp,
                    tiltX = tiltX,
                    tiltY = tiltY,
                    depth3d = depth3d * 0.65f,
                    frameLineColor = accentLine.copy(alpha = 0.72f),
                    frameLineWidthDp = (lineWidthDp * 0.6f).coerceAtLeast(0.6f),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(13f, FontWeight.ExtraBold, Color.White))
                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(10.5f, FontWeight.SemiBold, Color.White.copy(alpha = 0.78f)))
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height((lineWidthDp * 0.75f).coerceIn(0.8f, 2.4f).dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accentLine.copy(alpha = 0.65f)),
        )
    }
}

@Composable
fun PosterStickerGraphic(
    kind: PosterStickerKind,
    size: Dp,
    color: Color = Color.White,
    text: String = "",
    customImageUri: Uri? = null,
) {
    if (kind == PosterStickerKind.CUSTOM_IMAGE && customImageUri != null) {
        AsyncImage(
            model = customImageUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp)),
        )
        return
    }
    if (kind == PosterStickerKind.TEKS) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .border(0.9.dp, color.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = text.ifBlank { "vibes" },
                fontSize = (size.value * 0.28f).coerceIn(12f, 26f).sp,
                fontWeight = FontWeight.ExtraBold,
                color = color,
            )
        }
        return
    }

    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f

        when (kind) {
            PosterStickerKind.KILAU -> {
                drawIntoCanvas { canvas ->
                    val blur = android.graphics.Paint().apply {
                        isAntiAlias = true
                        this.color = color.copy(alpha = 0.42f).toArgb()
                        maskFilter = BlurMaskFilter((w * 0.14f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
                    }
                    canvas.nativeCanvas.drawCircle(cx, cy, w * 0.22f, blur)
                }
                val star = Path().apply {
                    moveTo(cx, h * 0.04f)
                    quadraticTo(cx, cy, w * 0.96f, cy)
                    quadraticTo(cx, cy, cx, h * 0.96f)
                    quadraticTo(cx, cy, w * 0.04f, cy)
                    quadraticTo(cx, cy, cx, h * 0.04f)
                    close()
                }
                drawPath(star, color)
                val sw = (w * 0.03f).coerceIn(1f, 3f)
                drawLine(color.copy(alpha = 0.65f), Offset(w * 0.28f, h * 0.28f), Offset(w * 0.72f, h * 0.72f), sw, StrokeCap.Round)
                drawLine(color.copy(alpha = 0.65f), Offset(w * 0.72f, h * 0.28f), Offset(w * 0.28f, h * 0.72f), sw, StrokeCap.Round)
            }

            PosterStickerKind.BURUNG -> {
                val stroke1 = Stroke((w * 0.075f).coerceIn(2f, 5f), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val b1 = Path().apply {
                    moveTo(w * 0.10f, h * 0.56f)
                    quadraticTo(w * 0.30f, h * 0.24f, w * 0.50f, h * 0.52f)
                    quadraticTo(w * 0.70f, h * 0.24f, w * 0.90f, h * 0.56f)
                }
                drawPath(b1, color, style = stroke1)
                val b2 = Path().apply {
                    moveTo(w * 0.30f, h * 0.74f)
                    quadraticTo(w * 0.42f, h * 0.58f, w * 0.54f, h * 0.72f)
                    quadraticTo(w * 0.66f, h * 0.58f, w * 0.78f, h * 0.74f)
                }
                drawPath(b2, color.copy(alpha = 0.78f), style = Stroke((w * 0.055f).coerceIn(1.6f, 3.8f), cap = StrokeCap.Round))
            }

            PosterStickerKind.BINTANG -> {
                val star = Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) w * 0.44f else w * 0.19f
                    val a = -PI / 2.0 + i * PI / 5.0
                    val x = cx + (cos(a) * r).toFloat()
                    val y = cy + (sin(a) * r).toFloat()
                    if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
                }
                star.close()
                drawPath(star, color)
            }

            PosterStickerKind.HATI -> {
                val heart = Path().apply {
                    moveTo(cx, h * 0.84f)
                    cubicTo(w * 0.08f, h * 0.56f, w * 0.06f, h * 0.18f, cx, h * 0.34f)
                    cubicTo(w * 0.94f, h * 0.18f, w * 0.92f, h * 0.56f, cx, h * 0.84f)
                    close()
                }
                drawPath(heart, color)
            }

            PosterStickerKind.PITA -> {
                val left = Path().apply {
                    moveTo(cx, cy)
                    cubicTo(w * 0.14f, h * 0.14f, w * 0.06f, h * 0.62f, cx, cy)
                }
                val right = Path().apply {
                    moveTo(cx, cy)
                    cubicTo(w * 0.86f, h * 0.14f, w * 0.94f, h * 0.62f, cx, cy)
                }
                drawPath(left, color)
                drawPath(right, color)
                val sw = (w * 0.08f).coerceIn(2.2f, 5f)
                drawLine(color, Offset(cx, cy), Offset(w * 0.28f, h * 0.88f), sw, StrokeCap.Round)
                drawLine(color, Offset(cx, cy), Offset(w * 0.72f, h * 0.88f), sw, StrokeCap.Round)
                drawCircle(color, w * 0.08f, Offset(cx, cy))
            }

            PosterStickerKind.NOT_MUSIK -> {
                val sw = (w * 0.075f).coerceIn(2.2f, 4.5f)
                drawLine(color, Offset(w * 0.36f, h * 0.72f), Offset(w * 0.36f, h * 0.24f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.74f, h * 0.64f), Offset(w * 0.74f, h * 0.18f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.36f, h * 0.24f), Offset(w * 0.74f, h * 0.18f), sw, StrokeCap.Round)
                drawOval(color, Offset(w * 0.17f, h * 0.66f), Size(w * 0.22f, h * 0.16f))
                drawOval(color, Offset(w * 0.55f, h * 0.58f), Size(w * 0.22f, h * 0.16f))
            }

            PosterStickerKind.KUPU_KUPU -> {
                val c = color.copy(alpha = 0.92f)
                drawOval(c, Offset(w * 0.11f, h * 0.21f), Size(w * 0.38f, h * 0.34f))
                drawOval(c, Offset(w * 0.51f, h * 0.21f), Size(w * 0.38f, h * 0.34f))
                drawOval(c, Offset(w * 0.21f, h * 0.52f), Size(w * 0.26f, h * 0.24f))
                drawOval(c, Offset(w * 0.53f, h * 0.52f), Size(w * 0.26f, h * 0.24f))
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.65f),
                    topLeft = Offset(cx - w * 0.04f, cy - h * 0.22f),
                    size = Size(w * 0.08f, h * 0.44f),
                    cornerRadius = CornerRadius(w * 0.04f),
                )
            }

            PosterStickerKind.AWAN -> {
                drawCircle(color, w * 0.20f, Offset(w * 0.34f, h * 0.56f))
                drawCircle(color, w * 0.24f, Offset(w * 0.54f, h * 0.46f))
                drawCircle(color, w * 0.17f, Offset(w * 0.72f, h * 0.58f))
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.18f, h * 0.56f),
                    size = Size(w * 0.68f, h * 0.19f),
                    cornerRadius = CornerRadius(w * 0.10f),
                )
            }

            PosterStickerKind.VINYL -> {
                drawCircle(Color(0xFF14161D), w * 0.44f, Offset(cx, cy))
                drawCircle(color, w * 0.44f, Offset(cx, cy), style = Stroke(1.6f))
                drawCircle(color, w * 0.18f, Offset(cx, cy))
                drawCircle(Color.Black, w * 0.05f, Offset(cx, cy))
            }

            PosterStickerKind.MAHKOTA -> {
                val crown = Path().apply {
                    moveTo(w * 0.14f, h * 0.74f)
                    lineTo(w * 0.18f, h * 0.30f)
                    lineTo(w * 0.38f, h * 0.52f)
                    lineTo(cx, h * 0.22f)
                    lineTo(w * 0.62f, h * 0.52f)
                    lineTo(w * 0.82f, h * 0.30f)
                    lineTo(w * 0.86f, h * 0.74f)
                    close()
                }
                drawPath(crown, color)
            }

            PosterStickerKind.PETIR -> {
                val bolt = Path().apply {
                    moveTo(w * 0.58f, h * 0.10f)
                    lineTo(w * 0.24f, h * 0.54f)
                    lineTo(w * 0.50f, h * 0.54f)
                    lineTo(w * 0.42f, h * 0.90f)
                    lineTo(w * 0.78f, h * 0.44f)
                    lineTo(w * 0.52f, h * 0.44f)
                    close()
                }
                drawPath(bolt, color)
            }

            PosterStickerKind.TEKS, PosterStickerKind.CUSTOM_IMAGE -> Unit
        }
    }
}

@Composable
fun PosterEffectsOverlay(
    sparkle: Float,
    grain: Float,
    vignette: Float,
    glow: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        if (glow > 0.01f) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.24f * glow), Color.Transparent),
                    center = Offset(w * 0.5f, h * 0.32f),
                    radius = size.maxDimension * 0.72f,
                ),
            )
        }

        if (vignette > 0.01f) {
            drawRect(
                brush = Brush.radialGradient(
                    0.46f to Color.Transparent,
                    1.0f to Color.Black.copy(alpha = 0.72f * vignette),
                    center = Offset(w / 2f, h / 2f),
                    radius = size.maxDimension * 0.72f,
                ),
            )
        }

        if (grain > 0.01f) {
            val rnd = Random(42)
            val count = (520 * grain).roundToInt()
            val cLight = Color.White.copy(alpha = 0.14f * grain)
            val cDark = Color.Black.copy(alpha = 0.16f * grain)
            for (i in 0 until count) {
                val gx = rnd.nextFloat() * w
                val gy = rnd.nextFloat() * h
                drawRect(
                    color = if (i % 2 == 0) cLight else cDark,
                    topLeft = Offset(gx, gy),
                    size = Size(1.3f.dp.toPx(), 1.3f.dp.toPx()),
                )
            }
        }

        if (sparkle > 0.01f) {
            val spots = listOf(
                Triple(0.14f, 0.18f, 1.0f),
                Triple(0.84f, 0.22f, 1.15f),
                Triple(0.24f, 0.56f, 0.75f),
                Triple(0.80f, 0.54f, 0.95f),
                Triple(0.16f, 0.80f, 0.80f),
                Triple(0.76f, 0.78f, 0.88f),
                Triple(0.50f, 0.14f, 0.72f),
            )
            for ((sx, sy, mult) in spots) {
                val cx = w * sx
                val cy = h * sy
                val r = (10f + 18f * sparkle) * mult
                drawIntoCanvas { canvas ->
                    val blur = android.graphics.Paint().apply {
                        isAntiAlias = true
                        this.color = Color.White.copy(alpha = 0.36f * sparkle).toArgb()
                        maskFilter = BlurMaskFilter((r * 0.42f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
                    }
                    canvas.nativeCanvas.drawCircle(cx, cy, r * 0.32f, blur)
                }
                val star = Path().apply {
                    moveTo(cx, cy - r)
                    quadraticTo(cx, cy, cx + r, cy)
                    quadraticTo(cx, cy, cx, cy + r)
                    quadraticTo(cx, cy, cx - r, cy)
                    quadraticTo(cx, cy, cx, cy - r)
                    close()
                }
                drawPath(star, Color.White.copy(alpha = 0.92f * sparkle))
            }
        }
    }
}
