package com.example.data

import com.example.model.PronunciationEntry

object BuiltInPrompts {

    val DEFAULT_PRONUNCIATION_DICTIONARY: List<PronunciationEntry> = listOf(
        PronunciationEntry("Nike", "Nayk"),
        PronunciationEntry("WiFi", "Way-Fay"),
        PronunciationEntry("₱", "piso"),
        PronunciationEntry("%", "porsyento"),
        PronunciationEntry("GCash", "ji-kash"),
        PronunciationEntry("OFW", "oh ef double-u"),
        PronunciationEntry("BPO", "bi pi oh"),
        PronunciationEntry("ATM", "ey ti em"),
        PronunciationEntry("ROI", "ar oh ay"),
        PronunciationEntry("YouTube", "yutyub"),
        PronunciationEntry("TikTok", "tik-tok")
    )

    data class VoicePreset(val id: String, val name: String, val tag: String)

    val VOICE_PRESETS: List<VoicePreset> = listOf(
        VoicePreset("N2IVS1w4EtoT3dr4eOWO", "Natural Creator (Default)", "Default"),
        VoicePreset("pNInz6obpgDQGcFmaJgB", "Adam (Deep Narrator)", "Storyteller"),
        VoicePreset("EXAVITQu4vr4xnSDxMaL", "Sarah (Warm & Clear)", "Explainer"),
        VoicePreset("TxGEqnHWrfWFTfGW9XjX", "Josh (Punchy Creator)", "Viral Reels"),
        VoicePreset("VR6AewLTigWG4xSOukaG", "Arnold (Dramatic)", "Horror / Suspense"),
        VoicePreset("21m00Tcm4TlvDq8ikWAM", "Rachel (Calm Host)", "Parable")
    )
}
