package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.PlaylistExport
import com.music.bitchord.data.stats.PlaylistExportFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistExportTest {

    private val sampleSongs = listOf(
        Song(
            videoId = "abc123",
            title = "Midnight \"City\", Remix",
            artist = "M83 & Friends",
            thumbnailUrl = "https://img.example/1.jpg",
            durationText = "4:03",
            albumName = "Hurry Up <2011>",
        ),
        Song(
            videoId = "local-1",
            title = "Lossless Track",
            artist = "Indie Band",
            thumbnailUrl = null,
            durationText = "3:15",
            localPath = "/storage/emulated/0/Music/track.flac",
        ),
    )

    @Test
    fun m3u8AndM3uIncludeExtM3uAndExtInfAndLocations() {
        val m3u8 = PlaylistExport.formatSongs("My Mix", sampleSongs, PlaylistExportFormat.M3U8)
        assertTrue(m3u8.startsWith("#EXTM3U"))
        assertTrue(m3u8.contains("#EXTENC:UTF-8"))
        assertTrue(m3u8.contains("#PLAYLIST:My Mix"))
        assertTrue(m3u8.contains("#EXTINF:243,M83 & Friends - Midnight \"City\", Remix"))
        assertTrue(m3u8.contains("https://music.youtube.com/watch?v=abc123"))
        assertTrue(m3u8.contains("/storage/emulated/0/Music/track.flac"))

        val m3u = PlaylistExport.formatSongs("My Mix", sampleSongs, PlaylistExportFormat.M3U)
        assertTrue(m3u.startsWith("#EXTM3U"))
        assertFalse(m3u.contains("#EXTENC:UTF-8"))
        assertTrue(m3u.contains("#EXTINF:195,Indie Band - Lossless Track"))
    }

    @Test
    fun xspfEscapesXmlEntities() {
        val xspf = PlaylistExport.formatSongs("Hits & Vibes", sampleSongs, PlaylistExportFormat.XSPF)
        assertTrue(xspf.contains("<title>Hits &amp; Vibes</title>"))
        assertTrue(xspf.contains("<creator>Musify</creator>"))
        assertTrue(xspf.contains("<creator>M83 &amp; Friends</creator>"))
        assertTrue(xspf.contains("<album>Hurry Up &lt;2011&gt;</album>"))
        assertTrue(xspf.contains("<duration>243000</duration>"))
    }

    @Test
    fun csvQuotesCommasAndDoubleQuotes() {
        val csv = PlaylistExport.formatSongs("Test", sampleSongs, PlaylistExportFormat.CSV)
        assertTrue(csv.startsWith("No,Title,Artist,Album,Duration,DurationSec,VideoId,Location,LocalPath"))
        assertTrue(csv.contains("\"Midnight \"\"City\"\", Remix\""))
    }

    @Test
    fun jsonMarksMusifyAppNeverOldBrand() {
        val json = PlaylistExport.formatSongs("Test", sampleSongs, PlaylistExportFormat.JSON)
        assertTrue(json.contains("\"app\": \"musify\""))
        assertFalse(json.contains("bitchord", ignoreCase = true))
    }

    @Test
    fun txtAndSuggestedFilenameAreClean() {
        val txt = PlaylistExport.formatSongs("Roadtrip", sampleSongs, PlaylistExportFormat.TXT)
        assertTrue(txt.contains("Roadtrip (2 tracks)"))
        assertTrue(txt.contains("01. M83 & Friends - Midnight \"City\", Remix [4:03]"))

        val fn = PlaylistExport.suggestedFilename("Fav Hits! 2026", PlaylistExportFormat.M3U8)
        assertTrue(fn.startsWith("musify-fav-hits-2026-"))
        assertTrue(fn.endsWith(".m3u8"))
    }
}
