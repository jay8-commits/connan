package com.example.llm

import com.example.model.ActorAction
import com.example.model.ActorExpression
import com.example.model.ActorRole
import com.example.model.AnimationStyle
import com.example.model.CameraMotion
import com.example.model.CharacterPose
import com.example.model.EnvironmentTheme
import com.example.model.HandProp
import com.example.model.HudWidget
import com.example.model.LightingState
import com.example.model.SceneScript
import com.example.model.ShotFraming
import com.example.model.StatusPill
import com.example.model.WorldFx
import org.json.JSONArray
import org.json.JSONObject

data class DirectParseResult(
    val scenes: List<SceneScript>,
    val providerLabel: String,
    val isJsonFormat: Boolean,
    val customHtmlDocument: String? = null,
    val isCustomHtmlOrSvg: Boolean = false
)

/**
 * Dual-format local storyboard parser for Direct Script Mode:
 * - JSON (`{...}` / `[...]`) -> cleanly parsed via [JsonSanitizer] with rich defaults for omitted optional keys.
 * - Plain Tagalog text -> zero-JSON fallback that splits into balanced ~12-18 word scenes with
 *   varied camera motion, varied character poses, alternating shot framing, and style-aware visual prompts.
 */
object DirectStoryboardParser {

    val CAMERA_MOTION_ROTATION = listOf(
        CameraMotion.ZOOM_IN,
        CameraMotion.PAN_RIGHT,
        CameraMotion.ZOOM_OUT,
        CameraMotion.PAN_LEFT
    )

    val CHARACTER_POSE_ROTATION = listOf(
        CharacterPose.THREE_QUARTER_EXPLAIN,
        CharacterPose.POINT_AND_GESTURE,
        CharacterPose.WALK_ACROSS_LEFT_TO_RIGHT,
        CharacterPose.SHOCK_AIRBORNE_RECOIL
    )

    val SHOT_FRAMING_ROTATION = listOf(
        ShotFraming.MEDIUM_WAIST_UP,
        ShotFraming.RULE_OF_THIRDS_LEFT,
        ShotFraming.CLOSE_UP_FACE,
        ShotFraming.RULE_OF_THIRDS_RIGHT,
        ShotFraming.WIDE_FULL_BODY
    )

    private val ENVIRONMENT_ROTATION = listOf(
        EnvironmentTheme.SUNNY_MARKET_STREET,
        EnvironmentTheme.MODERN_OFFICE_DESK,
        EnvironmentTheme.COZY_BEDROOM_MIDNIGHT,
        EnvironmentTheme.BRIGHT_MORNING_KITCHEN
    )

    private val KNOWN_JSON_KEYS = listOf(
        "\"scenes\"",
        "\"storyboard\"",
        "\"scene_id\"",
        "\"tagalog_narration\"",
        "\"visual_description\"",
        "\"camera_motion\"",
        "\"character_pose\"",
        "\"shot_framing\""
    )
    private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?])\\s+")
    private val CLAUSE_SPLIT_REGEX = Regex("(?<=[,;:])\\s+")
    private val TAGALOG_CLAUSE_CONJUNCTION = Regex("^(?:pero|kaya|dahil|kapag|habang|kasi|tapos|ngunit|subalit|para|upang)$", RegexOption.IGNORE_CASE)

    val COPY_JSON_TEMPLATE = """
{
  "scenes": [
    {
      "scene_id": 1,
      "tagalog_narration": "Alam mo ba kung bakit parang laging kulang ang sahod mo kahit todo kayod ka araw-araw?",
      "visual_description": "Stylized colorful Manila street at golden sunrise with bright market stalls, warm sunlight glow, no text, no people, empty stage, centered composition",
      "camera_motion": "ZOOM_IN",
      "character_pose": "SHOCK_AIRBORNE_RECOIL",
      "shot_framing": "MEDIUM_WAIST_UP",
      "actor_role": "POOR_MAN",
      "actor_action": "SURPRISED_JUMP",
      "world_fx": "FIRE_SPARKS",
      "character_outfit": "HOODIE",
      "outfit_color_hex": "#3B82F6",
      "hand_prop": "PHONE_SCREEN",
      "status_pill": "PROBLEM",
      "callout_banner": "UBOS AGAD?",
      "environment_theme": "SUNNY_MARKET_STREET"
    },
    {
      "scene_id": 2,
      "tagalog_narration": "Hindi dahil maliit ang kita kundi dahil nauuna lagi ang gastos bago ka magtabi ng ipon.",
      "visual_description": "Clean bright modern home desk with warm golden lamp lighting, organized notebook and glass jar backdrop, no text, no people, empty stage, centered composition",
      "camera_motion": "PAN_RIGHT",
      "character_pose": "THREE_QUARTER_EXPLAIN",
      "shot_framing": "RULE_OF_THIRDS_LEFT",
      "actor_role": "HERO",
      "actor_action": "TALK_BOUNCE",
      "world_fx": "CHALKBOARD_DRAW",
      "character_outfit": "HOODIE",
      "outfit_color_hex": "#3B82F6",
      "hand_prop": "BOOK_NOTEBOOK",
      "status_pill": "FIX",
      "callout_banner": "PAY YOURSELF FIRST",
      "environment_theme": "MODERN_OFFICE_DESK"
    },
    {
      "scene_id": 3,
      "tagalog_narration": "Subukan mong itabi agad ang bente porsyento pagdating ng sweldo para gumaan ang buhay mo buwan-buwan.",
      "visual_description": "Vibrant glowing golden vault backdrop with warm emerald highlights and floating bokeh light depth, no text, no people, empty stage, centered composition",
      "camera_motion": "ZOOM_OUT",
      "character_pose": "POINT_AND_GESTURE",
      "shot_framing": "RULE_OF_THIRDS_RIGHT",
      "actor_role": "HERO",
      "actor_action": "POINT_STICK",
      "world_fx": "COIN_RAIN",
      "character_outfit": "CASUAL_TSHIRT",
      "outfit_color_hex": "#F59E0B",
      "hand_prop": "MONEY_BAG",
      "status_pill": "GOAL",
      "callout_banner": "20% IPON RULE",
      "environment_theme": "BANK_VAULT_FINANCE"
    }
  ]
}
    """.trimIndent()

    fun stripCodeFenceIfNeeded(rawInput: String): String {
        var s = rawInput.trim()
        if (s.startsWith("```")) {
            val firstNewline = s.indexOf('\n')
            s = if (firstNewline >= 0) s.substring(firstNewline + 1) else s.removePrefix("```")
            val lastFence = s.lastIndexOf("```")
            if (lastFence >= 0) {
                s = s.substring(0, lastFence)
            }
            s = s.trim()
        }
        return s
    }

    fun looksLikeHtmlOrSvgInput(rawInput: String): Boolean {
        val cleaned = stripCodeFenceIfNeeded(rawInput).trimStart()
        if (cleaned.isEmpty()) return false
        return cleaned.startsWith("<!DOCTYPE html", ignoreCase = true) ||
            cleaned.startsWith("<html", ignoreCase = true) ||
            cleaned.startsWith("<svg", ignoreCase = true)
    }

    fun buildCustomHtmlDocumentIfNeeded(rawInput: String): String {
        val cleaned = stripCodeFenceIfNeeded(rawInput).trim()
        if (cleaned.startsWith("<!DOCTYPE html", ignoreCase = true) ||
            cleaned.startsWith("<html", ignoreCase = true)
        ) {
            return cleaned
        }

        val sanitizedSvg = JsonSanitizer.sanitizeCustomSvg(cleaned).ifBlank { cleaned }
        val normalizedSvg = ensureFullBleedSvgAttributes(sanitizedSvg)

        return """
<!DOCTYPE html>
<html lang="tl">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=1080,height=1920,initial-scale=1.0,user-scalable=no" />
<style>
  html, body {
    margin: 0;
    padding: 0;
    width: 1080px;
    height: 1920px;
    overflow: hidden;
    background: #080B12;
  }
  body {
    display: flex;
    align-items: center;
    justify-content: center;
  }
  svg {
    width: 1080px;
    height: 1920px;
    display: block;
  }
</style>
</head>
<body>
$normalizedSvg
<script>
(function() {
  function initSvgDeterminism() {
    var rootSvg = document.querySelector('svg');
    if (rootSvg && typeof rootSvg.pauseAnimations === 'function') {
      try { rootSvg.pauseAnimations(); } catch (e) {}
    }
    window.seekToTime = function(seconds) {
      var t = Number(seconds) || 0;
      if (rootSvg && typeof rootSvg.setCurrentTime === 'function') {
        try { rootSvg.setCurrentTime(t); } catch (e) {}
      }
      var pulses = document.querySelectorAll('.flow-pulse');
      for (var i = 0; i < pulses.length; i++) {
        var dot = pulses[i];
        var pathSel = dot.getAttribute('data-path') || '';
        var targetPath = pathSel ? document.querySelector(pathSel) : null;
        if (targetPath && typeof targetPath.getTotalLength === 'function') {
          var len = targetPath.getTotalLength();
          if (len > 0) {
            var speed = Number(dot.getAttribute('data-speed') || '0.45');
            var offset = Number(dot.getAttribute('data-offset') || (i * 0.27));
            var phase = (t * speed + offset) % 1;
            if (phase < 0) phase += 1;
            var pt = targetPath.getPointAtLength(phase * len);
            dot.setAttribute('cx', pt.x.toFixed(1));
            dot.setAttribute('cy', pt.y.toFixed(1));
          }
        }
      }
    };
    window.seekToTime(0);
    window.__toonforgeReady = true;
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initSvgDeterminism);
  } else {
    initSvgDeterminism();
  }
})();
</script>
</body>
</html>
        """.trimIndent()
    }

    private fun ensureFullBleedSvgAttributes(svgMarkup: String): String {
        val start = svgMarkup.indexOf("<svg", ignoreCase = true)
        if (start < 0) return svgMarkup
        val closeAngle = svgMarkup.indexOf('>', start)
        if (closeAngle < 0) return svgMarkup
        val openTag = svgMarkup.substring(start, closeAngle)
        val hasViewBox = openTag.contains("viewBox", ignoreCase = true)
        val extra = if (hasViewBox) "" else " viewBox=\"0 0 1080 1920\""
        return svgMarkup.substring(0, closeAngle) + extra + svgMarkup.substring(closeAngle)
    }

    fun looksLikeJsonInput(rawInput: String): Boolean {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return false
        if (looksLikeHtmlOrSvgInput(trimmed)) return false
        if (!trimmed.contains('{') && !trimmed.contains('[')) return false

        val candidateSpaces = JsonSanitizer.stripThinkAndMarkdownFences(trimmed)
        for (space in candidateSpaces) {
            val s = space.trim()
            if (s.startsWith("{") && (s.contains(":") || s.contains("\""))) return true
            if (s.startsWith("[") && (s.contains("{") || hasJsonKeyColonPair(s))) return true
        }
        return KNOWN_JSON_KEYS.any { key -> hasKeyFollowedByColon(trimmed, key) }
    }

    private fun hasKeyFollowedByColon(text: String, quotedKey: String): Boolean {
        var idx = text.indexOf(quotedKey)
        while (idx >= 0) {
            var pos = idx + quotedKey.length
            while (pos < text.length && text[pos].isWhitespace()) pos++
            if (pos < text.length && text[pos] == ':') return true
            idx = text.indexOf(quotedKey, idx + quotedKey.length)
        }
        return false
    }

    private fun hasJsonKeyColonPair(text: String): Boolean {
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

    fun parse(
        rawInput: String,
        style: AnimationStyle,
        jsonSceneParser: (String) -> List<SceneScript> = JsonSanitizer::parseAndValidateScenes
    ): DirectParseResult {
        val trimmed = rawInput.trim()
        require(trimmed.isNotEmpty()) {
            "Enter or import a JSON, HTML/SVG storyboard, or Tagalog script first."
        }

        if (looksLikeHtmlOrSvgInput(trimmed)) {
            val cleanedMarkup = stripCodeFenceIfNeeded(trimmed).trim()
            val isRawSvg = cleanedMarkup.startsWith("<svg", ignoreCase = true)
            val customHtmlDoc = buildCustomHtmlDocumentIfNeeded(cleanedMarkup)
            val scenes = extractScenesFromCustomMarkup(cleanedMarkup, style, jsonSceneParser)
            return DirectParseResult(
                scenes = scenes,
                providerLabel = if (isRawSvg) "Direct Custom SVG" else "Direct Custom HTML",
                isJsonFormat = false,
                customHtmlDocument = customHtmlDoc,
                isCustomHtmlOrSvg = true
            )
        }

        if (looksLikeJsonInput(trimmed)) {
            val preparedJson = enrichMinimalJsonIfSupported(trimmed, style)
            val parsedScenes = jsonSceneParser(preparedJson)
            require(parsedScenes.isNotEmpty()) {
                "JSON storyboard did not produce any scenes."
            }
            val normalizedScenes = parsedScenes.mapIndexed { idx, scene ->
                val resolvedBg = scene.backgroundPrompt.ifBlank {
                    scene.visualDescription.ifBlank { buildStyleVisualDescription(scene.tagalogNarration, style, idx) }
                }
                val resolvedVis = scene.visualDescription.ifBlank { resolvedBg }
                scene.copy(
                    sceneId = if (scene.sceneId > 0) scene.sceneId else idx + 1,
                    backgroundPrompt = resolvedBg,
                    visualDescription = resolvedVis,
                    cameraMotion = if (scene.cameraMotion == CameraMotion.STATIC) {
                        CAMERA_MOTION_ROTATION[idx % CAMERA_MOTION_ROTATION.size]
                    } else {
                        scene.cameraMotion
                    },
                    characterPose = if (scene.characterPose == CharacterPose.AUTO) {
                        CHARACTER_POSE_ROTATION[idx % CHARACTER_POSE_ROTATION.size]
                    } else {
                        scene.characterPose
                    },
                    shotFraming = if (scene.shotFraming == ShotFraming.AUTO) {
                        SHOT_FRAMING_ROTATION[idx % SHOT_FRAMING_ROTATION.size]
                    } else {
                        scene.shotFraming
                    }
                )
            }
            return DirectParseResult(
                scenes = normalizedScenes,
                providerLabel = "Direct JSON Storyboard",
                isJsonFormat = true
            )
        }

        val plainScenes = parsePlainTagalogText(trimmed, style)
        return DirectParseResult(
            scenes = plainScenes,
            providerLabel = "Direct Tagalog Script",
            isJsonFormat = false
        )
    }

    private fun extractScenesFromCustomMarkup(
        cleanedMarkup: String,
        style: AnimationStyle,
        jsonSceneParser: (String) -> List<SceneScript>
    ): List<SceneScript> {
        val embeddedJson = extractEmbeddedStoryboardJson(cleanedMarkup)
        if (!embeddedJson.isNullOrBlank()) {
            val parsed = runCatching { jsonSceneParser(enrichMinimalJsonIfSupported(embeddedJson, style)) }
                .getOrNull()
                .orEmpty()
            if (parsed.isNotEmpty()) return parsed
        }

        val extractedNarration = extractNarrationFromMarkup(cleanedMarkup)
            .ifBlank { "Narito ang detalyadong animated visual explainer para mas madaling maunawaan ang kuwento." }

        val cleanChunks = splitIntoBalancedScenes(extractedNarration)
            .ifEmpty { listOf(TagalogNarrationValidator.cleanForTts(extractedNarration).ifBlank { extractedNarration }) }

        return cleanChunks.mapIndexed { idx, line ->
            SceneScript(
                sceneId = idx + 1,
                tagalogNarration = line,
                backgroundPrompt = "${style.displayName} custom HTML SVG visual storyboard",
                visualDescription = "Custom pasted HTML/SVG storyboard frame rendering full-bleed deterministic vector animation.",
                cameraMotion = CAMERA_MOTION_ROTATION[idx % CAMERA_MOTION_ROTATION.size],
                actorRole = ActorRole.NARRATOR,
                actorAction = ActorAction.TALK_BOUNCE,
                worldFx = WorldFx.NONE,
                hasCharacter = false
            )
        }
    }

    private fun extractEmbeddedStoryboardJson(markup: String): String? {
        val lower = markup.lowercase()
        val idMarker = "storyboard-data"
        val idIdx = lower.indexOf(idMarker)
        if (idIdx < 0) return null
        val gtIdx = markup.indexOf('>', idIdx)
        if (gtIdx < 0) return null
        val endScript = lower.indexOf("</script>", gtIdx)
        if (endScript <= gtIdx) return null
        return markup.substring(gtIdx + 1, endScript).trim()
    }

    private fun extractNarrationFromMarkup(markup: String): String {
        val metaMarker = "toonforge-narration"
        val lower = markup.lowercase()
        val metaIdx = lower.indexOf(metaMarker)
        if (metaIdx >= 0) {
            val contentAttr = extractAttributeValue(markup, metaIdx, "content")
            if (!contentAttr.isNullOrBlank()) return TagalogNarrationValidator.cleanForTts(contentAttr)
        }

        val dataNarrationIdx = lower.indexOf("data-narration")
        if (dataNarrationIdx >= 0) {
            val attrVal = extractAttributeValue(markup, dataNarrationIdx, "data-narration")
            if (!attrVal.isNullOrBlank()) return TagalogNarrationValidator.cleanForTts(attrVal)
        }

        val commentNeedle = "<!-- narration:"
        val commentIdx = lower.indexOf(commentNeedle)
        if (commentIdx >= 0) {
            val commentEnd = markup.indexOf("-->", commentIdx + commentNeedle.length)
            if (commentEnd > commentIdx) {
                val inside = markup.substring(commentIdx + commentNeedle.length, commentEnd).trim()
                if (inside.isNotBlank()) return TagalogNarrationValidator.cleanForTts(inside)
            }
        }
        return ""
    }

    private fun extractAttributeValue(markup: String, fromIndex: Int, attrName: String): String? {
        val lower = markup.lowercase()
        val attrPos = lower.indexOf(attrName.lowercase(), fromIndex)
        if (attrPos < 0) return null
        val eqPos = markup.indexOf('=', attrPos + attrName.length)
        if (eqPos < 0) return null
        var p = eqPos + 1
        while (p < markup.length && markup[p].isWhitespace()) p++
        if (p >= markup.length) return null
        val quote = markup[p]
        if (quote != '"' && quote != '\'') return null
        val close = markup.indexOf(quote, p + 1)
        if (close <= p) return null
        return markup.substring(p + 1, close).trim()
    }

    fun parsePlainTagalogText(
        rawText: String,
        style: AnimationStyle
    ): List<SceneScript> {
        val chunks = splitIntoBalancedScenes(rawText)
        require(chunks.isNotEmpty()) {
            "Tagalog script has no speakable lines. Paste at least one Tagalog sentence."
        }

        val lockedGender = com.example.model.CharacterGender.MALE
        val lockedAge = com.example.model.CharacterAge.YOUNG_ADULT
        val lockedOutfit = com.example.model.CharacterOutfit.HOODIE
        val lockedOutfitHex = "#2563EB"
        val lockedHairHex = "#1E293B"

        return chunks.mapIndexed { idx, rawChunk ->
            val cleanNarration = TagalogNarrationValidator.cleanForTts(rawChunk)
                .ifBlank { rawChunk.trim() }

            val cameraMotion = CAMERA_MOTION_ROTATION[idx % CAMERA_MOTION_ROTATION.size]
            val characterPose = CHARACTER_POSE_ROTATION[idx % CHARACTER_POSE_ROTATION.size]
            val shotFraming = SHOT_FRAMING_ROTATION[idx % SHOT_FRAMING_ROTATION.size]
            val actorAction = mapActionForPose(characterPose)
            val expression = mapExpressionForPose(characterPose)
            val contextCue = inferContextCue(cleanNarration, idx, style)
            val visualDesc = buildStyleVisualDescription(
                narration = cleanNarration,
                style = style,
                sceneIndex = idx,
                settingCue = contextCue.settingPhrase
            )

            SceneScript(
                sceneId = idx + 1,
                tagalogNarration = cleanNarration,
                backgroundPrompt = visualDesc,
                cameraMotion = cameraMotion,
                actorRole = contextCue.role,
                actorAction = actorAction,
                worldFx = contextCue.worldFx,
                statusPill = contextCue.statusPill,
                calloutBanner = contextCue.calloutBanner,
                hudWidget = contextCue.hudWidget,
                hudLabel = contextCue.hudLabel,
                hudFromValue = contextCue.hudFrom,
                hudToValue = contextCue.hudTo,
                shotFraming = shotFraming,
                characterPose = characterPose,
                handProp = contextCue.handProp,
                expression = expression,
                environmentTheme = contextCue.environmentTheme,
                characterGender = lockedGender,
                characterAge = lockedAge,
                characterOutfit = lockedOutfit,
                outfitColorHex = lockedOutfitHex,
                hairColorHex = lockedHairHex,
                visualDescription = visualDesc,
                hasCharacter = contextCue.hasCharacter,
                lightingState = contextCue.lightingState,
                hudUnit = contextCue.hudUnit
            )
        }
    }

    /**
     * Splits plain Tagalog paragraphs/sentences into balanced scenes targeting ~12–18 words per scene.
     */
    fun splitIntoBalancedScenes(
        rawText: String,
        minTargetWords: Int = 12,
        maxTargetWords: Int = 18
    ): List<String> {
        val normalizedLines = rawText
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .split('\n')
            .map { TagalogNarrationValidator.cleanForTts(it) }
            .filter { it.isNotBlank() }

        if (normalizedLines.isEmpty()) return emptyList()

        // 1. Break paragraphs into sentence-level units, then split long sentences cleanly.
        val rawUnits = mutableListOf<String>()
        for (line in normalizedLines) {
            val sentences = line.split(SENTENCE_SPLIT_REGEX)
                .map { it.trim() }
                .filter { it.isNotBlank() }
            for (sentence in sentences) {
                val wc = countWords(sentence)
                if (wc <= maxTargetWords) {
                    rawUnits.add(sentence)
                } else {
                    rawUnits.addAll(splitLongSentence(sentence, minTargetWords, maxTargetWords))
                }
            }
        }

        if (rawUnits.isEmpty()) return emptyList()

        // 2. Merge adjacent short sentences/clauses into balanced 12–18 word scenes.
        val merged = mutableListOf<String>()
        var current = ""
        var currentWords = 0

        for (unit in rawUnits) {
            val unitWords = countWords(unit)
            if (current.isEmpty()) {
                current = unit
                currentWords = unitWords
            } else if (currentWords < minTargetWords && currentWords + unitWords <= maxTargetWords + 2) {
                current = "$current $unit"
                currentWords += unitWords
            } else if (unitWords <= 4 && currentWords + unitWords <= maxTargetWords + 3) {
                current = "$current $unit"
                currentWords += unitWords
            } else {
                merged.add(current)
                current = unit
                currentWords = unitWords
            }
        }
        if (current.isNotEmpty()) {
            // Avoid leaving a tiny orphan tail (<= 5 words) when it fits comfortably in the previous scene.
            if (merged.isNotEmpty() && currentWords <= 5 && countWords(merged.last()) + currentWords <= maxTargetWords + 4) {
                merged[merged.lastIndex] = "${merged.last()} $current"
            } else {
                merged.add(current)
            }
        }

        return merged
    }

    private fun splitLongSentence(
        sentence: String,
        minTargetWords: Int,
        maxTargetWords: Int
    ): List<String> {
        val clauses = sentence.split(CLAUSE_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val candidateSlices = mutableListOf<String>()
        if (clauses.size > 1) {
            var buffer = ""
            var bufferWords = 0
            for (clause in clauses) {
                val cw = countWords(clause)
                if (buffer.isEmpty()) {
                    buffer = clause
                    bufferWords = cw
                } else if (bufferWords < minTargetWords && bufferWords + cw <= maxTargetWords) {
                    buffer = "$buffer $clause"
                    bufferWords += cw
                } else {
                    candidateSlices.add(buffer)
                    buffer = clause
                    bufferWords = cw
                }
            }
            if (buffer.isNotEmpty()) candidateSlices.add(buffer)
        } else {
            candidateSlices.add(sentence)
        }

        val finalChunks = mutableListOf<String>()
        for (slice in candidateSlices) {
            val words = slice.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size <= maxTargetWords) {
                finalChunks.add(slice)
            } else {
                finalChunks.addAll(splitWordListEvenly(words, minTargetWords, maxTargetWords))
            }
        }
        return finalChunks
    }

    private fun splitWordListEvenly(
        words: List<String>,
        minTargetWords: Int,
        maxTargetWords: Int
    ): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        val idealSize = (minTargetWords + maxTargetWords) / 2 // 15 words

        while (start < words.size) {
            val remaining = words.size - start
            if (remaining <= maxTargetWords) {
                result.add(words.subList(start, words.size).joinToString(" "))
                break
            }
            // Choose a natural split point near idealSize, preferring Tagalog conjunctions.
            val minEnd = (start + minTargetWords).coerceAtMost(words.size)
            val maxEnd = (start + maxTargetWords).coerceAtMost(words.size)
            var chosenEnd = (start + idealSize).coerceIn(minEnd, maxEnd)

            // Prevent an awkward < 6-word tail if one more chunk remains
            if (words.size - chosenEnd in 1 until 6 && words.size - start <= maxTargetWords * 2) {
                chosenEnd = start + (remaining / 2)
            } else {
                for (k in minEnd until maxEnd) {
                    val cleanTok = words[k].trim(',', ';', ':', '.', '!', '?')
                    if (TAGALOG_CLAUSE_CONJUNCTION.matches(cleanTok)) {
                        chosenEnd = k
                        break
                    }
                }
            }
            result.add(words.subList(start, chosenEnd).joinToString(" "))
            start = chosenEnd
        }
        return result
    }

    private fun countWords(text: String): Int =
        text.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    fun deriveTopicTitle(rawInput: String): String {
        if (looksLikeHtmlOrSvgInput(rawInput)) {
            val narration = extractNarrationFromMarkup(stripCodeFenceIfNeeded(rawInput))
            if (narration.isNotBlank()) {
                return narration.split(Regex("\\s+")).filter { it.isNotBlank() }.take(6).joinToString(" ")
            }
            val isRawSvg = stripCodeFenceIfNeeded(rawInput).trimStart().startsWith("<svg", ignoreCase = true)
            return if (isRawSvg) "Custom SVG Storyboard" else "Custom HTML Storyboard"
        }
        val cleaned = if (looksLikeJsonInput(rawInput)) {
            extractFirstJsonStringValue(rawInput, "tagalog_narration").orEmpty()
        } else {
            rawInput.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        }
        val words = TagalogNarrationValidator.cleanForTts(cleaned)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(6)
        return words.joinToString(" ").ifBlank { "Direct Tagalog Story" }
    }

    fun estimatePreviewSummary(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return "Empty"
        if (looksLikeHtmlOrSvgInput(trimmed)) {
            val isRawSvg = stripCodeFenceIfNeeded(trimmed).trimStart().startsWith("<svg", ignoreCase = true)
            return if (isRawSvg) "Custom SVG • Ready" else "Custom HTML • Ready"
        }
        if (looksLikeJsonInput(trimmed)) {
            val count = countKeyOccurrences(trimmed, "\"tagalog_narration\"")
            return if (count > 0) "JSON • $count scene(s)" else "JSON Storyboard"
        }
        val chunks = splitIntoBalancedScenes(trimmed)
        val words = countWords(trimmed)
        return "Tagalog • ${chunks.size} scene(s) • $words words"
    }

    private fun extractFirstJsonStringValue(raw: String, keyName: String): String? {
        val quotedKey = "\"$keyName\""
        var idx = raw.indexOf(quotedKey)
        while (idx >= 0) {
            var pos = idx + quotedKey.length
            while (pos < raw.length && raw[pos].isWhitespace()) pos++
            if (pos < raw.length && raw[pos] == ':') {
                pos++
                while (pos < raw.length && raw[pos].isWhitespace()) pos++
                if (pos < raw.length && raw[pos] == '"') {
                    val start = pos + 1
                    var end = start
                    var escaped = false
                    while (end < raw.length) {
                        val c = raw[end]
                        if (escaped) {
                            escaped = false
                        } else if (c == '\\') {
                            escaped = true
                        } else if (c == '"') {
                            return raw.substring(start, end)
                        }
                        end++
                    }
                }
            }
            idx = raw.indexOf(quotedKey, idx + quotedKey.length)
        }
        return null
    }

    private fun countKeyOccurrences(raw: String, quotedKey: String): Int {
        var count = 0
        var idx = raw.indexOf(quotedKey)
        while (idx >= 0) {
            var pos = idx + quotedKey.length
            while (pos < raw.length && raw[pos].isWhitespace()) pos++
            if (pos < raw.length && raw[pos] == ':') {
                count++
            }
            idx = raw.indexOf(quotedKey, idx + quotedKey.length)
        }
        return count
    }

    private fun buildStyleVisualDescription(
        narration: String,
        style: AnimationStyle,
        sceneIndex: Int,
        settingCue: String = "bright colorful story environment with layered atmospheric depth"
    ): String {
        val styleBase = style.backdropPromptSuffix.ifBlank {
            "clean textured ${style.displayName} illustration backdrop, crisp studio contrast, no text, no people, empty stage, centered composition"
        }
        val framingFlavor = when (sceneIndex % 4) {
            0 -> "warm inviting key lighting, subtle golden highlights"
            1 -> "crisp vibrant daylight perspective, balanced parallax background"
            2 -> "dynamic depth-of-field studio stage, clear focal center"
            else -> "energetic rim lighting, clean visual storytelling backdrop"
        }
        val lower = narration.lowercase()
        val extraPropCue = when {
            listOf("pera", "sahod", "sweldo", "ipon", "gastos", "utang", "piso").any { lower.contains(it) } ->
                "subtle floating coins and clean finance motif accents"
            listOf("tinda", "negosyo", "palengke", "benta", "kape").any { lower.contains(it) } ->
                "cozy Philippine neighborhood store and sunlit street atmosphere"
            listOf("trabaho", "opisina", "computer", "phone", "online").any { lower.contains(it) } ->
                "clean contemporary workspace with ambient screen glow"
            else -> "expressive Philippine everyday setting"
        }
        return "${style.displayName} scene backdrop featuring $settingCue, $extraPropCue, $framingFlavor, $styleBase"
    }

    private data class SceneContextCue(
        val role: ActorRole,
        val worldFx: WorldFx,
        val statusPill: StatusPill,
        val calloutBanner: String,
        val handProp: HandProp,
        val environmentTheme: EnvironmentTheme,
        val settingPhrase: String,
        val hasCharacter: Boolean,
        val lightingState: LightingState,
        val hudWidget: HudWidget,
        val hudLabel: String,
        val hudFrom: Int,
        val hudTo: Int,
        val hudUnit: String
    )

    private val EXPLICIT_CHARACTER_TOKENS = setOf(
        "lola", "lolo", "nanay", "tatay", "tindera", "tindero", "bata", "lalaki", "babae",
        "kaibigan", "kuya", "ate", "boss", "doktor", "nurse", "teacher", "guro",
        "driver", "farmer", "magsasaka", "manok", "aso", "pusa", "isda", "character",
        "tao", "tauhan"
    )

    private fun containsWholeWordToken(text: String, tokens: Set<String>): Boolean {
        val sb = StringBuilder()
        for (i in 0..text.length) {
            val ch = if (i < text.length) text[i] else ' '
            if (ch.isLetter()) {
                sb.append(ch.lowercaseChar())
            } else if (sb.isNotEmpty()) {
                if (sb.toString() in tokens) return true
                sb.setLength(0)
            }
        }
        return false
    }

    private fun inferContextCue(
        narration: String,
        index: Int,
        style: AnimationStyle = AnimationStyle.ATMOSPHERIC_2D_STORY
    ): SceneContextCue {
        val lower = narration.lowercase()
        val isFinance = listOf("pera", "sahod", "sweldo", "ipon", "piso", "yaman", "negosyo", "presyo").any { lower.contains(it) }
        val isProblem = listOf("ubos", "kulang", "utang", "hirap", "mali", "sayang", "gastos", "problema", "bakit").any { lower.contains(it) }
        val isSolution = listOf("sikreto", "diskarte", "subukan", "itabi", "solusyon", "gawin", "simulan", "tips").any { lower.contains(it) }
        val isOffice = listOf("trabaho", "opisina", "bos", "laptop", "online", "bpo", "meeting").any { lower.contains(it) }
        val isKitchen = listOf("kape", "umaga", "almusal", "lutuin", "kusina", "kain").any { lower.contains(it) }
        val isMystery = listOf("gabi", "dilim", "takot", "sikreto", "multo", "nakakagulat", "madaling araw").any { lower.contains(it) }
        val isStorm = listOf("ulan", "bagyo", "kulog", "baha", "storm", "rain").any { lower.contains(it) }
        val isEnergyOrSystem = listOf("solar", "kuryente", "battery", "watts", "tubig", "tangke", "pipe", "aquaponics", "filter", "pump", "sensor").any { lower.contains(it) }

        val theme = when {
            isMystery -> EnvironmentTheme.DRAMATIC_SUNSET_CITY
            isFinance && isSolution -> EnvironmentTheme.BANK_VAULT_FINANCE
            isOffice -> EnvironmentTheme.MODERN_OFFICE_DESK
            isKitchen -> EnvironmentTheme.BRIGHT_MORNING_KITCHEN
            isEnergyOrSystem -> EnvironmentTheme.SCIENCE_LAB_INTERIOR
            else -> ENVIRONMENT_ROTATION[index % ENVIRONMENT_ROTATION.size]
        }

        val role = when {
            isSolution && isFinance -> ActorRole.RICH_MAN
            isProblem -> ActorRole.POOR_MAN
            index % 3 == 1 -> ActorRole.NARRATOR
            else -> ActorRole.HERO
        }

        val worldFx = when {
            isFinance && isSolution -> WorldFx.COIN_RAIN
            isProblem && index == 0 -> WorldFx.FIRE_SPARKS
            isSolution -> WorldFx.CHALKBOARD_DRAW
            else -> WorldFx.NONE
        }

        val statusPill = when {
            index == 0 && isProblem -> StatusPill.PROBLEM
            isProblem -> StatusPill.PROBLEM
            isSolution -> StatusPill.FIX
            isFinance -> StatusPill.GOAL
            isMystery -> StatusPill.SECRET
            else -> StatusPill.NONE
        }

        val handProp = when {
            isFinance && isSolution -> HandProp.MONEY_BAG
            isFinance -> HandProp.MONEY_BAG
            isOffice -> HandProp.PHONE_SCREEN
            isKitchen -> HandProp.COFFEE_CUP
            isSolution -> HandProp.BOOK_NOTEBOOK
            else -> HandProp.POINTER_STICK
        }

        val callout = when {
            statusPill == StatusPill.PROBLEM && index == 0 -> "ALAM MO BA?"
            statusPill == StatusPill.FIX -> "DISKARTE TIP"
            statusPill == StatusPill.GOAL -> "IPON GOAL"
            else -> ""
        }

        val lightingState = when {
            isStorm -> LightingState.RAIN_STORM
            isMystery -> LightingState.NIGHT
            isKitchen || index % 3 == 0 -> LightingState.SUNNY
            else -> LightingState.AUTO
        }

        val hasCharacter = style != AnimationStyle.CROSS_SECTION &&
            style != AnimationStyle.KINETIC_TYPOGRAPHY_DIAGRAM &&
            containsWholeWordToken(narration, EXPLICIT_CHARACTER_TOKENS)

        val (hudWidget, hudLabel, hudFrom, hudTo, hudUnit) = when {
            lower.contains("watt") || lower.contains("solar") || lower.contains("kuryente") ->
                Tuple5(HudWidget.PROGRESS_GAUGE, "SOLAR OUTPUT", 120, 840, "W")
            lower.contains("volt") || lower.contains("battery") ->
                Tuple5(HudWidget.PROGRESS_GAUGE, "CELL VOLTAGE", 11, 14, "V")
            lower.contains("ppm") || lower.contains("filter") || lower.contains("tubig") || lower.contains("aquaponics") ->
                Tuple5(HudWidget.PROGRESS_GAUGE, "WATER PURITY", 420, 48, "PPM")
            isFinance ->
                Tuple5(
                    if (style == AnimationStyle.CROSS_SECTION) HudWidget.PROGRESS_GAUGE else HudWidget.NONE,
                    "IPON SAVINGS",
                    if (isProblem) 85 else 20,
                    if (isProblem) 12 else 92,
                    "%"
                )
            style == AnimationStyle.CROSS_SECTION ->
                Tuple5(HudWidget.PROGRESS_GAUGE, "SYSTEM FLOW", 22, 94, "%")
            else ->
                Tuple5(HudWidget.NONE, "", 0, 100, "%")
        }

        val settingPhrase = when (theme) {
            EnvironmentTheme.BANK_VAULT_FINANCE -> "polished golden finance vault and clean savings diorama"
            EnvironmentTheme.MODERN_OFFICE_DESK -> "modern sunlit Filipino workspace desk"
            EnvironmentTheme.BRIGHT_MORNING_KITCHEN -> "warm morning kitchen table with sunlit windows"
            EnvironmentTheme.DRAMATIC_SUNSET_CITY -> "moody twilight urban alley with dramatic streetlamps"
            EnvironmentTheme.COZY_BEDROOM_MIDNIGHT -> "cozy evening bedroom illuminated by warm lamp glow"
            EnvironmentTheme.SUNNY_MARKET_STREET -> "lively sunny Philippine neighborhood street"
            EnvironmentTheme.SCIENCE_LAB_INTERIOR -> "detailed split-level cutaway system cross-section"
            else -> "vibrant animated stage backdrop"
        }

        return SceneContextCue(
            role = role,
            worldFx = worldFx,
            statusPill = statusPill,
            calloutBanner = callout,
            handProp = handProp,
            environmentTheme = theme,
            settingPhrase = settingPhrase,
            hasCharacter = hasCharacter,
            lightingState = lightingState,
            hudWidget = hudWidget,
            hudLabel = hudLabel,
            hudFrom = hudFrom,
            hudTo = hudTo,
            hudUnit = hudUnit
        )
    }

    private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

    private fun mapActionForPose(pose: CharacterPose): ActorAction = when (pose) {
        CharacterPose.THREE_QUARTER_EXPLAIN -> ActorAction.TALK_BOUNCE
        CharacterPose.POINT_AND_GESTURE -> ActorAction.POINT_STICK
        CharacterPose.WALK_ACROSS_LEFT_TO_RIGHT -> ActorAction.WALK_IN
        CharacterPose.SHOCK_AIRBORNE_RECOIL -> ActorAction.SURPRISED_JUMP
        CharacterPose.CELEBRATE_JUMP -> ActorAction.THUMBS_UP
        CharacterPose.SIT_SLUMPED -> ActorAction.CRY_SHAKE
        else -> ActorAction.TALK_BOUNCE
    }

    private fun mapExpressionForPose(pose: CharacterPose): ActorExpression = when (pose) {
        CharacterPose.SHOCK_AIRBORNE_RECOIL -> ActorExpression.SHOCKED_WIDE_EYES
        CharacterPose.POINT_AND_GESTURE -> ActorExpression.HAPPY_TALK
        CharacterPose.SIT_SLUMPED -> ActorExpression.PANIC_SWEAT_FIRE
        CharacterPose.CELEBRATE_JUMP -> ActorExpression.HAPPY_TALK
        else -> ActorExpression.HAPPY_TALK
    }

    /**
     * Enriches user-pasted JSON storyboards that omit optional director keys (`actor_role`, `world_fx`,
     * `camera_motion`, etc.) before passing them to [JsonSanitizer.parseAndValidateScenes].
     * Uses `org.json` on Android runtime; safely returns [raw] untouched on plain JVM unit tests.
     */
    private fun enrichMinimalJsonIfSupported(raw: String, style: AnimationStyle): String {
        return try {
            val spaces = JsonSanitizer.stripThinkAndMarkdownFences(raw)
            val candidate = spaces.firstOrNull { it.trim().startsWith("{") || it.trim().startsWith("[") } ?: raw
            val trimmed = candidate.trim()

            val array: JSONArray = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val root = JSONObject(trimmed)
                val existingArr = root.optJSONArray("scenes")
                    ?: root.optJSONArray("storyboard")
                    ?: root.optJSONArray("script")
                    ?: root.optJSONArray("items")
                    ?: root.optJSONArray("data")
                if (existingArr != null) {
                    existingArr
                } else if (root.has("tagalog_narration")) {
                    JSONArray().apply { put(root) }
                } else {
                    return raw
                }
            }

            if (array.length() == 0) return raw

            val enriched = JSONArray()
            for (i in 0 until array.length()) {
                val src = array.optJSONObject(i) ?: continue
                val copy = JSONObject(src.toString())
                if (!copy.has("scene_id") || copy.optInt("scene_id", 0) <= 0) {
                    copy.put("scene_id", i + 1)
                }
                val narration = copy.optString("tagalog_narration", "").trim()
                if (!copy.has("camera_motion") || copy.optString("camera_motion").isBlank()) {
                    copy.put("camera_motion", CAMERA_MOTION_ROTATION[i % CAMERA_MOTION_ROTATION.size].name)
                }
                if (!copy.has("character_pose") || copy.optString("character_pose").isBlank()) {
                    copy.put("character_pose", CHARACTER_POSE_ROTATION[i % CHARACTER_POSE_ROTATION.size].name)
                }
                if (!copy.has("shot_framing") || copy.optString("shot_framing").isBlank()) {
                    copy.put("shot_framing", SHOT_FRAMING_ROTATION[i % SHOT_FRAMING_ROTATION.size].name)
                }
                val pose = CharacterPose.fromRaw(copy.optString("character_pose"))
                if (!copy.has("actor_role") || copy.optString("actor_role").isBlank()) {
                    copy.put("actor_role", inferContextCue(narration, i).role.name)
                }
                if (!copy.has("actor_action") || copy.optString("actor_action").isBlank()) {
                    copy.put("actor_action", mapActionForPose(pose).name)
                }
                if (!copy.has("world_fx") || copy.optString("world_fx").isBlank()) {
                    copy.put("world_fx", inferContextCue(narration, i).worldFx.name)
                }
                val vis = copy.optString("visual_description", "").trim()
                val bg = copy.optString("background_prompt", "").trim()
                if (vis.isEmpty() && bg.isEmpty()) {
                    val generatedVis = buildStyleVisualDescription(narration, style, i)
                    copy.put("visual_description", generatedVis)
                    copy.put("background_prompt", generatedVis)
                } else if (bg.isEmpty()) {
                    copy.put("background_prompt", vis)
                } else if (vis.isEmpty()) {
                    copy.put("visual_description", bg)
                }
                enriched.put(copy)
            }
            JSONObject().put("scenes", enriched).toString()
        } catch (_: Throwable) {
            raw
        }
    }
}
