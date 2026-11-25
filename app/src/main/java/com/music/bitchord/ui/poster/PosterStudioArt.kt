package com.music.bitchord.ui.poster

import android.graphics.BlurMaskFilter
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
        PosterCardStyle.DARK_PLAYER -> DarkPlayerCard(song, showControls, showHeart, lines, cardWidth, textMaker)
        PosterCardStyle.LIQUID_GLASS -> LiquidGlassCard(song, tint, showControls, showHeart, lines, cardWidth, textMaker)
        PosterCardStyle.APPLE_LYRIC -> AppleLyricCard(song, tint, lines, cardWidth, textMaker)
        PosterCardStyle.VINYL -> VinylCard(song, cardWidth, textMaker)
        PosterCardStyle.CASSETTE -> CassetteCard(song, tint, cardWidth, textMaker)
        PosterCardStyle.POLAROID -> PolaroidCard(song, cardWidth, textMaker)
        PosterCardStyle.MINIMAL -> MinimalCard(song, cardWidth, textMaker)
    }
}

@Composable
private fun PosterCoverArt(url: String?, size: Dp, cornerRadius: Dp, circle: Boolean = false) {
    val shape = if (circle) CircleShape else RoundedCornerShape(cornerRadius)
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(Color(0xFF1F222A)),
    )
}

@Composable
private fun DarkPlayerCard(
    song: Song,
    showControls: Boolean,
    showHeart: Boolean,
    lines: List<String>,
    width: Dp,
    ts: (Float, FontWeight, Color) -> TextStyle,
) {
    val artSize = width - 20.dp
    Column(
        modifier = Modifier
            .width(width)
            .shadow(16.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xD912141A))
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
            .padding(10.dp),
    ) {
        PosterCoverArt(song.thumbnailUrl, artSize, 12.dp)
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
            Text(
                text = lines.take(2).joinToString("\n"),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = ts(9.5f, FontWeight.SemiBold, Color.White.copy(alpha = 0.78f)).copy(lineHeight = 12.sp),
            )
        }
        if (showControls) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(3.dp)),
            ) {
                Box(Modifier.weight(0.42f).fillMaxHeight().background(Color.White))
                Box(Modifier.weight(0.58f).fillMaxHeight().background(Color.White.copy(alpha = 0.24f)))
            }
            Spacer(Modifier.height(7.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(17.dp))
                Icon(Icons.Rounded.Pause, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Icon(Icons.Rounded.SkipNext, null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(17.dp))
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
    ts: (Float, FontWeight, Color) -> TextStyle,
) {
    Column(
        modifier = Modifier
            .width(width)
            .shadow(16.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.22f),
                        tint.copy(alpha = 0.32f),
                        Color.Black.copy(alpha = 0.44f),
                    ),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.34f), RoundedCornerShape(20.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PosterCoverArt(song.thumbnailUrl, 46.dp, 10.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(12.5f, FontWeight.ExtraBold, Color.White))
                Spacer(Modifier.height(2.dp))
                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(10.5f, FontWeight.Medium, Color.White.copy(alpha = 0.75f)))
            }
            if (showHeart) {
                Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
        if (lines.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
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
                Icon(Icons.Rounded.GraphicEq, null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(3.dp)),
                ) {
                    Box(Modifier.weight(0.56f).fillMaxHeight().background(Color.White))
                    Box(Modifier.weight(0.44f).fillMaxHeight().background(Color.White.copy(alpha = 0.24f)))
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
    ts: (Float, FontWeight, Color) -> TextStyle,
) {
    val shown = if (lines.isNotEmpty()) lines.take(4) else listOf(song.title, song.artist)
    val darkTint = Color(
        red = tint.red * 0.42f,
        green = tint.green * 0.42f,
        blue = tint.blue * 0.42f,
        alpha = 0.94f,
    )
    Column(
        modifier = Modifier
            .width(width + 20.dp)
            .shadow(18.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.88f), darkTint)))
            .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(20.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PosterCoverArt(song.thumbnailUrl, 34.dp, 8.dp)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(11.5f, FontWeight.ExtraBold, Color.White))
                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(9.5f, FontWeight.Medium, Color.White.copy(alpha = 0.75f)))
            }
        }
        Spacer(Modifier.height(10.dp))
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

@Composable
private fun VinylCard(
    song: Song,
    width: Dp,
    ts: (Float, FontWeight, Color) -> TextStyle,
) {
    val discSize = width * 0.90f
    Column(
        modifier = Modifier.width(width),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(discSize)
                .shadow(18.dp, CircleShape)
                .clip(CircleShape)
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
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.matchParentSize()) {
                val rBase = size.minDimension / 2f
                for (f in listOf(0.58f, 0.68f, 0.78f, 0.88f)) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.11f),
                        radius = rBase * f,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }
            PosterCoverArt(song.thumbnailUrl, discSize * 0.48f, 0.dp, circle = true)
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color.Black)
                    .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
            )
        }
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .border(0.7.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(11f, FontWeight.ExtraBold, Color.White))
            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(9.5f, FontWeight.Medium, Color.White.copy(alpha = 0.75f)))
        }
    }
}

@Composable
private fun CassetteCard(
    song: Song,
    tint: Color,
    width: Dp,
    ts: (Float, FontWeight, Color) -> TextStyle,
) {
    Column(
        modifier = Modifier
            .width(width + 16.dp)
            .shadow(16.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xEA161922))
            .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFF5F1E6))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PosterCoverArt(song.thumbnailUrl, 30.dp, 5.dp)
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
                .border(0.7.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CassetteReel()
            Box(
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .padding(horizontal = 10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(tint.copy(alpha = 0.55f)),
            )
            CassetteReel()
        }
    }
}

@Composable
private fun CassetteReel() {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .border(1.6.dp, Color.White.copy(alpha = 0.78f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun PolaroidCard(
    song: Song,
    width: Dp,
    ts: (Float, FontWeight, Color) -> TextStyle,
) {
    val artSize = width - 18.dp
    Column(
        modifier = Modifier
            .width(width)
            .shadow(16.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFF6F2E9))
            .padding(start = 9.dp, end = 9.dp, top = 9.dp, bottom = 14.dp),
    ) {
        PosterCoverArt(song.thumbnailUrl, artSize, 3.dp)
        Spacer(Modifier.height(9.dp))
        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(12f, FontWeight.ExtraBold, Color(0xFF15161A)))
        Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(9.5f, FontWeight.SemiBold, Color(0xFF5A5D66)))
    }
}

@Composable
private fun MinimalCard(
    song: Song,
    width: Dp,
    ts: (Float, FontWeight, Color) -> TextStyle,
) {
    Row(
        modifier = Modifier
            .width(width)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.shadow(12.dp, RoundedCornerShape(10.dp))) {
            PosterCoverArt(song.thumbnailUrl, 46.dp, 10.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(13f, FontWeight.ExtraBold, Color.White))
            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ts(10.5f, FontWeight.SemiBold, Color.White.copy(alpha = 0.78f)))
        }
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
