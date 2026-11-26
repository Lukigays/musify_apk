package com.music.bitchord.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.web.QuietWebChromeClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import kotlin.coroutines.resume

private const val MUSIC_ORIGIN = "https://music.youtube.com"

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin" +
        "?ltmpl=music&service=youtube&prompt=select_account" +
        "&continue=https%3A%2F%2Fmusic.youtube.com%2F"

private const val TAG = "Musify"

/** Max wait for Chromium CookieManager to flush Set-Cookie after reaching music.youtube.com. */
private const val COOKIE_SETTLE_POLLS = 80
private const val COOKIE_SETTLE_STEP_MS = 100L

internal fun isMusicYouTubeUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.lowercase(Locale.ROOT)
    return lower == MUSIC_ORIGIN ||
        lower.startsWith("$MUSIC_ORIGIN/") ||
        lower.startsWith("$MUSIC_ORIGIN?")
}

internal fun isGoogleSignInHost(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.lowercase(Locale.ROOT)
    return lower.startsWith("https://accounts.google.com") ||
        lower.startsWith("https://accounts.youtube.com") ||
        lower.startsWith("https://consent.youtube.com")
}

internal fun extractAuthUserQueryParam(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val query = url.substringAfter('?', "").substringBefore('#')
    if (query.isEmpty()) return null
    return query.split('&').firstNotNullOfOrNull { param ->
        val key = param.substringBefore('=')
        if (key == "authuser") {
            param.substringAfter('=', "").takeIf { it.isNotBlank() }
        } else {
            null
        }
    }
}

/**
 * In-app Google sign-in for YouTube Music, and channel switcher.
 *
 * In [WebSessionMode.SIGN_IN], as soon as Google redirects back onto
 * `music.youtube.com`, the WebView is immediately covered, stopped, and its
 * signed-in cookies are captured straight away so the user is never dropped
 * onto the YouTube Music web homepage or asked to click "Use this profile".
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YtMusicLoginScreen(
    mode: WebSessionMode,
    /** Cookie snapshot to install before opening an existing account's channel picker. */
    initialCookie: String? = null,
    onCaptured: (CapturedSession) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Raise to take the session from the page as it stands. Ignored at its
     * initial value, so arriving on the screen doesn't capture anything.
     */
    captureRequest: Int = 0,
    /** Told when a capture was asked for and there was no session to take. */
    onCaptureUnavailable: () -> Unit = {},
    /** True once a signed-in YouTube Music page is available for confirmation. */
    onPageReady: (Boolean) -> Unit = {},
    /** True while auto-capturing right after Google auth returns to music.youtube.com. */
    onAutoCompleting: (Boolean) -> Unit = {},
    /** For a host that needs the WebView set up differently — the TV app, for its remote. */
    configureWebView: (WebView) -> Unit = {},
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var waitingForMusicCookies by remember(mode) { mutableStateOf(false) }
    var lastMusicUrl by remember(mode) { mutableStateOf<String?>(null) }
    var captureDelivered by remember(mode) { mutableStateOf(false) }

    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val currentOnUnavailable by rememberUpdatedState(onCaptureUnavailable)
    val currentOnPageReady by rememberUpdatedState(onPageReady)
    val currentOnAutoCompleting by rememberUpdatedState(onAutoCompleting)

    fun deliverSignInCookiesIfReady(view: WebView, url: String?): Boolean {
        if (mode != WebSessionMode.SIGN_IN) return false
        if (captureDelivered) return true
        val cookies = CookieManager.getInstance().getCookie(MUSIC_ORIGIN)
        if (cookies == null || !AuthStore.hasApiSid(cookies)) return false
        captureDelivered = true
        waitingForMusicCookies = false
        currentOnAutoCompleting(true)
        view.stopLoading()
        view.visibility = View.GONE
        CookieManager.getInstance().flush()
        currentOnCaptured(
            CapturedSession(
                cookie = cookies,
                pageId = null,
                dataSyncId = null,
                authUser = extractAuthUserQueryParam(url ?: lastMusicUrl),
                visitorData = null,
                clientVersion = null,
                loggedIn = true,
            ),
        )
        return true
    }

    LaunchedEffect(captureRequest) {
        if (captureRequest == 0 || captureDelivered) return@LaunchedEffect
        val view = webView
        if (view == null) {
            currentOnUnavailable()
            return@LaunchedEffect
        }
        if (mode == WebSessionMode.SIGN_IN && deliverSignInCookiesIfReady(view, view.url)) {
            return@LaunchedEffect
        }
        val session = probeCapture(view)
        if (session != null) {
            captureDelivered = true
            currentOnCaptured(session)
        } else {
            currentOnUnavailable()
        }
    }

    LaunchedEffect(waitingForMusicCookies, mode) {
        if (!waitingForMusicCookies || mode != WebSessionMode.SIGN_IN || captureDelivered) {
            return@LaunchedEffect
        }
        repeat(COOKIE_SETTLE_POLLS) {
            if (captureDelivered || !waitingForMusicCookies) return@LaunchedEffect
            val view = webView
            if (view != null && deliverSignInCookiesIfReady(view, lastMusicUrl ?: view.url)) {
                return@LaunchedEffect
            }
            delay(COOKIE_SETTLE_STEP_MS)
        }
        waitingForMusicCookies = false
        webView?.stopLoading()
        currentOnAutoCompleting(false)
        currentOnCaptureUnavailable()
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            if (mode == WebSessionMode.SIGN_IN) {
                BrowserSession.clearGoogleCookies()
            } else {
                initialCookie?.let(BrowserSession::installGoogleCookies)
            }
            WebView(context).apply {
                if (mode == WebSessionMode.SIGN_IN) {
                    clearHistory()
                    clearFormData()
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = browserUserAgent(settings.userAgentString)
                cookieManager.setAcceptThirdPartyCookies(this, true)
                webChromeClient = QuietWebChromeClient()
                configureWebView(this)

                fun handleNavigation(view: WebView?, url: String?) {
                    val onMusicOrigin = isMusicYouTubeUrl(url)
                    if (mode == WebSessionMode.SWITCH_CHANNEL) {
                        currentOnPageReady(onMusicOrigin)
                        return
                    }
                    if (captureDelivered) return
                    if (onMusicOrigin) {
                        lastMusicUrl = url
                        currentOnAutoCompleting(true)
                        if (view != null && deliverSignInCookiesIfReady(view, url)) {
                            return
                        }
                        view?.visibility = View.INVISIBLE
                        waitingForMusicCookies = true
                    } else if (isGoogleSignInHost(url) && waitingForMusicCookies) {
                        waitingForMusicCookies = false
                        currentOnAutoCompleting(false)
                        view?.visibility = View.VISIBLE
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString()
                        handleNavigation(view, url)
                        return mode == WebSessionMode.SIGN_IN && captureDelivered
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        handleNavigation(view, url)
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        handleNavigation(view, url)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        handleNavigation(view, url)
                    }
                }

                webView = this
                loadUrl(if (mode == WebSessionMode.SIGN_IN) LOGIN_URL else "$MUSIC_ORIGIN/")
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.destroy()
        },
    )
}

private suspend fun probeCapture(view: WebView): CapturedSession? {
    val cookies = CookieManager.getInstance().getCookie(MUSIC_ORIGIN)
    if (cookies == null || !AuthStore.hasApiSid(cookies)) return null
    CookieManager.getInstance().flush()
    val raw = suspendCancellableCoroutine<String?> { cont ->
        view.evaluateJavascript(YTCFG_PROBE) { result ->
            if (cont.isActive) cont.resume(result)
        }
    }
    val config = raw.parseConfig() ?: return null
    val loggedIn = config["loggedIn"].let { it is JsonPrimitive && it.content == "true" }
    if (!loggedIn) {
        Log.w(TAG, "waiting for YouTube Music session ytcfg LOGGED_IN")
        return null
    }
    val pageId = config.string("pageId")
    return CapturedSession(
        cookie = cookies,
        dataSyncId = pageId ?: normalizeDataSyncId(config.string("dataSyncId")),
        pageId = pageId,
        authUser = config.string("authUser"),
        visitorData = config.string("visitorData"),
        clientVersion = config.string("clientVersion"),
        loggedIn = true,
    )
}

private const val YTCFG_PROBE = """
(function () {
  try {
    if (!window.ytcfg || !window.ytcfg.get) return null;
    var get = function (key) {
      var value = window.ytcfg.get(key);
      return (value === undefined || value === null || value === '') ? null : String(value);
    };
    return {
      loggedIn: String(!!window.ytcfg.get('LOGGED_IN')),
      pageId: get('DELEGATED_SESSION_ID'),
      dataSyncId: get('DATASYNC_ID'),
      authUser: get('SESSION_INDEX'),
      visitorData: get('VISITOR_DATA'),
      clientVersion: get('INNERTUBE_CLIENT_VERSION')
    };
  } catch (e) {
    return null;
  }
})()
"""

private val json = Json { ignoreUnknownKeys = true }

private fun String?.parseConfig(): JsonObject? =
    this?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
