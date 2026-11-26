package com.music.bitchord

import com.music.bitchord.auth.AuthStore
import com.music.bitchord.auth.extractAuthUserQueryParam
import com.music.bitchord.auth.isGoogleSignInHost
import com.music.bitchord.auth.isMusicYouTubeUrl
import com.music.bitchord.data.innertube.InnertubeParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a captured cookie header actually carries a secret Innertube requests
 * can be signed with.
 *
 * Pinned because the failure this replaces was silent in both directions and
 * cost users their listening history. The old test was
 * `cookie.contains("SAPISID")`, and the string `__Secure-3PAPISID` contains
 * `SAPISID` — so a cookie jar holding only the `__Secure-` forms passed a check
 * for a cookie it did not have. The app then reported itself signed in, sent the
 * cookie, and sent no `Authorization` header, which Google answers as a stranger:
 * the library degraded quietly and no play was ever written to history.
 *
 * The reverse mistake is just as bad and is what makes the name/value split
 * necessary rather than fussy — a substring test is satisfied by a cookie whose
 * *value* happens to contain the text, or by a name that merely ends in it.
 */
class AuthCookieTest {

    @Test
    fun `plain SAPISID is accepted`() {
        assertTrue(AuthStore.hasApiSid("SID=abc; SAPISID=secret; HSID=def"))
    }

    @Test
    fun `a jar with only the secure forms is accepted`() {
        // The case the substring test got right by accident and the
        // header-signing code then got wrong: signed in, but unsigned.
        assertTrue(AuthStore.hasApiSid("SID=abc; __Secure-3PAPISID=secret"))
        assertTrue(AuthStore.hasApiSid("SID=abc; __Secure-1PAPISID=secret"))
    }

    @Test
    fun `leading and trailing whitespace does not hide a cookie`() {
        assertTrue(AuthStore.hasApiSid("SID=abc;SAPISID=secret;HSID=def"))
        assertTrue(AuthStore.hasApiSid("  SAPISID = secret  "))
    }

    @Test
    fun `a jar with no signing secret is rejected`() {
        assertFalse(AuthStore.hasApiSid("SID=abc; HSID=def; SSID=ghi; APISID=jkl"))
        assertFalse(AuthStore.hasApiSid(""))
    }

    @Test
    fun `a present but empty value is rejected`() {
        // A cleared cookie is not a credential, and signing with the empty
        // string produces a digest Google refuses rather than an obvious error.
        assertFalse(AuthStore.hasApiSid("SID=abc; SAPISID=; HSID=def"))
    }

    @Test
    fun `the name has to be the whole name`() {
        // `APISID` is a different cookie and cannot sign anything, and a value
        // that merely mentions the text is not the text.
        assertFalse(AuthStore.hasApiSid("APISID=secret"))
        assertFalse(AuthStore.hasApiSid("NOT-SAPISID=secret"))
        assertFalse(AuthStore.hasApiSid("PREF=tz=SAPISID"))
    }

    @Test
    fun `music youtube url matching requires exact origin`() {
        assertTrue(isMusicYouTubeUrl("https://music.youtube.com"))
        assertTrue(isMusicYouTubeUrl("https://music.youtube.com/"))
        assertTrue(isMusicYouTubeUrl("https://music.youtube.com/?authuser=1"))
        assertFalse(isMusicYouTubeUrl("https://music.youtube.com.evil.com/"))
        assertFalse(isMusicYouTubeUrl("https://accounts.google.com/ServiceLogin?continue=https://music.youtube.com/"))
        assertFalse(isMusicYouTubeUrl(null))
    }

    @Test
    fun `google sign in hosts match auth redirects`() {
        assertTrue(isGoogleSignInHost("https://accounts.google.com/v3/signin/identifier"))
        assertTrue(isGoogleSignInHost("https://accounts.youtube.com/accounts/SetSID"))
        assertTrue(isGoogleSignInHost("https://consent.youtube.com/m?continue=https://music.youtube.com/"))
        assertFalse(isGoogleSignInHost("https://music.youtube.com/"))
    }

    @Test
    fun `authuser query parameter extracts cleanly`() {
        assertEquals("1", extractAuthUserQueryParam("https://music.youtube.com/?hl=id&authuser=1#frag"))
        assertEquals("0", extractAuthUserQueryParam("https://music.youtube.com?authuser=0"))
        assertNull(extractAuthUserQueryParam("https://music.youtube.com/"))
        assertNull(extractAuthUserQueryParam("https://music.youtube.com/?authuser="))
    }

    @Test
    fun `parseAccount handles simpleText and runs`() {
        val json = Json.parseToJsonElement(
            """
            {
              "actions": [{
                "openPopupAction": {
                  "popup": {
                    "multiPageMenuRenderer": {
                      "header": {
                        "activeAccountHeaderRenderer": {
                          "accountName": {"simpleText": "LUKYY"},
                          "channelHandle": {"simpleText": "@lukyy"},
                          "accountPhoto": {"thumbnails": [{"url": "https://img/a.jpg", "width": 88}]}
                        }
                      }
                    }
                  }
                }
              }]
            }
            """.trimIndent(),
        )
        val account = InnertubeParser.parseAccount(json)
        assertEquals("LUKYY", account?.name)
        assertEquals("@lukyy", account?.email)
        assertEquals("https://img/a.jpg", account?.thumbnailUrl)
    }
}
