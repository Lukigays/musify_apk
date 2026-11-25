package com.music.bitchord.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.PlaylistExport
import com.music.bitchord.data.stats.PlaylistExportFormat
import com.music.bitchord.ui.theme.MusifyCoral
import kotlinx.coroutines.launch

data class ExportDataSource(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val songs: List<Song>? = null,
    val loadSongs: (suspend () -> Result<List<Song>>)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistExportSheet(
    sources: List<ExportDataSource>,
    onDismiss: () -> Unit,
    initialSourceIndex: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedSourceIndex by remember(sources) {
        mutableIntStateOf(initialSourceIndex.coerceIn(0, (sources.size - 1).coerceAtLeast(0)))
    }
    var selectedFormat by remember { mutableStateOf(PlaylistExportFormat.M3U8) }
    var resolvedSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loadingSongs by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val activeSource = sources.getOrNull(selectedSourceIndex)

    LaunchedEffect(activeSource?.id, sources) {
        val src = activeSource
        if (src == null) {
            resolvedSongs = emptyList()
            loadError = null
            loadingSongs = false
            return@LaunchedEffect
        }
        if (src.songs != null) {
            resolvedSongs = src.songs
            loadError = null
            loadingSongs = false
            return@LaunchedEffect
        }
        val loader = src.loadSongs
        if (loader == null) {
            resolvedSongs = emptyList()
            loadError = null
            loadingSongs = false
            return@LaunchedEffect
        }
        loadingSongs = true
        loadError = null
        loader().fold(
            onSuccess = {
                resolvedSongs = it
                loadError = null
            },
            onFailure = {
                resolvedSongs = emptyList()
                loadError = it.message ?: context.getString(R.string.couldnt_load_tracks)
            },
        )
        loadingSongs = false
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { targetUri ->
        if (targetUri == null || activeSource == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            PlaylistExport.exportToUri(
                context = context,
                target = targetUri,
                title = activeSource.title,
                songs = resolvedSongs,
                format = selectedFormat,
            ).fold(
                onSuccess = { count ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.export_playlist_saved, count, selectedFormat.label),
                        Toast.LENGTH_SHORT,
                    ).show()
                    onDismiss()
                },
                onFailure = { err ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.export_failed, err.message ?: context.getString(R.string.unknown_error)),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
            busy = false
        }
    }

    val previewText = remember(activeSource?.title, resolvedSongs, selectedFormat) {
        if (activeSource == null || resolvedSongs.isEmpty()) {
            ""
        } else {
            PlaylistExport.formatSongs(
                title = activeSource.title,
                songs = resolvedSongs.take(6),
                format = selectedFormat,
            ).lines().take(7).joinToString("\n")
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MusifyCoral.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Description,
                        contentDescription = null,
                        tint = MusifyCoral,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.export_playlist_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.export_playlist_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (sources.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.export_choose_source),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        sources.forEachIndexed { index, src ->
                            val selected = index == selectedSourceIndex
                            val shape = RoundedCornerShape(14.dp)
                            Column(
                                modifier = Modifier
                                    .clip(shape)
                                    .background(
                                        if (selected) MusifyCoral.copy(alpha = 0.18f)
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (selected) MusifyCoral else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                        shape = shape,
                                    )
                                    .clickable { selectedSourceIndex = index }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = src.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) MusifyCoral else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (src.subtitle.isNotBlank()) {
                                    Text(
                                        text = src.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (activeSource != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = activeSource.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (activeSource.subtitle.isNotBlank()) {
                            Text(
                                text = activeSource.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        text = if (loadingSongs) "..." else stringResource(R.string.local_playlist_subtitle, resolvedSongs.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MusifyCoral,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.export_choose_format),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlaylistExportFormat.entries.forEach { format ->
                        val selected = format == selectedFormat
                        val shape = RoundedCornerShape(12.dp)
                        Row(
                            modifier = Modifier
                                .clip(shape)
                                .background(
                                    if (selected) MusifyCoral
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .clickable { selectedFormat = format }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(
                                text = ".${format.extension.uppercase()}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                Text(
                    text = "${selectedFormat.label} — ${selectedFormat.description}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (loadingSongs) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MusifyCoral, modifier = Modifier.size(28.dp))
                }
            } else if (loadError != null) {
                Text(
                    text = loadError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (resolvedSongs.isEmpty()) {
                Text(
                    text = stringResource(R.string.export_empty_source),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                        .border(0.75.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "${stringResource(R.string.local_playlist_subtitle, resolvedSongs.size)} • .${selectedFormat.extension.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MusifyCoral,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val enabled = !loadingSongs && !busy && resolvedSongs.isNotEmpty() && activeSource != null
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        val src = activeSource ?: return@Button
                        createDocumentLauncher.launch(PlaylistExport.suggestedFilename(src.title, selectedFormat))
                    },
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MusifyCoral,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1.3f).height(48.dp),
                ) {
                    Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.export_save_file), fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = {
                        val src = activeSource ?: return@OutlinedButton
                        scope.launch {
                            busy = true
                            PlaylistExport.writeShareFile(context, src.title, resolvedSongs, selectedFormat)
                                .onSuccess { file ->
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = selectedFormat.mimeType
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, src.title)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, src.title))
                                }
                                .onFailure { err ->
                                    Toast.makeText(
                                        context,
                                        err.message ?: context.getString(R.string.unknown_error),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            busy = false
                        }
                    },
                    enabled = enabled,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.share))
                }
            }

            OutlinedButton(
                onClick = {
                    val src = activeSource ?: return@OutlinedButton
                    val content = PlaylistExport.formatSongs(src.title, resolvedSongs, selectedFormat)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText(src.title, content))
                    Toast.makeText(
                        context,
                        context.getString(R.string.export_copied_clipboard, selectedFormat.label),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                enabled = enabled,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.export_copy_text))
            }
        }
    }
}
