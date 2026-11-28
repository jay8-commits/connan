package com.example.llm

import com.example.model.ActorAction
import com.example.model.ActorExpression
import com.example.model.ActorHeadgear
import com.example.model.ActorRole
import com.example.model.CameraMotion
import com.example.model.CharacterAge
import com.example.model.CharacterGender
import com.example.model.CharacterOutfit
import com.example.model.CharacterPose
import com.example.model.CutawayStage
import com.example.model.CutawaySubject
import com.example.model.DynamicDiagramType
import com.example.model.EnvironmentTheme
import com.example.model.FlowDirection
import com.example.model.HandProp
import com.example.model.HudWidget
import com.example.model.LightingState
import com.example.model.MetaphorVisual
import com.example.model.PointerCallout
import com.example.model.SceneScript
import com.example.model.ShotFraming
import com.example.model.ShotType
import com.example.model.StatusPill
import com.example.model.StoryNiche
import com.example.model.StudioActorSpec
import com.example.model.StudioCameraSpec
import com.example.model.WorldFx
import org.json.JSONArray
import org.json.JSONObject

class MalformedSceneJsonException(message: String, cause: Throwable? = null) : Exception(message, cause)

object JsonSanitizer {

    private val THINK_BLOCK_REGEX = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
    private val UNCLOSED_THINK_REGEX = Regex("<think>[\\s\\S]*$", RegexOption.IGNORE_CASE)
    private val FENCED_CODE_BLOCK_REGEX = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    private val CODE_FENCE_START = Regex("```(?:json)?\\s*", RegexOption.IGNORE_CASE)
    private val CODE_FENCE_END = Regex("```")

    private val WRAPPER_ARRAY_KEYS = setOf(
        "scenes",
        "storyboard",
        "scenes_list",
        "scene_list",
        "script",
        "video_script",
        "items",
        "data",
        "results",
        "result",
        "output"
    )

    private val REQUIRED_KEYS = listOf(
        "scene_id",
        "tagalog_narration",
        "camera_motion",
        "actor_role",
        "actor_action",
        "world_fx"
    )

    private fun containsKeyFollowedByColon(text: String, quotedKey: String): Boolean {
        var idx = text.indexOf(quotedKey)
        while (idx >= 0) {
            var pos = idx + quotedKey.length
            while (pos < text.length && text[pos].isWhitespace()) pos++
            if (pos < text.length && text[pos] == ':') return true
            idx = text.indexOf(quotedKey, idx + quotedKey.length)
        }
        return false
    }

    private fun containsAnyJsonKeyColon(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            if (text[i] == '"') {
                val close = text.indexOf('"', i + 1)
                if (close > i + 1) {
                    var pos = close + 1
                    while (pos < text.length && text[pos].isWhitespace()) pos++
                    if (pos < text.length && text[pos] == ':') return true
                }
                i = if (close > i) close + 1 else i + 1
            } else {
                i++
            }
        }
        return false
    }

    private data class ArrayCandidate(
        val startIndex: Int,
        val isPreferredWrapper: Boolean,
        val slice: String
    )

    fun stripThinkAndMarkdownFences(rawOutput: String): List<String> {
        val withoutThink = rawOutput
            .replace(THINK_BLOCK_REGEX, "")
            .replace(UNCLOSED_THINK_REGEX, "")
            .trim()

        val fencedBlocks = FENCED_CODE_BLOCK_REGEX.findAll(withoutThink)
            .map { it.groupValues[1].trim() }
            .filter { it.contains('[') || it.contains('{') }
            .toList()

        val strippedFull = withoutThink
            .replace(CODE_FENCE_START, "")
            .replace(CODE_FENCE_END, "")
            .trim()

        return buildList {
            addAll(fencedBlocks)
            if (strippedFull.isNotEmpty() && strippedFull !in fencedBlocks) {
                add(strippedFull)
            }
        }
    }

    private data class UnwrappedRootArray(
        val array: JSONArray,
        val title: String = "",
        val niche: StoryNiche? = null
    )

    private fun tryUnwrapRootObjectArrayWithMeta(space: String): UnwrappedRootArray? {
        val trimmed = space.trim()
        val objectCandidates = buildList {
            if (trimmed.startsWith("{")) add(trimmed)
            val firstBrace = trimmed.indexOf('{')
            val lastBrace = trimmed.lastIndexOf('}')
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                val sliced = trimmed.substring(firstBrace, lastBrace + 1)
                if (sliced != trimmed) add(sliced)
            }
        }

        val orderedKeys = listOf("scenes", "storyboard") + WRAPPER_ARRAY_KEYS.filter { it != "scenes" && it != "storyboard" }
        for (objText in objectCandidates) {
            val root = try {
                JSONObject(objText)
            } catch (_: Exception) {
                continue
            }
            val rootTitle = root.optString("title", "").trim()
            val rootNiche = root.optString("niche", "").trim().takeIf { it.isNotEmpty() }?.let { StoryNiche.fromRaw(it) }
            for (key in orderedKeys) {
                val arr = root.optJSONArray(key) ?: continue
                if (isValidSceneJsonArray(arr, requireTagalogNarration = true)) {
                    return UnwrappedRootArray(array = arr, title = rootTitle, niche = rootNiche)
                }
            }
            for (key in orderedKeys) {
                val arr = root.optJSONArray(key) ?: continue
                if (isValidSceneJsonArray(arr, requireTagalogNarration = false)) {
                    return UnwrappedRootArray(array = arr, title = rootTitle, niche = rootNiche)
                }
            }
        }
        return null
    }

    private fun tryUnwrapRootObjectArray(space: String): JSONArray? =
        tryUnwrapRootObjectArrayWithMeta(space)?.array

    fun extractJsonArrayText(rawOutput: String): String {
        val spaces = stripThinkAndMarkdownFences(rawOutput)
        val candidates = collectAllArrayCandidates(spaces)
        if (candidates.isEmpty()) {
            throw MalformedSceneJsonException(
                "No JSON array brackets found. Input snippet: ${rawOutput.take(220)}"
            )
        }

        val best = candidates.firstOrNull { it.isPreferredWrapper && isNarrationObjectArrayText(it.slice) }
            ?: candidates.firstOrNull { isNarrationObjectArrayText(it.slice) }
            ?: candidates.firstOrNull { it.isPreferredWrapper && isSceneObjectArrayText(it.slice) }
            ?: candidates.firstOrNull { isSceneObjectArrayText(it.slice) }
            ?: candidates.firstOrNull { it.isPreferredWrapper && isObjectArrayText(it.slice) }
            ?: candidates.firstOrNull { isObjectArrayText(it.slice) }
            ?: throw MalformedSceneJsonException(
                "No JSON array of scene objects found. Input snippet: ${rawOutput.take(220)}"
            )

        return best.slice
    }

    private fun collectAllArrayCandidates(searchSpaces: List<String>): List<ArrayCandidate> {
        val seenSlices = LinkedHashSet<String>()
        val results = mutableListOf<ArrayCandidate>()
        for (space in searchSpaces) {
            scanArrayCandidatesInText(space).forEach { cand ->
                if (seenSlices.add(cand.slice)) {
                    results.add(cand)
                }
            }
        }
        return results
    }

    /**
     * Scans every `[` outside string literals in [text], extracts its matching/repaired `[ ... ]` slice,
     * and records whether it belongs to a wrapper key like `"scenes"` or `"storyboard"`.
     */
    private fun scanArrayCandidatesInText(text: String): List<ArrayCandidate> {
        val candidates = mutableListOf<ArrayCandidate>()
        var inString = false
        var escaped = false
        var stringStart = -1
        var lastStringValue: String? = null
        var sawColonAfterKey = false

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                    if (stringStart >= 0 && i > stringStart) {
                        lastStringValue = text.substring(stringStart + 1, i)
                        sawColonAfterKey = false
                    }
                }
                i++
                continue
            }

            when {
                ch == '"' -> {
                    inString = true
                    stringStart = i
                }
                ch == ':' -> {
                    if (lastStringValue != null) {
                        sawColonAfterKey = true
                    }
                }
                ch == '[' -> {
                    val isWrapper = sawColonAfterKey &&
                        lastStringValue != null &&
                        lastStringValue.trim().lowercase() in WRAPPER_ARRAY_KEYS
                    val extracted = tryExtractArraySlice(text, i)
                    if (extracted != null) {
                        candidates.add(
                            ArrayCandidate(
                                startIndex = i,
                                isPreferredWrapper = isWrapper,
                                slice = extracted
                            )
                        )
                    }
                    sawColonAfterKey = false
                    lastStringValue = null
                }
                !ch.isWhitespace() -> {
                    sawColonAfterKey = false
                    lastStringValue = null
                }
            }
            i++
        }
        return candidates
    }

    private fun isNarrationObjectArrayText(slice: String): Boolean {
        if (!isObjectArrayText(slice)) return false
        return slice.contains("\"tagalog_narration\"") ||
            containsKeyFollowedByColon(slice, "\"narration\"")
    }

    /**
     * Pure-Kotlin check confirming the `[ ... ]` slice begins with a JSON object `{ ... }`
     * (rejecting `[Scene 1]`, `["tag1", "tag2"]`, `[1, 2]`, `[]`) and contains scene keys.
     */
    private fun isSceneObjectArrayText(slice: String): Boolean {
        if (!isObjectArrayText(slice)) return false
        return containsKeyFollowedByColon(slice, "\"tagalog_narration\"") ||
            containsKeyFollowedByColon(slice, "\"narration\"") ||
            containsKeyFollowedByColon(slice, "\"scene_id\"") ||
            containsKeyFollowedByColon(slice, "\"id\"") ||
            containsKeyFollowedByColon(slice, "\"bgTheme\"")
    }

    private fun isObjectArrayText(slice: String): Boolean {
        val trimmed = slice.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return false
        val firstInner = nextNonWhitespaceChar(trimmed, 1) ?: return false
        if (firstInner != '{') return false
        return containsAnyJsonKeyColon(trimmed)
    }

    private fun nextNonWhitespaceChar(text: String, startIndex: Int): Char? {
        for (idx in startIndex until text.length) {
            val c = text[idx]
            if (!c.isWhitespace()) return c
        }
        return null
    }

    /**
     * Scans from the opening `[` while respecting JSON strings/escapes.
     * - If the outer `[...]` is cleanly closed (`bracketDepth == 0`), returns that exact slice.
     * - If truncated mid-stream before closing `]`, repairs at the last cleanly closed
     *   top-level scene object (`}` where `braceDepth == 0` and `bracketDepth == 1`).
     */
    private fun tryExtractArraySlice(text: String, firstBracket: Int): String? {
        var inString = false
        var escaped = false
        var braceDepth = 0
        var bracketDepth = 0
        var lastCompleteSceneObjectEnd = -1

        for (i in firstBracket until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> braceDepth++
                '}' -> {
                    if (braceDepth > 0) {
                        braceDepth--
                        if (braceDepth == 0 && bracketDepth == 1) {
                            lastCompleteSceneObjectEnd = i
                        }
                    }
                }
                '[' -> bracketDepth++
                ']' -> {
                    if (bracketDepth > 0) {
                        bracketDepth--
                        if (bracketDepth == 0) {
                            val slice = text.substring(firstBracket, i + 1)
                            return stripTrailingCommasBeforeArrayEnd(slice)
                        }
                    }
                }
            }
        }

        if (lastCompleteSceneObjectEnd > firstBracket) {
            val repairedPrefix = text.substring(firstBracket, lastCompleteSceneObjectEnd + 1)
                .trimEnd()
                .trimEnd(',')
                .trimEnd()
            return "$repairedPrefix]"
        }
        return null
    }

    private fun stripTrailingCommasBeforeArrayEnd(jsonArrayText: String): String {
        val trimmed = jsonArrayText.trimEnd()
        if (!trimmed.endsWith("]")) return jsonArrayText
        var pos = trimmed.length - 2
        while (pos >= 0 && trimmed[pos].isWhitespace()) pos--
        return if (pos >= 0 && trimmed[pos] == ',') {
            trimmed.substring(0, pos) + "]"
        } else {
            jsonArrayText
        }
    }

    fun parseAndValidateScenes(rawOutput: String): List<SceneScript> {
        val spaces = stripThinkAndMarkdownFences(rawOutput)

        // 1. Prefer direct root JSONObject parsing (`{"scenes": [...]}` / `{"storyboard": [...]}`)
        var selectedArray: JSONArray? = null
        var episodeTitle = ""
        var rootNiche: StoryNiche? = null
        for (space in spaces) {
            val unwrapped = tryUnwrapRootObjectArrayWithMeta(space)
            if (unwrapped != null) {
                selectedArray = unwrapped.array
                episodeTitle = unwrapped.title
                rootNiche = unwrapped.niche
                break
            }
        }

        // 2. Fallback: scan `[...]` blocks in text whose elements are JSONObjects containing narration keys
        var lastSyntaxError: Exception? = null
        if (selectedArray == null) {
            val candidates = collectAllArrayCandidates(spaces)
            val orderedSlices = buildList {
                candidates.filter { it.isPreferredWrapper && isNarrationObjectArrayText(it.slice) }
                    .forEach { add(it.slice) }
                candidates.filter { isNarrationObjectArrayText(it.slice) }
                    .forEach { if (it.slice !in this) add(it.slice) }
                candidates.filter { it.isPreferredWrapper && isSceneObjectArrayText(it.slice) }
                    .forEach { if (it.slice !in this) add(it.slice) }
                candidates.filter { isSceneObjectArrayText(it.slice) }
                    .forEach { if (it.slice !in this) add(it.slice) }
                candidates.filter { isObjectArrayText(it.slice) }
                    .forEach { if (it.slice !in this) add(it.slice) }
            }

            if (orderedSlices.isEmpty()) {
                throw MalformedSceneJsonException(
                    "No JSON array brackets found. Input snippet: ${rawOutput.take(220)}"
                )
            }

            for (slice in orderedSlices) {
                val arr = try {
                    JSONArray(slice)
                } catch (e: Exception) {
                    lastSyntaxError = e
                    continue
                }
                if (isValidSceneJsonArray(arr, requireTagalogNarration = true)) {
                    selectedArray = arr
                    break
                }
            }

            if (selectedArray == null) {
                for (slice in orderedSlices) {
                    val arr = try {
                        JSONArray(slice)
                    } catch (e: Exception) {
                        lastSyntaxError = e
                        continue
                    }
                    if (isValidSceneJsonArray(arr, requireTagalogNarration = false)) {
                        selectedArray = arr
                        break
                    }
                }
            }
        }

        val array = selectedArray ?: throw MalformedSceneJsonException(
            "No valid JSONArray of scene objects found (${lastSyntaxError?.message ?: "non-scene elements"}). Input snippet: ${rawOutput.take(220)}",
            lastSyntaxError
        )

        val scenes = mutableListOf<SceneScript>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
                ?: throw MalformedSceneJsonException("Element at index $i is not a JSONObject")
            scenes.add(parseSceneObject(obj, i, episodeTitle = episodeTitle, rootNiche = rootNiche))
        }
        return scenes
    }

    private fun isValidSceneJsonArray(
        array: JSONArray,
        requireTagalogNarration: Boolean = true
    ): Boolean {
        if (array.length() == 0) return false
        var hasMatchingKey = false
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: return false
            if (obj.has("tagalog_narration") || obj.has("narration")) {
                hasMatchingKey = true
            } else if (!requireTagalogNarration && (obj.has("scene_id") || obj.has("id"))) {
                hasMatchingKey = true
            }
        }
        return hasMatchingKey
    }

    private fun parseSceneObject(
        obj: JSONObject,
        index: Int,
        episodeTitle: String = "",
        rootNiche: StoryNiche? = null
    ): SceneScript {
        val isStudioChoreography = obj.has("narration") ||
            obj.has("bgTheme") ||
            obj.has("actors") ||
            (obj.has("camera") && obj.optJSONObject("camera") != null)

        if (!isStudioChoreography) {
            for (key in REQUIRED_KEYS) {
                if (!obj.has(key) || obj.isNull(key)) {
                    throw MalformedSceneJsonException("Scene[$index] missing required key '$key'")
                }
            }
        }

        val sceneId = when {
            obj.has("scene_id") && !obj.isNull("scene_id") -> obj.optInt("scene_id", index + 1)
            obj.has("id") && !obj.isNull("id") -> obj.optInt("id", index + 1)
            else -> index + 1
        }.coerceAtLeast(1)

        val rawNarrationText = obj.optString("tagalog_narration", "")
            .ifBlank { obj.optString("narration", "") }
        val narration = TagalogNarrationValidator.cleanPreservingAudioTags(rawNarrationText)
        if (narration.isEmpty()) {
            throw MalformedSceneJsonException("Scene[$index] has empty narration")
        }
        TagalogNarrationValidator.validateOrThrow(narration)

        // Studio Episode Choreography fields
        val durationSec = obj.optDouble("duration", 0.0)
            .let { if (it > 0.2 && !it.isNaN()) it else 0.0 }
        val bgTheme = obj.optString("bgTheme", "").trim()
        val camObj = obj.optJSONObject("camera")
        val cameraSpec = if (camObj != null) {
            StudioCameraSpec(
                preset = camObj.optString("preset", "push_in").trim().ifBlank { "push_in" },
                zoom = camObj.optDouble("zoom", 1.08).toFloat().coerceIn(0.85f, 1.6f)
            )
        } else {
            StudioCameraSpec()
        }
        val actorsList = parseActorsArray(obj.optJSONArray("actors"))
        val fxKey = obj.optString("fx", "").trim()
        val calloutText = obj.optString("callout", "").trim()

        val rawBgPrompt = obj.optString("background_prompt", "").trim()
        val rawVisualDesc = obj.optString("visual_description", "").trim()
        val studioSynthesizedVisual = if (isStudioChoreography && rawVisualDesc.isEmpty() && rawBgPrompt.isEmpty()) {
            synthesizeStudioVisualDescription(bgTheme, cameraSpec, actorsList, fxKey, calloutText)
        } else ""
        val bgPrompt = rawBgPrompt.ifBlank { rawVisualDesc.ifBlank { studioSynthesizedVisual } }
        val visualDesc = rawVisualDesc.ifBlank { rawBgPrompt.ifBlank { studioSynthesizedVisual } }

        val camRaw = obj.optString("camera_motion", "").trim().uppercase().ifBlank {
            mapStudioPresetToCameraMotion(cameraSpec.preset, index).name
        }
        val primaryActor = actorsList.firstOrNull()
        val roleRaw = obj.optString("actor_role", "").trim().uppercase().ifBlank {
            mapStudioRoleToActorRole(primaryActor?.role).name
        }
        val actionRaw = obj.optString("actor_action", "").trim().uppercase().ifBlank {
            mapStudioActionToActorAction(primaryActor?.action).name
        }
        val fxRaw = obj.optString("world_fx", "").trim().uppercase().ifBlank {
            mapStudioFxToWorldFx(fxKey).name
        }

        if (bgPrompt.isEmpty() && visualDesc.isEmpty()) {
            throw MalformedSceneJsonException("Scene[$index] missing visual_description/background_prompt")
        }

        // Backward-compatible optional HUD & kinetic motion-graphics fields
        val statusPill = StatusPill.fromRaw(obj.optString("status_pill", "NONE"))
        val calloutBanner = obj.optString("callout_banner", "").trim().ifBlank { calloutText }
        val hudWidget = HudWidget.fromRaw(obj.optString("hud_widget", "NONE"))
        val hudLabel = obj.optString("hud_label", "").trim()
        val hudUnit = obj.optString("hud_unit", "%").trim().ifBlank { "%" }
        val rawHudFrom = if (obj.has("hud_value_from")) obj.optInt("hud_value_from", 0) else obj.optInt("hud_from_value", 0)
        val rawHudTo = if (obj.has("hud_value_to")) obj.optInt("hud_value_to", 100) else obj.optInt("hud_to_value", 100)
        val hudFrom = rawHudFrom.coerceIn(0, 1_000_000)
        val hudTo = rawHudTo.coerceIn(0, 1_000_000)
        val hasCharacter: Boolean? = if (obj.has("has_character") && !obj.isNull("has_character")) {
            obj.optBoolean("has_character")
        } else {
            null
        }
        val lightingState = LightingState.fromRaw(obj.optString("lighting_state", "AUTO"))
        val sfxCues = buildList {
            val sfxArr = obj.optJSONArray("sfx")
            if (sfxArr != null) {
                for (k in 0 until sfxArr.length()) {
                    val item = sfxArr.optString(k, "").trim().uppercase()
                    if (item.isNotEmpty()) add(item)
                }
            } else {
                val sfxStr = obj.optString("sfx", "").trim()
                if (sfxStr.isNotEmpty()) {
                    sfxStr.split(',').forEach { part ->
                        val clean = part.trim().uppercase()
                        if (clean.isNotEmpty()) add(clean)
                    }
                }
            }
        }

        val checklistArray = obj.optJSONArray("checklist_items")
        val checklistItems = if (checklistArray != null) {
            buildList {
                for (k in 0 until minOf(4, checklistArray.length())) {
                    val item = checklistArray.optString(k, "").trim()
                    if (item.isNotEmpty()) add(item)
                }
            }
        } else {
            emptyList()
        }

        val shotFraming = ShotFraming.fromRaw(obj.optString("shot_framing", "AUTO"))
        val characterPose = CharacterPose.fromRaw(obj.optString("character_pose", "AUTO"))
        val handProp = HandProp.fromRaw(obj.optString("hand_prop", "NONE"))
        val shotType = ShotType.fromRaw(obj.optString("shot_type", "AUTO"))
        val secondaryRoleRaw = obj.optString("secondary_actor_role", "").trim()
        val secondaryActorRole = if (secondaryRoleRaw.isNotEmpty()) ActorRole.fromRaw(secondaryRoleRaw) else null
        val expression = ActorExpression.fromRaw(obj.optString("expression", "HAPPY_TALK"))
        val headgear = ActorHeadgear.fromRaw(obj.optString("headgear", "NONE"))
        val metaphorVisual = MetaphorVisual.fromRaw(obj.optString("metaphor_visual", "NONE"))
        val topicPillLabel = obj.optString("topic_pill", "").trim()
        val cutawayStage = CutawayStage.fromRaw(obj.optString("cutaway_stage", ""))
        val cutawaySubject = CutawaySubject.fromRaw(obj.optString("cutaway_subject", ""))
        val environmentTheme = EnvironmentTheme.fromRaw(obj.optString("environment_theme", ""))
        val characterGender = CharacterGender.fromRaw(obj.optString("character_gender", ""))
        val characterAge = CharacterAge.fromRaw(obj.optString("character_age", ""))
        val characterOutfit = CharacterOutfit.fromRaw(obj.optString("character_outfit", ""))
        val outfitColorHex = obj.optString("outfit_color_hex", "").trim()
        val hairColorHex = obj.optString("hair_color_hex", "").trim()
        val cutawayDiagramPrompt = obj.optString("cutaway_diagram_prompt", "").trim()
        val flowDirection = FlowDirection.fromRaw(obj.optString("flow_direction", "LEFT_TO_RIGHT"))
        val diagramType = DynamicDiagramType.fromRaw(obj.optString("diagram_type", "NONE"))
        val leftLabel = obj.optString("left_label", "").trim()
        val rightLabel = obj.optString("right_label", "").trim()
        val step1 = obj.optString("step_1", "").trim()
        val step2 = obj.optString("step_2", "").trim()
        val step3 = obj.optString("step_3", "").trim()

        val pointerCalloutsArray = obj.optJSONArray("pointer_callouts")
        val pointerCallouts = if (pointerCalloutsArray != null) {
            buildList {
                for (k in 0 until minOf(2, pointerCalloutsArray.length())) {
                    val pObj = pointerCalloutsArray.optJSONObject(k) ?: continue
                    val label = pObj.optString("label", "").trim().uppercase()
                    if (label.isNotEmpty()) {
                        val px = pObj.optDouble("x", 135.0).toFloat()
                        val py = pObj.optDouble("y", 170.0).toFloat()
                        add(PointerCallout(label = label, x = px, y = py))
                    }
                }
            }
        } else {
            emptyList()
        }

        val rawCustomSvg = obj.optString("custom_svg", "")
            .ifBlank { obj.optString("scene_svg", "") }
            .ifBlank { obj.optString("svg_markup", "") }
            .ifBlank { obj.optString("svg", "") }
        val sanitizedCustomSvg = sanitizeCustomSvg(rawCustomSvg)
        val speaker = obj.optString("speaker", "").trim()
        val resolvedHasCharacter = hasCharacter ?: if (isStudioChoreography) {
            actorsList.any { !it.role.equals("anatomy_cutaway", ignoreCase = true) }
        } else null
        val resolvedFraming = if (shotFraming == ShotFraming.AUTO && isStudioChoreography) {
            when (cameraSpec.preset.lowercase()) {
                "close_up" -> ShotFraming.CLOSE_UP_FACE
                "wide" -> ShotFraming.WIDE_FULL_BODY
                else -> ShotFraming.entries.filter { it != ShotFraming.AUTO }[index % 5]
            }
        } else shotFraming
        val resolvedPose = if (characterPose == CharacterPose.AUTO && isStudioChoreography) {
            when (primaryActor?.action?.lowercase()) {
                "walk", "run" -> CharacterPose.WALK_ACROSS_LEFT_TO_RIGHT
                "cry_shake", "kneel" -> CharacterPose.SIT_SLUMPED
                "wave", "hug" -> CharacterPose.POINT_AND_GESTURE
                "chop", "block" -> CharacterPose.SHOCK_AIRBORNE_RECOIL
                else -> CharacterPose.entries.filter { it != CharacterPose.AUTO }[index % 6]
            }
        } else characterPose

        return SceneScript(
            sceneId = sceneId,
            tagalogNarration = narration,
            backgroundPrompt = bgPrompt,
            cameraMotion = CameraMotion.fromRaw(camRaw),
            actorRole = ActorRole.fromRaw(roleRaw),
            actorAction = ActorAction.fromRaw(actionRaw),
            worldFx = WorldFx.fromRaw(fxRaw),
            statusPill = statusPill,
            calloutBanner = calloutBanner,
            hudWidget = hudWidget,
            hudLabel = hudLabel,
            hudFromValue = hudFrom,
            hudToValue = hudTo,
            checklistItems = checklistItems,
            shotFraming = resolvedFraming,
            characterPose = resolvedPose,
            handProp = handProp,
            shotType = shotType,
            secondaryActorRole = secondaryActorRole,
            expression = expression,
            headgear = headgear,
            metaphorVisual = metaphorVisual,
            topicPillLabel = topicPillLabel,
            environmentTheme = environmentTheme,
            cutawaySubject = cutawaySubject,
            characterGender = characterGender,
            characterAge = characterAge,
            characterOutfit = characterOutfit,
            outfitColorHex = outfitColorHex,
            hairColorHex = hairColorHex,
            cutawayStage = cutawayStage,
            cutawayDiagramPrompt = cutawayDiagramPrompt,
            flowDirection = flowDirection,
            diagramType = diagramType,
            leftLabel = leftLabel,
            rightLabel = rightLabel,
            step1 = step1,
            step2 = step2,
            step3 = step3,
            pointerCallouts = pointerCallouts,
            visualDescription = visualDesc,
            hasCharacter = resolvedHasCharacter,
            lightingState = lightingState,
            hudUnit = hudUnit,
            sfxCues = sfxCues,
            customSvg = sanitizedCustomSvg,
            speaker = speaker,
            durationSec = durationSec,
            bgTheme = bgTheme,
            cameraSpec = cameraSpec,
            actors = actorsList,
            fxKey = fxKey,
            episodeTitle = episodeTitle,
            storyNiche = rootNiche ?: StoryNiche.SA_BAYANG_ITO
        )
    }

    fun parseActorsArray(arr: JSONArray?): List<StudioActorSpec> {
        if (arr == null || arr.length() == 0) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val role = obj.optString("role", "hero").trim().ifBlank { "hero" }
                val x = obj.optDouble("x", 0.5).toFloat().coerceIn(0.08f, 0.92f)
                val y = obj.optDouble("y", 0.58).toFloat().coerceIn(0.20f, 0.85f)
                val scale = obj.optDouble("scale", 1.0).toFloat().coerceIn(0.4f, 2.2f)
                val targetScale = obj.optDouble("targetScale", scale.toDouble()).toFloat().coerceIn(0.4f, 2.4f)
                val facing = if (obj.optInt("facing", 1) < 0) -1 else 1
                val mood = obj.optString("mood", "determined").trim().ifBlank { "determined" }
                val action = obj.optString("action", "idle").trim().ifBlank { "idle" }
                add(
                    StudioActorSpec(
                        role = role,
                        x = x,
                        y = y,
                        scale = scale,
                        targetScale = targetScale,
                        facing = facing,
                        mood = mood,
                        action = action
                    )
                )
            }
        }
    }

    private fun synthesizeStudioVisualDescription(
        bgTheme: String,
        camera: StudioCameraSpec,
        actors: List<StudioActorSpec>,
        fxKey: String,
        callout: String
    ): String {
        val primary = actors.firstOrNull()
        val subjectPhrase = when (primary?.role?.lowercase()) {
            "rat" -> "Matalinong Daga character standing alert holding small prop"
            "cat" -> "expressive Si Pusa character gesturing with curious face"
            "anatomy_cutaway" -> "detailed human anatomy cross-section cutaway diagram highlighting neural nerve pathways"
            "lily" -> "warm animated village girl character smiling in soft pose"
            "mayor" -> "stern town mayor character standing with clenched hands"
            else -> "animated 2.5D character standing in dynamic pose on stage"
        }
        val moodPhrase = when (primary?.mood?.lowercase()) {
            "crying", "scared" -> "tearful worried expression"
            "shocked" -> "wide-eyed shocked expression"
            "laugh", "happy" -> "bright smiling expression"
            "squint" -> "calculating narrowed-eye expression"
            else -> "focused determined expression"
        }
        val envPhrase = when (bgTheme.lowercase()) {
            "town_storm" -> "stormy Philippine town street backdrop with dark thunderclouds"
            "town_sunset" -> "warm golden sunset village street with soft twilight sky"
            "school_hallway" -> "colorful sunlit school hallway corridor with lockers and floor reflections"
            "bedroom_night" -> "cozy midnight bedroom interior lit by warm bedside lamp"
            "dark_tunnel" -> "dramatic shadowy stone tunnel interior with rim light"
            "anatomy_lab" -> "clean dark science anatomy laboratory backdrop with glowing telemetry grid"
            else -> "bright colorful Philippine town plaza backdrop under crisp daylight sky"
        }
        val fxPhrase = if (fxKey.isNotBlank() && !fxKey.equals("none", ignoreCase = true)) {
            "cinematic rim lighting with subtle $fxKey atmospheric particles"
        } else {
            "soft atmospheric rim lighting and crisp ambient shadows"
        }
        val calloutHint = if (callout.isNotBlank()) "highlighting $callout" else "using ${camera.preset} camera"
        return "$subjectPhrase with $moodPhrase and ${primary?.action ?: "idle"} motion inside $envPhrase, $fxPhrase, $calloutHint."
    }

    private fun mapStudioPresetToCameraMotion(preset: String, index: Int): CameraMotion = when (preset.lowercase()) {
        "push_in", "close_up" -> CameraMotion.ZOOM_IN
        "wide" -> CameraMotion.ZOOM_OUT
        "pan_right" -> if (index % 2 == 0) CameraMotion.PAN_RIGHT else CameraMotion.PAN_LEFT
        "impact_shake" -> CameraMotion.IMPACT_SHAKE
        else -> listOf(CameraMotion.ZOOM_IN, CameraMotion.PAN_RIGHT, CameraMotion.ZOOM_OUT, CameraMotion.PAN_LEFT)[index % 4]
    }

    private fun mapStudioRoleToActorRole(role: String?): ActorRole = when (role?.lowercase()) {
        "hero", "lily" -> ActorRole.HERO
        "mayor", "mom", "dad" -> ActorRole.RICH_MAN
        "bully" -> ActorRole.POOR_MAN
        else -> ActorRole.NARRATOR
    }

    private fun mapStudioActionToActorAction(action: String?): ActorAction = when (action?.lowercase()) {
        "walk", "run" -> ActorAction.WALK_IN
        "wave", "hug" -> ActorAction.THUMBS_UP
        "cry_shake", "kneel" -> ActorAction.CRY_SHAKE
        "chop", "block" -> ActorAction.SURPRISED_JUMP
        else -> ActorAction.TALK_BOUNCE
    }

    private fun mapStudioFxToWorldFx(fx: String): WorldFx = when (fx.lowercase()) {
        "nerve_sparks", "shockwave_rings" -> WorldFx.FIRE_SPARKS
        "floating_hearts", "sunbeams" -> WorldFx.COIN_RAIN
        else -> WorldFx.NONE
    }

    private val SVG_DRAW_TAGS = listOf(
        "<path", "<rect", "<circle", "<ellipse", "<polygon", "<polyline", "<line", "<g", "<text"
    )
    private val UNSAFE_EVENT_ATTR_REGEX = Regex("\\son[a-z]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)", RegexOption.IGNORE_CASE)
    private val JAVASCRIPT_URI_REGEX = Regex("javascript\\s*:", RegexOption.IGNORE_CASE)

    fun sanitizeCustomSvg(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val cleaned = stripUnsafeTagBlock(stripUnsafeTagBlock(trimmed, "script"), "foreignObject")
            .replace(UNSAFE_EVENT_ATTR_REGEX, "")
            .replace(JAVASCRIPT_URI_REGEX, "blocked:")
            .trim()

        val lower = cleaned.lowercase()
        val hasSvgPrimitives = SVG_DRAW_TAGS.any { lower.contains(it) }
        if (!hasSvgPrimitives) return ""

        val svgStart = lower.indexOf("<svg")
        val svgEnd = lower.lastIndexOf("</svg>")
        if (svgStart >= 0 && svgEnd > svgStart) {
            return cleaned.substring(svgStart, svgEnd + "</svg>".length).trim()
        }

        // Bare SVG primitives without outer <svg> wrapper
        return "<svg viewBox=\"0 0 1080 1920\" xmlns=\"http://www.w3.org/2000/svg\">$cleaned</svg>"
    }

    private fun stripUnsafeTagBlock(input: String, tagName: String): String {
        val openNeedle = "<$tagName"
        val closeNeedle = "</$tagName>"
        var current = input
        while (true) {
            val lower = current.lowercase()
            val start = lower.indexOf(openNeedle.lowercase())
            if (start < 0) break
            val end = lower.indexOf(closeNeedle.lowercase(), start)
            current = if (end >= 0) {
                current.removeRange(start, end + closeNeedle.length)
            } else {
                val closeAngle = current.indexOf('>', start)
                if (closeAngle >= 0) current.removeRange(start, closeAngle + 1) else current.take(start)
            }
        }
        return current
    }
}
