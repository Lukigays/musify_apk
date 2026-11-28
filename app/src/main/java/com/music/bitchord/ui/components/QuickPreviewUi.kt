package com.music.bitchord.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.playback.QuickPreviewController
import com.music.bitchord.playback.QuickPreviewStartSource
import com.music.bitchord.playback.QuickPreviewState
import com.music.bitchord.playback.QuickPreviewStatus
import com.music.bitchord.ui.components.backdrop.Backdrop
import com.music.bitchord.ui.icons.SfIcons
import dev.chrisbanes.haze.HazeState

/**
 * Compact Liquid Glass strip tucked directly beside the lifted song card in [HeldContextMenu]
 * while **Quick Preview** is running for [song]. Collapses cleanly to zero height when idle
 * or stopped via its dismiss button.
 */
@Composable
fun QuickPreviewCardBar(
    song: Song,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewState by QuickPreviewController.state.collectAsStateWithLifecycle()
    val visible = previewState.isActive && previewState.song?.videoId == song.videoId
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(tween(160)),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(tween(140)),
    ) {
        QuickPreviewControlsContent(
            state = previewState,
            colors = iosMenuColors(),
            onToggle = onToggle,
            onStop = onStop,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

/**
 * Inline banner inside [SongActionsMenu] when Quick Preview was triggered from the `⋮` overflow
 * menu (`IosMenuPopover`) rather than a long-press lift card.
 */
@Composable
fun QuickPreviewMenuBanner(
    song: Song,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewState by QuickPreviewController.state.collectAsStateWithLifecycle()
    val visible = previewState.isActive && previewState.song?.videoId == song.videoId
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(tween(160)),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(tween(140)),
    ) {
        val colors = iosMenuColors()
        Column(modifier = modifier.fillMaxWidth()) {
            QuickPreviewControlsContent(
                state = previewState,
                colors = colors,
                onToggle = onToggle,
                onStop = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
            )
            IosMenuSeparator(colors)
        }
    }
}

@Composable
private fun QuickPreviewControlsContent(
    state: QuickPreviewState,
    colors: IosMenuColors,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val animatedProgress by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = tween(durationMillis = 90),
        label = "quickPreviewProgress",
    )

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QuickPreviewLeadingIndicator(
                status = state.status,
                accent = accent,
                muted = colors.secondaryLabel,
                errorColor = errorColor,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = when (state.status) {
                            QuickPreviewStatus.LOADING -> stringResource(R.string.quick_preview_loading)
                            QuickPreviewStatus.ERROR -> stringResource(R.string.quick_preview_error)
                            else -> stringResource(R.string.quick_preview_label)
                        },
                        color = if (state.status == QuickPreviewStatus.ERROR) errorColor else colors.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (
                        state.startSource == QuickPreviewStartSource.BEST_PART &&
                        (state.status == QuickPreviewStatus.PLAYING || state.status == QuickPreviewStatus.PAUSED)
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(accent.copy(alpha = 0.16f))
                                .padding(horizontal = 5.dp, vertical = 1.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(
                                imageVector = SfIcons.AutoAwesome,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(10.dp),
                            )
                            Text(
                                text = stringResource(R.string.quick_preview_best_part),
                                color = accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Text(
                    text = when (state.status) {
                        QuickPreviewStatus.LOADING ->
                            state.song?.title.orEmpty()
                        QuickPreviewStatus.ERROR ->
                            stringResource(R.string.quick_preview_retry_hint)
                        else ->
                            "${QuickPreviewController.formatDuration(state.elapsedMs)} / ${QuickPreviewController.formatDuration(state.durationMs)}"
                    },
                    color = colors.secondaryLabel,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (state.status != QuickPreviewStatus.LOADING) {
                IconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = when (state.status) {
                            QuickPreviewStatus.PLAYING -> Icons.Rounded.Pause
                            QuickPreviewStatus.ERROR -> Icons.Rounded.Refresh
                            else -> Icons.Rounded.PlayArrow
                        },
                        contentDescription = stringResource(
                            if (state.status == QuickPreviewStatus.PLAYING) {
                                R.string.quick_preview_pause
                            } else {
                                R.string.quick_preview_resume
                            },
                        ),
                        tint = colors.label,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            IconButton(
                onClick = onStop,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = SfIcons.Close,
                    contentDescription = stringResource(R.string.quick_preview_stop),
                    tint = colors.secondaryLabel,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 9.dp)
                .height(3.dp)
                .clip(CircleShape)
                .background(colors.label.copy(alpha = 0.10f)),
        ) {
            if (state.status == QuickPreviewStatus.PLAYING || state.status == QuickPreviewStatus.PAUSED) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        }
    }
}

/**
 * Floating Liquid Glass capsule displayed while holding a song in *Hold-only* Quick Preview mode
 * (where releasing your finger stops the preview without opening the context menu).
 */
@Composable
fun QuickPreviewHoldOverlay(
    target: Pair<Song, HeldItem?>?,
    hazeState: HazeState,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
) {
    val previewState by QuickPreviewController.state.collectAsStateWithLifecycle()
    val visible = target != null
    val song = target?.first ?: previewState.song
    val held = target?.second

    AnimatedVisibility(
        visible = visible && song != null,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(140)),
        modifier = modifier.fillMaxSize(),
    ) {
        if (song == null) return@AnimatedVisibility
        val colors = iosMenuColors()
        val accent = MaterialTheme.colorScheme.primary
        val progress by animateFloatAsState(
            targetValue = previewState.progress,
            animationSpec = tween(durationMillis = 90),
            label = "quickPreviewHoldProgress",
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            val screenHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
            val placeAtTop = held == null || held.bounds.center.y > screenHeightPx * 0.48f
            val alignment = if (placeAtTop) Alignment.TopCenter else Alignment.BottomCenter

            Box(
                modifier = Modifier
                    .align(alignment)
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .iosMenuPanel(
                        colors = colors,
                        translucent = true,
                        backdrop = backdrop,
                        hazeState = hazeState,
                    ),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AsyncImage(
                            model = song.artworkAt(ROW_ART_PX),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .thumbnailBorder(RoundedCornerShape(8.dp)),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = song.title,
                                    color = colors.label,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (previewState.startSource == QuickPreviewStartSource.BEST_PART) {
                                    Text(
                                        text = stringResource(R.string.quick_preview_best_part),
                                        color = accent,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(accent.copy(alpha = 0.16f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    )
                                }
                            }
                            Text(
                                text = when (previewState.status) {
                                    QuickPreviewStatus.LOADING ->
                                        "${song.artist} · ${stringResource(R.string.quick_preview_loading)}"
                                    QuickPreviewStatus.ERROR ->
                                        stringResource(R.string.quick_preview_error)
                                    else ->
                                        "${song.artist} · ${QuickPreviewController.formatDuration(previewState.elapsedMs)} / ${QuickPreviewController.formatDuration(previewState.durationMs)}"
                                },
                                color = colors.secondaryLabel,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(2.dp))
                        QuickPreviewLeadingIndicator(
                            status = previewState.status,
                            accent = accent,
                            muted = colors.secondaryLabel,
                            errorColor = MaterialTheme.colorScheme.error,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(colors.label.copy(alpha = 0.10f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(accent),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickPreviewLeadingIndicator(
    status: QuickPreviewStatus,
    accent: Color,
    muted: Color,
    errorColor: Color,
    size: Dp = 22.dp,
) {
    when (status) {
        QuickPreviewStatus.LOADING -> {
            Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = accent,
                )
            }
        }
        QuickPreviewStatus.ERROR -> {
            Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = SfIcons.ErrorOutline,
                    contentDescription = null,
                    tint = errorColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        QuickPreviewStatus.PLAYING -> {
            QuickPreviewEqBars(color = accent, animating = true, modifier = Modifier.size(size))
        }
        else -> {
            QuickPreviewEqBars(color = muted, animating = false, modifier = Modifier.size(size))
        }
    }
}

@Composable
private fun QuickPreviewEqBars(
    color: Color,
    animating: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "quickPreviewEq")
    val heights = if (animating) {
        listOf(
            transition.animateFloat(
                initialValue = 0.28f,
                targetValue = 0.95f,
                animationSpec = infiniteRepeatable(tween(340, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bar0",
            ).value,
            transition.animateFloat(
                initialValue = 0.92f,
                targetValue = 0.34f,
                animationSpec = infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bar1",
            ).value,
            transition.animateFloat(
                initialValue = 0.36f,
                targetValue = 0.88f,
                animationSpec = infiniteRepeatable(tween(300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bar2",
            ).value,
            transition.animateFloat(
                initialValue = 0.72f,
                targetValue = 0.25f,
                animationSpec = infiniteRepeatable(tween(460, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bar3",
            ).value,
        )
    } else {
        listOf(0.38f, 0.64f, 0.48f, 0.30f)
    }

    Row(
        modifier = modifier.padding(vertical = 3.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        heights.forEach { frac ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(frac.coerceIn(0.18f, 1f))
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
