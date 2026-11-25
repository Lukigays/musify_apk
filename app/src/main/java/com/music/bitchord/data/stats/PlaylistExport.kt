package com.music.bitchord.data.stats

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class PlaylistExportFormat(
    val extension: String,
    val mimeType: String,
    val label: String,
    val description: String,
) {
    M3U8(
        extension = "m3u8",
        mimeType = "audio/x-mpegurl",
        label = "M3U8 (UTF-8)",
        description = "UTF-8 playlist for VLC, Poweramp, Symfonium & Plex",
    ),
    M3U(
        extension = "m3u",
        mimeType = "audio/x-mpegurl",
        label = "M3U Classic",
        description = "Standard M3U playlist for wide player compatibility",
    ),
    XSPF(
        extension = "xspf",
        mimeType = "application/xspf+xml",
        label = "XSPF (XML)",
        description = "W3C XML Shareable Playlist for desktop & media apps",
    ),
    CSV(
        extension = "csv",
        mimeType = "text/csv",
        label = "CSV (Spreadsheet)",
        description = "Excel & Google Sheets table with metadata and links",
    ),
    TXT(
        extension = "txt",
        mimeType = "text/plain",
        label = "Plain Text (.txt)",
        description = "Clean readable tracklist for notes and sharing",
    ),
    JSON(
        extension = "json",
        mimeType = "application/json",
        label = "JSON Playlist",
        description = "Structured track metadata for backup & automation",
    ),
}

object PlaylistExport {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun suggestedFilename(title: String, format: PlaylistExportFormat): String {
        val slug = title.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .ifBlank { "playlist" }
        val day = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .format(Instant.now().atZone(ZoneId.systemDefault()))
        return "musify-$slug-$day.${format.extension}"
    }

    fun defaultLocationFor(song: Song, downloadedPath: String? = null): String {
        val local = song.localPath?.takeIf { it.isNotBlank() }
            ?: downloadedPath?.takeIf { it.isNotBlank() }
            ?: song.localUri?.takeIf { it.isNotBlank() }
        if (local != null) return local
        val vid = song.videoId.trim()
        return if (vid.startsWith("http://") || vid.startsWith("https://")) {
            vid
        } else {
            "https://music.youtube.com/watch?v=$vid"
        }
    }

    fun formatSongs(
        title: String,
        songs: List<Song>,
        format: PlaylistExportFormat,
        exportedAt: String = Instant.now().toString(),
        locationResolver: (Song) -> String = { defaultLocationFor(it) },
    ): String = when (format) {
        PlaylistExportFormat.M3U -> formatM3u(title, songs, utf8 = false, locationResolver)
        PlaylistExportFormat.M3U8 -> formatM3u(title, songs, utf8 = true, locationResolver)
        PlaylistExportFormat.XSPF -> formatXspf(title, songs, exportedAt, locationResolver)
        PlaylistExportFormat.CSV -> formatCsv(songs, locationResolver)
        PlaylistExportFormat.TXT -> formatTxt(title, songs, exportedAt, locationResolver)
        PlaylistExportFormat.JSON -> formatJson(title, songs, exportedAt, locationResolver)
    }

    suspend fun exportToUri(
        context: Context,
        target: Uri,
        title: String,
        songs: List<Song>,
        format: PlaylistExportFormat,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val saved = Downloads.saved.value
            val payload = formatSongs(
                title = title,
                songs = songs,
                format = format,
                locationResolver = { song -> defaultLocationFor(song, saved[song.videoId]) },
            )
            context.contentResolver.openOutputStream(target, "wt")?.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: error("Couldn't open file for writing")
            songs.size
        }
    }

    suspend fun writeShareFile(
        context: Context,
        title: String,
        songs: List<Song>,
        format: PlaylistExportFormat,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val saved = Downloads.saved.value
            val payload = formatSongs(
                title = title,
                songs = songs,
                format = format,
                locationResolver = { song -> defaultLocationFor(song, saved[song.videoId]) },
            )
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, suggestedFilename(title, format))
            file.writeText(payload, Charsets.UTF_8)
            file
        }
    }

    private fun cleanLine(value: String?): String =
        value.orEmpty().replace('\r', ' ').replace('\n', ' ').trim()

    private fun formatM3u(
        title: String,
        songs: List<Song>,
        utf8: Boolean,
        locationResolver: (Song) -> String,
    ): String = buildString {
        appendLine("#EXTM3U")
        if (utf8) appendLine("#EXTENC:UTF-8")
        appendLine("#PLAYLIST:${cleanLine(title.ifBlank { "Musify Playlist" })}")
        songs.forEach { song ->
            val seconds = TrackMatcher.secondsOf(song.durationText) ?: -1
            val songTitle = cleanLine(song.title.ifBlank { "Untitled" })
            val songArtist = cleanLine(song.artist)
            val display = if (songArtist.isNotBlank()) "$songArtist - $songTitle" else songTitle
            if (utf8) {
                if (songArtist.isNotBlank()) appendLine("#EXTART:$songArtist")
                val album = cleanLine(song.albumName)
                if (album.isNotBlank()) appendLine("#EXTALB:$album")
            }
            appendLine("#EXTINF:$seconds,$display")
            appendLine(locationResolver(song))
        }
    }

    private fun escapeXml(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private fun formatXspf(
        title: String,
        songs: List<Song>,
        exportedAt: String,
        locationResolver: (Song) -> String,
    ): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<playlist version="1" xmlns="http://xspf.org/ns/0/">""")
        appendLine("  <title>${escapeXml(title.ifBlank { "Musify Playlist" })}</title>")
        appendLine("  <creator>Musify</creator>")
        appendLine("  <date>${escapeXml(exportedAt)}</date>")
        appendLine("  <trackList>")
        songs.forEach { song ->
            appendLine("    <track>")
            appendLine("      <location>${escapeXml(locationResolver(song))}</location>")
            if (song.title.isNotBlank()) {
                appendLine("      <title>${escapeXml(cleanLine(song.title))}</title>")
            }
            if (song.artist.isNotBlank()) {
                appendLine("      <creator>${escapeXml(cleanLine(song.artist))}</creator>")
            }
            song.albumName?.takeIf { it.isNotBlank() }?.let { album ->
                appendLine("      <album>${escapeXml(cleanLine(album))}</album>")
            }
            TrackMatcher.secondsOf(song.durationText)?.let { sec ->
                appendLine("      <duration>${sec * 1000L}</duration>")
            }
            song.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { art ->
                appendLine("      <image>${escapeXml(art)}</image>")
            }
            appendLine("    </track>")
        }
        appendLine("  </trackList>")
        appendLine("</playlist>")
    }

    private fun csvCell(value: String?): String {
        val clean = value.orEmpty()
        return if (clean.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${clean.replace("\"", "\"\"")}\""
        } else {
            clean
        }
    }

    private fun formatCsv(
        songs: List<Song>,
        locationResolver: (Song) -> String,
    ): String = buildString {
        appendLine("No,Title,Artist,Album,Duration,DurationSec,VideoId,Location,LocalPath")
        songs.forEachIndexed { idx, song ->
            val sec = TrackMatcher.secondsOf(song.durationText)?.toString().orEmpty()
            val row = listOf(
                (idx + 1).toString(),
                song.title,
                song.artist,
                song.albumName.orEmpty(),
                song.durationText.orEmpty(),
                sec,
                song.videoId,
                locationResolver(song),
                song.localPath.orEmpty(),
            ).joinToString(",") { csvCell(it) }
            appendLine(row)
        }
    }

    private fun formatTxt(
        title: String,
        songs: List<Song>,
        exportedAt: String,
        locationResolver: (Song) -> String,
    ): String = buildString {
        appendLine("${title.ifBlank { "Musify Playlist" }} (${songs.size} tracks)")
        appendLine("Exported by Musify • $exportedAt")
        appendLine("=".repeat(48))
        songs.forEachIndexed { idx, song ->
            val num = (idx + 1).toString().padStart(2, '0')
            val dur = song.durationText?.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
            val artist = song.artist.takeIf { it.isNotBlank() }?.let { "$it - " }.orEmpty()
            appendLine("$num. $artist${song.title}$dur")
            appendLine("    ${locationResolver(song)}")
        }
    }

    private fun formatJson(
        title: String,
        songs: List<Song>,
        exportedAt: String,
        locationResolver: (Song) -> String,
    ): String {
        val doc = PlaylistJsonFile(
            app = "musify",
            format = "playlist",
            title = title.ifBlank { "Musify Playlist" },
            exportedAt = exportedAt,
            trackCount = songs.size,
            tracks = songs.mapIndexed { idx, song ->
                PlaylistJsonTrack(
                    index = idx + 1,
                    title = song.title,
                    artist = song.artist,
                    album = song.albumName,
                    duration = song.durationText,
                    durationSeconds = TrackMatcher.secondsOf(song.durationText),
                    videoId = song.videoId,
                    location = locationResolver(song),
                    localPath = song.localPath,
                    thumbnailUrl = song.thumbnailUrl,
                )
            },
        )
        return json.encodeToString(PlaylistJsonFile.serializer(), doc)
    }

    @Serializable
    private data class PlaylistJsonFile(
        val app: String,
        val format: String,
        val title: String,
        val exportedAt: String,
        val trackCount: Int,
        val tracks: List<PlaylistJsonTrack>,
    )

    @Serializable
    private data class PlaylistJsonTrack(
        val index: Int,
        val title: String,
        val artist: String,
        val album: String? = null,
        val duration: String? = null,
        val durationSeconds: Int? = null,
        val videoId: String,
        val location: String,
        val localPath: String? = null,
        val thumbnailUrl: String? = null,
    )
}
