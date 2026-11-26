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
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Offscreen 1080x1920 deterministic WebView frame renderer that drives `window.seekToTime(seconds)`
 * frame-by-frame for [DeterministicVideoExporter] without real-time rAF timing drift.
 */
class WebViewFrameRenderer private constructor(
    private val width: Int,
    private val height: Int
) : Closeable {

    companion object {
        private val requestCounter = AtomicLong(1L)
        @Volatile
        private var slowWholeDocEnabled = false

        @SuppressLint("SetJavaScriptEnabled")
        suspend fun create(
            context: Context,
            htmlDocument: String,
            width: Int = 1080,
            height: Int = 1920
        ): WebViewFrameRenderer = withContext(Dispatchers.Main.immediate) {
            if (!slowWholeDocEnabled) {
                runCatching { WebView.enableSlowWholeDocumentDraw() }
                slowWholeDocEnabled = true
            }

            val renderer = WebViewFrameRenderer(width, height)
            renderer.initializeOnMain(context, htmlDocument)
            renderer
        }
    }

    private var webView: WebView? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var presentation: Presentation? = null
    private var useHardwarePresentation: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun initializeOnMain(context: Context, htmlDocument: String) {
        val pageFinished = CompletableDeferred<Unit>()

        // Try hardware-accelerated offscreen VirtualDisplay + Presentation first
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
                allowFileAccess = false
                allowContentAccess = false
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                builtInZoomControls = false
                displayZoomControls = false
            }
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
            }
        }
        webView = wv

        if (useHardwarePresentation && presentation != null) {
            runCatching {
                wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                presentation?.setContentView(wv)
                presentation?.show()
            }.onFailure {
                tearDownHardwarePresentation()
                wv.setLayerType(View.LAYER_TYPE_NONE, null)
            }
        } else {
            wv.setLayerType(View.LAYER_TYPE_NONE, null)
        }

        layoutWebView(wv)
        wv.loadDataWithBaseURL("https://toonforge.local/", htmlDocument, "text/html", "UTF-8", null)

        withTimeoutOrNull(12_000L) {
            pageFinished.await()
        }
        layoutWebView(wv)
        awaitReadyFlag(wv)
        awaitVisualCommit(wv)
    }

    private fun tryCreateHardwarePresentationContext(context: Context): Context? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: return null
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val vd = dm.createVirtualDisplay(
                "ToonForgeOffscreenWeb",
                width,
                height,
                160,
                reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
            val displayCtx = context.createDisplayContext(vd.display)
            val pres = Presentation(displayCtx, vd.display)
            imageReader = reader
            virtualDisplay = vd
            presentation = pres
            useHardwarePresentation = true
            displayCtx
        }.getOrElse {
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

    suspend fun renderFrameAt(seconds: Double, targetBitmap: Bitmap): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val wv = webView ?: return@withContext false
            val formattedSec = String.format(java.util.Locale.US, "%.6f", seconds.coerceAtLeast(0.0))
            suspendCancellableCoroutine<Unit> { cont ->
                wv.evaluateJavascript("if (typeof window.seekToTime === 'function') window.seekToTime($formattedSec);") {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            wv.invalidate()
            awaitVisualCommit(wv)

            if (useHardwarePresentation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val win = presentation?.window
                if (win != null && captureViaPixelCopy(win, targetBitmap)) {
                    return@withContext true
                }
                // Fall back permanently to synchronous software Skia draw if PixelCopy isn't supported
                tearDownHardwarePresentation()
                wv.setLayerType(View.LAYER_TYPE_NONE, null)
                layoutWebView(wv)
                awaitVisualCommit(wv)
            }

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

    private suspend fun captureViaPixelCopy(window: android.view.Window, targetBitmap: Bitmap): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return withTimeoutOrNull(500L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                runCatching {
                    PixelCopy.request(
                        window,
                        targetBitmap,
                        { copyResult ->
                            if (cont.isActive) {
                                cont.resume(copyResult == PixelCopy.SUCCESS)
                            }
                        },
                        mainHandler
                    )
                }.onFailure {
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: false
    }

    override fun close() {
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
