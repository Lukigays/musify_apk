package com.music.bitchord.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.music.bitchord.R
import com.music.bitchord.data.web.QuietWebChromeClient
import com.music.bitchord.ui.theme.MusifyCoral
import kotlinx.coroutines.delay

/**
 * Land on Spotify's lightweight account status page after login rather than the
 * heavy open.spotify.com web player homepage, so the user isn't taken to the
 * web homepage before Musify completes login.
 */
private const val LOGIN_URL =
    "https://accounts.spotify.com/login?continue=https%3A%2F%2Faccounts.spotify.com%2Fen%2Fstatus"

private val SPOTIFY_COOKIE_URLS = listOf(
    "https://open.spotify.com",
    "https://accounts.spotify.com",
    "https://www.spotify.com",
    "https://spotify.com",
)

private val LOGIN_HOST_SUFFIXES = listOf(
    "spotify.com", "scdn.co", "google.com", "gstatic.com", "facebook.com", "apple.com",
)

private fun hostMatches(uri: Uri?, suffixes: List<String>): Boolean {
    if (uri?.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    return suffixes.any { host == it || host.endsWith(".$it") }
}

private fun isLoginHost(uri: Uri?) = hostMatches(uri, LOGIN_HOST_SUFFIXES)

private fun isSpotifyPostLoginUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    val path = uri.path.orEmpty().lowercase()
    return when {
        host == "open.spotify.com" -> true
        host == "accounts.spotify.com" && (path == "/status" || path.endsWith("/status")) -> true
        else -> false
    }
}

private fun readSpdcCookie(): String? {
    val manager = CookieManager.getInstance()
    for (url in SPOTIFY_COOKIE_URLS) {
        val raw = manager.getCookie(url) ?: continue
        val token = raw.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("sp_dc=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
        if (token != null) return token
    }
    return null
}

/**
 * Signs the login WebView out of Spotify. Disconnecting only forgot the
 * stored cookie, and the WebView's own jar would have signed the next
 * "Sign in" straight back in. Only Spotify's cookies go — the same jar holds
 * the YouTube Music sign-in.
 */
fun clearSpotifyWebSession() {
    val cookies = CookieManager.getInstance()
    for (url in SPOTIFY_COOKIE_URLS) {
        val names = cookies.getCookie(url)?.split(";")
            ?.map { it.substringBefore("=").trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        for (name in names) {
            val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
            cookies.setCookie(url, expired)
            cookies.setCookie(url, "$expired; Domain=.spotify.com")
        }
    }
    cookies.flush()
}

/**
 * In-app Spotify sign-in. Lets accounts.spotify.com handle authentication,
 * then captures `sp_dc` immediately upon landing on `/status` (or `open.spotify.com`)
 * without ever rendering the Spotify web homepage.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(
    onConnected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var finishingLogin by remember { mutableStateOf(false) }
    var delivered by remember { mutableStateOf(false) }
    val currentOnConnected by rememberUpdatedState(onConnected)

    // Brief cookie-flush fallback if redirect hit /status a split second before CookieManager persisted sp_dc.
    LaunchedEffect(finishingLogin) {
        if (!finishingLogin || delivered) return@LaunchedEffect
        repeat(15) {
            val token = readSpdcCookie()
            if (token != null) {
                delivered = true
                CookieManager.getInstance().flush()
                webView?.stopLoading()
                webView?.visibility = View.GONE
                currentOnConnected(token)
                return@LaunchedEffect
            }
            delay(200)
        }
        finishingLogin = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = browserUserAgent(settings.userAgentString)
                    setBackgroundColor(0xFF121212.toInt())
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webChromeClient = QuietWebChromeClient()

                    fun tryComplete(view: WebView, url: String?): Boolean {
                        if (delivered || !isSpotifyPostLoginUrl(url)) return false
                        finishingLogin = true
                        val token = readSpdcCookie() ?: return false
                        delivered = true
                        CookieManager.getInstance().flush()
                        view.stopLoading()
                        view.visibility = View.GONE
                        currentOnConnected(token)
                        return true
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val target = request.url
                            if (!isLoginHost(target)) return true
                            if (tryComplete(view, target.toString())) return true
                            return false
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            canGoBack = view.canGoBack()
                            tryComplete(view, url)
                        }

                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            canGoBack = view.canGoBack()
                            tryComplete(view, url)
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            canGoBack = view.canGoBack()
                            tryComplete(view, url)
                        }
                    }
                    webView = this
                    loadUrl(LOGIN_URL)
                }
            },
            onRelease = { it.destroy() },
        )

        AnimatedVisibility(
            visible = finishingLogin,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = MusifyCoral, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.auto_signing_in),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
    }

    BackHandler(enabled = canGoBack && !finishingLogin) {
        webView?.goBack()
    }
}
