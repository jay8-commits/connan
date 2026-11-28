package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.BuiltInPrompts
import com.example.llm.DirectStoryboardParser
import com.example.model.PipelineStep
import com.example.model.VideoAspectRatio
import com.example.ui.theme.ForgeAmber
import com.example.ui.theme.ForgeCoral
import com.example.ui.theme.ForgeCyan
import com.example.ui.theme.ForgeMint
import com.example.ui.theme.StudioOutline
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioSurfaceHighlight
import com.example.ui.viewmodel.MainViewModel

@Composable
fun StudioScreen(
    viewModel: MainViewModel,
    onOpenVideosTab: () -> Unit,
    onOpenSettingsTab: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val directScriptText by viewModel.directScriptText.collectAsStateWithLifecycle()
    val aspectRatio by viewModel.aspectRatio.collectAsStateWithLifecycle()
    val stagedRenderQueue by viewModel.stagedRenderQueue.collectAsStateWithLifecycle()

    var voiceInput by remember(settings.selectedVoiceId) {
        mutableStateOf(settings.selectedVoiceId)
    }
    var voiceCatInput by remember(settings.secondaryVoiceId) {
        mutableStateOf(settings.secondaryVoiceId)
    }

    val directScriptLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                viewModel.importDirectScriptFile(uri)
            }
        }
    )

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { _ ->
            // Start generation whether granted or denied (service still runs foreground)
            viewModel.startGeneration(voiceIdDraft = voiceInput, voiceIdCatDraft = voiceCatInput)
        }
    )

    fun triggerGenerate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        viewModel.startGeneration(voiceIdDraft = voiceInput, voiceIdCatDraft = voiceCatInput)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Studio Header Banner
            StudioTopHeader(aspectRatio = aspectRatio)

            // Direct Storyboard / Script Input Card (Local Direct Script Workflow)
            DirectScriptInputCard(
                scriptText = directScriptText,
                onScriptChange = { viewModel.updateDirectScriptText(it) },
                onImportFile = {
                    directScriptLauncher.launch(
                        arrayOf("text/html", "image/svg+xml", "application/json", "text/plain", "*/*")
                    )
                },
                onCopyTemplate = {
                    clipboardManager.setText(AnnotatedString(DirectStoryboardParser.COPY_JSON_TEMPLATE))
                    viewModel.notifyMessage("Copied JSON storyboard template to clipboard")
                },
                onClear = { viewModel.updateDirectScriptText("") }
            )

            // Format & Dual Voices Selector
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioOutline),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Video Format & Dual Voices",
                        style = MaterialTheme.typography.titleSmall
                    )

                    // Aspect Ratio Switcher (9:16 Vertical vs 16:9 Widescreen)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Video Aspect Ratio",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            VideoAspectRatio.entries.forEach { ratioOption ->
                                val selected = aspectRatio == ratioOption
                                val accent = if (ratioOption == VideoAspectRatio.PORTRAIT_9_16) ForgeCoral else ForgeCyan
                                val tag = if (ratioOption == VideoAspectRatio.PORTRAIT_9_16) "aspect_ratio_9_16" else "aspect_ratio_16_9"
                                Surface(
                                    onClick = { viewModel.selectAspectRatio(ratioOption) },
                                    color = if (selected) accent.copy(alpha = 0.16f) else StudioSurfaceElevated,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) accent else StudioOutline
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag(tag)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = ratioOption.shortLabel,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (selected) accent else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (ratioOption == VideoAspectRatio.PORTRAIT_9_16) {
                                                "1080x1920 • Shorts"
                                            } else {
                                                "1920x1080 • YouTube"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ElevenLabs Voice Presets + Editable Dual Voice IDs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BuiltInPrompts.VOICE_PRESETS.forEach { preset ->
                            val active = voiceInput == preset.id
                            FilterChip(
                                selected = active,
                                onClick = {
                                    voiceInput = preset.id
                                    viewModel.selectVoiceId(preset.id)
                                },
                                label = {
                                    Text(
                                        text = "${preset.name} • ${preset.tag}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = voiceInput,
                        onValueChange = { raw ->
                            val clean = raw.replace("\r", "").replace("\n", "").trim()
                            voiceInput = clean
                            viewModel.selectVoiceId(clean)
                        },
                        label = { Text("Primary Voice ID (Narrator / Character 1)") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Rounded.GraphicEq, contentDescription = "Primary Voice ID")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("voice_id_input"),
                        colors = studioTextFieldColors()
                    )
                    OutlinedTextField(
                        value = voiceCatInput,
                        onValueChange = { raw ->
                            val clean = raw.replace("\r", "").replace("\n", "").trim()
                            voiceCatInput = clean
                            viewModel.setSecondaryVoiceId(clean)
                        },
                        label = { Text("Secondary Voice ID (Character 2 — Optional)") },
                        placeholder = { Text("Used when scene speaker == \"cat\"") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Rounded.GraphicEq, contentDescription = "Secondary Voice ID")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("voice_id_cat_input"),
                        colors = studioTextFieldColors()
                    )
                }
            }

            CustomBgmCard(
                customBgmName = settings.customBgmName,
                onImportBgm = { uri -> viewModel.importCustomBgm(uri) },
                onClearBgm = { viewModel.clearCustomBgm() }
            )

            // 5. GENERATE VIDEO CTA + ADD TO BATCH QUEUE (UP TO 5) + LIVE PIPELINE STEPPER
            val activePlusQueued = if (progress.isRunning) {
                1 + progress.queuedBatchCount
            } else {
                stagedRenderQueue.size
            }
            val maxQueue = com.example.service.VideoGenerationService.MAX_BATCH_QUEUE_SIZE

            if (!progress.isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { triggerGenerate() },
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .testTag("generate_video_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ForgeCoral)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MovieCreation,
                            contentDescription = "Generate Video"
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (stagedRenderQueue.isNotEmpty()) {
                                "Render Batch (${stagedRenderQueue.size})"
                            } else {
                                "Generate Video"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.addToRenderQueue(voiceIdDraft = voiceInput, voiceIdCatDraft = voiceCatInput) },
                        enabled = stagedRenderQueue.size < maxQueue,
                        border = BorderStroke(1.5.dp, ForgeCyan.copy(alpha = 0.8f)),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .height(54.dp)
                            .testTag("add_to_queue_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Add to Queue",
                            tint = ForgeCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (stagedRenderQueue.isEmpty()) "Add to Queue" else "Queue (${stagedRenderQueue.size}/$maxQueue)",
                            style = MaterialTheme.typography.labelLarge,
                            color = ForgeCyan,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { viewModel.cancelGeneration() },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("cancel_render_button"),
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Cancel",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (progress.totalBatchCount > 1) {
                                "Cancel Batch (${progress.currentBatchIndex}/${progress.totalBatchCount})"
                            } else {
                                "Cancel Render"
                            },
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.addToRenderQueue(voiceIdDraft = voiceInput, voiceIdCatDraft = voiceCatInput) },
                        enabled = activePlusQueued < maxQueue,
                        border = BorderStroke(1.5.dp, ForgeCyan.copy(alpha = 0.8f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .height(50.dp)
                            .testTag("add_to_queue_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QueuePlayNext,
                            contentDescription = "Add to Queue",
                            tint = ForgeCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Queue ($activePlusQueued/$maxQueue)",
                            style = MaterialTheme.typography.labelLarge,
                            color = ForgeCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (stagedRenderQueue.isNotEmpty() && !progress.isRunning) {
                BatchQueuePreviewCard(
                    items = stagedRenderQueue,
                    maxCount = maxQueue,
                    onRemove = { id -> viewModel.removeQueuedRenderItem(id) },
                    onClear = { viewModel.clearRenderQueue() }
                )
            }

            // Live Step-by-Step Progress Card
            if (progress.step != PipelineStep.IDLE) {
                PipelineProgressCard(
                    progress = progress,
                    onOpenVideos = onOpenVideosTab,
                    onOpenSettingsTab = onOpenSettingsTab
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StudioTopHeader(aspectRatio: VideoAspectRatio) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF1E172E), Color(0xFF15192B), Color(0xFF281718))
                )
            )
            .border(1.dp, StudioOutline, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ToonForge Tagalog",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${aspectRatio.width}x${aspectRatio.height} @ 30fps On-Device Motion & WebView Studio",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB5BDD6)
                )
            }
            Surface(
                color = ForgeCoral.copy(alpha = 0.2f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ForgeCoral.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = ForgeAmber,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = aspectRatio.shortLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = ForgeAmber
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectScriptInputCard(
    scriptText: String,
    onScriptChange: (String) -> Unit,
    onImportFile: () -> Unit,
    onCopyTemplate: () -> Unit,
    onClear: () -> Unit
) {
    val summary = remember(scriptText) {
        if (scriptText.isBlank()) "Ready for JSON, HTML/SVG, or Script" else DirectStoryboardParser.estimatePreviewSummary(scriptText)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = BorderStroke(1.dp, ForgeMint.copy(alpha = 0.50f)),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.testTag("direct_script_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Code,
                        contentDescription = "Direct Storyboard / Script Input",
                        tint = ForgeMint,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Direct Storyboard / Script Input",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    color = ForgeMint.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = ForgeMint,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Text(
                text = "Paste full scene JSON, raw HTML5/SVG animation code, or plain script in any language — renders directly with ElevenLabs TTS + MP4 export.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onImportFile,
                    border = BorderStroke(1.dp, ForgeCyan.copy(alpha = 0.65f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("import_direct_script_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileUpload,
                        contentDescription = "Import HTML, SVG, JSON, or TXT file",
                        tint = ForgeCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Import HTML / JSON / TXT",
                        style = MaterialTheme.typography.labelMedium,
                        color = ForgeCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onCopyTemplate,
                    border = BorderStroke(1.dp, ForgeMint.copy(alpha = 0.65f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("copy_json_template_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "Copy JSON Template",
                        tint = ForgeMint,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Copy JSON Template",
                        style = MaterialTheme.typography.labelMedium,
                        color = ForgeMint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            OutlinedTextField(
                value = scriptText,
                onValueChange = onScriptChange,
                label = { Text("JSON, Raw HTML/SVG, or Plain Script (Any Language)") },
                placeholder = {
                    Text("Paste {\"scenes\": [...]} JSON, <!DOCTYPE html>/<svg> bespoke animation code, OR plain script in English, Tagalog, or any language...")
                },
                minLines = 6,
                maxLines = 12,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("direct_script_input"),
                colors = studioTextFieldColors()
            )

            if (scriptText.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("clear_direct_script_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "Clear direct script",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Clear Script",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchQueuePreviewCard(
    items: List<com.example.ui.viewmodel.QueuedRenderUiItem>,
    maxCount: Int,
    onRemove: (String) -> Unit,
    onClear: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = StudioSurfaceElevated),
        border = BorderStroke(1.dp, ForgeCyan.copy(alpha = 0.45f)),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("batch_queue_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Batch Render Queue (${items.size}/$maxCount)",
                    style = MaterialTheme.typography.titleSmall,
                    color = ForgeCyan,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Clear All", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items.forEachIndexed { idx, item ->
                Surface(
                    color = StudioSurface,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, StudioOutline)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "#${idx + 1} • ${item.title}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = ForgeMint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { onRemove(item.id) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Remove queued item",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineProgressCard(
    progress: com.example.model.GenerationProgress,
    onOpenVideos: () -> Unit,
    onOpenSettingsTab: () -> Unit
) {
    val steps = listOf(
        PipelineStep.SCRIPT,
        PipelineStep.AUDIO,
        PipelineStep.ART,
        PipelineStep.PUPPET_RENDER,
        PipelineStep.MUXING
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = StudioSurfaceElevated),
        border = BorderStroke(
            1.dp,
            when (progress.step) {
                PipelineStep.COMPLETED -> ForgeMint
                PipelineStep.ERROR -> MaterialTheme.colorScheme.error
                else -> ForgeCoral
            }
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (progress.step) {
                        PipelineStep.COMPLETED -> "Export Complete!"
                        PipelineStep.ERROR -> "Pipeline Error"
                        else -> "Rendering in Background Service..."
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = when (progress.step) {
                        PipelineStep.COMPLETED -> ForgeMint
                        PipelineStep.ERROR -> MaterialTheme.colorScheme.error
                        else -> ForgeAmber
                    }
                )
                if (progress.providerInfo.isNotBlank()) {
                    Text(
                        text = progress.providerInfo,
                        style = MaterialTheme.typography.labelSmall,
                        color = ForgeCyan
                    )
                }
            }

            // 5-Step Pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                steps.forEach { st ->
                    val done = progress.step == PipelineStep.COMPLETED ||
                        (progress.step.index > st.index && progress.step.index > 0)
                    val active = progress.step == st
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = when {
                            done -> ForgeMint.copy(alpha = 0.2f)
                            active -> ForgeCoral.copy(alpha = 0.25f)
                            else -> StudioSurfaceHighlight
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = if (active) BorderStroke(1.dp, ForgeCoral) else null
                    ) {
                        Text(
                            text = st.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                done -> ForgeMint
                                active -> Color.White
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            LinearProgressIndicator(
                progress = { progress.overallProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (progress.step == PipelineStep.COMPLETED) ForgeMint else ForgeCoral,
                trackColor = StudioSurfaceHighlight
            )

            if (progress.step == PipelineStep.ERROR) {
                val rawErr = progress.errorMessage?.takeIf { it.isNotBlank() } ?: progress.statusMessage
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pipeline_error_box")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = "Error details",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Exact Error Details",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        SelectionContainer {
                            Text(
                                text = rawErr,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        OutlinedButton(
                            onClick = onOpenSettingsTab,
                            border = BorderStroke(1.dp, ForgeAmber.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("error_open_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "Go to API & Settings",
                                tint = ForgeAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Go to API & Settings",
                                color = ForgeAmber,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = progress.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (progress.step == PipelineStep.COMPLETED) {
                Button(
                    onClick = onOpenVideos,
                    colors = ButtonDefaults.buttonColors(containerColor = ForgeMint),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Open Gallery",
                        tint = Color.Black
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Watch in Videos Gallery", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun studioTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ForgeCoral,
    unfocusedBorderColor = StudioOutline,
    focusedContainerColor = StudioSurfaceElevated,
    unfocusedContainerColor = StudioSurfaceElevated
)
