package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.BuiltInPrompts
import com.example.tts.DetectedVoice
import com.example.tts.ElevenLabsClient
import com.example.ui.theme.ForgeAmber
import com.example.ui.theme.ForgeCoral
import com.example.ui.theme.ForgeCrimson
import com.example.ui.theme.ForgeCyan
import com.example.ui.theme.ForgeMint
import com.example.ui.theme.StudioOutline
import com.example.ui.theme.StudioOutlineSpecular
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.viewmodel.DetectionBannerState
import com.example.ui.viewmodel.MainViewModel
import java.util.Locale
import kotlin.math.roundToInt

private val EmeraldSuccess = Color(0xFF10B981)

@Composable
fun ApiSettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    SettingsScreen(viewModel = viewModel, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val previewCustomSprites by viewModel.previewCustomSprites.collectAsStateWithLifecycle()
    val isCheckingCredits by viewModel.isCheckingCredits.collectAsStateWithLifecycle()

    val detectedVoices by viewModel.detectedVoices.collectAsStateWithLifecycle()
    val isDetectingVoices by viewModel.isDetectingVoices.collectAsStateWithLifecycle()
    val voiceDetectionBanner by viewModel.voiceDetectionBanner.collectAsStateWithLifecycle()

    fun cleanInput(raw: String): String =
        raw.replace("\r", "").replace("\n", "").trim()

    var newElevenKey by remember { mutableStateOf("") }
    var showNewElevenKey by remember { mutableStateOf(false) }
    var ttsBaseUrl by remember { mutableStateOf(settings.effectiveTtsBaseUrl) }
    var ttsModelId by remember { mutableStateOf(settings.effectiveTtsModelId) }
    var ttsModelDropdownExpanded by remember { mutableStateOf(false) }
    var voiceIdInput by remember { mutableStateOf(settings.effectiveVoiceId) }
    var secondaryVoiceIdInput by remember { mutableStateOf(settings.effectiveSecondaryVoiceId) }
    var voiceDropdownExpanded by remember { mutableStateOf(false) }
    var narrationSpeed by remember { mutableFloatStateOf(settings.narrationSpeed) }

    LaunchedEffect(settings) {
        ttsBaseUrl = settings.effectiveTtsBaseUrl
        ttsModelId = settings.effectiveTtsModelId
        voiceIdInput = settings.effectiveVoiceId
        secondaryVoiceIdInput = settings.effectiveSecondaryVoiceId
        narrationSpeed = settings.narrationSpeed
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
            Column {
                Text(
                    text = "TWIS • API & Studio Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "ElevenLabs TTS proxy, dual voices, custom sprites, BGM, and pronunciation rules",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 1. ELEVENLABS / THIRD-PARTY TTS PROXY & VOICE AUTO-DETECTOR
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = BorderStroke(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(StudioOutlineSpecular.copy(alpha = 0.65f), StudioOutline.copy(alpha = 0.7f))
                    )
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(ForgeAmber.copy(alpha = 0.14f))
                                    .border(1.dp, ForgeAmber.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Key,
                                    contentDescription = null,
                                    tint = ForgeAmber,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            Text(
                                text = "ElevenLabs / TTS Proxy",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.checkAllElevenLabsCredits(ttsBaseUrl) },
                            enabled = !isCheckingCredits,
                            modifier = Modifier.testTag("check_credits_button")
                        ) {
                            if (isCheckingCredits) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Check Credits",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("Check Credits", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Text(
                        text = "Supports official ElevenLabs keys and third-party proxy endpoints with automatic voice listing & word-level timing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = ttsBaseUrl,
                        onValueChange = { raw ->
                            val clean = cleanInput(raw)
                            ttsBaseUrl = clean
                            viewModel.setTtsBaseUrl(clean)
                        },
                        label = { Text("TTS Base URL") },
                        placeholder = { Text("https://api.elevenlabs.io") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("tts_base_url_input"),
                        colors = studioTextFieldColors()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newElevenKey,
                            onValueChange = { newElevenKey = cleanInput(it) },
                            label = { Text("Add ElevenLabs xi-api-key") },
                            singleLine = true,
                            visualTransformation = if (showNewElevenKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(
                                    onClick = { showNewElevenKey = !showNewElevenKey },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = if (showNewElevenKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = if (showNewElevenKey) "Hide TTS key" else "Show TTS key"
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("elevenlabs_key_input"),
                            colors = studioTextFieldColors()
                        )
                        Button(
                            onClick = {
                                viewModel.addElevenLabsKey(newElevenKey, ttsBaseUrl)
                                newElevenKey = ""
                            },
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("add_elevenlabs_key_button")
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Add key")
                        }
                    }

                    // Detect Voices & Credits Button + Voice & TTS Model Inputs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.detectVoicesAndCredits(
                                    ttsBaseUrl = ttsBaseUrl,
                                    draftApiKey = newElevenKey
                                )
                            },
                            enabled = !isDetectingVoices,
                            border = BorderStroke(1.dp, ForgeAmber.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("detect_voices_button")
                        ) {
                            if (isDetectingVoices) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = ForgeAmber
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Detecting Voices & Credits...", color = ForgeAmber, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.GraphicEq,
                                    contentDescription = "Detect Voices & Credits",
                                    tint = ForgeAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Detect Voices & Credits", color = ForgeAmber, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    voiceDetectionBanner?.let { banner ->
                        DetectionStatusBanner(
                            state = banner,
                            modifier = Modifier.testTag("voice_detection_banner")
                        )
                    }

                    // Voice Picker Dropdown (combines detected voices + built-in presets)
                    val voiceOptions: List<DetectedVoice> = remember(detectedVoices) {
                        if (detectedVoices.isNotEmpty()) {
                            detectedVoices
                        } else {
                            BuiltInPrompts.VOICE_PRESETS.map {
                                DetectedVoice(voiceId = it.id, name = it.name, category = it.tag)
                            }
                        }
                    }
                    val activeVoiceDisplay = remember(settings.selectedVoiceId, voiceOptions) {
                        val match = voiceOptions.firstOrNull { it.voiceId == settings.selectedVoiceId }
                        if (match != null) {
                            "${match.name} (${match.category}) • ${match.voiceId}"
                        } else {
                            settings.selectedVoiceId
                        }
                    }

                    ExposedDropdownMenuBox(
                        expanded = voiceDropdownExpanded,
                        onExpandedChange = { voiceDropdownExpanded = !voiceDropdownExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = activeVoiceDisplay,
                            onValueChange = {},
                            readOnly = true,
                            label = {
                                Text(
                                    if (detectedVoices.isNotEmpty()) {
                                        "Voice Picker (${detectedVoices.size} detected)"
                                    } else {
                                        "Voice Picker (Built-in presets — tap Detect Voices)"
                                    }
                                )
                            },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceDropdownExpanded)
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .testTag("voice_picker_dropdown"),
                            colors = studioTextFieldColors()
                        )
                        ExposedDropdownMenu(
                            expanded = voiceDropdownExpanded,
                            onDismissRequest = { voiceDropdownExpanded = false }
                        ) {
                            voiceOptions.forEach { voice ->
                                val isSelected = voice.voiceId == settings.selectedVoiceId
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = voice.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isSelected) ForgeAmber else MaterialTheme.colorScheme.onSurface
                                                )
                                                Surface(
                                                    color = ForgeAmber.copy(alpha = 0.14f),
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        text = voice.category,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = ForgeAmber,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = voice.voiceId,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        voiceIdInput = voice.voiceId
                                        viewModel.selectVoiceId(voice.voiceId)
                                        voiceDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // ElevenLabs Model Selector (eleven_v4 Default / eleven_v4_turbo / eleven_multilingual_v2)
                    val activeModelPreset = remember(ttsModelId) {
                        ElevenLabsClient.SUPPORTED_TTS_MODEL_PRESETS.firstOrNull {
                            it.id.equals(ttsModelId.trim(), ignoreCase = true)
                        }
                    }
                    ExposedDropdownMenuBox(
                        expanded = ttsModelDropdownExpanded,
                        onExpandedChange = { ttsModelDropdownExpanded = !ttsModelDropdownExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = activeModelPreset?.let { "${it.id} (${it.subtitle})" } ?: ttsModelId,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("ElevenLabs Voice Model") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = ttsModelDropdownExpanded)
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .testTag("tts_model_picker_dropdown"),
                            colors = studioTextFieldColors()
                        )
                        ExposedDropdownMenu(
                            expanded = ttsModelDropdownExpanded,
                            onDismissRequest = { ttsModelDropdownExpanded = false }
                        ) {
                            ElevenLabsClient.SUPPORTED_TTS_MODEL_PRESETS.forEach { preset ->
                                val isSelected = ttsModelId.equals(preset.id, ignoreCase = true)
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = preset.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) ForgeAmber else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = preset.subtitle,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        ttsModelId = preset.id
                                        viewModel.setTtsModelId(preset.id)
                                        ttsModelDropdownExpanded = false
                                    },
                                    modifier = Modifier.testTag("tts_model_option_${preset.id}")
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ElevenLabsClient.SUPPORTED_TTS_MODEL_PRESETS.forEach { preset ->
                            val isSelected = ttsModelId.equals(preset.id, ignoreCase = true)
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    ttsModelId = preset.id
                                    viewModel.setTtsModelId(preset.id)
                                },
                                label = {
                                    Text(
                                        text = when (preset.id) {
                                            ElevenLabsClient.DEFAULT_TTS_MODEL_ID -> "eleven_v4 (Default)"
                                            ElevenLabsClient.TURBO_V4_TTS_MODEL_ID -> "eleven_v4_turbo"
                                            else -> "eleven_multilingual_v2"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ForgeAmber.copy(alpha = 0.20f),
                                    selectedLabelColor = ForgeAmber
                                ),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("tts_model_chip_${preset.id}")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = voiceIdInput,
                            onValueChange = { raw ->
                                val clean = cleanInput(raw)
                                voiceIdInput = clean
                                viewModel.selectVoiceId(clean)
                            },
                            label = { Text("Primary Voice ID (Narrator)") },
                            placeholder = { Text("N2IVS1w4EtoT3dr4eOWO") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_voice_id_input"),
                            colors = studioTextFieldColors()
                        )
                        OutlinedTextField(
                            value = ttsModelId,
                            onValueChange = { raw ->
                                val clean = cleanInput(raw)
                                ttsModelId = clean
                                viewModel.setTtsModelId(clean)
                            },
                            label = { Text("TTS Model ID") },
                            placeholder = { Text("eleven_v4") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("tts_model_id_input"),
                            colors = studioTextFieldColors()
                        )
                    }

                    OutlinedTextField(
                        value = secondaryVoiceIdInput,
                        onValueChange = { raw ->
                            val clean = cleanInput(raw)
                            secondaryVoiceIdInput = clean
                            viewModel.setSecondaryVoiceId(clean)
                        },
                        label = { Text("Secondary Voice ID (Character 2 — Optional)") },
                        placeholder = { Text("Optional Voice ID used when scene.speaker == \"cat\"") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_secondary_voice_id_input"),
                        colors = studioTextFieldColors()
                    )

                    if (settings.elevenLabsKeys.isEmpty()) {
                        Surface(
                            color = StudioSurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "No ElevenLabs keys added yet. Add a key above for voice synthesis with word-level alignment.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    } else {
                        settings.elevenLabsKeys.forEach { entry ->
                            Surface(
                                color = StudioSurfaceElevated,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, StudioOutline),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.maskedKey,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        val statusText = when {
                                            entry.lastError != null -> "Status: ${entry.lastError}"
                                            entry.remainingChars != null ->
                                                "${entry.remainingChars} / ${entry.limitChars ?: "?"} chars left (${entry.tier ?: "free"})"
                                            else -> "Tap Check Credits to verify quota"
                                        }
                                        Text(
                                            text = statusText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (entry.lastError != null) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                ForgeMint
                                            }
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.removeElevenLabsKey(entry.key) },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = "Remove Key",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Surface(
                        color = StudioSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, StudioOutline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val speedLabel = String.format(Locale.US, "%.2fx", narrationSpeed)
                            val pacingHint = when {
                                narrationSpeed < 0.95f -> "Dramatic Slow"
                                narrationSpeed > 1.05f -> "Viral Fast"
                                else -> "Natural Balanced"
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Narration Speed",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "$pacingHint • Passed to ElevenLabs voice_settings",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Surface(
                                    color = ForgeAmber.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .clickable {
                                            narrationSpeed = 1.0f
                                            viewModel.setNarrationSpeed(1.0f)
                                        }
                                        .minimumInteractiveComponentSize()
                                        .testTag("narration_speed_reset_badge")
                                ) {
                                    Text(
                                        text = speedLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = ForgeAmber,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Slider(
                                value = narrationSpeed,
                                onValueChange = { raw ->
                                    narrationSpeed = ((raw * 100f).roundToInt() / 100f).coerceIn(0.85f, 1.15f)
                                },
                                onValueChangeFinished = {
                                    viewModel.setNarrationSpeed(narrationSpeed)
                                },
                                valueRange = 0.85f..1.15f,
                                colors = SliderDefaults.colors(
                                    thumbColor = ForgeAmber,
                                    activeTrackColor = ForgeAmber,
                                    inactiveTrackColor = StudioOutline
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("narration_speed_slider")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "0.85x Dramatic",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "1.00x Default",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "1.15x Viral",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            viewModel.saveTtsSettings(
                                ttsBaseUrl = ttsBaseUrl,
                                ttsModelId = ttsModelId,
                                selectedVoiceId = voiceIdInput,
                                secondaryVoiceId = secondaryVoiceIdInput,
                                narrationSpeed = narrationSpeed
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("save_settings_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = ForgeCoral)
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = "Save Audio Settings")
                        Spacer(Modifier.width(8.dp))
                        Text("Save Audio & Voice Settings", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 2. CUSTOM PNG CHARACTER SPRITE MANAGER
            CharacterSpriteManagerCard(
                customSpritePaths = settings.customSpritePaths,
                previewSprites = previewCustomSprites,
                stickerOutlineEnabled = settings.spriteStickerOutline,
                onImportSprite = { role, uri -> viewModel.importCharacterSprite(role, uri) },
                onRemoveSprite = { role -> viewModel.removeCharacterSprite(role) },
                onToggleStickerOutline = { viewModel.setSpriteStickerOutline(it) }
            )

            // 3. CUSTOM BGM AUDIO STUDIO
            CustomBgmCard(
                customBgmName = settings.customBgmName,
                onImportBgm = { uri -> viewModel.importCustomBgm(uri) },
                onClearBgm = { viewModel.clearCustomBgm() }
            )

            // 4. EDITABLE PRONUNCIATION DICTIONARY
            PronunciationDictionaryCard(
                entries = settings.pronunciationDictionary,
                onUpsertEntry = { orig, spoken -> viewModel.upsertPronunciation(orig, spoken) },
                onDeleteEntry = { orig -> viewModel.deletePronunciation(orig) },
                onResetDefaults = { viewModel.resetPronunciationDictionary() }
            )

            Text(
                text = "TWIS • made by jay solis",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("settings_creator_footer")
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DetectionStatusBanner(
    state: DetectionBannerState,
    modifier: Modifier = Modifier
) {
    val accent = if (state.isSuccess) EmeraldSuccess else ForgeCrimson
    Surface(
        color = accent.copy(alpha = 0.14f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (state.isSuccess) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = if (state.isSuccess) "Success" else "Error",
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = state.message,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
