package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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
import com.example.model.ActorAction
import com.example.model.ActorRole
import com.example.model.AnimationStyle
import com.example.model.HudWidget
import com.example.model.PipelineStep
import com.example.model.ProductionMode
import com.example.model.ScriptSourceMode
import com.example.model.ShotFraming
import com.example.model.StatusPill
import com.example.model.StoryNiche
import com.example.model.VideoAspectRatio
import com.example.model.WorldFx
import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import com.example.render.MotionHtmlDirector
import com.example.render.SceneCompositor
import com.example.render.StudioRendererBridge
import com.example.render.WebViewFrameRenderer
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
    val topic by viewModel.topic.collectAsStateWithLifecycle()
    val directScriptText by viewModel.directScriptText.collectAsStateWithLifecycle()
    val productionMode by viewModel.productionMode.collectAsStateWithLifecycle()
    val selectedStyle by viewModel.selectedStyle.collectAsStateWithLifecycle()
    val importedClips by viewModel.importedClips.collectAsStateWithLifecycle()
    val previewCustomSprites by viewModel.previewCustomSprites.collectAsStateWithLifecycle()
    val selectedDurationSec by viewModel.selectedDurationSec.collectAsStateWithLifecycle()
    val aspectRatio by viewModel.aspectRatio.collectAsStateWithLifecycle()
    val previewPlaying by viewModel.previewPlaying.collectAsStateWithLifecycle()
    val previewTimeMs by viewModel.previewTimeMs.collectAsStateWithLifecycle()
    val demoRole by viewModel.demoActorRole.collectAsStateWithLifecycle()
    val demoAction by viewModel.demoActorAction.collectAsStateWithLifecycle()
    val demoFx by viewModel.demoWorldFx.collectAsStateWithLifecycle()
    val demoPill by viewModel.demoStatusPill.collectAsStateWithLifecycle()
    val demoHudWidget by viewModel.demoHudWidget.collectAsStateWithLifecycle()
    val demoShotFraming by viewModel.demoShotFraming.collectAsStateWithLifecycle()
    val stagedRenderQueue by viewModel.stagedRenderQueue.collectAsStateWithLifecycle()

    val scriptSourceMode = settings.effectiveScriptSourceMode
    val selectedNiche = settings.selectedNiche
    val activeTemplate = settings.activeTemplate
    var showPromptEditor by remember { mutableStateOf(false) }
    var templateDropdownOpen by remember { mutableStateOf(false) }
    var editableTemplateName by remember(activeTemplate?.id) {
        mutableStateOf(activeTemplate?.name.orEmpty())
    }
    var editableTemplateBody by remember(activeTemplate?.id, activeTemplate?.content) {
        mutableStateOf(activeTemplate?.content.orEmpty())
    }
    var voiceInput by remember(settings.selectedVoiceId) {
        mutableStateOf(settings.selectedVoiceId)
    }
    var voiceCatInput by remember(settings.secondaryVoiceId) {
        mutableStateOf(settings.secondaryVoiceId)
    }

    LaunchedEffect(activeTemplate) {
        if (activeTemplate != null) {
            editableTemplateName = activeTemplate.name
            editableTemplateBody = activeTemplate.content
        }
    }

    val txtImporterLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                viewModel.importTxtPrompt(uri)
            }
        }
    )

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

            // 3-Mode Script Source Selector (Direct Script / Custom LLM API / AI Horde)
            ScriptSourceSelectorCard(
                selectedMode = scriptSourceMode,
                activeModelId = settings.effectiveSelectedModelId,
                onSelectMode = { mode -> viewModel.setScriptSourceMode(mode) }
            )

            // Story Niche Selector (Multi-Niche GSAP Vector Studio)
            NicheSelectorCard(
                selectedNiche = selectedNiche,
                onSelectNiche = { niche -> viewModel.selectNiche(niche) }
            )

            // Direct Storyboard / Script Input Card (Zero LLM API Timeout)
            AnimatedVisibility(visible = scriptSourceMode == ScriptSourceMode.DIRECT_SCRIPT) {
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
                    onCopyGodPrompt = {
                        clipboardManager.setText(AnnotatedString(MotionHtmlDirector.MASTER_CONTENT_GOD_PROMPT_V2.trim()))
                        viewModel.notifyMessage("Copied Master Content God Prompt v2 to clipboard")
                    },
                    onClear = { viewModel.updateDirectScriptText("") }
                )
            }

            // 0. THREE STUDIO PRO PRODUCTION MODES + CLIP AUTO-HUD PICKER
            ProductionModeSelectorCard(
                selectedMode = productionMode,
                onSelectMode = { viewModel.selectProductionMode(it) }
            )

            AnimatedVisibility(visible = productionMode == ProductionMode.CLIP_AUTO_EDITOR) {
                ClipAutoEditorPickerCard(
                    clips = importedClips,
                    onAddClips = { viewModel.addImportedClips(it) },
                    onRemoveClip = { viewModel.removeImportedClip(it) },
                    onClearClips = { viewModel.clearImportedClips() }
                )
            }

            // 1. MASTER PROMPT TEMPLATE BAR + .TXT IMPORTER + INLINE EDITOR (Hidden in Direct Script Mode)
            AnimatedVisibility(visible = scriptSourceMode != ScriptSourceMode.DIRECT_SCRIPT) {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioOutline),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Description,
                                contentDescription = "Master prompt",
                                tint = ForgeAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Master System Prompt",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Surface(
                            color = if (scriptSourceMode == ScriptSourceMode.CUSTOM_LLM) {
                                ForgeCyan.copy(alpha = 0.16f)
                            } else {
                                ForgeMint.copy(alpha = 0.14f)
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (scriptSourceMode == ScriptSourceMode.CUSTOM_LLM) {
                                    "JSON Studio Director"
                                } else {
                                    "JSON Schema Locked"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (scriptSourceMode == ScriptSourceMode.CUSTOM_LLM) ForgeCyan else ForgeMint,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // Template selector dropdown + Import .txt + Edit Prompt
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { templateDropdownOpen = true }
                                    .testTag("prompt_template_selector"),
                                color = StudioSurfaceElevated,
                                border = BorderStroke(1.dp, StudioOutline),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = activeTemplate?.name ?: "Select Template",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = "Choose prompt template",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = templateDropdownOpen,
                                onDismissRequest = { templateDropdownOpen = false }
                            ) {
                                settings.promptTemplates.forEach { tpl ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = tpl.name,
                                                    fontWeight = if (tpl.id == activeTemplate?.id) {
                                                        FontWeight.Bold
                                                    } else FontWeight.Normal
                                                )
                                                if (!tpl.isBuiltIn) {
                                                    Text(
                                                        text = ".TXT",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = ForgeCyan
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            viewModel.selectPromptTemplate(tpl.id)
                                            templateDropdownOpen = false
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { txtImporterLauncher.launch(arrayOf("text/plain")) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            border = BorderStroke(1.dp, ForgeCyan.copy(alpha = 0.6f)),
                            modifier = Modifier.testTag("import_txt_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FileUpload,
                                contentDescription = "Import .txt",
                                tint = ForgeCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Import .txt",
                                style = MaterialTheme.typography.labelMedium,
                                color = ForgeCyan
                            )
                        }

                        OutlinedButton(
                            onClick = { showPromptEditor = !showPromptEditor },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (showPromptEditor) ForgeCoral else StudioOutline
                            ),
                            modifier = Modifier.testTag("edit_prompt_toggle")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "Edit Prompt",
                                tint = if (showPromptEditor) ForgeCoral else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Edit Prompt",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (showPromptEditor) ForgeCoral else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Expandable Inline System Prompt Editor
                    AnimatedVisibility(visible = showPromptEditor && activeTemplate != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = editableTemplateName,
                                onValueChange = { editableTemplateName = it },
                                label = { Text("Template Name") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("prompt_name_input"),
                                colors = studioTextFieldColors()
                            )
                            OutlinedTextField(
                                value = editableTemplateBody,
                                onValueChange = { editableTemplateBody = it },
                                label = { Text("Creative Storytelling Instructions (Tagalog Tone / Rules)") },
                                minLines = 5,
                                maxLines = 10,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("prompt_body_input"),
                                colors = studioTextFieldColors()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = {
                                            activeTemplate?.let { tpl ->
                                                if (tpl.isBuiltIn) {
                                                    viewModel.resetPromptTemplate(tpl.id)
                                                } else {
                                                    editableTemplateName = tpl.name
                                                    editableTemplateBody = tpl.content
                                                }
                                            }
                                        },
                                        modifier = Modifier.testTag("reset_prompt_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = "Reset",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("Reset")
                                    }
                                    if (activeTemplate != null && !activeTemplate.isBuiltIn) {
                                        TextButton(
                                            onClick = { viewModel.deletePromptTemplate(activeTemplate.id) },
                                            colors = ButtonDefaults.textButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error
                                            ),
                                            modifier = Modifier.testTag("delete_prompt_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Delete,
                                                contentDescription = "Delete",
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Delete")
                                        }
                                    }
                                }

                                Button(
                                    onClick = {
                                        activeTemplate?.let {
                                            viewModel.savePromptTemplate(
                                                it.id,
                                                editableTemplateName,
                                                editableTemplateBody
                                            )
                                        }
                                    },
                                    modifier = Modifier.testTag("save_prompt_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Save,
                                        contentDescription = "Save",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Save")
                                }
                            }
                        }
                    }
                }
            }
            }

            // 2. TOPIC INPUT + RANDOM VIRAL TAGALOG IDEA (Hidden in Direct Script Mode)
            AnimatedVisibility(visible = scriptSourceMode != ScriptSourceMode.DIRECT_SCRIPT) {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(1.dp, StudioOutline),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Story Topic / Hook",
                        style = MaterialTheme.typography.titleSmall
                    )
                    OutlinedTextField(
                        value = topic,
                        onValueChange = { viewModel.updateTopic(it) },
                        placeholder = { Text("e.g. Bakit laging ubos ang sahod...") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("topic_input"),
                        colors = studioTextFieldColors()
                    )
                    OutlinedButton(
                        onClick = { viewModel.pickRandomIdea() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("random_idea_button"),
                        border = BorderStroke(1.dp, ForgeAmber.copy(alpha = 0.55f))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = "Random Idea",
                            tint = ForgeAmber,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Random Viral Tagalog Idea",
                            color = ForgeAmber,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            }

            // 3. HORIZONTAL ANIMATION STYLE PRESET CARDS (4 Viral 2D & 2.5D Motion Presets)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Animation Style Preset (${AnimationStyle.STUDIO_VIRAL_PRESETS.size} Motion Engines)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    items(AnimationStyle.STUDIO_VIRAL_PRESETS) { style ->
                        val isSelected = style == selectedStyle
                        Card(
                            modifier = Modifier
                                .width(220.dp)
                                .clickable { viewModel.selectStyle(style) }
                                .testTag("style_card_${style.name}"),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    style.accentColor.copy(alpha = 0.14f)
                                } else {
                                    StudioSurface
                                }
                            ),
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) style.accentColor else StudioOutline
                            ),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    color = style.accentColor.copy(alpha = 0.22f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = style.badge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = style.accentColor,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                                Text(
                                    text = style.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = style.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Captions: ${style.captionStyleLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ForgeAmber.copy(alpha = 0.9f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // 4. DURATION & VOICE ID SELECTOR
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
                        text = "Target Duration, Format & Dual Voices",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(30, 60, 90).forEach { sec ->
                            val selected = selectedDurationSec == sec
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.selectDuration(sec) },
                                label = {
                                    Text(
                                        text = "${sec}s (${if (sec == 30) "7" else if (sec == 60) "13" else "19"} scenes)",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("duration_${sec}s"),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ForgeCoral.copy(alpha = 0.22f),
                                    selectedLabelColor = ForgeCoral
                                )
                            )
                        }
                    }

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
                        label = { Text("Primary Voice ID (Matalinong Daga — Narrator)") },
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
                        label = { Text("Secondary Voice ID (Si Pusa — Optional)") },
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

            // 4B. QUICK STUDIO PRO SPRITE & BGM CONTROLS
            CharacterSpriteManagerCard(
                customSpritePaths = settings.customSpritePaths,
                previewSprites = previewCustomSprites,
                stickerOutlineEnabled = settings.spriteStickerOutline,
                onImportSprite = { role, uri -> viewModel.importCharacterSprite(role, uri) },
                onRemoveSprite = { role -> viewModel.removeCharacterSprite(role) },
                onToggleStickerOutline = { viewModel.setSpriteStickerOutline(it) }
            )

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
                                when (productionMode) {
                                    ProductionMode.CLIP_AUTO_EDITOR -> "Clips + Auto-HUD"
                                    ProductionMode.CROSS_SECTION_EXPLAINER -> "Generate Cutaway"
                                    ProductionMode.FULL_AI_STORY -> "Generate Video"
                                }
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

            // 6. INTERACTIVE LIVE CANVAS PREVIEW
            LiveCanvasPreviewCard(
                viewModel = viewModel,
                productionMode = productionMode,
                selectedStyle = selectedStyle,
                selectedNiche = selectedNiche,
                aspectRatio = aspectRatio,
                scriptSourceMode = scriptSourceMode,
                directScriptText = directScriptText,
                customSprites = previewCustomSprites,
                previewPlaying = previewPlaying,
                previewTimeMs = previewTimeMs,
                generatedScenes = progress.generatedScenesPreview,
                demoRole = demoRole,
                demoAction = demoAction,
                demoFx = demoFx,
                demoPill = demoPill,
                demoHudWidget = demoHudWidget,
                demoShotFraming = demoShotFraming
            )

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
private fun ScriptSourceSelectorCard(
    selectedMode: ScriptSourceMode,
    activeModelId: String,
    onSelectMode: (ScriptSourceMode) -> Unit
) {
    val activeAccent = when (selectedMode) {
        ScriptSourceMode.DIRECT_SCRIPT -> ForgeMint
        ScriptSourceMode.CUSTOM_LLM -> ForgeCyan
        ScriptSourceMode.AI_HORDE -> ForgeAmber
    }
    val statusText = when (selectedMode) {
        ScriptSourceMode.DIRECT_SCRIPT -> "Sureball Local • ElevenLabs TTS"
        ScriptSourceMode.CUSTOM_LLM -> "${activeModelId.ifBlank { "Custom LLM" }} • ElevenLabs TTS"
        ScriptSourceMode.AI_HORDE -> "aihorde.net • ElevenLabs TTS"
    }

    Surface(
        color = StudioSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, activeAccent.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("studio_engine_toggle_pill")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Script Source",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = activeAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val options = listOf(
                    Triple(
                        ScriptSourceMode.DIRECT_SCRIPT,
                        Icons.Rounded.Code,
                        Pair(ForgeMint, "studio_mode_direct_script")
                    ),
                    Triple(
                        ScriptSourceMode.CUSTOM_LLM,
                        Icons.Rounded.SmartToy,
                        Pair(ForgeCyan, "studio_mode_custom_llm")
                    ),
                    Triple(
                        ScriptSourceMode.AI_HORDE,
                        Icons.Rounded.AutoAwesome,
                        Pair(ForgeAmber, "studio_mode_ai_horde")
                    )
                )

                options.forEach { (mode, icon, accentAndTag) ->
                    val (accent, tag) = accentAndTag
                    val isSelected = selectedMode == mode
                    val subLabel = when (mode) {
                        ScriptSourceMode.DIRECT_SCRIPT -> "No Timeout"
                        ScriptSourceMode.CUSTOM_LLM -> "Dual-Attempt"
                        ScriptSourceMode.AI_HORDE -> "aihorde.net"
                    }
                    Surface(
                        color = if (isSelected) accent.copy(alpha = 0.18f) else StudioSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) accent else StudioOutline
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelectMode(mode) }
                            .testTag(tag)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = mode.title,
                                    tint = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = mode.shortLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = subLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) accent.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NicheSelectorCard(
    selectedNiche: StoryNiche,
    onSelectNiche: (StoryNiche) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = BorderStroke(1.dp, ForgeAmber.copy(alpha = 0.45f)),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Story Niche & Rig Pack",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    color = ForgeAmber.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = selectedNiche.seriesBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = ForgeAmber,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    onClick = { expanded = true },
                    color = StudioSurfaceElevated,
                    border = BorderStroke(1.dp, StudioOutline),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("niche_selector_dropdown")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = selectedNiche.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = selectedNiche.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Select story niche",
                            tint = ForgeAmber
                        )
                    }
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    StoryNiche.entries.forEach { niche ->
                        val isSelected = niche == selectedNiche
                        DropdownMenuItem(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("niche_option_${niche.nicheKey}"),
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = niche.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) ForgeAmber else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = niche.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                onSelectNiche(niche)
                                expanded = false
                            }
                        )
                    }
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
    onCopyGodPrompt: () -> Unit,
    onClear: () -> Unit
) {
    val summary = remember(scriptText) {
        if (scriptText.isBlank()) "Ready for JSON, HTML/SVG, or Tagalog" else DirectStoryboardParser.estimatePreviewSummary(scriptText)
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
                text = "Zero LLM/Horde network calls. Paste full scene JSON, raw HTML5/SVG animation code, or plain Tagalog text — renders directly to ElevenLabs TTS + 1080x1920 MP4.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(
                onClick = onImportFile,
                border = BorderStroke(1.dp, ForgeCyan.copy(alpha = 0.65f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .fillMaxWidth()
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
                    text = "Import HTML / SVG / JSON / TXT",
                    style = MaterialTheme.typography.labelMedium,
                    color = ForgeCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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

                OutlinedButton(
                    onClick = onCopyGodPrompt,
                    border = BorderStroke(1.dp, ForgeAmber.copy(alpha = 0.72f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("copy_god_prompt_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = "Copy God Prompt",
                        tint = ForgeAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Copy God Prompt",
                        style = MaterialTheme.typography.labelMedium,
                        color = ForgeAmber,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            OutlinedTextField(
                value = scriptText,
                onValueChange = onScriptChange,
                label = { Text("JSON, Raw HTML/SVG, or Plain Tagalog Text") },
                placeholder = {
                    Text("Paste {\"scenes\": [...]} JSON, <!DOCTYPE html>/<svg> bespoke animation code, OR plain Tagalog sentences...")
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
private fun LiveCanvasPreviewCard(
    viewModel: MainViewModel,
    productionMode: ProductionMode,
    selectedStyle: AnimationStyle,
    selectedNiche: StoryNiche,
    aspectRatio: VideoAspectRatio,
    scriptSourceMode: ScriptSourceMode,
    directScriptText: String,
    customSprites: Map<ActorRole, android.graphics.Bitmap>,
    previewPlaying: Boolean,
    previewTimeMs: Double,
    generatedScenes: List<com.example.model.TimelineScene>,
    demoRole: ActorRole,
    demoAction: ActorAction,
    demoFx: WorldFx,
    demoPill: StatusPill,
    demoHudWidget: HudWidget,
    demoShotFraming: ShotFraming
) {
    val activeScenes = remember(
        generatedScenes, selectedStyle, selectedNiche, demoRole, demoAction, demoFx, demoPill, demoHudWidget, demoShotFraming
    ) {
        if (generatedScenes.isNotEmpty()) generatedScenes else viewModel.buildDemoPreviewScenes()
    }
    val totalDurMs = activeScenes.lastOrNull()?.endMs?.coerceAtLeast(1000.0) ?: 4200.0
    val isWide = aspectRatio == VideoAspectRatio.LANDSCAPE_16_9

    Card(
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = BorderStroke(1.dp, StudioOutline),
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
                Column {
                    Text(
                        text = if (generatedScenes.isNotEmpty()) "Generated Story Preview" else "Interactive Puppet Stage Preview",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Live ${aspectRatio.shortLabel} Canvas • ${selectedStyle.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = selectedStyle.accentColor
                    )
                }
                IconButton(
                    onClick = { viewModel.togglePreviewPlay() },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(ForgeCoral)
                        .testTag("preview_play_pause")
                ) {
                    Icon(
                        imageVector = if (previewPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (previewPlaying) "Pause preview" else "Play preview",
                        tint = Color.White
                    )
                }
            }

            // Live Preview Viewport (9:16 Vertical or 16:9 Widescreen)
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (isWide) 1f else 0.68f)
                    .aspectRatio(if (isWide) 16f / 9f else 9f / 16f)
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(16.dp))
                    .border(2.dp, selectedStyle.accentColor.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
            ) {
                if (productionMode == ProductionMode.CLIP_AUTO_EDITOR) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawIntoCanvas { canvas ->
                            SceneCompositor.drawFrame(
                                canvas = canvas.nativeCanvas,
                                width = size.width,
                                height = size.height,
                                scenes = activeScenes,
                                style = selectedStyle,
                                currentMs = previewTimeMs,
                                fallbackBackdropCache = viewModel.previewBackdropCache,
                                productionMode = productionMode,
                                customSprites = customSprites
                            )
                        }
                    }
                } else {
                    val previewCtx = LocalContext.current
                    val customDirectHtml = remember(generatedScenes, scriptSourceMode, directScriptText) {
                        if (generatedScenes.isEmpty() &&
                            scriptSourceMode == ScriptSourceMode.DIRECT_SCRIPT &&
                            DirectStoryboardParser.looksLikeHtmlOrSvgInput(directScriptText)
                        ) {
                            DirectStoryboardParser.buildCustomHtmlDocumentIfNeeded(directScriptText)
                        } else {
                            null
                        }
                    }
                    val previewHtml = remember(customDirectHtml, activeScenes, selectedNiche, totalDurMs, aspectRatio) {
                        customDirectHtml ?: StudioRendererBridge.buildStudioHtmlDocument(
                            scenes = activeScenes,
                            title = activeScenes.firstOrNull()?.scene?.episodeTitle?.ifBlank { selectedNiche.seriesBadge }
                                ?: selectedNiche.seriesBadge,
                            niche = selectedNiche,
                            aspectRatio = aspectRatio,
                            context = previewCtx
                        )
                    }
                    MotionWebViewPreview(
                        htmlDocument = previewHtml,
                        aspectRatioKey = aspectRatio.ratioKey,
                        currentSeconds = previewTimeMs / 1000.0,
                        modifier = Modifier.fillMaxSize(),
                        fallbackContent = {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawIntoCanvas { canvas ->
                                    SceneCompositor.drawFrame(
                                        canvas = canvas.nativeCanvas,
                                        width = size.width,
                                        height = size.height,
                                        scenes = activeScenes,
                                        style = selectedStyle,
                                        currentMs = previewTimeMs,
                                        fallbackBackdropCache = viewModel.previewBackdropCache,
                                        productionMode = productionMode,
                                        customSprites = customSprites
                                    )
                                }
                            }
                        }
                    )
                }
            }

            // Timeline Scrubber
            Slider(
                value = (previewTimeMs / totalDurMs).toFloat().coerceIn(0f, 1f),
                onValueChange = { frac -> viewModel.seekPreview(frac * totalDurMs) },
                colors = SliderDefaults.colors(
                    thumbColor = ForgeCoral,
                    activeTrackColor = ForgeCoral
                )
            )

            // Interactive Demo Rig Controls when viewing sample stage
            if (generatedScenes.isEmpty()) {
                Text(
                    text = "Test Puppet Rig, Action & World FX Live:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActorRole.entries.forEach { role ->
                        FilterChip(
                            selected = role == demoRole,
                            onClick = { viewModel.setDemoRole(role) },
                            label = { Text(role.name, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActorAction.entries.forEach { act ->
                        FilterChip(
                            selected = act == demoAction,
                            onClick = { viewModel.setDemoAction(act) },
                            label = { Text(act.name, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WorldFx.entries.forEach { fx ->
                        FilterChip(
                            selected = fx == demoFx,
                            onClick = { viewModel.setDemoWorldFx(fx) },
                            label = { Text(fx.name, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Text(
                    text = "Test Kinetic HUD Pill & Telemetry Widget:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusPill.entries.forEach { pill ->
                        FilterChip(
                            selected = pill == demoPill,
                            onClick = { viewModel.setDemoStatusPill(pill) },
                            label = { Text("Pill: ${pill.name}", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HudWidget.entries.forEach { widget ->
                        FilterChip(
                            selected = widget == demoHudWidget,
                            onClick = { viewModel.setDemoHudWidget(widget) },
                            label = { Text("HUD: ${widget.name}", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        ShotFraming.WIDE_FULL_BODY,
                        ShotFraming.MEDIUM_WAIST_UP,
                        ShotFraming.CLOSE_UP_FACE,
                        ShotFraming.RULE_OF_THIRDS_LEFT,
                        ShotFraming.RULE_OF_THIRDS_RIGHT
                    ).forEach { framing ->
                        FilterChip(
                            selected = framing == demoShotFraming,
                            onClick = { viewModel.setDemoShotFraming(framing) },
                            label = { Text("Shot: ${framing.name}", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun MotionWebViewPreview(
    htmlDocument: String,
    aspectRatioKey: String,
    currentSeconds: Double,
    modifier: Modifier = Modifier,
    fallbackContent: (@Composable () -> Unit)? = null
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var lastLoadedHtml by remember { mutableStateOf("") }
    var lastAspectRatioKey by remember { mutableStateOf(aspectRatioKey) }
    var pageReady by remember { mutableStateOf(false) }
    var rendererCrashed by remember { mutableStateOf(false) }
    var evalInFlight by remember { mutableStateOf(false) }
    var lastEvalAtMs by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                runCatching {
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.stopLoading()
                    wv.destroy()
                }
            }
            webViewRef = null
        }
    }

    if (rendererCrashed) {
        Box(modifier = modifier.testTag("motion_webview_preview_fallback")) {
            fallbackContent?.invoke()
            Surface(
                color = StudioSurface.copy(alpha = 0.85f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
            ) {
                TextButton(
                    onClick = {
                        lastLoadedHtml = ""
                        pageReady = false
                        evalInFlight = false
                        rendererCrashed = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        text = "Reload WebView",
                        style = MaterialTheme.typography.labelSmall,
                        color = ForgeCyan
                    )
                }
            }
        }
        return
    }

    AndroidView(
        modifier = modifier.testTag("motion_webview_preview"),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.parseColor("#080B12"))
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setOnTouchListener { _, event ->
                    event.action == MotionEvent.ACTION_MOVE
                }
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
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = true

                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageReady = true
                        evalInFlight = false
                        val ratioJs = WebViewFrameRenderer.buildSetAspectRatioScript(aspectRatioKey)
                        val secStr = String.format(java.util.Locale.US, "%.4f", currentSeconds.coerceAtLeast(0.0))
                        lastEvalAtMs = SystemClock.uptimeMillis()
                        view?.evaluateJavascript("$ratioJs if (window.seekToTime) window.seekToTime($secStr);", null)
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
                        Log.e("MotionWebViewPreview", "WebView renderer process gone (didCrash=$didCrash)")
                        pageReady = false
                        evalInFlight = false
                        rendererCrashed = true
                        runCatching {
                            (view?.parent as? ViewGroup)?.removeView(view)
                            view?.stopLoading()
                            view?.destroy()
                        }
                        if (webViewRef === view) {
                            webViewRef = null
                        }
                        return true
                    }
                }
                webViewRef = this
                lastLoadedHtml = htmlDocument
                lastAspectRatioKey = aspectRatioKey
                pageReady = false
                evalInFlight = false
                val preparedHtml = WebViewFrameRenderer.prepareHtmlForWebView(htmlDocument)
                loadDataWithBaseURL(WebViewFrameRenderer.ASSET_BASE_URL, preparedHtml, "text/html", "UTF-8", null)
            }
        },
        update = { wv ->
            if (rendererCrashed) return@AndroidView
            if (lastLoadedHtml != htmlDocument) {
                lastLoadedHtml = htmlDocument
                lastAspectRatioKey = aspectRatioKey
                pageReady = false
                evalInFlight = false
                val preparedHtml = WebViewFrameRenderer.prepareHtmlForWebView(htmlDocument)
                wv.stopLoading()
                wv.loadDataWithBaseURL(WebViewFrameRenderer.ASSET_BASE_URL, preparedHtml, "text/html", "UTF-8", null)
            } else if (pageReady) {
                val ratioChanged = lastAspectRatioKey != aspectRatioKey
                val nowMs = SystemClock.uptimeMillis()
                if (!evalInFlight && (ratioChanged || nowMs - lastEvalAtMs >= 66L)) {
                    val ratioJs = if (ratioChanged) {
                        lastAspectRatioKey = aspectRatioKey
                        WebViewFrameRenderer.buildSetAspectRatioScript(aspectRatioKey) + " "
                    } else ""
                    val secStr = String.format(java.util.Locale.US, "%.4f", currentSeconds.coerceAtLeast(0.0))
                    evalInFlight = true
                    lastEvalAtMs = nowMs
                    wv.evaluateJavascript("${ratioJs}if (window.seekToTime) window.seekToTime($secStr);") {
                        evalInFlight = false
                    }
                }
            }
        }
    )
}

@Composable
fun studioTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ForgeCoral,
    unfocusedBorderColor = StudioOutline,
    focusedContainerColor = StudioSurfaceElevated,
    unfocusedContainerColor = StudioSurfaceElevated
)
