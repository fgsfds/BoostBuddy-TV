// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.ui.widgets

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.slartus.boostbuddy.data.ktor.USER_AGENT
import ru.slartus.boostbuddy.ui.common.BackHandlerEffect
import ru.slartus.boostbuddy.ui.common.LocalPlatformConfiguration
import ru.slartus.boostbuddy.utils.Platform
import ru.slartus.boostbuddy.utils.isBoostyWebOrigin
import kotlin.coroutines.resume


@Composable
actual fun WebView(url: String, clickCoors: Offset?, onCookieChange: (String) -> Unit) {
    val isAtv = LocalPlatformConfiguration.current.platform == Platform.AndroidTV
    val scope = rememberCoroutineScope()
    val latestOnCookieChange by rememberUpdatedState(onCookieChange)
    var buttons by remember { mutableStateOf<ImmutableList<HtmlButton>>(persistentListOf()) }
    var webError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val webViewMajorVersion = remember(context) {
        val ua = WebSettings.getDefaultUserAgent(context)
        Regex("""Chrome/(\d+)""").find(ua)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }
    var buttonsJob: Job by remember { mutableStateOf(Job(null)) }
    var webViewFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val scanButtons: (view: WebView?) -> Unit = { view ->
        buttonsJob.cancel()
        buttonsJob = scope.launch {
            delay(100)
            buttons =
                view?.getAllButtons().orEmpty()
                    .distinctBy { it.id }
                    .sortedBy { it.text ?: "яяя" }
                    .toImmutableList()
        }
    }
    val reportCookies: (String?) -> Unit = { pageUrl ->
        if (isBoostyWebOrigin(pageUrl)) {
            latestOnCookieChange(CookieManager.getInstance().getCookie(pageUrl).orEmpty())
        }
    }
    val webView = remember(context) {
        WebView(context).apply {
            this.setOnFocusChangeListener { _, b ->
                webViewFocused = b
            }
            settings.apply {
                javaScriptEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(true)
                userAgentString = USER_AGENT
            }
            webViewClient = object : WebViewClient() {
                override fun onLoadResource(view: WebView?, url: String?) {
                    reportCookies(url)
                    if (isAtv) {
                        scanButtons(this@apply)
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    // Never send account credentials over an unverified connection.
                    handler?.cancel()
                    webError = "Не удалось проверить защищённое соединение. Проверьте дату и время на ТВ и обновления Android System WebView."
                    Log.w(WV_TAG, "Web login cancelled because TLS verification failed")
                }

                override fun onPageStarted(webView: WebView, url: String?, favicon: Bitmap?) {
                    webError = null
                    reportCookies(url)
                }

                override fun onPageFinished(view: WebView?, currentUrl: String?) {
                    super.onPageFinished(view, currentUrl)
                    reportCookies(currentUrl)
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    Log.e(
                        WV_TAG,
                        "Web login resource failed: code=$errorCode"
                    )
                }

                // Перехватываем HTML-документы и инжектируем полифиллы в <head>
                // ПЕРЕД любыми скриптами страницы. Работает только на Android 7 (API ≤ 25),
                // где Chromium не поддерживает globalThis, queueMicrotask и др.
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? =
                    injectPolyfillsIfNeeded(request) ?: super.shouldInterceptRequest(view, request)
            }
            // Authentication pages may print credentials or token-bearing URLs.
            // Do not copy their JavaScript console to application logs.
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean = true
            }
        }
    }

    BackHandlerEffect(enabled = webViewFocused) {
        if (webViewFocused)
            webView.clearFocus()
    }

    Column {
        webError?.let { message ->
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // boosty.to использует type="module" (ES modules) — требует Chrome 63+.
        // На старых WebView страница загружается, но JS не работает.
        if (webViewMajorVersion in 1..62) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    modifier = Modifier.padding(12.dp),
                    text = "Ваш Android System WebView устарел (версия $webViewMajorVersion, требуется 63+). " +
                            "Обновите приложение «Android System WebView» в Google Play — после этого авторизация заработает.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (isAtv) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable { scanButtons(webView) }
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "rescan",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                items(buttons, key = { it.id }) { button ->
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable {
                                val buttonId = Json.encodeToString(button.id)
                                if (button.isInput) {
                                    webView.click(0f, 0f)
                                    webView.evaluateJavascript(
                                        "setTimeout(function() {" +
                                                "var target=document.getElementById($buttonId);" +
                                                "if(target){target.focus();target.click();}" +
                                                "}, 1000);",

                                        null
                                    )
                                } else {
                                    webView.evaluateJavascript(
                                        "(function(){var target=document.getElementById($buttonId);if(target){target.click();}})();",
                                        null
                                    )
                                }
                            }
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = button.nodeText,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
        AndroidView(
            modifier = Modifier
                .focusRequester(focusRequester)
                .weight(1f),
            factory = {
                webView
            },
            onRelease = { view ->
                buttonsJob.cancel()
                view.stopLoading()
                view.webViewClient = WebViewClient()
                view.webChromeClient = null
                view.setOnFocusChangeListener(null)
                view.removeAllViews()
                view.destroy()
            },
        )
    }
    LaunchedEffect(clickCoors) {
        if (clickCoors != null) {
            webView.simulateClick(scope, clickCoors.x, clickCoors.y)
        }
    }
    LaunchedEffect(url) {
        webView.loadUrl(url)
    }
}

private suspend fun WebView.getAllButtons(): List<HtmlButton> = suspendCancellableCoroutine { continuation ->
    val script =
        "Array.from(document.querySelectorAll(\"button, input, [role='button']\")).forEach(b => b.id = b.id||(Date.now().toString(36) + Math.random().toString(36).substr(2)));" +
                "return Array.from(document.querySelectorAll(\"button, input, [role='button']\")).map(function(b){return {id: b.id, node: b.nodeName, text: b.innerText||b.placeholder||b.getAttribute('data-provider')}});"

    val functionScript = "(function() { $script; })();"
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    evaluateJavascript(functionScript) { result ->
        if (!continuation.isActive) return@evaluateJavascript
        val buttons = runCatching {
            json.decodeFromString(
                ListSerializer(HtmlButton.serializer()),
                result
            )
        }.getOrDefault(emptyList())
        continuation.resume(buttons)
    }
}

@Serializable
private data class HtmlButton(val id: String, val text: String?, val node: String) {
    val isInput: Boolean = node.lowercase() == "input"
    val nodeText: String = if (isInput) "[${text ?: id}]" else text ?: id
}

private fun View.click(x: Float, y: Float) {
    val downTime: Long = SystemClock.uptimeMillis()
    val eventTime: Long = SystemClock.uptimeMillis()
    val properties = arrayOfNulls<PointerProperties>(1)
    val pp1 = PointerProperties()
    pp1.id = 0
    pp1.toolType = MotionEvent.TOOL_TYPE_FINGER
    properties[0] = pp1
    val pointerCoords = arrayOfNulls<PointerCoords>(1)
    val pc1 = PointerCoords()
    pc1.x = x + scrollX
    pc1.y = y + scrollY
    pc1.pressure = 1f
    pc1.size = 1f
    pointerCoords[0] = pc1
    var motionEvent = MotionEvent.obtain(
        downTime, eventTime,
        MotionEvent.ACTION_DOWN, 1, properties,
        pointerCoords, 0, 0, 1f, 1f, 0, 0, 0, 0
    )
    dispatchTouchEvent(motionEvent)
    motionEvent.recycle()
    motionEvent = MotionEvent.obtain(
        downTime, eventTime,
        MotionEvent.ACTION_UP, 1, properties,
        pointerCoords, 0, 0, 1f, 1f, 0, 0, 0, 0
    )
    dispatchTouchEvent(motionEvent)
    motionEvent.recycle()
}

private fun View.simulateClick(coroutineScope: CoroutineScope, x: Float, y: Float) {
    coroutineScope.launch {
        click(x, y)
        //flingScroll(x.toInt(), y.toInt())
        delay(300)

        clearFocus()
    }
}

// Перехватываем HTML-документы и инжектируем полифиллы в <head>
// ПЕРЕД любыми скриптами страницы. Работает только на Android 7 (API ≤ 25),
// где Chromium не поддерживает globalThis, queueMicrotask и др.
// Возвращает null, если перехват не нужен — вызывающий должен делегировать super.
private fun injectPolyfillsIfNeeded(request: WebResourceRequest?): WebResourceResponse? {
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) return null
    request ?: return null
    // A request body is unavailable here: intercepting a login POST as a GET
    // breaks authentication. Keep the compatibility shim on the website only.
    if (!request.isForMainFrame || request.method != "GET") return null
    val urlString = request.url.toString()
    if (!isBoostyWebOrigin(urlString)) return null
    val accept = request.requestHeaders.entries
        .firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value.orEmpty()
    if (!accept.contains("text/html")) return null
    return try {
        val okRequest = Request.Builder()
            .url(urlString)
            .also { builder ->
                request.requestHeaders.forEach { (k, v) -> builder.header(k, v) }
                val cookies = CookieManager.getInstance().getCookie(urlString)
                if (!cookies.isNullOrEmpty()) builder.header("Cookie", cookies)
            }
            .build()
        polyfillOkHttpClient.newCall(okRequest).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val mimeType = response.header("Content-Type")
                ?.substringBefore(';')?.trim() ?: return null
            if (!mimeType.equals("text/html", ignoreCase = true)) return null
            response.headers.values("Set-Cookie").forEach {
                CookieManager.getInstance().setCookie(urlString, it)
            }
            var html = body.string()
            html = html.replace(Regex("""\s*type="module""""), "")
            val headMatch = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
            val modified = if (headMatch != null) {
                html.substring(0, headMatch.range.last + 1) +
                    android7Polyfill + html.substring(headMatch.range.last + 1)
            } else html
            val headers = response.headers.toMultimap().entries
                .filterNot { it.key.lowercase() in setOf("content-length", "content-encoding", "set-cookie") }
                .associate { it.key to it.value.joinToString(", ") }
            WebResourceResponse(
                mimeType, "utf-8", response.code, response.message.ifBlank { "OK" },
                headers, modified.byteInputStream(Charsets.UTF_8),
            )
        }
    } catch (e: Exception) {
        Log.w(WV_TAG, "Website compatibility request failed: ${e.javaClass.simpleName}")
        null
    }
}

private const val WV_TAG = "BoostWebView"

// OkHttpClient для скачивания HTML в shouldInterceptRequest на Android 7.
// Использует okhttp3 (не com.android.okhttp) — он поддерживает современные
// cipher suites и TLS 1.2 там, где системный HttpURLConnection ломается.
private val polyfillOkHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .followRedirects(false)
        .build()
}

// Полифилл для Android 7 (Chromium < 71): globalThis, queueMicrotask, Promise.allSettled
private val android7Polyfill = """<script>""" +
        """(function(){""" +
        """if(typeof globalThis==='undefined'){globalThis=window;}""" +
        """if(typeof queueMicrotask==='undefined'){queueMicrotask=function(f){Promise.resolve().then(f);};}""" +
        """if(!Promise.allSettled){Promise.allSettled=function(ps){return Promise.all(ps.map(function(p){return Promise.resolve(p).then(function(v){return{status:'fulfilled',value:v};},function(e){return{status:'rejected',reason:e};});}));};}""" +
        """if(!Object.fromEntries){Object.fromEntries=function(it){var o={};for(var e of it){o[e[0]]=e[1];}return o;};}""" +
        """})();""" +
        """</script>"""
