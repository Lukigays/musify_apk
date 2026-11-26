package com.music.bitchord

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.addon.AddonQualityOption
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the 1-tap official addon preset detection and JSON/URL source
 * export & import helpers in [SourceRegistry].
 */
class SourcePresetAndBackupTest {

    private val before = SourceRegistry.configs.value

    @After
    fun tearDown() {
        SourceRegistry.configs.value = before
    }

    @Test
    fun `preset detection recognizes root, trailing slash, and manifest spellings`() {
        SourceRegistry.configs.value = listOf(
            SourceConfig(kind = SourceKind.JIOSAAVN),
            SourceConfig(kind = SourceKind.YOUTUBE),
        )
        assertFalse(SourceRegistry.hasPresetAddon())

        SourceRegistry.add(
            SourceConfig(
                kind = SourceKind.ADDON,
                label = "Musify Hi-Res · FLAC",
                baseUrl = "${SourceRegistry.PRESET_HIRES_ADDON_URL}/manifest.json",
            ),
        )
        assertTrue(SourceRegistry.hasPresetAddon())
    }

    @Test
    fun `export and import JSON round-trip preserves addons, quality pick, and JioSaavn toggle`() {
        val preset = SourceConfig(
            kind = SourceKind.ADDON,
            label = "Musify Hi-Res · FLAC",
            baseUrl = SourceRegistry.PRESET_HIRES_ADDON_URL,
            enabled = true,
            qualityOptions = listOf(
                AddonQualityOption("LOSSLESS", "FLAC"),
                AddonQualityOption("HIGH", "320kbps"),
            ),
            pickedQuality = "HIGH",
        )
        val secondary = SourceConfig(
            kind = SourceKind.ADDON,
            label = "Backup Addon",
            baseUrl = "https://backup-addon.example.com",
            enabled = false,
        )
        SourceRegistry.configs.value = listOf(
            preset,
            secondary,
            SourceConfig(kind = SourceKind.JIOSAAVN, enabled = true),
            SourceConfig(kind = SourceKind.YOUTUBE, enabled = true),
        )

        val exported = SourceRegistry.exportSourcesJson()

        // Reset to clean state and import back
        SourceRegistry.configs.value = listOf(
            SourceConfig(kind = SourceKind.JIOSAAVN, enabled = false),
            SourceConfig(kind = SourceKind.YOUTUBE, enabled = true),
        )

        val result = SourceRegistry.importSourcesPayload(exported).getOrThrow()
        assertEquals(2, result.added)
        assertEquals(1, result.updated)

        val restored = SourceRegistry.configs.value
        assertEquals(
            listOf(SourceKind.ADDON, SourceKind.ADDON, SourceKind.JIOSAAVN, SourceKind.YOUTUBE),
            restored.map { it.kind },
        )
        assertEquals("HIGH", restored[0].pickedQuality)
        assertFalse(restored[1].enabled)
        assertTrue(restored.single { it.kind == SourceKind.JIOSAAVN }.enabled)
    }

    @Test
    fun `import merges plain URL lists without duplicating existing addons and ignores unsafe schemes`() {
        SourceRegistry.configs.value = listOf(
            SourceConfig(
                kind = SourceKind.ADDON,
                label = "Existing",
                baseUrl = "https://musify-hires-addon.vercel.app",
            ),
            SourceConfig(kind = SourceKind.JIOSAAVN, enabled = false),
            SourceConfig(kind = SourceKind.YOUTUBE, enabled = true),
        )

        val result = SourceRegistry.importSourcesPayload(
            """
            https://musify-hires-addon.vercel.app/manifest.json
            https://extra-addon.example.com/
            file:///etc/passwd
            """.trimIndent(),
        ).getOrThrow()

        assertEquals(1, result.added)
        assertEquals(2, SourceRegistry.configs.value.count { it.kind == SourceKind.ADDON })
        assertTrue(
            SourceRegistry.configs.value.any {
                it.baseUrl == "https://extra-addon.example.com"
            },
        )
    }

    @Test
    fun `import rejects empty or invalid payloads`() {
        assertTrue(SourceRegistry.importSourcesPayload("   ").isFailure)
        assertTrue(SourceRegistry.importSourcesPayload("{bad json").isFailure)
        assertTrue(SourceRegistry.importSourcesPayload("not a url").isFailure)
    }
}
