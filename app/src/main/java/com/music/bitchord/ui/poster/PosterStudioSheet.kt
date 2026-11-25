package com.music.bitchord.ui.poster

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FilterVintage
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.R
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.PillTextField
import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.ui.player.PosterStudioRequest
import com.music.bitchord.ui.replay.cacheForSharing
import com.music.bitchord.ui.replay.saveToGallery
import com.music.bitchord.ui.replay.sendIntent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

private enum class StudioTab(val label: String) {
    CARDS("Kartu"),
    BACKGROUND("Latar"),
    DEPTH("Kedalaman"),
    STICKERS("Stiker"),
    EFFECTS("Efek"),
}

private enum class DepthBrushMode(val label: String) {
    OFF("Geser"),
    ADD("Tambah Subjek"),
    ERASE("Hapus Potongan"),
}

private data class PosterCardItem(
    val id: String,
    val song: Song,
    val x: Float = 0.50f,
    val y: Float = 0.60f,
    val scale: Float = 1.0f,
    val rotationDeg: Float = 0f,
    val tiltX: Float = Poster3dPreset.TILT_LEFT.tiltX,
    val tiltY: Float = Poster3dPreset.TILT_LEFT.tiltY,
    val depth3d: Float = Poster3dPreset.TILT_LEFT.depth3d,
    val style: PosterCardStyle = PosterCardStyle.DARK_PLAYER,
    val showControls: Boolean = true,
    val showHeart: Boolean = true,
    val showLyrics: Boolean = false,
    val behindSubject: Boolean = true,
    val lineColor: Color = Color.White,
    val lineWidthDp: Float = 1.5f,
)

private data class PosterStickerItem(
    val id: String,
    val kind: PosterStickerKind,
    val x: Float = 0.5f,
    val y: Float = 0.35f,
    val scale: Float = 1.0f,
    val rotationDeg: Float = 0f,
    val opacity: Float = 1.0f,
    val color: Color = Color.White,
    val text: String = "",
    val customImageUri: Uri? = null,
)

private data class StudioUndoSnapshot(
    val cards: List<PosterCardItem>,
    val stickers: List<PosterStickerItem>,
    val depthStrokes: List<DepthStroke>,
)

private class DepthClipShape(
    private val strokes: List<DepthStroke>,
    private val startFull: Boolean,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        return Outline.Generic(buildDepthMaskPath(strokes, size.width, size.height, startFull))
    }
}

@Composable
fun AndroidPosterStudioDialog(
    request: PosterStudioRequest,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        PosterStudioContent(request = request, onDismiss = onDismiss)
    }
}

@Composable
private fun PosterStudioContent(
    request: PosterStudioRequest,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val posterLayer = rememberGraphicsLayer()

    var activeTab by remember { mutableStateOf(StudioTab.CARDS) }
    var aspectRatio by remember { mutableStateOf(PosterAspectRatio.STORY_9_16) }
    var backgroundMode by remember { mutableStateOf(PosterBackgroundMode.ALBUM_BLUR) }
    var tintColor by remember { mutableStateOf(POSTER_TINT_PALETTE.first()) }
    var fontStyle by remember { mutableStateOf(PosterFontStyle.CLEAN) }
    var colorFilter by remember { mutableStateOf(PosterColorFilter.ORIGINAL) }
    var showLogo by remember { mutableStateOf(true) }

    var sparkle by remember { mutableFloatStateOf(0f) }
    var grain by remember { mutableFloatStateOf(0.10f) }
    var vignette by remember { mutableFloatStateOf(0.28f) }
    var glow by remember { mutableFloatStateOf(0.22f) }
    var frameColor by remember { mutableStateOf(Color.White) }
    var frameWidthDp by remember { mutableFloatStateOf(0f) }

    var photoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cutoutBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var segmentMethod by remember { mutableStateOf("") }
    var segmenting by remember { mutableStateOf(false) }

    var cards by remember(request.song.videoId) {
        mutableStateOf(
            listOf(
                PosterCardItem(
                    id = "main",
                    song = request.song,
                    showLyrics = request.lyrics.isNotEmpty(),
                ),
            ),
        )
    }
    var selectedCardId by remember { mutableStateOf("main") }
    var drag3dMode by remember { mutableStateOf(false) }
    var stickers by remember { mutableStateOf<List<PosterStickerItem>>(emptyList()) }
    var selectedStickerId by remember { mutableStateOf<String?>(null) }

    var depthBrushMode by remember { mutableStateOf(DepthBrushMode.OFF) }
    var brushRadiusNorm by remember { mutableFloatStateOf(0.06f) }
    var depthStrokes by remember { mutableStateOf<List<DepthStroke>>(emptyList()) }
    var activeStrokePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    var undoStack by remember { mutableStateOf<List<StudioUndoSnapshot>>(emptyList()) }
    var busyExport by remember { mutableStateOf(false) }
    var showAddCardDialog by remember { mutableStateOf(false) }
    var editingStickerText by remember { mutableStateOf<PosterStickerItem?>(null) }
    var exportingOverlayHidden by remember { mutableStateOf(false) }

    val pushUndo: () -> Unit = {
        undoStack = (undoStack + StudioUndoSnapshot(cards, stickers, depthStrokes)).takeLast(20)
    }

    val performUndo: () -> Unit = {
        val last = undoStack.lastOrNull()
        if (last != null) {
            cards = last.cards
            stickers = last.stickers
            depthStrokes = last.depthStrokes
            undoStack = undoStack.dropLast(1)
            if (cards.none { it.id == selectedCardId }) {
                selectedCardId = cards.firstOrNull()?.id.orEmpty()
            }
        }
    }
    val currentPushUndo by rememberUpdatedState(pushUndo)

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        segmenting = true
        scope.launch {
            val res = SubjectSegmenter.segmentUri(context, uri)
            if (res != null) {
                photoBitmap = res.backgroundBitmap
                cutoutBitmap = res.cutoutBitmap
                segmentMethod = res.method
                backgroundMode = PosterBackgroundMode.PHOTO
                depthStrokes = emptyList()
            }
            segmenting = false
        }
    }

    val segmentSongCover: () -> Unit = {
        val url = request.song.thumbnailUrl
        if (!url.isNullOrBlank() && !segmenting) {
            segmenting = true
            scope.launch {
                val loader = SingletonImageLoader.get(context)
                val req = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .build()
                val resImg = (loader.execute(req) as? SuccessResult)?.image?.toBitmap()
                if (resImg != null) {
                    val seg = SubjectSegmenter.segmentBitmap(context, resImg)
                    photoBitmap = seg.backgroundBitmap
                    cutoutBitmap = seg.cutoutBitmap
                    segmentMethod = seg.method
                    backgroundMode = PosterBackgroundMode.PHOTO
                    depthStrokes = emptyList()
                }
                segmenting = false
            }
        }
    }

    val stickerImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null || stickers.size >= 30) return@rememberLauncherForActivityResult
        pushUndo()
        val item = PosterStickerItem(
            id = UUID.randomUUID().toString(),
            kind = PosterStickerKind.CUSTOM_IMAGE,
            customImageUri = uri,
        )
        stickers = stickers + item
        selectedStickerId = item.id
    }

    suspend fun capturePosterBitmap(): Bitmap? {
        exportingOverlayHidden = true
        delay(60)
        val captured = runCatching {
            posterLayer.toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        }.getOrNull()
        exportingOverlayHidden = false
        return captured
    }

    val selectedCard = cards.firstOrNull { it.id == selectedCardId } ?: cards.firstOrNull()
    val selectedSticker = stickers.firstOrNull { it.id == selectedStickerId }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0A0B0F),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel), tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.poster_studio),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (segmenting || segmentMethod.isNotEmpty()) {
                        Text(
                            text = when {
                                segmenting -> "Memindai subjek AI…"
                                segmentMethod == "mlkit" -> "AI Selfie Cutout aktif"
                                segmentMethod == "saliency" -> "Potongan subjek otomatis aktif"
                                else -> "Gunakan Kuas Kedalaman untuk potong manual"
                            },
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                }
                IconButton(onClick = performUndo, enabled = undoStack.isNotEmpty()) {
                    Icon(
                        SfIcons.Revert,
                        contentDescription = "Undo",
                        tint = if (undoStack.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f),
                    )
                }
                IconButton(
                    enabled = !busyExport,
                    onClick = {
                        busyExport = true
                        scope.launch {
                            val bmp = capturePosterBitmap()
                            val saved = bmp != null && saveToGallery(context, bmp, request.song.title, "musify-poster")
                            Toast.makeText(
                                context,
                                if (saved) context.getString(R.string.poster_studio_saved) else context.getString(R.string.couldnt_draw_picture),
                                Toast.LENGTH_SHORT,
                            ).show()
                            busyExport = false
                        }
                    },
                ) {
                    if (busyExport) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.poster_studio_save), tint = Color.White)
                    }
                }
                IconButton(
                    enabled = !busyExport,
                    onClick = {
                        busyExport = true
                        scope.launch {
                            val bmp = capturePosterBitmap()
                            val uri = bmp?.let { cacheForSharing(context, it, "musify-poster.png") }
                            if (uri != null) {
                                context.startActivity(android.content.Intent.createChooser(sendIntent(uri), null))
                            }
                            busyExport = false
                        }
                    },
                ) {
                    Icon(Icons.Rounded.IosShare, contentDescription = stringResource(R.string.poster_studio_share), tint = Color(0xFFFB5C74))
                }
            }

            // Preview Stage
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .aspectRatio(aspectRatio.ratio)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF14161D))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(22.dp)),
                ) {
                    val canvasW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                    val canvasH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                    val density = LocalDensity.current
                    val stageModifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .drawWithContent {
                            posterLayer.record {
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(posterLayer)
                        }

                    Box(modifier = stageModifier) {
                        // 1. Background layer
                        PosterBackgroundLayer(
                            mode = backgroundMode,
                            song = request.song,
                            tint = tintColor,
                            photoBitmap = photoBitmap,
                            colorFilter = colorFilter,
                        )

                        // 2. Cards behind subject
                        cards.filter { it.behindSubject }.forEach { card ->
                            key(card.id) {
                                InteractivePosterCard(
                                    item = card,
                                    tint = tintColor,
                                    fontStyle = fontStyle,
                                    lyrics = request.lyrics,
                                    canvasW = canvasW,
                                    canvasH = canvasH,
                                    density = density,
                                    isSelected = !exportingOverlayHidden && card.id == selectedCardId && depthBrushMode == DepthBrushMode.OFF,
                                    interactive = depthBrushMode == DepthBrushMode.OFF,
                                    drag3dMode = drag3dMode,
                                    onSelect = {
                                        selectedCardId = card.id
                                        selectedStickerId = null
                                    },
                                    onGestureStart = pushUndo,
                                    onUpdate = { updated ->
                                        cards = cards.map { if (it.id == updated.id) updated else it }
                                    },
                                )
                            }
                        }

                        // 3. Subject Cutout / Manual Depth Foreground Layer
                        if (backgroundMode == PosterBackgroundMode.PHOTO) {
                            val fgBitmap = cutoutBitmap ?: if (depthStrokes.isNotEmpty() || activeStrokePoints.isNotEmpty()) photoBitmap else null
                            if (fgBitmap != null) {
                                val allStrokes = if (activeStrokePoints.isNotEmpty()) {
                                    depthStrokes + DepthStroke(
                                        points = activeStrokePoints,
                                        radiusNorm = brushRadiusNorm,
                                        erase = depthBrushMode == DepthBrushMode.ERASE,
                                    )
                                } else {
                                    depthStrokes
                                }
                                val clipShape = remember(cutoutBitmap, allStrokes) {
                                    DepthClipShape(
                                        strokes = allStrokes,
                                        startFull = cutoutBitmap != null,
                                    )
                                }
                                Image(
                                    bitmap = fgBitmap.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    colorFilter = colorFilter.toColorFilter(),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(clipShape),
                                )
                            }
                        }

                        // 4. Cards in front of subject
                        cards.filter { !it.behindSubject }.forEach { card ->
                            key(card.id) {
                                InteractivePosterCard(
                                    item = card,
                                    tint = tintColor,
                                    fontStyle = fontStyle,
                                    lyrics = request.lyrics,
                                    canvasW = canvasW,
                                    canvasH = canvasH,
                                    density = density,
                                    isSelected = !exportingOverlayHidden && card.id == selectedCardId && depthBrushMode == DepthBrushMode.OFF,
                                    interactive = depthBrushMode == DepthBrushMode.OFF,
                                    drag3dMode = drag3dMode,
                                    onSelect = {
                                        selectedCardId = card.id
                                        selectedStickerId = null
                                    },
                                    onGestureStart = pushUndo,
                                    onUpdate = { updated ->
                                        cards = cards.map { if (it.id == updated.id) updated else it }
                                    },
                                )
                            }
                        }

                        // 5. Stickers layer
                        stickers.forEach { st ->
                            key(st.id) {
                                InteractiveSticker(
                                    item = st,
                                    canvasW = canvasW,
                                    canvasH = canvasH,
                                    density = density,
                                    isSelected = !exportingOverlayHidden && st.id == selectedStickerId && depthBrushMode == DepthBrushMode.OFF,
                                    interactive = depthBrushMode == DepthBrushMode.OFF,
                                    onSelect = { selectedStickerId = st.id },
                                    onDoubleTap = {
                                        if (st.kind == PosterStickerKind.TEKS) editingStickerText = st
                                    },
                                    onGestureStart = pushUndo,
                                    onUpdate = { updated ->
                                        stickers = stickers.map { if (it.id == updated.id) updated else it }
                                    },
                                )
                            }
                        }

                        // 6. Visual Effects overlay
                        PosterEffectsOverlay(
                            sparkle = sparkle,
                            grain = grain,
                            vignette = vignette,
                            glow = glow,
                            modifier = Modifier.fillMaxSize(),
                        )

                        // 7. Optional customizable outer frame line (included in saved/shared PNG)
                        if (frameWidthDp > 0.1f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(frameWidthDp.dp, frameColor, RoundedCornerShape(22.dp)),
                            )
                        }

                        // 8. Musify mark
                        if (showLogo) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(12.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.Black.copy(alpha = 0.42f))
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.MusicNote, null, tint = Color(0xFFFB5C74), modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Musify", color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // 9. Interactive Manual Depth Brush Overlay
                        if (depthBrushMode != DepthBrushMode.OFF && backgroundMode == PosterBackgroundMode.PHOTO) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(depthBrushMode, brushRadiusNorm) {
                                        detectDragGestures(
                                            onDragStart = { start ->
                                                currentPushUndo()
                                                activeStrokePoints = listOf(
                                                    Offset(
                                                        (start.x / size.width).coerceIn(0f, 1f),
                                                        (start.y / size.height).coerceIn(0f, 1f),
                                                    ),
                                                )
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                activeStrokePoints = activeStrokePoints + Offset(
                                                    (change.position.x / size.width).coerceIn(0f, 1f),
                                                    (change.position.y / size.height).coerceIn(0f, 1f),
                                                )
                                            },
                                            onDragEnd = {
                                                if (activeStrokePoints.isNotEmpty()) {
                                                    depthStrokes = depthStrokes + DepthStroke(
                                                        points = activeStrokePoints,
                                                        radiusNorm = brushRadiusNorm,
                                                        erase = depthBrushMode == DepthBrushMode.ERASE,
                                                    )
                                                    activeStrokePoints = emptyList()
                                                }
                                            },
                                            onDragCancel = {
                                                activeStrokePoints = emptyList()
                                            },
                                        )
                                    },
                            ) {
                                val tintBrush = if (depthBrushMode == DepthBrushMode.ADD) {
                                    Color(0x6634D399)
                                } else {
                                    Color(0x66F87171)
                                }
                                val r = brushRadiusNorm * size.minDimension
                                activeStrokePoints.forEach { pt ->
                                    drawCircle(
                                        color = tintBrush,
                                        radius = r,
                                        center = Offset(pt.x * size.width, pt.y * size.height),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Studio Dock
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF14161D))
                    .padding(top = 6.dp, bottom = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StudioTab.entries.forEach { tab ->
                        val tabIcon = when (tab) {
                            StudioTab.CARDS -> Icons.Rounded.Style
                            StudioTab.BACKGROUND -> Icons.Rounded.PhotoLibrary
                            StudioTab.DEPTH -> Icons.Rounded.Layers
                            StudioTab.STICKERS -> Icons.Rounded.AutoFixHigh
                            StudioTab.EFFECTS -> Icons.Rounded.FilterVintage
                        }
                        FilterChip(
                            selected = activeTab == tab,
                            onClick = {
                                activeTab = tab
                                if (tab != StudioTab.DEPTH && depthBrushMode != DepthBrushMode.OFF) {
                                    depthBrushMode = DepthBrushMode.OFF
                                }
                            },
                            label = { Text(tab.label, fontSize = 12.sp) },
                            leadingIcon = { Icon(tabIcon, null, modifier = Modifier.size(16.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFFA233B),
                                selectedLabelColor = Color.White,
                                selectedLeadingIconColor = Color.White,
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp),
                ) {
                    when (activeTab) {
                        StudioTab.CARDS -> CardsTabPanel(
                            cards = cards,
                            selectedCard = selectedCard,
                            lyricsAvailable = request.lyrics.isNotEmpty(),
                            showLogo = showLogo,
                            drag3dMode = drag3dMode,
                            onToggleDrag3d = { drag3dMode = !drag3dMode },
                            onSelectCard = { selectedCardId = it },
                            onAddCard = { if (cards.size < 4) showAddCardDialog = true },
                            onRemoveCard = { id ->
                                if (cards.size > 1) {
                                    pushUndo()
                                    cards = cards.filterNot { it.id == id }
                                    selectedCardId = cards.first().id
                                }
                            },
                            onToggleLogo = { showLogo = it },
                            onUpdateCard = { updated ->
                                cards = cards.map { if (it.id == updated.id) updated else it }
                            },
                        )

                        StudioTab.BACKGROUND -> BackgroundTabPanel(
                            mode = backgroundMode,
                            hasPhoto = photoBitmap != null,
                            aspectRatio = aspectRatio,
                            tintColor = tintColor,
                            fontStyle = fontStyle,
                            onPickPhoto = { photoPicker.launch("image/*") },
                            onSelectMode = { backgroundMode = it },
                            onSelectRatio = { aspectRatio = it },
                            onSelectTint = { tintColor = it },
                            onSelectFont = { fontStyle = it },
                        )

                        StudioTab.DEPTH -> DepthTabPanel(
                            hasPhoto = photoBitmap != null,
                            hasCutout = cutoutBitmap != null,
                            hasCoverUrl = !request.song.thumbnailUrl.isNullOrBlank(),
                            segmenting = segmenting,
                            brushMode = depthBrushMode,
                            brushSize = brushRadiusNorm,
                            onPickPhoto = { photoPicker.launch("image/*") },
                            onUseCoverCutout = segmentSongCover,
                            onBrushModeChange = { depthBrushMode = it },
                            onBrushSizeChange = { brushRadiusNorm = it },
                            onResetStrokes = {
                                pushUndo()
                                depthStrokes = emptyList()
                            },
                        )

                        StudioTab.STICKERS -> StickersTabPanel(
                            stickers = stickers,
                            selected = selectedSticker,
                            onAddBuiltIn = { kind ->
                                if (stickers.size < 30) {
                                    pushUndo()
                                    val st = PosterStickerItem(
                                        id = UUID.randomUUID().toString(),
                                        kind = kind,
                                        text = if (kind == PosterStickerKind.TEKS) "musify vibes" else "",
                                    )
                                    stickers = stickers + st
                                    selectedStickerId = st.id
                                    if (kind == PosterStickerKind.TEKS) editingStickerText = st
                                }
                            },
                            onPickStickerPhoto = { stickerImagePicker.launch("image/*") },
                            onEditText = { editingStickerText = it },
                            onDeleteSelected = { id ->
                                pushUndo()
                                stickers = stickers.filterNot { it.id == id }
                                selectedStickerId = null
                            },
                            onUpdateSticker = { updated ->
                                stickers = stickers.map { if (it.id == updated.id) updated else it }
                            },
                        )

                        StudioTab.EFFECTS -> EffectsTabPanel(
                            colorFilter = colorFilter,
                            sparkle = sparkle,
                            grain = grain,
                            vignette = vignette,
                            glow = glow,
                            frameColor = frameColor,
                            frameWidthDp = frameWidthDp,
                            onFilterChange = { colorFilter = it },
                            onSparkleChange = { sparkle = it },
                            onGrainChange = { grain = it },
                            onVignetteChange = { vignette = it },
                            onGlowChange = { glow = it },
                            onFrameColorChange = { frameColor = it },
                            onFrameWidthChange = { frameWidthDp = it },
                        )
                    }
                }
            }
        }
    }

    if (showAddCardDialog) {
        AddSongCardDialog(
            onDismiss = { showAddCardDialog = false },
            onSelectSong = { song ->
                pushUndo()
                val added = PosterCardItem(
                    id = UUID.randomUUID().toString(),
                    song = song,
                    x = 0.50f,
                    y = 0.34f,
                    scale = 0.88f,
                    tiltX = Poster3dPreset.TILT_RIGHT.tiltX,
                    tiltY = Poster3dPreset.TILT_RIGHT.tiltY,
                    depth3d = Poster3dPreset.TILT_RIGHT.depth3d,
                )
                cards = cards + added
                selectedCardId = added.id
                showAddCardDialog = false
            },
        )
    }

    editingStickerText?.let { target ->
        var textDraft by remember(target.id) { mutableStateOf(target.text) }
        AlertDialog(
            onDismissRequest = { editingStickerText = null },
            title = { Text("Teks Stiker") },
            text = {
                PillTextField(
                    value = textDraft,
                    onValueChange = { textDraft = it.take(40) },
                    placeholder = "Tulis teks stiker…",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        stickers = stickers.map {
                            if (it.id == target.id) it.copy(text = textDraft) else it
                        }
                        editingStickerText = null
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { editingStickerText = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun PosterBackgroundLayer(
    mode: PosterBackgroundMode,
    song: Song,
    tint: Color,
    photoBitmap: Bitmap?,
    colorFilter: PosterColorFilter,
) {
    when {
        mode == PosterBackgroundMode.PHOTO && photoBitmap != null -> {
            Image(
                bitmap = photoBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = colorFilter.toColorFilter(),
                modifier = Modifier.fillMaxSize(),
            )
        }
        mode == PosterBackgroundMode.ALBUM_BLUR && !song.thumbnailUrl.isNullOrBlank() -> {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = song.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = colorFilter.toColorFilter(),
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(26.dp),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.25f),
                                    tint.copy(alpha = 0.28f),
                                    Color.Black.copy(alpha = 0.65f),
                                ),
                            ),
                        ),
                )
            }
        }
        else -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                tint.copy(alpha = 0.90f),
                                Color(0xFF141824),
                                Color(0xFF090A0F),
                            ),
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun InteractivePosterCard(
    item: PosterCardItem,
    tint: Color,
    fontStyle: PosterFontStyle,
    lyrics: List<String>,
    canvasW: Float,
    canvasH: Float,
    density: Density,
    isSelected: Boolean,
    interactive: Boolean,
    drag3dMode: Boolean,
    onSelect: () -> Unit,
    onGestureStart: () -> Unit,
    onUpdate: (PosterCardItem) -> Unit,
) {
    val currentItem by rememberUpdatedState(item)
    val currentDrag3d by rememberUpdatedState(drag3dMode)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnGestureStart by rememberUpdatedState(onGestureStart)
    val currentOnUpdate by rememberUpdatedState(onUpdate)

    val cardWidthPx = with(density) { 192.dp.toPx() }
    val estHeightPx = with(density) { 240.dp.toPx() }
    val pxX = (item.x * canvasW) - cardWidthPx / 2f
    val pxY = (item.y * canvasH) - estHeightPx / 2f

    Box(
        modifier = Modifier
            .offset { IntOffset(pxX.roundToInt(), pxY.roundToInt()) }
            .graphicsLayer {
                scaleX = item.scale
                scaleY = item.scale
                rotationZ = item.rotationDeg
                rotationX = item.tiltX
                rotationY = item.tiltY
                // Compose multiplies cameraDistance by device density internally;
                // multiplying by density.density flattened 3D perspective ~3x too much.
                cameraDistance = if (item.depth3d > 0.02f) {
                    4.3f + (1f - item.depth3d.coerceIn(0f, 1f)) * 5.7f
                } else {
                    12f
                }
            }
            .then(
                if (interactive) {
                    Modifier
                        .clickable { currentOnSelect() }
                        .pointerInput("undo-${item.id}") {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.any { it.pressed && !it.previousPressed }) {
                                        currentOnGestureStart()
                                    }
                                }
                            }
                        }
                        .pointerInput(item.id, canvasW, canvasH, drag3dMode) {
                            if (currentDrag3d) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    currentOnSelect()
                                    val latest = currentItem
                                    currentOnUpdate(
                                        latest.copy(
                                            tiltY = (latest.tiltY + dragAmount.x * 0.30f).coerceIn(-42f, 42f),
                                            tiltX = (latest.tiltX - dragAmount.y * 0.30f).coerceIn(-35f, 35f),
                                            depth3d = if (latest.depth3d < 0.2f) 0.85f else latest.depth3d,
                                        ),
                                    )
                                }
                            } else {
                                detectTransformGestures { _, pan, zoom, rotation ->
                                    currentOnSelect()
                                    val latest = currentItem
                                    val nx = ((latest.x * canvasW + pan.x) / canvasW).coerceIn(0.05f, 0.95f)
                                    val ny = ((latest.y * canvasH + pan.y) / canvasH).coerceIn(0.05f, 0.95f)
                                    currentOnUpdate(
                                        latest.copy(
                                            x = nx,
                                            y = ny,
                                            scale = (latest.scale * zoom).coerceIn(0.45f, 2.2f),
                                            rotationDeg = latest.rotationDeg + rotation,
                                        ),
                                    )
                                }
                            }
                        }
                } else {
                    Modifier
                },
            )
            .then(
                if (isSelected) {
                    val selShape = if (item.style == PosterCardStyle.VINYL) CircleShape else RoundedCornerShape(20.dp)
                    Modifier.border(1.2.dp, Color(0xFFFB5C74).copy(alpha = 0.70f), selShape)
                } else {
                    Modifier
                },
            ),
    ) {
        PosterSongCard(
            song = item.song,
            style = item.style,
            tint = tint,
            fontStyle = fontStyle,
            showControls = item.showControls,
            showHeart = item.showHeart,
            lines = if (item.showLyrics) lyrics else emptyList(),
            lineColor = item.lineColor,
            lineWidthDp = item.lineWidthDp,
            tiltX = item.tiltX,
            tiltY = item.tiltY,
            depth3d = item.depth3d,
        )
    }
}

@Composable
private fun InteractiveSticker(
    item: PosterStickerItem,
    canvasW: Float,
    canvasH: Float,
    density: Density,
    isSelected: Boolean,
    interactive: Boolean,
    onSelect: () -> Unit,
    onDoubleTap: () -> Unit,
    onGestureStart: () -> Unit,
    onUpdate: (PosterStickerItem) -> Unit,
) {
    val currentItem by rememberUpdatedState(item)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentOnGestureStart by rememberUpdatedState(onGestureStart)
    val currentOnUpdate by rememberUpdatedState(onUpdate)

    val sizeDp = 72.dp
    val sizePx = with(density) { sizeDp.toPx() }
    val pxX = (item.x * canvasW) - sizePx / 2f
    val pxY = (item.y * canvasH) - sizePx / 2f

    Box(
        modifier = Modifier
            .offset { IntOffset(pxX.roundToInt(), pxY.roundToInt()) }
            .graphicsLayer {
                scaleX = item.scale
                scaleY = item.scale
                rotationZ = item.rotationDeg
                alpha = item.opacity
            }
            .then(
                if (interactive) {
                    Modifier
                        .clickable {
                            currentOnSelect()
                            if (currentItem.kind == PosterStickerKind.TEKS) currentOnDoubleTap()
                        }
                        .pointerInput("undo-st-${item.id}") {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.any { it.pressed && !it.previousPressed }) {
                                        currentOnGestureStart()
                                    }
                                }
                            }
                        }
                        .pointerInput(item.id, canvasW, canvasH) {
                            detectTransformGestures { _, pan, zoom, rotation ->
                                currentOnSelect()
                                val latest = currentItem
                                val nx = ((latest.x * canvasW + pan.x) / canvasW).coerceIn(0.05f, 0.95f)
                                val ny = ((latest.y * canvasH + pan.y) / canvasH).coerceIn(0.05f, 0.95f)
                                currentOnUpdate(
                                    latest.copy(
                                        x = nx,
                                        y = ny,
                                        scale = (latest.scale * zoom).coerceIn(0.35f, 3.0f),
                                        rotationDeg = latest.rotationDeg + rotation,
                                    ),
                                )
                            }
                        }
                } else {
                    Modifier
                },
            )
            .then(
                if (isSelected) {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                } else {
                    Modifier
                },
            ),
    ) {
        PosterStickerGraphic(
            kind = item.kind,
            size = sizeDp,
            color = item.color,
            text = item.text,
            customImageUri = item.customImageUri,
        )
    }
}

@Composable
private fun CardsTabPanel(
    cards: List<PosterCardItem>,
    selectedCard: PosterCardItem?,
    lyricsAvailable: Boolean,
    showLogo: Boolean,
    drag3dMode: Boolean,
    onToggleDrag3d: () -> Unit,
    onSelectCard: (String) -> Unit,
    onAddCard: () -> Unit,
    onRemoveCard: (String) -> Unit,
    onToggleLogo: (Boolean) -> Unit,
    onUpdateCard: (PosterCardItem) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cards.forEachIndexed { idx, c ->
            FilterChip(
                selected = c.id == selectedCard?.id,
                onClick = { onSelectCard(c.id) },
                label = { Text("Kartu ${idx + 1}", fontSize = 11.sp) },
                trailingIcon = if (cards.size > 1) {
                    {
                        Icon(
                            Icons.Rounded.Close,
                            null,
                            modifier = Modifier.size(14.dp).clickable { onRemoveCard(c.id) },
                        )
                    }
                } else null,
            )
        }
        if (cards.size < 4) {
            FilterChip(
                selected = false,
                onClick = onAddCard,
                label = { Text("+ Kartu Lagu", fontSize = 11.sp) },
            )
        }
    }

    if (selectedCard != null) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PosterCardStyle.entries.forEach { st ->
                FilterChip(
                    selected = selectedCard.style == st,
                    onClick = { onUpdateCard(selectedCard.copy(style = st)) },
                    label = { Text(st.label, fontSize = 11.sp) },
                    leadingIcon = { Icon(st.icon, null, modifier = Modifier.size(15.dp)) },
                )
            }
        }

        // Instant 3D depth presets + touch-3D rotation toggle
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = drag3dMode,
                onClick = onToggleDrag3d,
                label = { Text(if (drag3dMode) "Jari: Putar 3D" else "Putar 3D dgn Jari", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Rounded.TouchApp, null, modifier = Modifier.size(14.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF7C3AED),
                    selectedLabelColor = Color.White,
                    selectedLeadingIconColor = Color.White,
                ),
            )
            Poster3dPreset.entries.forEach { preset ->
                val isCurrent = kotlin.math.abs(selectedCard.tiltX - preset.tiltX) < 1.5f &&
                    kotlin.math.abs(selectedCard.tiltY - preset.tiltY) < 1.5f &&
                    kotlin.math.abs(selectedCard.depth3d - preset.depth3d) < 0.08f
                FilterChip(
                    selected = isCurrent,
                    onClick = {
                        onUpdateCard(
                            selectedCard.copy(
                                tiltX = preset.tiltX,
                                tiltY = preset.tiltY,
                                depth3d = preset.depth3d,
                            ),
                        )
                    },
                    label = { Text(preset.label, fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Rounded.Layers, null, modifier = Modifier.size(14.dp)) },
                )
            }
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedCard.behindSubject,
                onClick = { onUpdateCard(selectedCard.copy(behindSubject = !selectedCard.behindSubject)) },
                label = { Text(stringResource(R.string.poster_studio_subject_depth), fontSize = 11.sp) },
            )
            FilterChip(
                selected = selectedCard.showControls,
                onClick = { onUpdateCard(selectedCard.copy(showControls = !selectedCard.showControls)) },
                label = { Text("Kontrol", fontSize = 11.sp) },
            )
            FilterChip(
                selected = selectedCard.showHeart,
                onClick = { onUpdateCard(selectedCard.copy(showHeart = !selectedCard.showHeart)) },
                label = { Text("Hati", fontSize = 11.sp) },
            )
            if (lyricsAvailable) {
                FilterChip(
                    selected = selectedCard.showLyrics,
                    onClick = { onUpdateCard(selectedCard.copy(showLyrics = !selectedCard.showLyrics)) },
                    label = { Text("Lirik", fontSize = 11.sp) },
                )
            }
            FilterChip(
                selected = showLogo,
                onClick = { onToggleLogo(!showLogo) },
                label = { Text("Badge Musify", fontSize = 11.sp) },
            )
        }

        Spacer(Modifier.height(4.dp))
        FreeColorSelector(
            label = "Warna Garis Kartu",
            color = selectedCard.lineColor,
            presets = POSTER_LINE_PALETTE,
            onColorChange = { c -> onUpdateCard(selectedCard.copy(lineColor = c)) },
            showResetWhite = true,
        )
        LabeledSlider("Tebal Garis", selectedCard.lineWidthDp, 0.6f..3.8f) {
            onUpdateCard(selectedCard.copy(lineWidthDp = it))
        }
        LabeledSlider("Tebal & Efek 3D", selectedCard.depth3d, 0f..1f) {
            onUpdateCard(selectedCard.copy(depth3d = it))
        }
        LabeledSlider("3D Putar X", selectedCard.tiltX, -35f..35f) {
            onUpdateCard(selectedCard.copy(tiltX = it))
        }
        LabeledSlider("3D Putar Y", selectedCard.tiltY, -42f..42f) {
            onUpdateCard(selectedCard.copy(tiltY = it))
        }
        LabeledSlider("Skala Kartu", selectedCard.scale, 0.5f..1.8f) {
            onUpdateCard(selectedCard.copy(scale = it))
        }
    }
}

@Composable
private fun BackgroundTabPanel(
    mode: PosterBackgroundMode,
    hasPhoto: Boolean,
    aspectRatio: PosterAspectRatio,
    tintColor: Color,
    fontStyle: PosterFontStyle,
    onPickPhoto: () -> Unit,
    onSelectMode: (PosterBackgroundMode) -> Unit,
    onSelectRatio: (PosterAspectRatio) -> Unit,
    onSelectTint: (Color) -> Unit,
    onSelectFont: (PosterFontStyle) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = false,
            onClick = onPickPhoto,
            label = { Text("Pilih Foto Galeri", fontSize = 11.sp) },
            leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null, modifier = Modifier.size(15.dp)) },
        )
        PosterBackgroundMode.entries.forEach { m ->
            val enabled = m != PosterBackgroundMode.PHOTO || hasPhoto
            FilterChip(
                selected = mode == m,
                enabled = enabled,
                onClick = { onSelectMode(m) },
                label = { Text(m.label, fontSize = 11.sp) },
            )
        }
    }
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PosterAspectRatio.entries.forEach { r ->
            FilterChip(
                selected = aspectRatio == r,
                onClick = { onSelectRatio(r) },
                label = { Text(r.label, fontSize = 11.sp) },
            )
        }
        PosterFontStyle.entries.forEach { f ->
            FilterChip(
                selected = fontStyle == f,
                onClick = { onSelectFont(f) },
                label = { Text(f.label, fontSize = 11.sp) },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    FreeColorSelector(
        label = "Warna Tema",
        color = tintColor,
        presets = POSTER_TINT_PALETTE,
        onColorChange = onSelectTint,
    )
}

@Composable
private fun DepthTabPanel(
    hasPhoto: Boolean,
    hasCutout: Boolean,
    hasCoverUrl: Boolean,
    segmenting: Boolean,
    brushMode: DepthBrushMode,
    brushSize: Float,
    onPickPhoto: () -> Unit,
    onUseCoverCutout: () -> Unit,
    onBrushModeChange: (DepthBrushMode) -> Unit,
    onBrushSizeChange: (Float) -> Unit,
    onResetStrokes: () -> Unit,
) {
    if (!hasPhoto) {
        Text(
            text = "Pilih foto galeri atau potong subjek cover lagu untuk mengaktifkan efek kedalaman di belakang objek.",
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = false,
                onClick = onPickPhoto,
                label = { Text("Pilih Foto Galeri", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null, modifier = Modifier.size(15.dp)) },
            )
            if (hasCoverUrl) {
                FilterChip(
                    selected = false,
                    enabled = !segmenting,
                    onClick = onUseCoverCutout,
                    label = { Text("Potong Cover Lagu (AI)", fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(15.dp)) },
                )
            }
        }
        return
    }
    Text(
        text = if (hasCutout) {
            "Subjek otomatis dipotong oleh AI. Gunakan kuas di bawah untuk merapikan atau kembalikan ke mode Geser."
        } else {
            "Gunakan kuas Tambah Subjek untuk menyapu area orang/objek agar tampil di depan kartu lagu."
        },
        color = Color.White.copy(alpha = 0.72f),
        fontSize = 11.sp,
    )
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DepthBrushMode.entries.forEach { m ->
            FilterChip(
                selected = brushMode == m,
                onClick = { onBrushModeChange(m) },
                label = { Text(m.label, fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Rounded.Brush, null, modifier = Modifier.size(14.dp)) },
            )
        }
        FilterChip(
            selected = false,
            onClick = onResetStrokes,
            label = { Text("Reset Kuas", fontSize = 11.sp) },
        )
        if (hasCoverUrl) {
            FilterChip(
                selected = false,
                enabled = !segmenting,
                onClick = onUseCoverCutout,
                label = { Text("Pakai Cover", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(14.dp)) },
            )
        }
    }
    LabeledSlider("Ukuran Kuas", brushSize, 0.02f..0.16f) { onBrushSizeChange(it) }
}

@Composable
private fun StickersTabPanel(
    stickers: List<PosterStickerItem>,
    selected: PosterStickerItem?,
    onAddBuiltIn: (PosterStickerKind) -> Unit,
    onPickStickerPhoto: () -> Unit,
    onEditText: (PosterStickerItem) -> Unit,
    onDeleteSelected: (String) -> Unit,
    onUpdateSticker: (PosterStickerItem) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = false,
            enabled = stickers.size < 30,
            onClick = onPickStickerPhoto,
            label = { Text("+ Foto", fontSize = 11.sp) },
        )
        PosterStickerKind.entries
            .filter { it != PosterStickerKind.CUSTOM_IMAGE }
            .forEach { kind ->
                FilterChip(
                    selected = false,
                    enabled = stickers.size < 30,
                    onClick = { onAddBuiltIn(kind) },
                    label = { Text(kind.label, fontSize = 11.sp) },
                )
            }
    }
    if (selected != null) {
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selected.kind == PosterStickerKind.TEKS) {
                FilterChip(
                    selected = false,
                    onClick = { onEditText(selected) },
                    label = { Text("Edit Teks", fontSize = 11.sp) },
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onDeleteSelected(selected.id) }) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Hapus", tint = Color(0xFFF87171))
            }
        }
        FreeColorSelector(
            label = "Warna Stiker",
            color = selected.color,
            presets = POSTER_LINE_PALETTE,
            onColorChange = { col -> onUpdateSticker(selected.copy(color = col)) },
            showResetWhite = true,
        )
        LabeledSlider("Skala Stiker", selected.scale, 0.4f..2.5f) {
            onUpdateSticker(selected.copy(scale = it))
        }
        LabeledSlider("Opasitas", selected.opacity, 0.2f..1.0f) {
            onUpdateSticker(selected.copy(opacity = it))
        }
    }
}

@Composable
private fun EffectsTabPanel(
    colorFilter: PosterColorFilter,
    sparkle: Float,
    grain: Float,
    vignette: Float,
    glow: Float,
    frameColor: Color,
    frameWidthDp: Float,
    onFilterChange: (PosterColorFilter) -> Unit,
    onSparkleChange: (Float) -> Unit,
    onGrainChange: (Float) -> Unit,
    onVignetteChange: (Float) -> Unit,
    onGlowChange: (Float) -> Unit,
    onFrameColorChange: (Color) -> Unit,
    onFrameWidthChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PosterColorFilter.entries.forEach { f ->
            FilterChip(
                selected = colorFilter == f,
                onClick = { onFilterChange(f) },
                label = { Text(f.label, fontSize = 11.sp) },
            )
        }
    }
    LabeledSlider("Kilau", sparkle, 0f..1f, onSparkleChange)
    LabeledSlider("Film Grain", grain, 0f..1f, onGrainChange)
    LabeledSlider("Vignette", vignette, 0f..1f, onVignetteChange)
    LabeledSlider("Soft Glow", glow, 0f..1f, onGlowChange)
    LabeledSlider("Garis Bingkai", frameWidthDp, 0f..6f, onFrameWidthChange)
    if (frameWidthDp > 0.1f) {
        FreeColorSelector(
            label = "Warna Bingkai",
            color = frameColor,
            presets = POSTER_LINE_PALETTE,
            onColorChange = onFrameColorChange,
            showResetWhite = true,
        )
    }
}

/**
 * Free HSV color picker + curated presets so users can choose any custom color
 * for card lines/borders, theme tint, poster frame lines, or stickers.
 */
@Composable
private fun FreeColorSelector(
    label: String,
    color: Color,
    presets: List<Color>,
    onColorChange: (Color) -> Unit,
    showResetWhite: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var lastEmitted by remember { mutableStateOf(color) }
    var hue by remember { mutableFloatStateOf(composeColorToHsv(color)[0]) }
    var sat by remember { mutableFloatStateOf(composeColorToHsv(color)[1]) }
    var bri by remember { mutableFloatStateOf(composeColorToHsv(color)[2]) }

    LaunchedEffect(color) {
        if (color != lastEmitted) {
            val hsv = composeColorToHsv(color)
            if (hsv[1] > 0.01f && hsv[2] > 0.01f) hue = hsv[0]
            sat = hsv[1]
            bri = hsv[2]
            lastEmitted = color
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.84f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, color.copy(alpha = 0.85f), RoundedCornerShape(50))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(0.7.dp, Color.White.copy(alpha = 0.7f), CircleShape),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = composeColorToHex(color),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            FilterChip(
                selected = expanded,
                onClick = { expanded = !expanded },
                label = { Text("Bebas HSV", fontSize = 10.sp) },
                leadingIcon = { Icon(Icons.Rounded.Palette, null, modifier = Modifier.size(13.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFFFA233B),
                    selectedLabelColor = Color.White,
                    selectedLeadingIconColor = Color.White,
                ),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            presets.forEach { preset ->
                val active = composeColorToHex(preset) == composeColorToHex(color)
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(preset)
                        .border(
                            width = if (active) 2.5.dp else 1.dp,
                            color = if (active) Color(0xFFFB5C74) else Color.White.copy(alpha = 0.35f),
                            shape = CircleShape,
                        )
                        .clickable {
                            val hsv = composeColorToHsv(preset)
                            if (hsv[1] > 0.01f && hsv[2] > 0.01f) hue = hsv[0]
                            sat = hsv[1]
                            bri = hsv[2]
                            lastEmitted = preset
                            onColorChange(preset)
                        },
                )
            }
            if (showResetWhite) {
                Text(
                    text = "Reset",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.10f))
                        .clickable {
                            sat = 0f
                            bri = 1f
                            lastEmitted = Color.White
                            onColorChange(Color.White)
                        }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HsvColorBar(
                    label = "Hue",
                    fraction = (hue / 360f).coerceIn(0f, 1f),
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color(0xFFFF0000),
                            Color(0xFFFFFF00),
                            Color(0xFF00FF00),
                            Color(0xFF00FFFF),
                            Color(0xFF0000FF),
                            Color(0xFFFF00FF),
                            Color(0xFFFF0000),
                        ),
                    ),
                    thumbColor = hsvToComposeColor(hue, 1f, 1f),
                    onFractionChange = { f ->
                        hue = (f * 360f).coerceIn(0f, 360f)
                        if (sat < 0.05f && bri > 0.95f) {
                            sat = 0.85f
                        }
                        val next = hsvToComposeColor(hue, sat, bri)
                        lastEmitted = next
                        onColorChange(next)
                    },
                )
                HsvColorBar(
                    label = "Jenuh",
                    fraction = sat.coerceIn(0f, 1f),
                    brush = Brush.horizontalGradient(
                        listOf(
                            hsvToComposeColor(hue, 0f, bri),
                            hsvToComposeColor(hue, 1f, bri),
                        ),
                    ),
                    thumbColor = hsvToComposeColor(hue, sat, bri),
                    onFractionChange = { f ->
                        sat = f.coerceIn(0f, 1f)
                        val next = hsvToComposeColor(hue, sat, bri)
                        lastEmitted = next
                        onColorChange(next)
                    },
                )
                HsvColorBar(
                    label = "Terang",
                    fraction = bri.coerceIn(0f, 1f),
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Black,
                            hsvToComposeColor(hue, sat, 1f),
                        ),
                    ),
                    thumbColor = hsvToComposeColor(hue, sat, bri),
                    onFractionChange = { f ->
                        bri = f.coerceIn(0f, 1f)
                        val next = hsvToComposeColor(hue, sat, bri)
                        lastEmitted = next
                        onColorChange(next)
                    },
                )
            }
        }
    }
}

@Composable
private fun HsvColorBar(
    label: String,
    fraction: Float,
    brush: Brush,
    thumbColor: Color,
    onFractionChange: (Float) -> Unit,
) {
    var widthPx by remember { mutableIntStateOf(1) }
    val currentOnChange by rememberUpdatedState(onFractionChange)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 10.sp,
            modifier = Modifier.width(44.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(22.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        currentOnChange((pos.x / widthPx.toFloat()).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        currentOnChange((change.position.x / widthPx.toFloat()).coerceIn(0f, 1f))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackH = 12.dp.toPx()
                val topY = (size.height - trackH) / 2f
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(0f, topY),
                    size = Size(size.width, trackH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2f),
                )
                val cx = (fraction.coerceIn(0f, 1f) * size.width).coerceIn(8.dp.toPx(), size.width - 8.dp.toPx())
                val cy = size.height / 2f
                drawCircle(color = Color.White, radius = 8.5.dp.toPx(), center = Offset(cx, cy))
                drawCircle(color = thumbColor, radius = 6.5.dp.toPx(), center = Offset(cx, cy))
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 11.sp,
            modifier = Modifier.width(96.dp),
            maxLines = 1,
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFB5C74),
                activeTrackColor = Color(0xFFFB5C74),
            ),
        )
    }
}

@Composable
private fun AddSongCardDialog(
    onDismiss: () -> Unit,
    onSelectSong: (Song) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }

    val searchNow: () -> Unit = {
        val q = query.trim()
        if (q.isNotEmpty() && !searching) {
            searching = true
            scope.launch {
                results = YtMusicRepository.search(q, SearchFilter.SONGS)
                    .getOrNull()
                    ?.filterIsInstance<SearchResult.Track>()
                    ?.map { it.song }
                    ?.take(6)
                    .orEmpty()
                searching = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tambah Kartu Lagu") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Cari lagu…",
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { searchNow() }),
                    )
                    IconButton(onClick = searchNow, enabled = query.isNotBlank() && !searching) {
                        if (searching) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Search, contentDescription = null)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                results.forEach { song ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectSong(song) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = song.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.Add, null)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
