package com.example.render

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Offscreen 1080x1920 / 1920x1080 deterministic WebView frame renderer that drives
 * `window.seekToTime(seconds)` frame-by-frame for [DeterministicVideoExporter].
 * Keeps hardware acceleration (`View.LAYER_TYPE_HARDWARE` + `Presentation` + `PixelCopy`)
 * active for GPU/WebGL Three.js canvases and waits for `requestAnimationFrame` completion.
 */
class WebViewFrameRenderer private constructor(
    private val width: Int,
    private val height: Int
) : Closeable {

    companion object {
        private const val TAG = "WebViewFrameRenderer"
        const val ASSET_BASE_URL = "file:///android_asset/"
        private const val THREE_JS_SCRIPT_TAG = "<script src=\"three.min.js\"></script>"
        private const val WEBGL_PRESERVE_BUFFER_SHIM = """<script>(function(){if(window.__tfWebGlPatched)return;window.__tfWebGlPatched=true;var orig=HTMLCanvasElement.prototype.getContext;HTMLCanvasElement.prototype.getContext=function(t,a){if(t==='webgl'||t==='webgl2'||t==='experimental-webgl'){var m={};if(a&&typeof a==='object'){for(var k in a){if(Object.prototype.hasOwnProperty.call(a,k))m[k]=a[k];}}m.preserveDrawingBuffer=true;return orig.call(this,t,m);}return orig.call(this,t,a);};})();</script>"""

        private val requestCounter = AtomicLong(1L)
        @Volatile
        private var slowWholeDocEnabled = false

        fun buildSetSceneTimingsScript(durationsSec: List<Double>): String {
            val formatted = durationsSec.joinToString(",") { sec ->
                String.format(java.util.Locale.US, "%.4f", sec.coerceAtLeast(0.05))
            }
            return "if(window.setSceneTimings){window.setSceneTimings([$formatted]);}"
        }

        fun buildSetAspectRatioScript(ratioKey: String): String {
            val safeRatio = ratioKey.trim().replace("'", "").replace("\"", "")
            return "if(typeof window.setAspectRatio==='function'){window.setAspectRatio('$safeRatio');}"
        }

        fun buildLoadEpisodeJsonScript(episodeJson: String): String {
            val quotedJson = org.json.JSONObject.quote(episodeJson)
            return "if(typeof window.loadEpisodeJSON==='function'){window.loadEpisodeJSON($quotedJson);}"
        }

        /**
         * Prepares Custom HTML for offline asset loading via `loadDataWithBaseURL("file:///android_asset/", ...)`:
         * - Automatically injects `<script src="three.min.js"></script>` before `</head>` if the HTML
         *   references `THREE.` and does not already include `three.min.js`.
         * - Ensures WebGL `preserveDrawingBuffer: true` is enforced before user scripts execute so
         *   `PixelCopy.request(...)` captures Three.js / WebGL frames reliably without blank buffers.
         */
        fun prepareHtmlForWebView(htmlDocument: String): String {
            val trimmed = htmlDocument.trim()
            if (trimmed.isEmpty()) return htmlDocument

            val referencesThree = htmlDocument.contains("THREE.")
            val hasThreeScript = htmlDocument.contains("three.min.js", ignoreCase = true)
            val usesWebGl = referencesThree || htmlDocument.contains("webgl", ignoreCase = true)
            val hasWebGlShim = htmlDocument.contains("__tfWebGlPatched")

            val headInjections = buildString {
                if (usesWebGl && !hasWebGlShim) {
                    append(WEBGL_PRESERVE_BUFFER_SHIM)
                    append('\n')
                }
                if (referencesThree && !hasThreeScript) {
                    append(THREE_JS_SCRIPT_TAG)
                    append('\n')
                }
            }
            if (headInjections.isEmpty()) return htmlDocument

            val closeHeadIdx = htmlDocument.indexOf("</head>", ignoreCase = true)
            if (closeHeadIdx >= 0) {
                return htmlDocument.substring(0, closeHeadIdx) +
                    headInjections +
                    htmlDocument.substring(closeHeadIdx)
            }

            val bodyOpenIdx = htmlDocument.indexOf("<body", ignoreCase = true)
            if (bodyOpenIdx >= 0) {
                return htmlDocument.substring(0, bodyOpenIdx) +
                    "<head>\n$headInjections</head>\n" +
                    htmlDocument.substring(bodyOpenIdx)
            }

            return "$headInjections$htmlDocument"
        }

        @SuppressLint("SetJavaScriptEnabled")
        suspend fun create(
            context: Context,
            htmlDocument: String,
            width: Int = 1080,
            height: Int = 1920,
            sceneDurationsSec: List<Double> = emptyList(),
            aspectRatioKey: String = "",
            episodeJson: String = ""
        ): WebViewFrameRenderer = withContext(Dispatchers.Main.immediate) {
            if (!slowWholeDocEnabled) {
                runCatching { WebView.enableSlowWholeDocumentDraw() }
                slowWholeDocEnabled = true
            }

            val renderer = WebViewFrameRenderer(width, height)
            renderer.initializeOnMain(context, htmlDocument, sceneDurationsSec, aspectRatioKey, episodeJson)
            renderer
        }
    }

    private var webView: WebView? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var presentation: Presentation? = null
    private var useHardwarePresentation: Boolean = false
    @Volatile
    private var rendererCrashed: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingRafLatches = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private inner class RafJsBridge {
        @JavascriptInterface
        fun onRafDone(token: String) {
            pendingRafLatches.remove(token)?.complete(Unit)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun initializeOnMain(
        context: Context,
        htmlDocument: String,
        sceneDurationsSec: List<Double> = emptyList(),
        aspectRatioKey: String = "",
        episodeJson: String = ""
    ) {
        val pageFinished = CompletableDeferred<Unit>()
        val preparedHtml = prepareHtmlForWebView(htmlDocument)

        // Create hardware-accelerated offscreen VirtualDisplay + Presentation surface
        val displayContext: Context = tryCreateHardwarePresentationContext(context) ?: context

        val wv = WebView(displayContext).apply {
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setInitialScale(100)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                allowFileAccess = true
                allowContentAccess = false
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                builtInZoomControls = false
                displayZoomControls = false
            }
            addJavascriptInterface(RafJsBridge(), "__tfBridge")
            layoutParams = ViewGroup.LayoutParams(width, height)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = true

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!pageFinished.isCompleted) {
                        pageFinished.complete(Unit)
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    val didCrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        detail?.didCrash() == true
                    } else {
                        true
                    }
                    rendererCrashed = true
                    Log.e(TAG, "Offscreen WebView renderer process gone (didCrash=$didCrash)")
                    if (!pageFinished.isCompleted) {
                        pageFinished.complete(Unit)
                    }
                    pendingRafLatches.values.forEach { it.cancel() }
                    pendingRafLatches.clear()
                    runCatching {
                        (view?.parent as? ViewGroup)?.removeView(view)
                        view?.stopLoading()
                        view?.destroy()
                    }
                    if (webView === view) {
                        webView = null
                    }
                    return true
                }
            }
        }
        webView = wv

        if (useHardwarePresentation && presentation != null) {
            wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            runCatching {
                presentation?.setContentView(wv)
                presentation?.show()
            }.onFailure { err ->
                Log.e(TAG, "Failed to attach/show hardware Presentation surface: ${err.message}", err)
            }
        } else {
            Log.w(TAG, "Hardware Presentation unavailable (API=${Build.VERSION.SDK_INT}); using default layer")
            wv.setLayerType(View.LAYER_TYPE_NONE, null)
        }

        layoutWebView(wv)
        wv.loadDataWithBaseURL(ASSET_BASE_URL, preparedHtml, "text/html", "UTF-8", null)

        withTimeoutOrNull(12_000L) {
            pageFinished.await()
        }
        if (rendererCrashed) return
        layoutWebView(wv)
        awaitReadyFlag(wv)
        if (rendererCrashed) return
        if (episodeJson.isNotBlank()) {
            evaluateLoadEpisodeJsonOnWebView(wv, episodeJson)
        }
        if (aspectRatioKey.isNotBlank()) {
            evaluateAspectRatioOnWebView(wv, aspectRatioKey)
        }
        if (sceneDurationsSec.isNotEmpty()) {
            evaluateSceneTimingsOnWebView(wv, sceneDurationsSec)
        }
        awaitNextAnimationFrame(wv)
        awaitVisualCommit(wv)
    }

    suspend fun loadEpisodeJson(episodeJson: String) = withContext(Dispatchers.Main.immediate) {
        val wv = webView ?: return@withContext
        if (episodeJson.isBlank()) return@withContext
        evaluateLoadEpisodeJsonOnWebView(wv, episodeJson)
        wv.invalidate()
        awaitNextAnimationFrame(wv)
        awaitVisualCommit(wv)
    }

    suspend fun setAspectRatio(ratioKey: String) = withContext(Dispatchers.Main.immediate) {
        val wv = webView ?: return@withContext
        if (ratioKey.isBlank()) return@withContext
        evaluateAspectRatioOnWebView(wv, ratioKey)
        wv.invalidate()
        awaitNextAnimationFrame(wv)
        awaitVisualCommit(wv)
    }

    suspend fun setSceneTimings(durationsSec: List<Double>) = withContext(Dispatchers.Main.immediate) {
        val wv = webView ?: return@withContext
        if (durationsSec.isEmpty()) return@withContext
        evaluateSceneTimingsOnWebView(wv, durationsSec)
        wv.invalidate()
        awaitNextAnimationFrame(wv)
        awaitVisualCommit(wv)
    }

    private suspend fun evaluateLoadEpisodeJsonOnWebView(wv: WebView, episodeJson: String) {
        val js = buildLoadEpisodeJsonScript(episodeJson)
        suspendCancellableCoroutine<Unit> { cont ->
            wv.evaluateJavascript(js) {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private suspend fun evaluateAspectRatioOnWebView(wv: WebView, ratioKey: String) {
        val js = buildSetAspectRatioScript(ratioKey)
        suspendCancellableCoroutine<Unit> { cont ->
            wv.evaluateJavascript(js) {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private suspend fun evaluateSceneTimingsOnWebView(wv: WebView, durationsSec: List<Double>) {
        val js = buildSetSceneTimingsScript(durationsSec)
        suspendCancellableCoroutine<Unit> { cont ->
            wv.evaluateJavascript(js) {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private fun tryCreateHardwarePresentationContext(context: Context): Context? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: return null
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
                setOnImageAvailableListener({ r ->
                    runCatching { r.acquireLatestImage()?.close() }
                }, mainHandler)
            }
            val vd = dm.createVirtualDisplay(
                "ToonForgeOffscreenWeb",
                width,
                height,
                160,
                reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            )
            val displayCtx = context.createDisplayContext(vd.display)
            val pres = Presentation(displayCtx, vd.display)
            imageReader = reader
            virtualDisplay = vd
            presentation = pres
            useHardwarePresentation = true
            displayCtx
        }.getOrElse { err ->
            Log.e(TAG, "Failed to create VirtualDisplay/Presentation for hardware WebGL: ${err.message}", err)
            tearDownHardwarePresentation()
            null
        }
    }

    private fun tearDownHardwarePresentation() {
        useHardwarePresentation = false
        runCatching { presentation?.dismiss() }
        presentation = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
    }

    private fun layoutWebView(wv: WebView) {
        val wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        wv.measure(wSpec, hSpec)
        wv.layout(0, 0, width, height)
    }

    private suspend fun awaitReadyFlag(wv: WebView) {
        for (attempt in 0 until 20) {
            val ready = suspendCancellableCoroutine<Boolean> { cont ->
                wv.evaluateJavascript(
                    "Boolean(window.__toonforgeReady) || (document.readyState === 'complete' && (typeof window.seekToTime === 'function' || (document.body && document.body.children.length > 0)))"
                ) { raw ->
                    if (cont.isActive) cont.resume(raw == "true")
                }
            }
            if (ready) return
            kotlinx.coroutines.delay(25L)
        }
    }

    private suspend fun awaitNextAnimationFrame(wv: WebView) {
        val token = "raf_${requestCounter.getAndIncrement()}"
        val latch = CompletableDeferred<Unit>()
        pendingRafLatches[token] = latch
        wv.evaluateJavascript(
            "(function(){requestAnimationFrame(function(){if(window.__tfBridge&&window.__tfBridge.onRafDone){window.__tfBridge.onRafDone('$token');}});})();",
            null
        )
        withTimeoutOrNull(450L) {
            latch.await()
        }
        pendingRafLatches.remove(token)
    }

    private suspend fun seekAndAwaitAnimationFrame(wv: WebView, formattedSec: String) {
        val token = "raf_${requestCounter.getAndIncrement()}"
        val latch = CompletableDeferred<Unit>()
        pendingRafLatches[token] = latch
        val js = "(function(){if(typeof window.seekToTime==='function'){window.seekToTime($formattedSec);}requestAnimationFrame(function(){if(window.__tfBridge&&window.__tfBridge.onRafDone){window.__tfBridge.onRafDone('$token');}});})();"
        suspendCancellableCoroutine<Unit> { cont ->
            wv.evaluateJavascript(js) {
                if (cont.isActive) cont.resume(Unit)
            }
        }
        withTimeoutOrNull(450L) {
            latch.await()
        }
        pendingRafLatches.remove(token)
    }

    suspend fun renderFrameAt(seconds: Double, targetBitmap: Bitmap): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (rendererCrashed) return@withContext false
            val wv = webView ?: return@withContext false
            val formattedSec = String.format(java.util.Locale.US, "%.6f", seconds.coerceAtLeast(0.0))

            // 1. Drive deterministic seekToTime(t) and wait for requestAnimationFrame completion
            seekAndAwaitAnimationFrame(wv, formattedSec)
            if (rendererCrashed) return@withContext false
            wv.invalidate()
            awaitVisualCommit(wv)
            if (rendererCrashed) return@withContext false

            // 2. Capture hardware-accelerated VirtualDisplay + Presentation surface via PixelCopy
            if (useHardwarePresentation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val win = presentation?.window
                if (win != null) {
                    if (captureViaPixelCopy(win, targetBitmap, formattedSec)) {
                        return@withContext true
                    }
                    // Retry once after an additional rAF + visual commit while keeping LAYER_TYPE_HARDWARE active
                    awaitNextAnimationFrame(wv)
                    wv.invalidate()
                    awaitVisualCommit(wv)
                    if (captureViaPixelCopy(win, targetBitmap, formattedSec)) {
                        return@withContext true
                    }
                    Log.e(
                        TAG,
                        "PixelCopy capture failed after retry at t=${formattedSec}s; keeping View.LAYER_TYPE_HARDWARE active (no LAYER_TYPE_NONE fallback)."
                    )
                    return@withContext false
                } else {
                    Log.e(
                        TAG,
                        "Presentation window is null at t=${formattedSec}s while hardware presentation is active."
                    )
                    return@withContext false
                }
            }

            // Software draw path only used when API < 26 or DisplayManager/Presentation couldn't initialize
            val canvas = Canvas(targetBitmap)
            wv.draw(canvas)
            true
        }

    private suspend fun awaitVisualCommit(wv: WebView) {
        withTimeoutOrNull(400L) {
            suspendCancellableCoroutine<Unit> { cont ->
                val reqId = requestCounter.getAndIncrement()
                wv.postVisualStateCallback(
                    reqId,
                    object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            Choreographer.getInstance().postFrameCallback {
                                if (cont.isActive) cont.resume(Unit)
                            }
                        }
                    }
                )
            }
        }
    }

    private suspend fun captureViaPixelCopy(
        window: android.view.Window,
        targetBitmap: Bitmap,
        formattedSec: String
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return withTimeoutOrNull(500L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                runCatching {
                    PixelCopy.request(
                        window,
                        targetBitmap,
                        { copyResult ->
                            if (copyResult != PixelCopy.SUCCESS) {
                                Log.e(TAG, "PixelCopy.request failed with code=$copyResult at t=${formattedSec}s")
                            }
                            if (cont.isActive) {
                                cont.resume(copyResult == PixelCopy.SUCCESS)
                            }
                        },
                        mainHandler
                    )
                }.onFailure { err ->
                    Log.e(TAG, "PixelCopy.request threw at t=${formattedSec}s: ${err.message}", err)
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: run {
            Log.e(TAG, "PixelCopy.request timed out at t=${formattedSec}s")
            false
        }
    }

    override fun close() {
        pendingRafLatches.values.forEach { it.cancel() }
        pendingRafLatches.clear()
        val cleanup = {
            val wv = webView
            webView = null
            if (wv != null) {
                runCatching {
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.stopLoading()
                    wv.destroy()
                }
            }
            tearDownHardwarePresentation()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup()
        } else {
            mainHandler.post(cleanup)
        }
    }
}
