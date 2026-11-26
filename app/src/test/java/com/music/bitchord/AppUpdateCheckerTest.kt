package com.music.bitchord

import com.music.bitchord.data.AppUpdateChecker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies ABI-first APK asset selection in [AppUpdateChecker].
 */
class AppUpdateCheckerTest {

    private fun releaseWith(vararg assets: Pair<String, String>): JsonObject {
        val entries = assets.joinToString(",") { (name, state) ->
            """
            {
              "name": "$name",
              "state": "$state",
              "browser_download_url": "https://example.com/releases/$name"
            }
            """.trimIndent()
        }
        return Json.parseToJsonElement("""{"assets":[$entries]}""").jsonObject
    }

    @Test
    fun `prefers matching split ABI APK over universal APK`() {
        val release = releaseWith(
            "Musify-v1.0.8.apk" to "uploaded",
            "Musify-v1.0.8_arm64-v8a.apk" to "uploaded",
            "Musify-v1.0.8_armeabi-v7a.apk" to "uploaded",
            "MusifyTV-v1.0.8_arm64-v8a.apk" to "uploaded",
        )

        assertEquals(
            "https://example.com/releases/Musify-v1.0.8_arm64-v8a.apk",
            AppUpdateChecker.selectApkAssetUrl(
                release,
                prefix = "Musify-",
                supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            ),
        )
        assertEquals(
            "https://example.com/releases/Musify-v1.0.8_armeabi-v7a.apk",
            AppUpdateChecker.selectApkAssetUrl(
                release,
                prefix = "Musify-",
                supportedAbis = listOf("armeabi-v7a"),
            ),
        )
    }

    @Test
    fun `falls back to universal APK when no split matches device ABI`() {
        val release = releaseWith(
            "Musify-v1.0.8.apk" to "uploaded",
            "Musify-v1.0.8_arm64-v8a.apk" to "uploaded",
        )

        assertEquals(
            "https://example.com/releases/Musify-v1.0.8.apk",
            AppUpdateChecker.selectApkAssetUrl(
                release,
                prefix = "Musify-",
                supportedAbis = listOf("riscv64"),
            ),
        )
    }

    @Test
    fun `skips un-uploaded assets and keeps TV vs phone prefixes separate`() {
        val release = releaseWith(
            "Musify-v1.0.8_arm64-v8a.apk" to "starter",
            "Musify-v1.0.8.apk" to "uploaded",
            "MusifyTV-v1.0.8_arm64-v8a.apk" to "uploaded",
        )

        assertEquals(
            "https://example.com/releases/Musify-v1.0.8.apk",
            AppUpdateChecker.selectApkAssetUrl(
                release,
                prefix = "Musify-",
                supportedAbis = listOf("arm64-v8a"),
            ),
        )
        assertEquals(
            "https://example.com/releases/MusifyTV-v1.0.8_arm64-v8a.apk",
            AppUpdateChecker.selectApkAssetUrl(
                release,
                prefix = "MusifyTV-",
                supportedAbis = listOf("arm64-v8a"),
            ),
        )
    }

    @Test
    fun `returns null when no uploaded asset matches prefix`() {
        val release = releaseWith("MusifyTV-v1.0.8.apk" to "uploaded")

        assertNull(
            AppUpdateChecker.selectApkAssetUrl(
                release,
                prefix = "Musify-",
                supportedAbis = listOf("arm64-v8a"),
            ),
        )
    }
}
