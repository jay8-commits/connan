package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.ElevenLabsKeyEntry
import com.example.model.PronunciationEntry
import com.example.model.StudioSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "toonforge_settings"
)

typealias SettingsRepository = SettingsDataStore

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val ELEVENLABS_KEYS_JSON = stringPreferencesKey("elevenlabs_keys_json")
        val SELECTED_VOICE_ID = stringPreferencesKey("selected_voice_id")
        val SECONDARY_VOICE_ID = stringPreferencesKey("secondary_voice_id")
        val DIRECT_SCRIPT_TEXT = stringPreferencesKey("direct_script_text")
        val CUSTOM_SPRITE_PATHS_JSON = stringPreferencesKey("custom_sprite_paths_json")
        val SPRITE_STICKER_OUTLINE = booleanPreferencesKey("sprite_sticker_outline")
        val CUSTOM_BGM_PATH = stringPreferencesKey("custom_bgm_path")
        val CUSTOM_BGM_NAME = stringPreferencesKey("custom_bgm_name")
        val PRONUNCIATION_DICT_JSON = stringPreferencesKey("pronunciation_dict_json")
        val TTS_BASE_URL = stringPreferencesKey("tts_base_url")
        val TTS_MODEL_ID = stringPreferencesKey("tts_model_id")
        val NARRATION_SPEED = floatPreferencesKey("narration_speed")
    }

    private fun cleanCredential(raw: String?): String =
        raw.orEmpty().replace("\r", "").replace("\n", "").trim()

    val settingsFlow: Flow<StudioSettings> = context.settingsDataStore.data.map { prefs ->
        StudioSettings(
            elevenLabsKeys = parseElevenLabsKeys(prefs[Keys.ELEVENLABS_KEYS_JSON]),
            selectedVoiceId = cleanCredential(prefs[Keys.SELECTED_VOICE_ID]).ifBlank { "N2IVS1w4EtoT3dr4eOWO" },
            secondaryVoiceId = cleanCredential(prefs[Keys.SECONDARY_VOICE_ID]),
            ttsBaseUrl = cleanCredential(prefs[Keys.TTS_BASE_URL]).ifBlank { "https://api.elevenlabs.io" },
            ttsModelId = cleanCredential(prefs[Keys.TTS_MODEL_ID]).ifBlank { "eleven_v4" },
            directScriptText = prefs[Keys.DIRECT_SCRIPT_TEXT] ?: "",
            customSpritePaths = parseSpritePaths(prefs[Keys.CUSTOM_SPRITE_PATHS_JSON]),
            spriteStickerOutline = prefs[Keys.SPRITE_STICKER_OUTLINE] ?: true,
            customBgmPath = prefs[Keys.CUSTOM_BGM_PATH] ?: "",
            customBgmName = prefs[Keys.CUSTOM_BGM_NAME] ?: "",
            pronunciationDictionary = parsePronunciationDict(prefs[Keys.PRONUNCIATION_DICT_JSON]),
            narrationSpeed = (prefs[Keys.NARRATION_SPEED] ?: 1.0f).coerceIn(0.85f, 1.15f)
        )
    }

    suspend fun getSnapshot(): StudioSettings = settingsFlow.first()

    suspend fun setSelectedVoiceId(voiceId: String) {
        val clean = cleanCredential(voiceId)
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SELECTED_VOICE_ID] = clean
        }
    }

    suspend fun setSecondaryVoiceId(voiceId: String) {
        val clean = cleanCredential(voiceId)
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SECONDARY_VOICE_ID] = clean
        }
    }

    suspend fun addElevenLabsKey(rawKey: String) {
        val clean = cleanCredential(rawKey)
        if (clean.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val list = parseElevenLabsKeys(prefs[Keys.ELEVENLABS_KEYS_JSON]).toMutableList()
            if (list.none { it.key == clean }) {
                list.add(ElevenLabsKeyEntry(key = clean))
                prefs[Keys.ELEVENLABS_KEYS_JSON] = serializeElevenLabsKeys(list)
            }
        }
    }

    suspend fun removeElevenLabsKey(key: String) {
        val clean = cleanCredential(key)
        context.settingsDataStore.edit { prefs ->
            val list = parseElevenLabsKeys(prefs[Keys.ELEVENLABS_KEYS_JSON])
                .filterNot { it.key == clean || it.key == key }
            prefs[Keys.ELEVENLABS_KEYS_JSON] = serializeElevenLabsKeys(list)
        }
    }

    suspend fun updateElevenLabsKeyEntry(updated: ElevenLabsKeyEntry) {
        val cleanUpdated = updated.copy(key = cleanCredential(updated.key))
        context.settingsDataStore.edit { prefs ->
            val list = parseElevenLabsKeys(prefs[Keys.ELEVENLABS_KEYS_JSON]).map {
                if (cleanCredential(it.key) == cleanUpdated.key) cleanUpdated else it
            }
            prefs[Keys.ELEVENLABS_KEYS_JSON] = serializeElevenLabsKeys(list)
        }
    }

    suspend fun saveTtsSettings(
        ttsBaseUrl: String = "https://api.elevenlabs.io",
        ttsModelId: String = "eleven_v4",
        selectedVoiceId: String? = null,
        secondaryVoiceId: String? = null,
        narrationSpeed: Float? = null
    ) {
        val cleanTtsBase = cleanCredential(ttsBaseUrl).ifBlank { "https://api.elevenlabs.io" }
        val cleanTtsModel = cleanCredential(ttsModelId).ifBlank { "eleven_v4" }
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.TTS_BASE_URL] = cleanTtsBase
            prefs[Keys.TTS_MODEL_ID] = cleanTtsModel
            if (selectedVoiceId != null) {
                prefs[Keys.SELECTED_VOICE_ID] = cleanCredential(selectedVoiceId).ifBlank { "N2IVS1w4EtoT3dr4eOWO" }
            }
            if (secondaryVoiceId != null) {
                prefs[Keys.SECONDARY_VOICE_ID] = cleanCredential(secondaryVoiceId)
            }
            if (narrationSpeed != null) {
                prefs[Keys.NARRATION_SPEED] = narrationSpeed.coerceIn(0.85f, 1.15f)
            }
        }
    }

    suspend fun setDirectScriptText(scriptText: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.DIRECT_SCRIPT_TEXT] = scriptText
        }
    }

    suspend fun setNarrationSpeed(speed: Float) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.NARRATION_SPEED] = speed.coerceIn(0.85f, 1.15f)
        }
    }

    suspend fun setTtsBaseUrl(ttsBaseUrl: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.TTS_BASE_URL] = cleanCredential(ttsBaseUrl).ifBlank { "https://api.elevenlabs.io" }
        }
    }

    suspend fun setTtsModelId(ttsModelId: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.TTS_MODEL_ID] = cleanCredential(ttsModelId).ifBlank { "eleven_v4" }
        }
    }

    suspend fun setCustomSpritePath(roleName: String, path: String?) {
        context.settingsDataStore.edit { prefs ->
            val map = parseSpritePaths(prefs[Keys.CUSTOM_SPRITE_PATHS_JSON]).toMutableMap()
            if (path.isNullOrBlank()) {
                map.remove(roleName)
            } else {
                map[roleName] = path
            }
            prefs[Keys.CUSTOM_SPRITE_PATHS_JSON] = serializeSpritePaths(map)
        }
    }

    suspend fun setSpriteStickerOutline(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SPRITE_STICKER_OUTLINE] = enabled
        }
    }

    suspend fun setCustomBgm(path: String, displayName: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.CUSTOM_BGM_PATH] = path
            prefs[Keys.CUSTOM_BGM_NAME] = displayName
        }
    }

    suspend fun upsertPronunciationEntry(original: String, spoken: String) {
        val cleanOrig = original.trim()
        val cleanSpoken = spoken.trim()
        if (cleanOrig.isEmpty() || cleanSpoken.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val list = parsePronunciationDict(prefs[Keys.PRONUNCIATION_DICT_JSON]).toMutableList()
            val idx = list.indexOfFirst { it.original.equals(cleanOrig, ignoreCase = true) }
            val entry = PronunciationEntry(cleanOrig, cleanSpoken)
            if (idx >= 0) {
                list[idx] = entry
            } else {
                list.add(0, entry)
            }
            prefs[Keys.PRONUNCIATION_DICT_JSON] = serializePronunciationDict(list)
        }
    }

    suspend fun deletePronunciationEntry(original: String) {
        context.settingsDataStore.edit { prefs ->
            val list = parsePronunciationDict(prefs[Keys.PRONUNCIATION_DICT_JSON])
                .filterNot { it.original.equals(original, ignoreCase = true) }
            prefs[Keys.PRONUNCIATION_DICT_JSON] = serializePronunciationDict(list)
        }
    }

    suspend fun resetPronunciationDictionary() {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.PRONUNCIATION_DICT_JSON] =
                serializePronunciationDict(BuiltInPrompts.DEFAULT_PRONUNCIATION_DICTIONARY)
        }
    }

    private fun parseElevenLabsKeys(json: String?): List<ElevenLabsKeyEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        ElevenLabsKeyEntry(
                            key = obj.getString("key"),
                            remainingChars = if (obj.has("remainingChars") && !obj.isNull("remainingChars")) {
                                obj.getInt("remainingChars")
                            } else null,
                            limitChars = if (obj.has("limitChars") && !obj.isNull("limitChars")) {
                                obj.getInt("limitChars")
                            } else null,
                            tier = obj.optString("tier", "").ifBlank { null },
                            lastError = obj.optString("lastError", "").ifBlank { null },
                            lastCheckedAtMs = obj.optLong("lastCheckedAtMs", 0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializeElevenLabsKeys(list: List<ElevenLabsKeyEntry>): String {
        val arr = JSONArray()
        list.forEach { entry ->
            arr.put(
                JSONObject().apply {
                    put("key", entry.key)
                    if (entry.remainingChars != null) put("remainingChars", entry.remainingChars)
                    if (entry.limitChars != null) put("limitChars", entry.limitChars)
                    if (entry.tier != null) put("tier", entry.tier)
                    if (entry.lastError != null) put("lastError", entry.lastError)
                    put("lastCheckedAtMs", entry.lastCheckedAtMs)
                }
            )
        }
        return arr.toString()
    }

    private fun parseSpritePaths(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap {
                obj.keys().forEach { key ->
                    val path = obj.optString(key, "")
                    if (path.isNotBlank()) put(key, path)
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun serializeSpritePaths(map: Map<String, String>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    private fun parsePronunciationDict(json: String?): List<PronunciationEntry> {
        if (json == null) return BuiltInPrompts.DEFAULT_PRONUNCIATION_DICTIONARY
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val orig = obj.optString("original", "").trim()
                    val spoken = obj.optString("spoken", "").trim()
                    if (orig.isNotEmpty() && spoken.isNotEmpty()) {
                        add(PronunciationEntry(orig, spoken))
                    }
                }
            }
        } catch (_: Exception) {
            BuiltInPrompts.DEFAULT_PRONUNCIATION_DICTIONARY
        }
    }

    private fun serializePronunciationDict(list: List<PronunciationEntry>): String {
        val arr = JSONArray()
        list.forEach { entry ->
            arr.put(
                JSONObject().apply {
                    put("original", entry.original)
                    put("spoken", entry.spoken)
                }
            )
        }
        return arr.toString()
    }
}
