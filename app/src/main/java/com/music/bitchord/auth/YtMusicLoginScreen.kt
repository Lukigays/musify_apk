package com.music.bitchord.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val MUSIC_ORIGIN = "https://music.youtube.com"

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin" +
        "?ltmpl=music&service=youtube&prompt=select_account" +
        "&continue=https%3A%2F%2Fmusic.youtube.com%2F"

private const val TAG = "Musify"

/**
 * In-app Google sign-in for YouTube Music, and channel switcher.
 *
 * In [WebSessionMode.SIGN_IN], as soon as Google redirects back onto
 * `music.youtube.com` with valid auth cookies (`AuthStore.hasApiSid`), this
 * automatically covers the web homepage and polls `ytcfg.LOGGED_IN` so login
 * completes immediately without stopping on the YouTube Music web homepage.
 * If an extra multi-brand-account prompt needs manual confirmation after ~4s,
 * it gracefully reveals the page and lets the user confirm manually.
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
    var autoCaptureActive by remember(mode) { mutableStateOf(false) }
    var captureDelivered by remember(mode) { mutableStateOf(false) }

    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val currentOnUnavailable by rememberUpdatedState(onCaptureUnavailable)
    val currentOnPageReady by rememberUpdatedState(onPageReady)
    val currentOnAutoCompleting by rememberUpdatedState(onAutoCompleting)

    LaunchedEffect(captureRequest) {
        if (captureRequest == 0 || captureDelivered) return@LaunchedEffect
        val view = webView
        if (view == null) {
            currentOnUnavailable()
        } else {
            val session = probeCapture(view)
            if (session != null) {
                captureDelivered = true
                currentOnCaptured(session)
            } else {
                currentOnUnavailable()
            }
        }
    }

    LaunchedEffect(autoCaptureActive, mode) {
        if (!autoCaptureActive || mode != WebSessionMode.SIGN_IN || captureDelivered) return@LaunchedEffect
        val view = webView ?: run {
            autoCaptureActive = false
            currentOnAutoCompleting(false)
            return@LaunchedEffect
        }
        repeat(16) {
            if (captureDelivered) return@LaunchedEffect
            val session = probeCapture(view)
            if (session != null) {
                captureDelivered = true
                currentOnCaptured(session)
                return@LaunchedEffect
            }
            delay(250)
        }
        // Fallback: if an intermediate prompt stayed open after 4s, let user finish manually.
        autoCaptureActive = false
        currentOnAutoCompleting(false)
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            if (mode == WebSessionMode.SIGN_IN) {
                BrowserSession.clearGoogleCookies()
            } else initialCookie?.let(BrowserSession::installGoogleCookies)
            WebView(context).apply {
                if (mode == WebSessionMode.SIGN_IN) {
                    clearCache(true)
                    clearHistory()
                    clearFormData()
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webChromeClient = QuietWebChromeClient()
                configureWebView(this)

                fun maybeTriggerAutoCapture(url: String?) {
                    val onMusicOrigin = url?.startsWith(MUSIC_ORIGIN) == true
                    currentOnPageReady(onMusicOrigin)
                    if (!onMusicOrigin || mode != WebSessionMode.SIGN_IN || captureDelivered || autoCaptureActive) {
                        return
                    }
                    val cookies = CookieManager.getInstance().getCookie(MUSIC_ORIGIN)
                    if (cookies != null && AuthStore.hasApiSid(cookies)) {
                        autoCaptureActive = true
                        currentOnAutoCompleting(true)
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                    ): Boolean {
                        maybeTriggerAutoCapture(request?.url?.toString())
                        return false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        maybeTriggerAutoCapture(url)
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        maybeTriggerAutoCapture(url)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        maybeTriggerAutoCapture(url)
                    }
                }

                webView = this
                loadUrl(if (mode == WebSessionMode.SIGN_IN) LOGIN_URL else "$MUSIC_ORIGIN/")
            }
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
