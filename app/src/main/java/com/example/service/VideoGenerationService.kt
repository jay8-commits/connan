package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.art.AiHordeImageClient
import com.example.data.SettingsDataStore
import com.example.data.VideoGalleryRepository
import com.example.llm.AiHordeDirectorClient
import com.example.llm.DirectStoryboardParser
import com.example.llm.LlmDirectorClient
import com.example.llm.LlmScriptResult
import com.example.llm.UniversalModelDetector
import com.example.model.ActorRole
import com.example.model.AnimationStyle
import com.example.model.GenerationProgress
import com.example.model.PipelineStep
import com.example.model.ProductionMode
import com.example.model.ScriptSourceMode
import com.example.model.TimelineScene
import com.example.model.WordTiming
import com.example.render.CharacterSpriteProcessor
import com.example.render.DeterministicVideoExporter
import com.example.render.ExternalClipFrameSource
import com.example.render.MotionHtmlDirector
import com.example.render.WebViewFrameRenderer
import com.example.tts.AudioPcmStitcher
import com.example.tts.ElevenLabsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RenderBatchItem(
    val topic: String,
    val style: AnimationStyle,
    val durationSec: Int,
    val voiceIdOverride: String,
    val productionMode: ProductionMode,
    val importedClipUris: List<Uri>,
    val scriptSourceModeOverride: ScriptSourceMode?,
    val directScriptOverride: String
)

class VideoGenerationService : Service() {

    companion object {
        const val ACTION_START = "com.example.action.START_GENERATION"
        const val ACTION_ENQUEUE = "com.example.action.ENQUEUE_GENERATION"
        const val ACTION_CANCEL = "com.example.action.CANCEL_GENERATION"
        const val EXTRA_TOPIC = "extra_topic"
        const val EXTRA_STYLE = "extra_style"
        const val EXTRA_DURATION_SEC = "extra_duration_sec"
        const val EXTRA_VOICE_ID = "extra_voice_id"
        const val EXTRA_PRODUCTION_MODE = "extra_production_mode"
        const val EXTRA_IMPORTED_CLIP_URIS = "extra_imported_clip_uris"
        const val EXTRA_SCRIPT_SOURCE_MODE = "extra_script_source_mode"
        const val EXTRA_DIRECT_SCRIPT = "extra_direct_script"
        const val EXTRA_BATCH_QUEUE_FILE = "extra_batch_queue_file"
        const val MAX_BATCH_QUEUE_SIZE = 5

        private const val CHANNEL_ID = "toonforge_render_channel"
        private const val NOTIFICATION_ID = 4201

        fun serializeBatchItemsToJson(items: List<RenderBatchItem>): String {
            val arr = JSONArray()
            items.take(MAX_BATCH_QUEUE_SIZE).forEach { item ->
                arr.put(
                    JSONObject().apply {
                        put("topic", item.topic)
                        put("style", item.style.name)
                        put("duration_sec", item.durationSec)
                        put("voice_id", item.voiceIdOverride)
                        put("production_mode", item.productionMode.name)
                        put("script_source_mode", item.scriptSourceModeOverride?.name.orEmpty())
                        put("direct_script", item.directScriptOverride)
                        put("clip_uris", JSONArray(item.importedClipUris.map { it.toString() }))
                    }
                )
            }
            return arr.toString()
        }

        fun deserializeBatchItemsFromJson(rawJson: String): List<RenderBatchItem> {
            return runCatching {
                val arr = JSONArray(rawJson)
                buildList {
                    for (i in 0 until minOf(MAX_BATCH_QUEUE_SIZE, arr.length())) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val rawClips = obj.optJSONArray("clip_uris")
                        val clips = buildList {
                            if (rawClips != null) {
                                for (k in 0 until rawClips.length()) {
                                    val s = rawClips.optString(k, "").trim()
                                    if (s.isNotEmpty()) {
                                        runCatching { Uri.parse(s) }.getOrNull()?.let { add(it) }
                                    }
                                }
                            }
                        }
                        val styleName = obj.optString("style", AnimationStyle.ATMOSPHERIC_2D_STORY.name)
                        val style = AnimationStyle.entries.firstOrNull { it.name == styleName }
                            ?: AnimationStyle.ATMOSPHERIC_2D_STORY
                        val modeName = obj.optString("script_source_mode", "")
                        val sourceMode = modeName.takeIf { it.isNotBlank() }?.let { ScriptSourceMode.fromRaw(it) }
                        add(
                            RenderBatchItem(
                                topic = obj.optString("topic", "").trim(),
                                style = style,
                                durationSec = obj.optInt("duration_sec", 30),
                                voiceIdOverride = obj.optString("voice_id", ""),
                                productionMode = ProductionMode.fromRaw(obj.optString("production_mode", "")),
                                importedClipUris = clips,
                                scriptSourceModeOverride = sourceMode,
                                directScriptOverride = obj.optString("direct_script", "")
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null
    private val queueLock = Any()
    private val renderQueue = ArrayDeque<RenderBatchItem>()
    private var totalBatchCount = 0
    private var completedBatchCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    private fun extractBatchItemsFromIntent(intent: Intent): List<RenderBatchItem> {
        val queueFilePath = intent.getStringExtra(EXTRA_BATCH_QUEUE_FILE).orEmpty()
        if (queueFilePath.isNotBlank()) {
            val file = File(queueFilePath)
            if (file.exists() && file.canRead()) {
                val parsed = runCatching {
                    val raw = file.readText(Charsets.UTF_8)
                    deserializeBatchItemsFromJson(raw)
                }.getOrDefault(emptyList())
                runCatching { file.delete() }
                if (parsed.isNotEmpty()) return parsed
            }
        }

        val topic = intent.getStringExtra(EXTRA_TOPIC).orEmpty().trim()
        val styleName = intent.getStringExtra(EXTRA_STYLE) ?: AnimationStyle.ATMOSPHERIC_2D_STORY.name
        val style = AnimationStyle.entries.firstOrNull { it.name == styleName }
            ?: AnimationStyle.ATMOSPHERIC_2D_STORY
        val durationSec = intent.getIntExtra(EXTRA_DURATION_SEC, 30)
        val voiceId = intent.getStringExtra(EXTRA_VOICE_ID).orEmpty()
        val productionMode = ProductionMode.fromRaw(intent.getStringExtra(EXTRA_PRODUCTION_MODE))
        val importedClipUris = intent.getStringArrayListExtra(EXTRA_IMPORTED_CLIP_URIS)
            ?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            .orEmpty()
        val scriptSourceModeOverride = intent.getStringExtra(EXTRA_SCRIPT_SOURCE_MODE)
            ?.takeIf { it.isNotBlank() }
            ?.let { ScriptSourceMode.fromRaw(it) }
        val directScriptOverride = intent.getStringExtra(EXTRA_DIRECT_SCRIPT).orEmpty()
        val effectiveTopic = topic.ifBlank {
            if (directScriptOverride.isNotBlank()) {
                DirectStoryboardParser.deriveTopicTitle(directScriptOverride)
            } else {
                ""
            }
        }
        val effectiveStyle = if (productionMode == ProductionMode.CROSS_SECTION_EXPLAINER) {
            AnimationStyle.CROSS_SECTION
        } else {
            style
        }
        if (effectiveTopic.isEmpty() && directScriptOverride.isBlank()) {
            return emptyList()
        }
        return listOf(
            RenderBatchItem(
                topic = effectiveTopic.ifBlank { "Direct Tagalog Story" },
                style = effectiveStyle,
                durationSec = durationSec,
                voiceIdOverride = voiceId,
                productionMode = productionMode,
                importedClipUris = importedClipUris,
                scriptSourceModeOverride = scriptSourceModeOverride,
                directScriptOverride = directScriptOverride
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                synchronized(queueLock) {
                    renderQueue.clear()
                    totalBatchCount = 0
                    completedBatchCount = 0
                }
                activeJob?.cancel()
                PipelineProgressBus.update {
                    it.copy(
                        isRunning = false,
                        step = PipelineStep.IDLE,
                        statusMessage = "Render cancelled by user",
                        queuedBatchCount = 0,
                        currentBatchIndex = 0,
                        totalBatchCount = 0
                    )
                }
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ENQUEUE -> {
                val incoming = extractBatchItemsFromIntent(intent)
                if (incoming.isEmpty()) {
                    if (activeJob?.isActive != true) stopSelf()
                    return START_NOT_STICKY
                }
                val shouldStartLoop: Boolean
                val queuedSnapshot: Int
                val totalSnapshot: Int
                synchronized(queueLock) {
                    val activeOffset = if (activeJob?.isActive == true) 1 else 0
                    val availableSlots = (MAX_BATCH_QUEUE_SIZE - (renderQueue.size + activeOffset)).coerceAtLeast(0)
                    incoming.take(availableSlots).forEach { renderQueue.addLast(it) }
                    if (activeJob?.isActive == true) {
                        totalBatchCount = (completedBatchCount + activeOffset + renderQueue.size).coerceAtMost(MAX_BATCH_QUEUE_SIZE)
                    } else {
                        totalBatchCount = renderQueue.size
                        completedBatchCount = 0
                    }
                    queuedSnapshot = renderQueue.size
                    totalSnapshot = totalBatchCount
                    shouldStartLoop = activeJob?.isActive != true
                }
                PipelineProgressBus.update { cur ->
                    cur.copy(
                        queuedBatchCount = queuedSnapshot,
                        totalBatchCount = totalSnapshot
                    )
                }
                if (shouldStartLoop) {
                    startQueueForegroundAndRun()
                }
            }
            ACTION_START -> {
                val incoming = extractBatchItemsFromIntent(intent)
                if (incoming.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                synchronized(queueLock) {
                    renderQueue.clear()
                    incoming.take(MAX_BATCH_QUEUE_SIZE).forEach { renderQueue.addLast(it) }
                    totalBatchCount = renderQueue.size
                    completedBatchCount = 0
                }
                activeJob?.cancel()
                startQueueForegroundAndRun()
            }
        }
        return START_NOT_STICKY
    }

    private fun startQueueForegroundAndRun() {
        ensureNotificationChannel()
        val initialNotif = buildNotification("Starting ToonForge Tagalog pipeline...", 5)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotif)
        }

        activeJob = serviceScope.launch {
            processRenderQueue()
        }
    }

    private suspend fun processRenderQueue() {
        var lastSuccessResult: com.example.render.ExportResult? = null
        var lastFailureMessage: String? = null
        try {
            while (true) {
                val nextItem: RenderBatchItem?
                val batchIdx: Int
                val batchTotal: Int
                val remainingCount: Int
                synchronized(queueLock) {
                    nextItem = renderQueue.removeFirstOrNull()
                    remainingCount = renderQueue.size
                    batchIdx = completedBatchCount + 1
                    batchTotal = maxOf(totalBatchCount, completedBatchCount + (if (nextItem != null) 1 else 0) + remainingCount)
                    totalBatchCount = batchTotal
                }
                if (nextItem == null) break

                PipelineProgressBus.update { cur ->
                    cur.copy(
                        isRunning = true,
                        currentBatchIndex = batchIdx,
                        totalBatchCount = batchTotal,
                        queuedBatchCount = remainingCount,
                        completedBatchCount = completedBatchCount
                    )
                }

                val itemOutcome = runPipeline(
                    topic = nextItem.topic.ifBlank { "Direct Tagalog Story" },
                    style = nextItem.style,
                    durationSec = nextItem.durationSec,
                    voiceIdOverride = nextItem.voiceIdOverride,
                    productionMode = nextItem.productionMode,
                    importedClipUris = nextItem.importedClipUris,
                    scriptSourceModeOverride = nextItem.scriptSourceModeOverride,
                    directScriptOverride = nextItem.directScriptOverride,
                    batchIndex = batchIdx,
                    batchTotal = batchTotal
                )

                if (itemOutcome.exportResult != null) {
                    lastSuccessResult = itemOutcome.exportResult
                    synchronized(queueLock) {
                        completedBatchCount++
                    }
                    val doneNow = completedBatchCount
                    val remNow = synchronized(queueLock) { renderQueue.size }
                    PipelineProgressBus.update { cur ->
                        cur.copy(
                            completedBatchCount = doneNow,
                            queuedBatchCount = remNow,
                            outputVideoUri = itemOutcome.exportResult.mediaStoreUri,
                            outputDisplayName = itemOutcome.exportResult.displayName
                        )
                    }
                } else {
                    lastFailureMessage = itemOutcome.errorMessage
                }
            }

            val totalDone = completedBatchCount
            val finalTotal = totalBatchCount.coerceAtLeast(totalDone)
            if (lastSuccessResult != null) {
                val finalMsg = if (finalTotal > 1) {
                    "Batch finished: $totalDone/$finalTotal video(s) saved to Movies/ToonForgeTagalog!"
                } else {
                    "Saved ${lastSuccessResult.displayName} to Movies/ToonForgeTagalog!"
                }
                PipelineProgressBus.update { cur ->
                    cur.copy(
                        step = PipelineStep.COMPLETED,
                        stepProgress = 1f,
                        overallProgress = 1f,
                        statusMessage = finalMsg,
                        outputVideoUri = lastSuccessResult.mediaStoreUri,
                        outputDisplayName = lastSuccessResult.displayName,
                        errorMessage = null,
                        isRunning = false,
                        queuedBatchCount = 0,
                        currentBatchIndex = finalTotal,
                        totalBatchCount = finalTotal,
                        completedBatchCount = totalDone
                    )
                }
                updateNotification(finalMsg, 100)
            } else if (lastFailureMessage != null) {
                PipelineProgressBus.update { cur ->
                    cur.copy(
                        step = PipelineStep.ERROR,
                        statusMessage = "Failed: $lastFailureMessage",
                        errorMessage = lastFailureMessage,
                        isRunning = false,
                        queuedBatchCount = 0
                    )
                }
                updateNotification("Render failed: $lastFailureMessage", 0)
            }
        } catch (ce: CancellationException) {
            PipelineProgressBus.update { cur ->
                cur.copy(
                    step = PipelineStep.IDLE,
                    statusMessage = "Render cancelled",
                    isRunning = false,
                    queuedBatchCount = 0
                )
            }
            throw ce
        } finally {
            stopForegroundCompat()
            stopSelf()
        }
    }

    private data class SingleRenderOutcome(
        val exportResult: com.example.render.ExportResult? = null,
        val errorMessage: String? = null
    )
    private suspend fun runPipeline(
        topic: String,
        style: AnimationStyle,
        durationSec: Int,
        voiceIdOverride: String,
        productionMode: ProductionMode,
        importedClipUris: List<Uri>,
        scriptSourceModeOverride: ScriptSourceMode?,
        directScriptOverride: String,
        batchIndex: Int = 1,
        batchTotal: Int = 1
    ): SingleRenderOutcome {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ToonForge::VideoRenderWakeLock"
        )
        wakeLock.acquire(15 * 60 * 1000L) // 15-minute safety ceiling

        val tempWorkDir = File(cacheDir, "toonforge_work_${System.currentTimeMillis()}").apply {
            mkdirs()
        }
        val renderBackdrops = mutableListOf<Bitmap>()
        var customSprites: Map<ActorRole, Bitmap> = emptyMap()
        var externalClipFrameSource: ExternalClipFrameSource? = null
        var webViewFrameRenderer: WebViewFrameRenderer? = null

        try {
            val settingsStore = SettingsDataStore(applicationContext)
            val settings = settingsStore.getSnapshot()
            val activeScriptMode = scriptSourceModeOverride ?: settings.effectiveScriptSourceMode
            val effectiveDirectScript = directScriptOverride.ifBlank { settings.directScriptText }.trim()
            val effectiveVoiceId = voiceIdOverride
                .replace("\r", "").replace("\n", "").trim()
                .ifBlank { settings.effectiveVoiceId }
            val resolvedLlmModel = settings.effectiveSelectedModelId.ifBlank {
                UniversalModelDetector.matchPresetForUrl(settings.effectiveLlmBaseUrl).defaultModelHint
            }
            val missingCreds = buildList {
                when (activeScriptMode) {
                    ScriptSourceMode.DIRECT_SCRIPT -> {
                        if (effectiveDirectScript.isBlank()) {
                            add("Direct Storyboard / Tagalog Script")
                        }
                    }
                    ScriptSourceMode.CUSTOM_LLM -> {
                        if (settings.effectiveLlmBaseUrl.isBlank()) add("LLM Base URL")
                        if (settings.effectiveLlmApiKey.isBlank()) add("LLM API Key")
                        if (resolvedLlmModel.isBlank()) add("LLM Model ID")
                    }
                    ScriptSourceMode.AI_HORDE -> {
                        // AI Horde uses configured key or anonymous key "0000000000"
                    }
                }
                if (settings.elevenLabsKeys.none { it.key.replace("\r", "").replace("\n", "").trim().isNotEmpty() }) {
                    add("ElevenLabs API Key")
                }
                if (effectiveVoiceId.isBlank()) add("ElevenLabs Voice ID")
            }
            if (missingCreds.isNotEmpty()) {
                val errMsg = if (activeScriptMode == ScriptSourceMode.DIRECT_SCRIPT &&
                    missingCreds.contains("Direct Storyboard / Tagalog Script")
                ) {
                    "Direct Script input is empty. Paste JSON or plain Tagalog text in the Studio tab."
                } else {
                    "Missing required credentials (${missingCreds.joinToString(", ")}). Please configure them in API & Settings."
                }
                if (batchTotal <= 1) {
                    PipelineProgressBus.update { cur ->
                        cur.copy(
                            step = PipelineStep.ERROR,
                            statusMessage = errMsg,
                            errorMessage = errMsg,
                            isRunning = false
                        )
                    }
                    updateNotification("Setup required: configure Studio / API & Settings", 0)
                }
                return SingleRenderOutcome(errorMessage = errMsg)
            }
            val galleryRepo = VideoGalleryRepository(applicationContext)
            val batchPrefix = if (batchTotal > 1) "[Batch $batchIndex/$batchTotal] " else ""

            fun publish(
                step: PipelineStep,
                stepProgress: Float,
                overall: Float,
                message: String,
                sceneIdx: Int = 0,
                sceneCount: Int = 0,
                providerInfo: String? = null
            ) {
                val prefixedMsg = "$batchPrefix$message"
                PipelineProgressBus.update { cur ->
                    cur.copy(
                        step = step,
                        stepProgress = stepProgress.coerceIn(0f, 1f),
                        overallProgress = overall.coerceIn(0f, 1f),
                        statusMessage = prefixedMsg,
                        currentSceneIndex = sceneIdx,
                        sceneCount = if (sceneCount > 0) sceneCount else cur.sceneCount,
                        providerInfo = providerInfo ?: cur.providerInfo,
                        errorMessage = null,
                        isRunning = true
                    )
                }
                updateNotification(prefixedMsg, (overall * 100).toInt().coerceIn(0, 100))
            }

            // STEP 1: SCRIPT GENERATION (Strict 3-mode routing — Direct Script / Custom LLM / AI Horde)
            val initialScriptMsg = when (activeScriptMode) {
                ScriptSourceMode.DIRECT_SCRIPT -> "Parsing Direct Script locally (zero LLM call)..."
                ScriptSourceMode.AI_HORDE -> "Submitting Tagalog script to AI Horde queue..."
                ScriptSourceMode.CUSTOM_LLM -> "Directing Tagalog story script..."
            }
            publish(PipelineStep.SCRIPT, 0.1f, 0.05f, initialScriptMsg)
            val scriptResult = when (activeScriptMode) {
                ScriptSourceMode.DIRECT_SCRIPT -> {
                    val parsed = DirectStoryboardParser.parse(
                        rawInput = effectiveDirectScript,
                        style = style
                    )
                    LlmScriptResult(
                        scenes = parsed.scenes,
                        providerUsed = parsed.providerLabel,
                        customHtmlDocument = parsed.customHtmlDocument
                    )
                }
                ScriptSourceMode.AI_HORDE -> {
                    AiHordeDirectorClient().generateStoryScript(
                        topic = topic,
                        durationSeconds = durationSec,
                        style = style,
                        settings = settings,
                        onStatus = { status ->
                            publish(PipelineStep.SCRIPT, 0.5f, 0.12f, status)
                        }
                    )
                }
                ScriptSourceMode.CUSTOM_LLM -> {
                    LlmDirectorClient().generateStoryScript(
                        topic = topic,
                        durationSeconds = durationSec,
                        style = style,
                        settings = settings,
                        onStatus = { status ->
                            publish(PipelineStep.SCRIPT, 0.5f, 0.12f, status)
                        }
                    )
                }
            }
            val directCustomHtmlDoc: String? = scriptResult.customHtmlDocument
            val scenes = scriptResult.scenes
            publish(
                PipelineStep.SCRIPT,
                1.0f,
                0.20f,
                "Script ready (${scenes.size} scenes) via ${scriptResult.providerUsed}",
                sceneCount = scenes.size,
                providerInfo = scriptResult.providerUsed
            )

            // STEP 2: ELEVENLABS TTS + SAMPLE-ACCURATE PCM STITCHING
            val ttsClient = ElevenLabsClient(applicationContext)
            val pcmPerScene = mutableListOf<ByteArray>()
            val timingsPerScene = mutableListOf<List<WordTiming>>()

            scenes.forEachIndexed { idx, scene ->
                currentCoroutineContext().ensureActive()
                val frac = (idx + 1).toFloat() / scenes.size.toFloat()
                publish(
                    PipelineStep.AUDIO,
                    frac * 0.85f,
                    0.20f + frac * 0.22f,
                    "Synthesizing Tagalog voiceover (${idx + 1}/${scenes.size})...",
                    sceneIdx = idx + 1,
                    sceneCount = scenes.size
                )

                val synth = ttsClient.synthesizeScene(
                    rawTagalogNarration = scene.tagalogNarration,
                    voiceId = effectiveVoiceId,
                    keyPool = settingsStore.getSnapshot().elevenLabsKeys,
                    tempCacheDir = tempWorkDir,
                    customDictionary = settings.pronunciationDictionary,
                    previousText = scenes.getOrNull(idx - 1)?.tagalogNarration,
                    nextText = scenes.getOrNull(idx + 1)?.tagalogNarration,
                    ttsBaseUrl = settings.effectiveTtsBaseUrl,
                    ttsModelId = settings.effectiveTtsModelId,
                    narrationSpeed = settings.narrationSpeed,
                    onKeyStatusUpdate = { updatedKey ->
                        settingsStore.updateElevenLabsKeyEntry(updatedKey)
                    }
                )
                val pcm44100Mono = synth.decodedPcm44100Mono ?: AudioPcmStitcher.decodeAudioBytesToPcm44100Mono(
                    audioBytes = synth.audioBytes,
                    isWav = synth.isWav,
                    tempCacheDir = tempWorkDir
                )
                pcmPerScene.add(pcm44100Mono)
                timingsPerScene.add(synth.localWordTimings)
            }

            val customBgmPcm = if (settings.customBgmPath.isNotBlank()) {
                runCatching {
                    val bgmFile = File(settings.customBgmPath)
                    if (bgmFile.exists() && bgmFile.canRead()) {
                        AudioPcmStitcher.decodeAudioBytesToPcm44100Mono(
                            audioBytes = bgmFile.readBytes(),
                            isWav = bgmFile.name.endsWith(".wav", ignoreCase = true),
                            tempCacheDir = tempWorkDir
                        )
                    } else null
                }.getOrNull()
            } else null

            val stitchedAudio = AudioPcmStitcher.stitchScenes(
                decodedPcmPerScene = pcmPerScene,
                localTimingsPerScene = timingsPerScene,
                narrationPerScene = scenes.map { it.tagalogNarration },
                scenes = scenes,
                style = style,
                customBgmPcm44100Mono = customBgmPcm,
                targetDurationMs = durationSec * 1000.0,
                alwaysTriggerSceneBoundarySfx = (directCustomHtmlDoc != null),
                loopTailPaddingMs = AudioPcmStitcher.SEAMLESS_LOOP_TAIL_MS
            )
            pcmPerScene.clear() // release per-scene chunks immediately; keep unified buffer

            // STEP 3: ART / CLIPS / BACKDROP PIPELINE
            val timelineScenes = mutableListOf<TimelineScene>()
            val previewScenes = mutableListOf<TimelineScene>()

            if (directCustomHtmlDoc != null) {
                publish(
                    PipelineStep.ART,
                    0.8f,
                    0.60f,
                    "Loaded custom HTML/SVG storyboard for 1080x1920 deterministic render...",
                    sceneIdx = scenes.size,
                    sceneCount = scenes.size
                )
                scenes.forEachIndexed { idx, scene ->
                    currentCoroutineContext().ensureActive()
                    val ts = TimelineScene(
                        scene = scene,
                        startMs = stitchedAudio.sceneStartMs[idx],
                        endMs = stitchedAudio.sceneEndMs[idx],
                        wordTimings = stitchedAudio.sceneWordTimings[idx],
                        backdropBitmap = null
                    )
                    timelineScenes.add(ts)
                    previewScenes.add(ts)
                }
            } else if (productionMode == ProductionMode.CLIP_AUTO_EDITOR && importedClipUris.isNotEmpty()) {
                publish(
                    PipelineStep.ART,
                    0.2f,
                    0.46f,
                    "Preparing ${importedClipUris.size} imported video/image clip(s) for Auto-HUD...",
                    sceneIdx = 0,
                    sceneCount = scenes.size
                )
                val clipSource = ExternalClipFrameSource(
                    context = applicationContext,
                    clipUris = importedClipUris,
                    targetWidth = DeterministicVideoExporter.VIDEO_WIDTH,
                    targetHeight = DeterministicVideoExporter.VIDEO_HEIGHT
                )
                externalClipFrameSource = clipSource
                scenes.forEachIndexed { idx, scene ->
                    currentCoroutineContext().ensureActive()
                    val sampleThumb = clipSource.getFrameBitmap(idx, 100.0)?.let {
                        Bitmap.createScaledBitmap(it, 360, 640, true)
                    }
                    val startMs = stitchedAudio.sceneStartMs[idx]
                    val endMs = stitchedAudio.sceneEndMs[idx]
                    val wordTimings = stitchedAudio.sceneWordTimings[idx]
                    timelineScenes.add(
                        TimelineScene(
                            scene = scene,
                            startMs = startMs,
                            endMs = endMs,
                            wordTimings = wordTimings,
                            backdropBitmap = null
                        )
                    )
                    previewScenes.add(
                        TimelineScene(
                            scene = scene,
                            startMs = startMs,
                            endMs = endMs,
                            wordTimings = wordTimings,
                            backdropBitmap = sampleThumb
                        )
                    )
                    publish(
                        PipelineStep.ART,
                        (idx + 1).toFloat() / scenes.size.toFloat(),
                        0.46f + ((idx + 1).toFloat() / scenes.size.toFloat()) * 0.18f,
                        "Indexed clip ${idx + 1}/${scenes.size}...",
                        sceneIdx = idx + 1,
                        sceneCount = scenes.size
                    )
                }
            } else {
                val scenesNeedingVectorArt = scenes.filter { it.customSvg.isBlank() }
                val backdropMap = mutableMapOf<Int, Bitmap>()
                if (scenesNeedingVectorArt.isNotEmpty()) {
                    val imageClient = AiHordeImageClient()
                    val useAiHordeForArt = activeScriptMode == ScriptSourceMode.AI_HORDE
                    val artBatchStartMsg = if (useAiHordeForArt) {
                        "Generating ${scenesNeedingVectorArt.size} 2D flat vector illustrations via AI Horde..."
                    } else {
                        "Generating ${scenesNeedingVectorArt.size} 2D flat vector scene illustrations..."
                    }
                    publish(
                        PipelineStep.ART,
                        0.05f,
                        0.42f,
                        artBatchStartMsg,
                        sceneIdx = 0,
                        sceneCount = scenes.size
                    )

                    val backdropResults = imageClient.generateBackdropsBatch(
                        scenes = scenesNeedingVectorArt,
                        style = style,
                        aiHordeApiKey = settings.aiHordeApiKey,
                        topicSeed = topic.hashCode(),
                        preferAiHordeDirect = useAiHordeForArt,
                        forceNetworkArt = true,
                        useFlatVectorPrompt = true
                    ) { doneCount, totalCount ->
                        val frac = doneCount.toFloat() / totalCount.coerceAtLeast(1).toFloat()
                        val label = if (useAiHordeForArt) {
                            "AI Horde generated 2D vector illustration ($doneCount/$totalCount)..."
                        } else {
                            "Generated 2D flat vector illustration ($doneCount/$totalCount)..."
                        }
                        publish(
                            PipelineStep.ART,
                            frac,
                            0.42f + frac * 0.22f,
                            label,
                            sceneIdx = doneCount,
                            sceneCount = totalCount
                        )
                    }

                    scenesNeedingVectorArt.forEachIndexed { artIdx, artScene ->
                        val bmp = backdropResults.getOrNull(artIdx)?.bitmap
                        if (bmp != null) {
                            renderBackdrops.add(bmp)
                            backdropMap[artScene.sceneId] = bmp
                        }
                    }
                } else {
                    publish(
                        PipelineStep.ART,
                        0.85f,
                        0.60f,
                        "Using bespoke LLM SVG vector artwork for all ${scenes.size} scenes...",
                        sceneIdx = scenes.size,
                        sceneCount = scenes.size
                    )
                }

                scenes.forEachIndexed { idx, scene ->
                    currentCoroutineContext().ensureActive()
                    val bmp = if (scene.customSvg.isBlank()) backdropMap[scene.sceneId] else null
                    val previewThumb = bmp?.let { Bitmap.createScaledBitmap(it, 360, 640, true) }
                    val startMs = stitchedAudio.sceneStartMs[idx]
                    val endMs = stitchedAudio.sceneEndMs[idx]
                    val wordTimings = stitchedAudio.sceneWordTimings[idx]

                    timelineScenes.add(
                        TimelineScene(
                            scene = scene,
                            startMs = startMs,
                            endMs = endMs,
                            wordTimings = wordTimings,
                            backdropBitmap = bmp
                        )
                    )
                    previewScenes.add(
                        TimelineScene(
                            scene = scene,
                            startMs = startMs,
                            endMs = endMs,
                            wordTimings = wordTimings,
                            backdropBitmap = previewThumb
                        )
                    )
                }
            }

            PipelineProgressBus.update { cur ->
                cur.copy(generatedScenesPreview = previewScenes)
            }

            // Load optional custom PNG character cutouts with sticker outlines
            customSprites = CharacterSpriteProcessor.loadCustomSpritesMap(
                paths = settings.customSpritePaths,
                stickerOutline = settings.spriteStickerOutline
            )

            // STEP 4 & 5: PUPPET RENDER + EGL14/MEDIACODEC/MEDIAMUXER
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val slug = topic.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .take(24)
                .ifBlank { "story" }
            val fileName = "ToonForge_${slug}_$stamp.mp4"

            val useWebViewMotionEngine = directCustomHtmlDoc != null ||
                !(productionMode == ProductionMode.CLIP_AUTO_EDITOR && importedClipUris.isNotEmpty())
            val frameProvider: (suspend (Int, Double, Bitmap) -> Boolean)? = if (useWebViewMotionEngine) {
                publish(
                    PipelineStep.PUPPET_RENDER,
                    0.02f,
                    0.64f,
                    if (directCustomHtmlDoc != null) {
                        "Booting 1080x1920 WebView for Custom HTML/SVG Storyboard..."
                    } else {
                        "Booting 1080x1920 HTML5/SVG Motion Engine..."
                    }
                )
                val htmlDoc = directCustomHtmlDoc ?: MotionHtmlDirector.buildHtmlDocument(
                    scenes = timelineScenes,
                    style = style,
                    totalDurationMs = stitchedAudio.totalDurationMs,
                    includeAiBackdrops = scenes.any { it.customSvg.isBlank() } || settings.enableAiBackgroundArt,
                    context = applicationContext
                )
                val sceneDurationsSec = if (directCustomHtmlDoc != null) {
                    stitchedAudio.sceneStartMs.indices.map { i ->
                        ((stitchedAudio.sceneEndMs[i] - stitchedAudio.sceneStartMs[i]) / 1000.0)
                            .coerceAtLeast(0.1)
                    }
                } else {
                    emptyList()
                }
                val renderer = WebViewFrameRenderer.create(
                    context = applicationContext,
                    htmlDocument = htmlDoc,
                    width = DeterministicVideoExporter.VIDEO_WIDTH,
                    height = DeterministicVideoExporter.VIDEO_HEIGHT
                )
                if (directCustomHtmlDoc != null && sceneDurationsSec.isNotEmpty()) {
                    renderer.setSceneTimings(sceneDurationsSec)
                }
                webViewFrameRenderer = renderer
                { _, currentMs, targetBitmap ->
                    renderer.renderFrameAt(currentMs / 1000.0, targetBitmap)
                }
            } else {
                null
            }

            val exporter = DeterministicVideoExporter(galleryRepo)
            val exportRes = exporter.exportToMediaStore(
                scenes = timelineScenes,
                unifiedPcm44100Mono = stitchedAudio.unifiedPcm44100Mono,
                totalDurationMs = stitchedAudio.totalDurationMs,
                style = style,
                displayName = fileName,
                tempCacheDir = tempWorkDir,
                onRenderProgress = { pct, msg ->
                    publish(
                        PipelineStep.PUPPET_RENDER,
                        pct,
                        0.64f + pct * 0.28f,
                        msg
                    )
                },
                onMuxProgress = { msg ->
                    publish(
                        PipelineStep.MUXING,
                        0.85f,
                        0.95f,
                        msg
                    )
                },
                productionMode = productionMode,
                customSprites = customSprites,
                externalClipFrameSource = externalClipFrameSource,
                frameBitmapProvider = frameProvider,
                overlayCaptionsOnCustomFrames = (directCustomHtmlDoc != null),
                videoBitrate = DeterministicVideoExporter.VIDEO_BITRATE
            )

            PipelineProgressBus.update { cur ->
                cur.copy(
                    generatedScenesPreview = previewScenes,
                    outputVideoUri = exportRes.mediaStoreUri,
                    outputDisplayName = exportRes.displayName
                )
            }
            return SingleRenderOutcome(exportResult = exportRes)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val msg = e.message ?: "Unexpected rendering error"
            return SingleRenderOutcome(errorMessage = msg)
        } finally {
            // Strict temporary file & bitmap memory hygiene
            renderBackdrops.forEach { bmp ->
                runCatching { if (!bmp.isRecycled) bmp.recycle() }
            }
            renderBackdrops.clear()
            customSprites.values.forEach { bmp ->
                runCatching { if (!bmp.isRecycled) bmp.recycle() }
            }
            customSprites = emptyMap()
            runCatching { webViewFrameRenderer?.close() }
            webViewFrameRenderer = null
            runCatching { externalClipFrameSource?.close() }
            runCatching { tempWorkDir.deleteRecursively() }
            runCatching {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notification_channel_desc)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(content: String, progressPct: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, VideoGenerationService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPending = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("ToonForge Tagalog Studio")
            .setContentText(content)
            .setProgress(100, progressPct, progressPct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPending)
            .build()
    }

    private fun updateNotification(content: String, progressPct: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(content, progressPct))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
