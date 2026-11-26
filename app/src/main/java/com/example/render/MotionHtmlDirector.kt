package com.example.render

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.example.model.ActorAction
import com.example.model.ActorExpression
import com.example.model.ActorRole
import com.example.model.AnimationStyle
import com.example.model.CameraMotion
import com.example.model.CharacterAge
import com.example.model.CharacterGender
import com.example.model.CharacterOutfit
import com.example.model.CharacterPose
import com.example.model.DynamicDiagramType
import com.example.model.EnvironmentTheme
import com.example.model.HandProp
import com.example.model.LightingState
import com.example.model.SceneScript
import com.example.model.ShotFraming
import com.example.model.StatusPill
import com.example.model.TimelineScene
import com.example.model.WordTiming
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

data class LockedCharacterProfile(
    val role: ActorRole,
    val gender: CharacterGender,
    val age: CharacterAge,
    val outfit: CharacterOutfit,
    val outfitColorHex: String,
    val hairColorHex: String,
    val skinColorHex: String,
    val skinShadowHex: String
)

enum class ViralCaptionTone {
    NORMAL,
    ACCENT,
    DANGER
}

data class ViralCaptionToken(
    val text: String,
    val tone: ViralCaptionTone
)

data class ViralCaptionChunk(
    val startMs: Double,
    val endMs: Double,
    val tokens: List<ViralCaptionToken>
)

enum class MotionPresetEngine {
    ATMOSPHERIC_2D,
    STYLIZED_25D,
    PAPER_DIORAMA,
    KINETIC_DIAGRAM,
    CUTAWAY_SYSTEM
}

object MotionHtmlDirector {

    private val DANGER_WORDS = setOf(
        "UBOS", "UTANG", "MALI", "DELIKADO", "TAKOT", "MULTO", "HIRAP", "SAYANG",
        "HINDI", "BAWAL", "LUGI", "GASTOS", "PROBLEMA", "KULANG", "BROKE", "TRAP",
        "LAGOT", "CRISIS", "DANGER", "STOP", "MASAKIT", "NAKAKATAKOT", "PATAY", "WARN"
    )

    private val ACCENT_WORDS = setOf(
        "IPON", "YAMAN", "PERA", "SAHOD", "SWELDO", "SIKRETO", "DISKARTE", "NEGOSYO",
        "SOLUSYON", "GOAL", "TAMA", "LIBRE", "PANALO", "BAKIT", "PAANO", "RULE", "TIP",
        "TIPS", "LAKAS", "TAGUMPAY", "PAY", "FIRST", "MILLION", "MILYON", "MILYONARYO",
        "ALAM", "GRABE", "POWER", "TRUTH", "SECRET", "HOOK", "BENTE"
    )

    private val EXPLICIT_CHICKEN_STORY_KEYWORDS = listOf(
        "manok", "chicken", "sisiw", "rooster", "hen"
    )

    private val EXPLICIT_CHARACTER_CUES = setOf(
        "lola", "lolo", "nanay", "tatay", "tindera", "tindero", "bata", "lalaki", "babae",
        "kaibigan", "kuya", "ate", "boss", "doktor", "nurse", "teacher", "guro",
        "driver", "farmer", "magsasaka", "manok", "aso", "pusa", "isda", "character",
        "person", "woman", "man", "worker", "tauhan"
    )

    private fun containsWholeWordCue(text: String, cues: Set<String>): Boolean {
        val sb = StringBuilder()
        for (i in 0..text.length) {
            val ch = if (i < text.length) text[i] else ' '
            if (ch.isLetter()) {
                sb.append(ch.lowercaseChar())
            } else if (sb.isNotEmpty()) {
                if (sb.toString() in cues) return true
                sb.setLength(0)
            }
        }
        return false
    }

    fun mapPresetEngine(style: AnimationStyle): MotionPresetEngine = when (style) {
        AnimationStyle.ATMOSPHERIC_2D_STORY,
        AnimationStyle.DARK_SUSPENSE,
        AnimationStyle.CHIBI_STORYTIME -> MotionPresetEngine.ATMOSPHERIC_2D

        AnimationStyle.STYLIZED_25D_EXPLAINER,
        AnimationStyle.FLAT_VECTOR,
        AnimationStyle.VIRAL_2D_EXPLAINER -> MotionPresetEngine.STYLIZED_25D

        AnimationStyle.PAPER_DIORAMA,
        AnimationStyle.STICKMAN_WHITEBOARD -> MotionPresetEngine.PAPER_DIORAMA

        AnimationStyle.KINETIC_TYPOGRAPHY_DIAGRAM,
        AnimationStyle.TECH_EXPLAINER -> MotionPresetEngine.KINETIC_DIAGRAM

        AnimationStyle.CROSS_SECTION,
        AnimationStyle.PIXEL_CUTAWAY_EXPLAINER -> MotionPresetEngine.CUTAWAY_SYSTEM
    }

    /**
     * Bans default standing presenter/host characters. Characters only render when explicitly
     * requested (`hasCharacter == true`) or when the scene story explicitly depicts a named person/animal.
     */
    fun shouldRenderCharacter(scene: SceneScript, presetEngine: MotionPresetEngine): Boolean {
        scene.hasCharacter?.let { return it }
        if (presetEngine == MotionPresetEngine.CUTAWAY_SYSTEM ||
            presetEngine == MotionPresetEngine.KINETIC_DIAGRAM
        ) {
            return false
        }
        if (scene.secondaryActorRole != null) return true
        val combined = "${scene.tagalogNarration} ${scene.visualDescription}"
        return containsWholeWordCue(combined, EXPLICIT_CHARACTER_CUES)
    }

    /**
     * Locks one consistent identity per role across the entire story so characters never flip gender,
     * age, outfit colors, or turn into chicken/animal mascots mid-video.
     */
    fun resolveLockedProfiles(scenes: List<SceneScript>): Map<ActorRole, LockedCharacterProfile> {
        val result = linkedMapOf<ActorRole, LockedCharacterProfile>()
        // Determine dominant protagonist identity first
        val firstExplicit = scenes.firstOrNull {
            it.characterGender != null || it.characterOutfit != null || it.outfitColorHex.isNotBlank()
        }
        val baseGender = firstExplicit?.characterGender ?: CharacterGender.MALE
        val baseAge = firstExplicit?.characterAge ?: CharacterAge.YOUNG_ADULT
        val baseOutfit = firstExplicit?.characterOutfit ?: CharacterOutfit.HOODIE
        val baseOutfitColor = sanitizeHex(firstExplicit?.outfitColorHex, "#2563EB")
        val baseHairColor = sanitizeHex(firstExplicit?.hairColorHex, "#1E293B")

        val rolesToLock = listOf(
            ActorRole.HERO,
            ActorRole.NARRATOR,
            ActorRole.POOR_MAN,
            ActorRole.RICH_MAN,
            ActorRole.CHICKEN_MENTOR
        )

        for (role in rolesToLock) {
            val roleScene = scenes.firstOrNull { it.actorRole == role }
                ?: scenes.firstOrNull { it.secondaryActorRole == role }
            val gender = roleScene?.characterGender ?: when (role) {
                ActorRole.RICH_MAN -> CharacterGender.MALE
                ActorRole.NARRATOR -> baseGender
                else -> baseGender
            }
            val age = roleScene?.characterAge ?: when (role) {
                ActorRole.RICH_MAN -> CharacterAge.MIDDLE_AGED
                else -> baseAge
            }
            val outfit = roleScene?.characterOutfit ?: when (role) {
                ActorRole.RICH_MAN -> CharacterOutfit.OFFICE_SUIT
                ActorRole.POOR_MAN -> CharacterOutfit.CASUAL_TSHIRT
                ActorRole.NARRATOR -> baseOutfit
                ActorRole.HERO -> baseOutfit
                ActorRole.CHICKEN_MENTOR -> CharacterOutfit.OFFICE_SUIT
            }
            val outfitHex = sanitizeHex(
                roleScene?.outfitColorHex,
                when (role) {
                    ActorRole.RICH_MAN -> "#0F766E"
                    ActorRole.POOR_MAN -> "#D97706"
                    ActorRole.CHICKEN_MENTOR -> "#059669"
                    else -> baseOutfitColor
                }
            )
            val hairHex = sanitizeHex(
                roleScene?.hairColorHex,
                if (age == CharacterAge.ELDERLY) "#CBD5E1" else baseHairColor
            )
            val (skin, skinShadow) = when (role) {
                ActorRole.RICH_MAN -> "#F1C29B" to "#D19A72"
                ActorRole.POOR_MAN -> "#DDA87C" to "#BA8458"
                else -> "#EAC096" to "#C9966B"
            }
            result[role] = LockedCharacterProfile(
                role = role,
                gender = gender,
                age = age,
                outfit = outfit,
                outfitColorHex = outfitHex,
                hairColorHex = hairHex,
                skinColorHex = skin,
                skinShadowHex = skinShadow
            )
        }
        return result
    }

    /**
     * Never substitute human characters with chicken mascots. Only permits non-human rendering if
     * the scene narration explicitly talks about chickens.
     */
    fun shouldRenderAsHuman(scene: SceneScript): Boolean {
        if (scene.actorRole != ActorRole.CHICKEN_MENTOR) return true
        val combined = "${scene.tagalogNarration} ${scene.visualDescription}".lowercase(Locale.US)
        val explicitlyAboutChicken = EXPLICIT_CHICKEN_STORY_KEYWORDS.any { combined.contains(it) }
        return !explicitlyAboutChicken
    }

    fun classifyCaptionTone(rawWord: String): ViralCaptionTone {
        val cleaned = buildString {
            for (ch in rawWord.uppercase(Locale.US)) {
                if (ch.isLetterOrDigit() || ch == '₱' || ch == '%') append(ch)
            }
        }
        if (cleaned.isEmpty()) return ViralCaptionTone.NORMAL
        if (cleaned in DANGER_WORDS) return ViralCaptionTone.DANGER
        if (cleaned.contains('₱') || cleaned.contains('%') || cleaned.any { it.isDigit() }) {
            return ViralCaptionTone.ACCENT
        }
        if (cleaned in ACCENT_WORDS) return ViralCaptionTone.ACCENT
        return ViralCaptionTone.NORMAL
    }

    /**
     * Groups word timings (or fallback narration) into punchy 1–2 UPPERCASE word viral caption chunks.
     */
    fun buildViralCaptionChunks(
        wordTimings: List<WordTiming>,
        fallbackNarration: String,
        sceneStartMs: Double,
        sceneEndMs: Double
    ): List<ViralCaptionChunk> {
        val normalizedWords: List<WordTiming> = if (wordTimings.isNotEmpty()) {
            wordTimings.filter { it.word.isNotBlank() }
        } else {
            val rawTokens = fallbackNarration.trim()
                .split(' ', '\n', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (rawTokens.isEmpty()) return emptyList()
            val span = (sceneEndMs - sceneStartMs).coerceAtLeast(1000.0)
            val totalWeight = rawTokens.sumOf { it.length.coerceAtLeast(2) }.toDouble().coerceAtLeast(1.0)
            var cursor = sceneStartMs
            rawTokens.map { tok ->
                val dur = span * (tok.length.coerceAtLeast(2) / totalWeight)
                val wt = WordTiming(tok, cursor, cursor + dur)
                cursor += dur
                wt
            }
        }

        if (normalizedWords.isEmpty()) return emptyList()

        val chunks = mutableListOf<ViralCaptionChunk>()
        var idx = 0
        while (idx < normalizedWords.size) {
            val first = normalizedWords[idx]
            val firstUpper = first.word.trim().uppercase(Locale.US)
            val takeSingle = firstUpper.length >= 9 ||
                firstUpper.endsWith('?') ||
                firstUpper.endsWith('!') ||
                idx + 1 >= normalizedWords.size

            if (takeSingle) {
                chunks.add(
                    ViralCaptionChunk(
                        startMs = first.startMs,
                        endMs = first.endMs,
                        tokens = listOf(ViralCaptionToken(firstUpper, classifyCaptionTone(firstUpper)))
                    )
                )
                idx += 1
            } else {
                val second = normalizedWords[idx + 1]
                val secondUpper = second.word.trim().uppercase(Locale.US)
                chunks.add(
                    ViralCaptionChunk(
                        startMs = first.startMs,
                        endMs = second.endMs.coerceAtLeast(first.endMs),
                        tokens = listOf(
                            ViralCaptionToken(firstUpper, classifyCaptionTone(firstUpper)),
                            ViralCaptionToken(secondUpper, classifyCaptionTone(secondUpper))
                        )
                    )
                )
                idx += 2
            }
        }
        return chunks
    }

    fun buildHtmlDocument(
        scenes: List<TimelineScene>,
        style: AnimationStyle,
        totalDurationMs: Double,
        includeAiBackdrops: Boolean = false,
        context: Context? = null
    ): String {
        val presetEngine = mapPresetEngine(style)
        val rawScripts = scenes.map { it.scene }
        val lockedProfiles = resolveLockedProfiles(rawScripts)

        val payloadJson = JSONObject().apply {
            put("style", style.name)
            put("presetEngine", presetEngine.name)
            put("totalDurationSec", (totalDurationMs / 1000.0).coerceAtLeast(1.0))

            val profilesJson = JSONObject()
            lockedProfiles.forEach { (role, profile) ->
                profilesJson.put(
                    role.name,
                    JSONObject().apply {
                        put("gender", profile.gender.name)
                        put("age", profile.age.name)
                        put("outfit", profile.outfit.name)
                        put("outfitColor", profile.outfitColorHex)
                        put("hairColor", profile.hairColorHex)
                        put("skinColor", profile.skinColorHex)
                        put("skinShadow", profile.skinShadowHex)
                    }
                )
            }
            put("profiles", profilesJson)

            val scenesArr = JSONArray()
            scenes.forEachIndexed { idx, ts ->
                val sc = ts.scene
                val resolvedRole = if (sc.actorRole == ActorRole.CHICKEN_MENTOR && shouldRenderAsHuman(sc)) {
                    ActorRole.NARRATOR
                } else {
                    sc.actorRole
                }
                val resolvedSecondary = if (sc.secondaryActorRole == ActorRole.CHICKEN_MENTOR) {
                    ActorRole.RICH_MAN
                } else {
                    sc.secondaryActorRole
                }
                val envTheme = sc.environmentTheme ?: inferEnvironmentTheme(sc, idx)
                val captionChunks = buildViralCaptionChunks(
                    wordTimings = ts.wordTimings,
                    fallbackNarration = sc.tagalogNarration,
                    sceneStartMs = ts.startMs,
                    sceneEndMs = ts.endMs
                )
                val backdropDataUri = if (includeAiBackdrops && ts.backdropBitmap != null) {
                    encodeBitmapToDataUri(ts.backdropBitmap)
                } else ""

                scenesArr.put(
                    JSONObject().apply {
                        put("sceneId", sc.sceneId)
                        put("startSec", ts.startMs / 1000.0)
                        put("endSec", ts.endMs / 1000.0)
                        put("theme", envTheme.name)
                        put("cameraMotion", sc.cameraMotion.name)
                        put("shotFraming", sc.shotFraming.name)
                        put("characterPose", sc.characterPose.name)
                        put("actorRole", resolvedRole.name)
                        put("secondaryRole", resolvedSecondary?.name ?: "")
                        put("expression", sc.expression.name)
                        put("handProp", sc.handProp.name)
                        put("statusPill", sc.statusPill.name)
                        put("calloutBanner", sc.calloutBanner.ifBlank { sc.topicPillLabel })
                        put("hudWidget", sc.hudWidget.name)
                        put("hudLabel", sc.hudLabel)
                        put("hudUnit", sc.hudUnit)
                        put("hudFrom", sc.hudFromValue)
                        put("hudTo", sc.hudToValue)
                        put("hasCharacter", shouldRenderCharacter(sc, presetEngine))
                        put("lightingState", sc.lightingState.name)
                        put("flowDirection", sc.flowDirection.name)
                        put("diagramType", sc.diagramType.name)
                        put("leftLabel", sc.leftLabel)
                        put("rightLabel", sc.rightLabel)
                        put("step1", sc.step1)
                        put("step2", sc.step2)
                        put("step3", sc.step3)
                        put("worldFx", sc.worldFx.name)
                        put("titleCard", buildSceneTitleCard(sc, idx))
                        put("speechBubble", buildSpeechBubbleText(sc))
                        put("backdropUri", backdropDataUri)
                        put("customSvg", sc.customSvg)

                        val pcArr = JSONArray()
                        sc.pointerCallouts.forEach { pc ->
                            pcArr.put(
                                JSONObject().apply {
                                    put("label", pc.label)
                                    put("x", pc.x.toDouble())
                                    put("y", pc.y.toDouble())
                                }
                            )
                        }
                        put("pointerCallouts", pcArr)

                        val capArr = JSONArray()
                        captionChunks.forEach { chunk ->
                            val tokArr = JSONArray()
                            chunk.tokens.forEach { tok ->
                                tokArr.put(
                                    JSONObject().apply {
                                        put("text", tok.text)
                                        put("tone", tok.tone.name)
                                    }
                                )
                            }
                            capArr.put(
                                JSONObject().apply {
                                    put("startSec", chunk.startMs / 1000.0)
                                    put("endSec", chunk.endMs / 1000.0)
                                    put("tokens", tokArr)
                                }
                            )
                        }
                        put("captions", capArr)
                    }
                )
            }
            put("scenes", scenesArr)
        }

        // Escape `</` so arbitrary script narration cannot break out of the embedded JSON script tag
        val safeJson = payloadJson.toString().replace("</", "<\\/")

        if (presetEngine == MotionPresetEngine.CUTAWAY_SYSTEM) {
            val loadedTemplate = loadCutawayTemplateHtml(context)
            if (loadedTemplate != null) {
                val dataTag = "<script id=\"storyboard-data\" type=\"application/json\">$safeJson</script>"
                return if (loadedTemplate.contains("<!--STORYBOARD_DATA-->")) {
                    loadedTemplate.replace("<!--STORYBOARD_DATA-->", dataTag)
                } else {
                    loadedTemplate.replace("</body>", "$dataTag\n</body>")
                }
            }
        }

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=1080, height=1920, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
<title>ToonForge Motion Engine</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  html, body {
    width: 100%;
    height: 100%;
    overflow: hidden;
    background: #080B12;
    font-family: 'Montserrat', 'Roboto', 'Noto Sans', Arial, sans-serif;
    -webkit-font-smoothing: antialiased;
  }
  svg#stage {
    width: 100vw;
    height: 100vh;
    display: block;
    background: #080B12;
  }
  .cap-text {
    font-family: 'Montserrat', 'Roboto Black', 'Arial Black', sans-serif;
    font-weight: 900;
    font-size: 88px;
    letter-spacing: 1px;
    paint-order: stroke fill;
    stroke: #0B0E14;
    stroke-width: 19px;
    stroke-linejoin: round;
    stroke-linecap: round;
    filter: drop-shadow(0px 9px 12px rgba(0, 0, 0, 0.85));
  }
  .title-card-txt {
    font-family: 'Montserrat', 'Roboto', Arial, sans-serif;
    font-weight: 900;
    letter-spacing: 1.5px;
  }
  .diagram-txt {
    font-family: 'Roboto', Arial, sans-serif;
    font-weight: 800;
  }
  .paper-shadow {
    filter: drop-shadow(4px 8px 6px rgba(0, 0, 0, 0.44));
  }
</style>
</head>
<body>
<svg id="stage" viewBox="0 0 1080 1920" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <linearGradient id="sky-warm" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="#1E1B4B" />
      <stop offset="52%" stop-color="#4338CA" />
      <stop offset="100%" stop-color="#F97316" />
    </linearGradient>
    <linearGradient id="sky-night" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="#050814" />
      <stop offset="55%" stop-color="#0F172A" />
      <stop offset="100%" stop-color="#1E293B" />
    </linearGradient>
    <linearGradient id="sky-day" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="#0EA5E9" />
      <stop offset="58%" stop-color="#38BDF8" />
      <stop offset="100%" stop-color="#FEF3C7" />
    </linearGradient>
    <linearGradient id="sky-vault" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="#062820" />
      <stop offset="55%" stop-color="#0B3B31" />
      <stop offset="100%" stop-color="#111827" />
    </linearGradient>
    <linearGradient id="light-beam" x1="0" y1="0" x2="0.4" y2="1">
      <stop offset="0%" stop-color="#FFF7D6" stop-opacity="0.34" />
      <stop offset="55%" stop-color="#FDE68A" stop-opacity="0.14" />
      <stop offset="100%" stop-color="#FDE68A" stop-opacity="0" />
    </linearGradient>
    <linearGradient id="cool-beam" x1="0" y1="0" x2="0.3" y2="1">
      <stop offset="0%" stop-color="#7DD3FC" stop-opacity="0.28" />
      <stop offset="65%" stop-color="#38BDF8" stop-opacity="0.10" />
      <stop offset="100%" stop-color="#38BDF8" stop-opacity="0" />
    </linearGradient>
    <linearGradient id="rim-light-grad" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="#FEF3C7" stop-opacity="0.16" />
      <stop offset="45%" stop-color="#38BDF8" stop-opacity="0.05" />
      <stop offset="100%" stop-color="#000000" stop-opacity="0" />
    </linearGradient>
    <radialGradient id="vignette-grad" cx="50%" cy="46%" r="65%">
      <stop offset="48%" stop-color="#000000" stop-opacity="0" />
      <stop offset="100%" stop-color="#04060D" stop-opacity="0.64" />
    </radialGradient>
    <filter id="paper-grain" x="0%" y="0%" width="100%" height="100%">
      <feTurbulence type="fractalNoise" baseFrequency="0.65" numOctaves="2" result="noise" />
      <feColorMatrix type="matrix" values="0 0 0 0 0.95  0 0 0 0 0.92  0 0 0 0 0.85  0 0 0 0.06 0" in="noise" result="tint" />
      <feComposite in="SourceGraphic" in2="tint" operator="over" />
    </filter>
    <filter id="cinema-grain" x="0%" y="0%" width="100%" height="100%">
      <feTurbulence type="fractalNoise" baseFrequency="0.72" numOctaves="2" stitchTiles="stitch" result="noise" />
      <feColorMatrix type="matrix" values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 0.9 0" in="noise" result="mono" />
    </filter>
    <filter id="soft-glow" x="-30%" y="-30%" width="160%" height="160%">
      <feGaussianBlur stdDeviation="8" result="blur" />
      <feMerge>
        <feMergeNode in="blur" />
        <feMergeNode in="SourceGraphic" />
      </feMerge>
    </filter>
    <clipPath id="stage-clip">
      <rect x="0" y="0" width="1080" height="1920" />
    </clipPath>
  </defs>

  <g clip-path="url(#stage-clip)">
    <!-- Camera Transform Group -->
    <g id="camera-root">
      <image id="ai-backdrop-img" x="-54" y="-96" width="1188" height="2112" preserveAspectRatio="xMidYMid slice" opacity="0" />
      <g id="bg-far"></g>
      <g id="bg-mid"></g>
      <g id="bg-props"></g>
      <g id="custom-svg-layer"></g>
      <g id="atmospheric-beams"></g>
      <g id="flow-overlay-layer"></g>
      <g id="world-fx-layer"></g>
      <g id="cast-layer"></g>
      <g id="fg-silhouette"></g>
    </g>

    <!-- Non-face-overlapping Motion Graphics, Kinetic Cards, Speech Bubbles & Diagrams -->
    <g id="kinetic-hud-layer"></g>
    <g id="speech-bubble-layer"></g>
    <g id="editorial-hook-layer"></g>

    <!-- Cinema Post-Processing: Rim Light + Atmospheric Vignette + 0.035 Film Grain -->
    <rect x="0" y="0" width="1080" height="1920" fill="url(#rim-light-grad)" pointer-events="none" />
    <rect id="vignette-rect" x="0" y="0" width="1080" height="1920" fill="url(#vignette-grad)" pointer-events="none" />
    <rect id="cinema-grain-rect" x="0" y="0" width="1080" height="1920" fill="#FFFFFF" filter="url(#cinema-grain)" opacity="0.035" pointer-events="none" />

    <!-- Safe Zone Viral 1-2 Word Punchy Captions at y = 74% (1420px) -->
    <g id="caption-layer" transform="translate(540, 1420)"></g>
  </g>
</svg>

<script id="storyboard-data" type="application/json">$safeJson</script>
<script>
(function() {
  "use strict";
  const NS = "http://www.w3.org/2000/svg";
  const DATA = JSON.parse(document.getElementById("storyboard-data").textContent);
  const SCENES = DATA.scenes || [];
  const PROFILES = DATA.profiles || {};
  const PRESET = DATA.presetEngine || "ATMOSPHERIC_2D";

  const elCamera = document.getElementById("camera-root");
  const elBackdropImg = document.getElementById("ai-backdrop-img");
  const elBgFar = document.getElementById("bg-far");
  const elBgMid = document.getElementById("bg-mid");
  const elBgProps = document.getElementById("bg-props");
  const elCustomSvg = document.getElementById("custom-svg-layer");
  const elBeams = document.getElementById("atmospheric-beams");
  const elFlowOverlay = document.getElementById("flow-overlay-layer");
  const elFx = document.getElementById("world-fx-layer");
  const elCast = document.getElementById("cast-layer");
  const elFg = document.getElementById("fg-silhouette");
  const elHud = document.getElementById("kinetic-hud-layer");
  const elSpeech = document.getElementById("speech-bubble-layer");
  const elHook = document.getElementById("editorial-hook-layer");
  const elCaption = document.getElementById("caption-layer");
  const elVignette = document.getElementById("vignette-rect");

  let lastSceneIdx = -1;

  function svgEl(tag, attrs, parent) {
    const node = document.createElementNS(NS, tag);
    if (attrs) {
      for (const k in attrs) {
        node.setAttribute(k, String(attrs[k]));
      }
    }
    if (parent) parent.appendChild(node);
    return node;
  }

  function clearNode(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  function clamp(v, lo, hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }

  function lerp(a, b, t) {
    return a + (b - a) * t;
  }

  // Exact cubic-bezier(0.16, 1, 0.3, 1) exponential ease-out curve
  function easeOutExpoSnap(t) {
    const x = clamp(t, 0, 1);
    return x === 1 ? 1 : 1 - Math.pow(2, -10 * x);
  }

  // Spring overshoot matching cubic-bezier(0.34, 1.56, 0.64, 1)
  function springOvershoot(t) {
    const x = clamp(t, 0, 1);
    const c1 = 1.70158;
    const c3 = c1 + 1;
    return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2);
  }

  function easeInOut(t) {
    const x = clamp(t, 0, 1);
    return x < 0.5 ? 2 * x * x : 1 - Math.pow(-2 * x + 2, 2) / 2;
  }

  function themePalette(theme) {
    switch (theme) {
      case "COZY_BEDROOM_MIDNIGHT":
        return { sky: "url(#sky-night)", wall: "#111827", wall2: "#1E293B", floor: "#0B101D", accent: "#F59E0B", beam: "url(#light-beam)", isNight: true };
      case "DRAMATIC_SUNSET_CITY":
        return { sky: "url(#sky-warm)", wall: "#1E1B4B", wall2: "#312E81", floor: "#0F172A", accent: "#FB923C", beam: "url(#light-beam)", isNight: true };
      case "BANK_VAULT_FINANCE":
        return { sky: "url(#sky-vault)", wall: "#0A2E26", wall2: "#11463A", floor: "#061C17", accent: "#FACC15", beam: "url(#light-beam)", isNight: true };
      case "SCIENCE_LAB_INTERIOR":
        return { sky: "url(#sky-night)", wall: "#0C192C", wall2: "#142B47", floor: "#081220", accent: "#22D3EE", beam: "url(#cool-beam)", isNight: true };
      case "CLASSROOM_CHALKBOARD":
        return { sky: "url(#sky-day)", wall: "#1B3A2F", wall2: "#264F40", floor: "#5C4028", accent: "#FDE047", beam: "url(#light-beam)", isNight: false };
      case "BRIGHT_MORNING_KITCHEN":
        return { sky: "url(#sky-day)", wall: "#FFF1DC", wall2: "#F7DFC2", floor: "#8D5B4C", accent: "#F59E0B", beam: "url(#light-beam)", isNight: false };
      case "SUNNY_MARKET_STREET":
        return { sky: "url(#sky-day)", wall: "#F59E0B", wall2: "#EA580C", floor: "#334155", accent: "#FEF08A", beam: "url(#light-beam)", isNight: false };
      case "MODERN_OFFICE_DESK":
        return { sky: "url(#sky-day)", wall: "#1E293B", wall2: "#334155", floor: "#0F172A", accent: "#38BDF8", beam: "url(#cool-beam)", isNight: false };
      case "HOSPITAL_CLINIC":
        return { sky: "url(#sky-day)", wall: "#E2F1F8", wall2: "#C7E4F2", floor: "#64748B", accent: "#0EA5E9", beam: "url(#cool-beam)", isNight: false };
      case "SUNNY_PARK_NATURE":
        return { sky: "url(#sky-day)", wall: "#15803D", wall2: "#166534", floor: "#3F6212", accent: "#FACC15", beam: "url(#light-beam)", isNight: false };
      default:
        return { sky: "url(#sky-night)", wall: "#161B2E", wall2: "#232B45", floor: "#0E1220", accent: "#38BDF8", beam: "url(#cool-beam)", isNight: true };
    }
  }

  function injectCustomSvg(svgText) {
    clearNode(elCustomSvg);
    if (!svgText) return false;
    try {
      const parser = new DOMParser();
      const doc = parser.parseFromString(svgText, "image/svg+xml");
      const root = doc && doc.documentElement;
      if (!root || root.nodeName.toLowerCase() === "parsererror") return false;
      const imported = document.importNode(root, true);
      imported.setAttribute("x", "0");
      imported.setAttribute("y", "0");
      imported.setAttribute("width", "1080");
      imported.setAttribute("height", "1920");
      if (!imported.getAttribute("viewBox")) {
        imported.setAttribute("viewBox", "0 0 1080 1920");
      }
      if (!imported.getAttribute("preserveAspectRatio")) {
        imported.setAttribute("preserveAspectRatio", "xMidYMid meet");
      }
      if (typeof imported.pauseAnimations === "function") {
        try { imported.pauseAnimations(); } catch (e) {}
      }
      elCustomSvg.appendChild(imported);
      return true;
    } catch (e) {
      return false;
    }
  }

  function renderEnvironment(scene) {
    clearNode(elBgFar);
    clearNode(elBgMid);
    clearNode(elBgProps);
    clearNode(elCustomSvg);
    clearNode(elBeams);
    clearNode(elFlowOverlay);
    clearNode(elFg);

    const pal = themePalette(scene.theme);
    const hasCustomSvg = !!(scene.customSvg && scene.customSvg.trim().length > 0 && injectCustomSvg(scene.customSvg));
    const hasBackdrop = !hasCustomSvg && !!(scene.backdropUri && scene.backdropUri.trim().length > 0);

    if (hasCustomSvg) {
      // Tier 1: Bespoke LLM-generated self-contained SVG fills the stage unobstructed
      elBackdropImg.setAttribute("opacity", "0");
      elBackdropImg.removeAttribute("href");
      svgEl("rect", { x: -160, y: -160, width: 1400, height: 2240, fill: "#080B12" }, elBgFar);
    } else if (hasBackdrop) {
      // Tier 2: Full-bleed 2D flat vector illustration (opacity=1, zero wall/city boxes covering it)
      elBackdropImg.setAttribute("href", scene.backdropUri);
      elBackdropImg.setAttribute("opacity", "1");
    } else {
      // Tier 3: Clean atmospheric gradient stage + volumetric rays (zero generic bedrooms/city blocks)
      elBackdropImg.setAttribute("opacity", "0");
      elBackdropImg.removeAttribute("href");
      svgEl("rect", { x: -160, y: -160, width: 1400, height: 2240, fill: pal.sky }, elBgFar);

      if (PRESET === "KINETIC_DIAGRAM") {
        for (let gx = 0; gx <= 1080; gx += 120) {
          svgEl("line", { x1: gx, y1: 0, x2: gx, y2: 1920, stroke: "#38BDF8", "stroke-width": 1.5, opacity: 0.12 }, elBgFar);
        }
        for (let gy = 0; gy <= 1920; gy += 120) {
          svgEl("line", { x1: 0, y1: gy, x2: 1080, y2: gy, stroke: "#38BDF8", "stroke-width": 1.5, opacity: 0.12 }, elBgFar);
        }
      } else {
        const filterAttr = PRESET === "PAPER_DIORAMA" ? { filter: "url(#paper-grain)" } : {};
        const stageGrp = svgEl("g", filterAttr, elBgMid);
        svgEl("ellipse", { cx: 540, cy: 1320, rx: 680, ry: 280, fill: pal.floor, opacity: "0.52" }, stageGrp);
        svgEl("circle", { cx: 540, cy: 760, r: 440, fill: pal.accent, opacity: "0.08" }, stageGrp);
      }
    }

    // Subtle atmospheric volumetric light shafts & dust motes (kept light when custom artwork is active)
    if (!hasCustomSvg && (PRESET === "ATMOSPHERIC_2D" || PRESET === "PAPER_DIORAMA")) {
      const beamAlpha = hasBackdrop ? "0.28" : (PRESET === "ATMOSPHERIC_2D" ? "0.78" : "0.40");
      svgEl("polygon", {
        points: "690,120 960,120 620,1480 80,1480",
        fill: pal.beam,
        opacity: beamAlpha
      }, elBeams);
      svgEl("polygon", {
        points: "120,0 310,0 590,1420 260,1420",
        fill: pal.beam,
        opacity: hasBackdrop ? "0.14" : "0.30"
      }, elBeams);

      for (let i = 0; i < 10; i++) {
        svgEl("circle", {
          id: "dust-mote-" + i,
          cx: 420 + i * 32,
          cy: 340 + i * 88,
          r: 3 + (i % 3) * 1.4,
          fill: "#FFFBEB",
          opacity: hasBackdrop ? "0.35" : "0.50"
        }, elBeams);
      }
    }

    // Soft edge vignette silhouettes
    svgEl("path", {
      d: "M-80,1920 L-80,1580 Q35,1630 85,1920 Z",
      fill: "#05070E",
      opacity: hasCustomSvg ? "0.22" : "0.42"
    }, elFg);
    svgEl("path", {
      d: "M1160,1920 L1160,1600 Q1030,1660 985,1920 Z",
      fill: "#05070E",
      opacity: hasCustomSvg ? "0.20" : "0.38"
    }, elFg);
  }

  function renderAnimatedFlowOverlay(scene, sceneElapsed, tSec) {
    clearNode(elFlowOverlay);

    // 1. Drive any .flow-pulse circles or SMIL animations embedded inside Tier 1 customSvg
    const nestedSvg = elCustomSvg.querySelector("svg");
    if (nestedSvg && typeof nestedSvg.setCurrentTime === "function") {
      try { nestedSvg.setCurrentTime(sceneElapsed); } catch (e) {}
    }
    const customPulses = elCustomSvg.querySelectorAll(".flow-pulse");
    for (let i = 0; i < customPulses.length; i++) {
      const dot = customPulses[i];
      const pathSel = dot.getAttribute("data-path") || "";
      const targetPath = pathSel ? elCustomSvg.querySelector(pathSel) : null;
      if (targetPath && typeof targetPath.getTotalLength === "function") {
        const len = targetPath.getTotalLength();
        if (len > 0) {
          const speed = Number(dot.getAttribute("data-speed") || "0.45");
          const offset = Number(dot.getAttribute("data-offset") || (i * 0.27));
          let phase = (tSec * speed + offset) % 1;
          if (phase < 0) phase += 1;
          const pt = targetPath.getPointAtLength(phase * len);
          dot.setAttribute("cx", pt.x.toFixed(1));
          dot.setAttribute("cy", pt.y.toFixed(1));
        }
      }
    }

    // 2. When using 2D flat vector backdrop (or cutaway/diagram flow), composite crisp SVG vector flow rails
    if (!nestedSvg && (scene.backdropUri || !scene.hasCharacter)) {
      const dir = scene.flowDirection || "LEFT_TO_RIGHT";
      if (scene.pointerCallouts && scene.pointerCallouts.length > 0) {
        const beatPulse = (sceneElapsed % 2.5) < 0.45 ? 1 + 0.08 * Math.sin(((sceneElapsed % 2.5) / 0.45) * Math.PI) : 1;
        scene.pointerCallouts.forEach(function(pc) {
          const px = clamp((pc.x / 270) * 1080, 180, 900);
          const py = clamp((pc.y / 480) * 1920, 420, 1140);
          const pg = svgEl("g", { transform: "translate(" + px.toFixed(0) + "," + py.toFixed(0) + ") scale(" + beatPulse.toFixed(3) + ")" }, elFlowOverlay);
          svgEl("circle", { cx: 0, cy: 0, r: 12, fill: "#22D3EE", stroke: "#141821", "stroke-width": "2.5" }, pg);
          svgEl("line", { x1: 0, y1: -12, x2: 0, y2: -48, stroke: "#22D3EE", "stroke-width": "3.5" }, pg);
          const bw = Math.min(340, Math.max(180, pc.label.length * 16 + 44));
          svgEl("rect", { x: -bw / 2, y: -90, width: bw, height: 42, rx: 10, fill: "#0B1120", stroke: "#22D3EE", "stroke-width": "2.5" }, pg);
          const pt = svgEl("text", { x: 0, y: -63, "text-anchor": "middle", fill: "#F8FAFC", "font-size": "22", class: "diagram-txt" }, pg);
          pt.textContent = pc.label.toUpperCase();
        });
      } else if (dir === "UP" || dir === "DOWN" || dir === "RADIAL_BURST" || PRESET === "KINETIC_DIAGRAM") {
        const x1 = 200, x2 = 880, yMid = 1045;
        const dPath = dir === "UP"
          ? "M540,1080 L540,560"
          : (dir === "DOWN" ? "M540,560 L540,1080" : "M" + x1 + "," + yMid + " Q540," + (yMid - 75) + " " + x2 + "," + yMid);
        svgEl("path", { d: dPath, stroke: "#38BDF8", "stroke-width": "4", "stroke-dasharray": "14 10", fill: "none", opacity: "0.45" }, elFlowOverlay);
        for (let k = 0; k < 3; k++) {
          const frac = (tSec * 0.45 + k * 0.33) % 1;
          const px = dir === "UP" || dir === "DOWN" ? 540 : lerp(x1, x2, frac);
          const py = dir === "UP" ? lerp(1080, 560, frac) : (dir === "DOWN" ? lerp(560, 1080, frac) : yMid - Math.sin(frac * Math.PI) * 38);
          svgEl("circle", { cx: px.toFixed(1), cy: py.toFixed(1), r: 7.5, fill: "#FFD60A", stroke: "#141821", "stroke-width": "2", opacity: "0.88" }, elFlowOverlay);
        }
      }
    }
  }

  function renderHudAndDiagrams(scene, sceneElapsed, sceneDur, tSec) {
    clearNode(elHud);
    clearNode(elSpeech);
    clearNode(elHook);

    // Spring-physics entry curve (cubic-bezier(0.34, 1.56, 0.64, 1))
    const entrySpring = springOvershoot(clamp(sceneElapsed / 0.44, 0, 1));
    const entryOpacity = clamp(sceneElapsed / 0.18, 0, 1);
    const slideY = lerp(-36, 0, easeOutExpoSnap(clamp(sceneElapsed / 0.38, 0, 1)));
    const beatLocal = sceneElapsed % 2.5;
    const subPulse = beatLocal < 0.38 ? 1 + 0.035 * Math.sin((beatLocal / 0.38) * Math.PI) : 1.0;

    if (scene.titleCard) {
      const cardG = svgEl("g", {
        transform: "translate(540," + (158 + slideY) + ") scale(" + (entrySpring * subPulse).toFixed(4) + ")",
        opacity: entryOpacity
      }, elHud);
      const pill = scene.statusPill || "NONE";
      const pillGlyph = pill === "PROBLEM" ? "✕ " : ((pill === "GOAL" || pill === "FIX") ? "✓ " : "");
      const pillLabel = pill !== "NONE" ? (pillGlyph + pill) : "";
      const cardW = Math.min(920, Math.max(460, (scene.titleCard.length + pillLabel.length) * 26 + 96));
      svgEl("rect", {
        x: -cardW / 2, y: -44, width: cardW, height: 88, rx: 22,
        fill: "#0B1120", "fill-opacity": "0.92",
        stroke: pill === "PROBLEM" ? "#FF453A" : ((pill === "GOAL" || pill === "FIX") ? "#2DD4BF" : "#FFD60A"),
        "stroke-width": "4",
        class: "paper-shadow"
      }, cardG);
      const titleTxt = svgEl("text", {
        x: 0, y: 12, "text-anchor": "middle",
        fill: pill === "PROBLEM" ? "#FF6961" : ((pill === "GOAL" || pill === "FIX") ? "#2DD4BF" : "#FFD60A"),
        "font-size": "36", class: "title-card-txt"
      }, cardG);
      titleTxt.textContent = (pillLabel ? "[" + pillLabel + "] " : "") + scene.titleCard;
    }

    // Topic / Callout bracket card in top safe zone (y <= 345)
    if (scene.calloutBanner) {
      const badgeG = svgEl("g", {
        transform: "translate(540," + (258 + slideY * 0.6) + ") scale(" + entrySpring.toFixed(4) + ")",
        opacity: entryOpacity
      }, elHud);
      const bw = Math.min(820, Math.max(320, scene.calloutBanner.length * 24 + 80));
      svgEl("rect", {
        x: -bw / 2, y: -32, width: bw, height: 64, rx: 16,
        fill: "#111827", "fill-opacity": "0.92",
        stroke: "#38BDF8", "stroke-width": "3"
      }, badgeG);
      const bt = svgEl("text", {
        x: 0, y: 10, "text-anchor": "middle",
        fill: "#F8FAFC", "font-size": "30", class: "diagram-txt"
      }, badgeG);
      bt.textContent = scene.calloutBanner.toUpperCase();
    }

    // Pop-in Speech Bubble (only shown if character is rendered, or as top-right callout)
    if (scene.speechBubble && scene.hasCharacter) {
      const charOnRight = scene.shotFraming === "RULE_OF_THIRDS_RIGHT";
      const bx = charOnRight ? 330 : 750;
      const by = 435;
      const pop = springOvershoot(clamp((sceneElapsed - 0.15) / 0.36, 0, 1));
      if (pop > 0.02) {
        const sg = svgEl("g", { transform: "translate(" + bx + "," + by + ") scale(" + pop.toFixed(4) + ")", opacity: clamp((sceneElapsed - 0.15) / 0.18, 0, 1) }, elSpeech);
        const bw = Math.min(440, Math.max(220, scene.speechBubble.length * 19 + 64));
        svgEl("rect", {
          x: -bw / 2, y: -44, width: bw, height: 88, rx: 24,
          fill: "#FFFFFF", stroke: "#0F172A", "stroke-width": "5",
          class: "paper-shadow"
        }, sg);
        const tailDir = charOnRight ? 1 : -1;
        svgEl("polygon", {
          points: (tailDir * 45) + ",42 " + (tailDir * 95) + ",42 " + (tailDir * 120) + ",84",
          fill: "#FFFFFF", stroke: "#0F172A", "stroke-width": "5", "stroke-linejoin": "round"
        }, sg);
        svgEl("rect", { x: (tailDir * 40) - 4, y: 34, width: 64, height: 14, fill: "#FFFFFF" }, sg);
        const st = svgEl("text", {
          x: 0, y: 10, "text-anchor": "middle",
          fill: "#0F172A", "font-size": "28", class: "diagram-txt"
        }, sg);
        st.textContent = scene.speechBubble;
      }
    }

    // Data-driven Kinetic Diagrams / HUD Gauges (placed cleanly in y=1115..1275 lower band)
    const prog = easeOutExpoSnap(clamp(sceneElapsed / Math.min(1.6, sceneDur * 0.65), 0, 1));
    if (scene.hudWidget !== "NONE" || scene.diagramType !== "NONE") {
      const dg = svgEl("g", { transform: "translate(540, 1185) scale(" + subPulse.toFixed(4) + ")", opacity: entryOpacity }, elHud);
      svgEl("rect", {
        x: -430, y: -74, width: 860, height: 148, rx: 24,
        fill: "#090D16", "fill-opacity": "0.90",
        stroke: "#38BDF8", "stroke-width": "3",
        class: "paper-shadow"
      }, dg);

      if (scene.diagramType === "BEFORE_AFTER_SPLIT") {
        svgEl("line", { x1: 0, y1: -56, x2: 0, y2: 56, stroke: "#475569", "stroke-width": "3" }, dg);
        const lLbl = svgEl("text", { x: -215, y: 10, "text-anchor": "middle", fill: "#FF453A", "font-size": "32", class: "diagram-txt" }, dg);
        lLbl.textContent = "✕ " + (scene.leftLabel || "BEFORE");
        const rLbl = svgEl("text", { x: 215, y: 10, "text-anchor": "middle", fill: "#34D399", "font-size": "32", class: "diagram-txt" }, dg);
        rLbl.textContent = "✓ " + (scene.rightLabel || "AFTER");
      } else if (scene.diagramType === "STEP_FLOW_ARROWS") {
        const steps = [scene.step1 || "STEP 1", scene.step2 || "STEP 2", scene.step3 || "STEP 3"];
        for (let i = 0; i < 3; i++) {
          const sx = -270 + i * 270;
          const activeStep = prog >= i * 0.3;
          svgEl("rect", {
            x: sx - 108, y: -38, width: 216, height: 76, rx: 14,
            fill: activeStep ? "#0F172A" : "#1E293B",
            stroke: activeStep ? "#FFD60A" : "#475569", "stroke-width": "3"
          }, dg);
          const stxt = svgEl("text", {
            x: sx, y: 10, "text-anchor": "middle",
            fill: activeStep ? "#FFD60A" : "#94A3B8", "font-size": "24", class: "diagram-txt"
          }, dg);
          stxt.textContent = steps[i].slice(0, 14);
          if (i < 2) {
            svgEl("path", {
              d: "M" + (sx + 116) + ",0 L" + (sx + 150) + ",0",
              stroke: "#38BDF8", "stroke-width": "5", "stroke-linecap": "round"
            }, dg);
          }
        }
      } else {
        const fromV = Number(scene.hudFrom || 0);
        const toV = Number(scene.hudTo != null ? scene.hudTo : 100);
        const val = Math.round(lerp(fromV, toV, prog));
        const unit = scene.hudUnit || "%";
        const maxRef = Math.max(100, fromV, toV);
        const barFrac = unit === "%" ? clamp(val / 100, 0.04, 1) : clamp(val / maxRef, 0.05, 1);
        const lbl = svgEl("text", { x: -380, y: -18, fill: "#CBD5E1", "font-size": "26", class: "diagram-txt" }, dg);
        lbl.textContent = (scene.hudLabel || "PROGRESS").toUpperCase();
        const num = svgEl("text", { x: 380, y: -14, "text-anchor": "end", fill: "#FFD60A", "font-size": "40", class: "title-card-txt" }, dg);
        num.textContent = unit === "₱" ? ("₱" + val.toLocaleString("en-US")) : (val + (unit === "%" ? "%" : " " + unit));
        svgEl("rect", { x: -380, y: 14, width: 760, height: 28, rx: 14, fill: "#1E293B" }, dg);
        svgEl("rect", { x: -380, y: 14, width: Math.max(28, 760 * barFrac), height: 28, rx: 14, fill: "#2DD4BF" }, dg);
      }
    }

    // Bold Editorial Hook Title Card during first 1.8s (0.0s .. 1.8s)
    if (tSec <= 1.8 && SCENES.length > 0) {
      const hookTitle = (SCENES[0].titleCard || SCENES[0].calloutBanner || "ALAM MO BA ITO?").toUpperCase();
      const hookSpring = springOvershoot(clamp(tSec / 0.36, 0, 1));
      const exitOpacity = tSec > 1.45 ? clamp(1 - (tSec - 1.45) / 0.35, 0, 1) : 1;
      const hg = svgEl("g", {
        id: "editorial-hook-overlay",
        transform: "translate(540, 365) scale(" + hookSpring.toFixed(4) + ")",
        opacity: exitOpacity.toFixed(3),
        class: "paper-shadow"
      }, elHook);
      svgEl("rect", {
        x: -440, y: -68, width: 880, height: 136, rx: 24,
        fill: "#090E1A", "fill-opacity": "0.94", stroke: "#FFD60A", "stroke-width": "4.5"
      }, hg);
      svgEl("rect", { x: -410, y: -42, width: 12, height: 84, rx: 6, fill: "#FF453A" }, hg);
      const ht = svgEl("text", {
        x: 10, y: 14, "text-anchor": "middle", fill: "#FFFFFF", "font-size": "40", class: "title-card-txt"
      }, hg);
      ht.textContent = hookTitle.slice(0, 28);
    }
  }

  function drawHandPropSvg(propName, wristGroup) {
    if (!propName || propName === "NONE") return;
    if (propName === "MONEY_BAG") {
      // Wallet / Cash bundle anchored cleanly to wrist (0,0)
      svgEl("rect", { x: -24, y: -14, width: 64, height: 42, rx: 8, fill: "#16A34A", stroke: "#052E16", "stroke-width": 4 }, wristGroup);
      svgEl("rect", { x: -16, y: -6, width: 48, height: 26, rx: 4, fill: "#4ADE80" }, wristGroup);
      const pTxt = svgEl("text", { x: 8, y: 15, "text-anchor": "middle", fill: "#052E16", "font-size": 22, class: "diagram-txt" }, wristGroup);
      pTxt.textContent = "₱";
    } else if (propName === "PHONE_SCREEN") {
      svgEl("rect", { x: -18, y: -44, width: 42, height: 68, rx: 8, fill: "#0F172A", stroke: "#38BDF8", "stroke-width": 4 }, wristGroup);
      svgEl("rect", { x: -12, y: -36, width: 30, height: 50, rx: 4, fill: "#38BDF8" }, wristGroup);
    } else if (propName === "BOOK_NOTEBOOK") {
      svgEl("rect", { x: -20, y: -34, width: 52, height: 64, rx: 6, fill: "#F59E0B", stroke: "#451A03", "stroke-width": 4 }, wristGroup);
      svgEl("rect", { x: -10, y: -24, width: 32, height: 44, rx: 2, fill: "#FEF3C7" }, wristGroup);
    } else if (propName === "COFFEE_CUP") {
      svgEl("rect", { x: -18, y: -28, width: 38, height: 44, rx: 6, fill: "#FFFFFF", stroke: "#1E293B", "stroke-width": 4 }, wristGroup);
      svgEl("rect", { x: -20, y: -34, width: 42, height: 10, rx: 4, fill: "#78350F" }, wristGroup);
    } else if (propName === "BASKET_GOODS") {
      svgEl("path", { d: "M-32,-8 L32,-8 L24,30 L-24,30 Z", fill: "#B45309", stroke: "#451A03", "stroke-width": 4 }, wristGroup);
      svgEl("path", { d: "M-24,-8 Q0,-36 24,-8", fill: "none", stroke: "#78350F", "stroke-width": 6 }, wristGroup);
    } else if (propName === "LIGHTBULB") {
      svgEl("circle", { cx: 4, cy: -28, r: 22, fill: "#FDE047", stroke: "#CA8A04", "stroke-width": 4, filter: "url(#soft-glow)" }, wristGroup);
      svgEl("rect", { x: -4, y: -8, width: 16, height: 14, rx: 3, fill: "#94A3B8" }, wristGroup);
    } else if (propName === "MAGNIFYING_GLASS") {
      svgEl("line", { x1: 0, y1: 0, x2: 24, y2: -28, stroke: "#475569", "stroke-width": 8, "stroke-linecap": "round" }, wristGroup);
      svgEl("circle", { cx: 34, cy: -40, r: 22, fill: "#BAE6FD", "fill-opacity": "0.55", stroke: "#334155", "stroke-width": 6 }, wristGroup);
    } else if (propName === "POINTER_STICK") {
      svgEl("line", { x1: 0, y1: 0, x2: 88, y2: -94, stroke: "#F59E0B", "stroke-width": 7, "stroke-linecap": "round" }, wristGroup);
      svgEl("circle", { cx: 88, cy: -94, r: 8, fill: "#EF4444" }, wristGroup);
    }
  }

  function drawVectorCharacter(parent, profile, opts) {
    const { baseX, baseY, scale, facing, pose, expr, handProp, tSec, isPaper } = opts;
    const g = svgEl("g", {
      transform: "translate(" + baseX + "," + baseY + ") scale(" + (scale * facing) + "," + scale + ")",
      class: isPaper ? "paper-shadow" : ""
    }, parent);

    // Subtle organic breathing + gesture motion (pure deterministic sin of tSec)
    const breathe = Math.sin(tSec * 2.8) * 3.5;
    const talkCycle = Math.abs(Math.sin(tSec * 11.5));
    const blinkPhase = (tSec % 3.6);
    const isBlinking = blinkPhase > 3.44;

    // Ground contact shadow
    svgEl("ellipse", { cx: 0, cy: 196, rx: 92, ry: 18, fill: "#000000", opacity: "0.35" }, g);

    // Pose kinematics
    let torsoLeanDeg = 0;
    let lShoulderDeg = 22;
    let rShoulderDeg = -34;
    let lElbowDeg = 26;
    let rElbowDeg = -46;
    let hipDropY = 0;
    let stepOffset = 0;

    if (pose === "WALK_ACROSS_LEFT_TO_RIGHT") {
      stepOffset = Math.sin(tSec * 6.5) * 32;
      lShoulderDeg = Math.sin(tSec * 6.5) * 30;
      rShoulderDeg = -Math.sin(tSec * 6.5) * 30;
      hipDropY = Math.abs(Math.cos(tSec * 6.5)) * -8;
    } else if (pose === "POINT_AND_GESTURE") {
      rShoulderDeg = -96 + Math.sin(tSec * 4.2) * 8;
      rElbowDeg = -22;
      lShoulderDeg = 28;
    } else if (pose === "SHOCK_AIRBORNE_RECOIL") {
      torsoLeanDeg = -10;
      hipDropY = -22 + Math.sin(tSec * 9.0) * 6;
      lShoulderDeg = 128;
      rShoulderDeg = -128;
      lElbowDeg = 24;
      rElbowDeg = -24;
    } else if (pose === "CELEBRATE_JUMP") {
      hipDropY = -Math.abs(Math.sin(tSec * 5.2)) * 28;
      lShoulderDeg = 138;
      rShoulderDeg = -138;
    } else if (pose === "SIT_SLUMPED") {
      torsoLeanDeg = 14;
      hipDropY = 26;
      lShoulderDeg = 38;
      rShoulderDeg = -18;
      rElbowDeg = -70;
    } else {
      // THREE_QUARTER_EXPLAIN
      rShoulderDeg = -52 + Math.sin(tSec * 3.8) * 14;
      rElbowDeg = -55 + Math.cos(tSec * 3.8) * 12;
      lShoulderDeg = 24 + Math.sin(tSec * 2.5) * 6;
    }

    const bodyG = svgEl("g", { transform: "translate(0," + (hipDropY + breathe * 0.4) + ") rotate(" + torsoLeanDeg + ")" }, g);

    // Legs (with trousers)
    const pantColor = profile.outfit === "OFFICE_SUIT" ? "#0F172A" : "#1E293B";
    svgEl("path", {
      d: "M-24,40 L" + (-28 - stepOffset) + ",188",
      stroke: pantColor, "stroke-width": "32", "stroke-linecap": "round"
    }, bodyG);
    svgEl("path", {
      d: "M24,40 L" + (28 + stepOffset) + ",188",
      stroke: pantColor, "stroke-width": "32", "stroke-linecap": "round"
    }, bodyG);
    // Shoes
    svgEl("ellipse", { cx: -28 - stepOffset + 8, cy: 190, rx: 24, ry: 11, fill: "#0B0E14" }, bodyG);
    svgEl("ellipse", { cx: 28 + stepOffset + 8, cy: 190, rx: 24, ry: 11, fill: "#0B0E14" }, bodyG);

    // Back Arm (Left arm in character space)
    const lShX = -44, lShY = -74;
    const armLen1 = 68, armLen2 = 60;
    const lRad1 = (lShoulderDeg + 90) * Math.PI / 180;
    const lElX = lShX + Math.cos(lRad1) * armLen1;
    const lElY = lShY + Math.sin(lRad1) * armLen1;
    const lRad2 = (lShoulderDeg + lElbowDeg + 90) * Math.PI / 180;
    const lWrX = lElX + Math.cos(lRad2) * armLen2;
    const lWrY = lElY + Math.sin(lRad2) * armLen2;

    svgEl("line", { x1: lShX, y1: lShY, x2: lElX, y2: lElY, stroke: profile.outfitColor, "stroke-width": "24", "stroke-linecap": "round" }, bodyG);
    svgEl("line", { x1: lElX, y1: lElY, x2: lWrX, y2: lWrY, stroke: profile.skinColor, "stroke-width": "20", "stroke-linecap": "round" }, bodyG);
    svgEl("circle", { cx: lWrX, cy: lWrY, r: 14, fill: profile.skinColor }, bodyG);

    // Torso (Shaded 2D outfit with rim highlight)
    svgEl("rect", {
      x: -52, y: -96, width: 104, height: 148, rx: 32,
      fill: profile.outfitColor, stroke: "#0B0E14", "stroke-width": "5"
    }, bodyG);
    // Shading highlight on torso edge
    svgEl("path", {
      d: "M-44,-82 Q-46,-20 -44,36",
      stroke: "#FFFFFF", "stroke-width": "5", "stroke-linecap": "round", opacity: "0.22", fill: "none"
    }, bodyG);
    if (profile.outfit === "OFFICE_SUIT") {
      svgEl("polygon", { points: "-16,-94 16,-94 0,-26", fill: "#F8FAFC" }, bodyG);
      svgEl("polygon", { points: "-6,-88 6,-88 8,-28 0,-18 -8,-28", fill: "#EF4444" }, bodyG);
    } else if (profile.outfit === "MARKET_APRON") {
      svgEl("rect", { x: -34, y: -46, width: 68, height: 92, rx: 10, fill: "#F8FAFC", stroke: "#CBD5E1", "stroke-width": "3" }, bodyG);
    }

    // Head + Neck
    const headG = svgEl("g", { transform: "translate(0,-168)" }, bodyG);
    svgEl("rect", { x: -16, y: 54, width: 32, height: 32, rx: 8, fill: profile.skinShadow }, headG);

    // Hair Back Layer for female / longer hairstyles
    if (profile.gender === "FEMALE") {
      svgEl("ellipse", { cx: -6, cy: 14, rx: 74, ry: 78, fill: profile.hairColor }, headG);
    }

    // Head Sphere with cel shading
    svgEl("ellipse", { cx: 0, cy: 0, rx: 64, ry: 68, fill: profile.skinColor, stroke: "#0B0E14", "stroke-width": "5" }, headG);
    // Rim light arc
    svgEl("path", { d: "M-52,-22 A 54 58 0 0 1 12,-58", fill: "none", stroke: "#FFFFFF", "stroke-width": "6", "stroke-linecap": "round", opacity: "0.35" }, headG);

    // Hair Crown
    if (profile.gender === "FEMALE") {
      svgEl("path", { d: "M-64,-12 Q-62,-76 0,-76 Q62,-76 64,-12 Q24,-40 -16,-36 Q-46,-30 -64,-12 Z", fill: profile.hairColor, stroke: "#0B0E14", "stroke-width": "4" }, headG);
    } else {
      svgEl("path", { d: "M-62,-18 Q-58,-78 4,-78 Q62,-76 62,-22 Q30,-44 -6,-42 Q-42,-42 -62,-18 Z", fill: profile.hairColor, stroke: "#0B0E14", "stroke-width": "4" }, headG);
    }

    // Eyes (3/4 perspective offset toward right)
    const eyeH = isBlinking ? 2 : (expr === "SHOCKED_WIDE_EYES" ? 15 : 11);
    svgEl("ellipse", { cx: 14, cy: -8, rx: 9, ry: eyeH, fill: "#0F172A" }, headG);
    svgEl("ellipse", { cx: 44, cy: -8, rx: 8, ry: eyeH, fill: "#0F172A" }, headG);
    if (!isBlinking) {
      svgEl("circle", { cx: 17, cy: -11, r: 3.2, fill: "#FFFFFF" }, headG);
      svgEl("circle", { cx: 47, cy: -11, r: 2.8, fill: "#FFFFFF" }, headG);
    }

    // Expressive Eyebrows
    const browTilt = expr === "ANGRY_EVIL" ? 7 : (expr === "SHOCKED_WIDE_EYES" || expr === "PANIC_SWEAT_FIRE" ? -8 : -2);
    svgEl("line", { x1: 2, y1: -26 + browTilt, x2: 26, y2: -28 - browTilt * 0.4, stroke: "#0F172A", "stroke-width": "5", "stroke-linecap": "round" }, headG);
    svgEl("line", { x1: 35, y1: -28 - browTilt * 0.4, x2: 56, y2: -26 + browTilt, stroke: "#0F172A", "stroke-width": "5", "stroke-linecap": "round" }, headG);

    // Nose & Lip-synced Mouth
    svgEl("path", { d: "M34,-4 Q42,8 33,12", fill: "none", stroke: profile.skinShadow, "stroke-width": "4", "stroke-linecap": "round" }, headG);
    const mouthOpen = 4 + talkCycle * 11;
    if (expr === "SHOCKED_WIDE_EYES" || expr === "PANIC_SWEAT_FIRE") {
      svgEl("ellipse", { cx: 28, cy: 30, rx: 12, ry: 14, fill: "#7F1D1D", stroke: "#0B0E14", "stroke-width": "3.5" }, headG);
    } else {
      svgEl("path", {
        d: "M12,26 Q28," + (26 + mouthOpen * 1.4) + " 44,24 Q28," + (24 + mouthOpen * 0.35) + " 12,26 Z",
        fill: "#991B1B", stroke: "#0B0E14", "stroke-width": "3.5"
      }, headG);
    }

    // Front Arm (Right arm in character space) + EXACT WRIST-ANCHORED HAND PROP
    const rShX = 44, rShY = -74;
    const rRad1 = (rShoulderDeg + 90) * Math.PI / 180;
    const rElX = rShX + Math.cos(rRad1) * armLen1;
    const rElY = rShY + Math.sin(rRad1) * armLen1;
    const rRad2 = (rShoulderDeg + rElbowDeg + 90) * Math.PI / 180;
    const rWrX = rElX + Math.cos(rRad2) * armLen2;
    const rWrY = rElY + Math.sin(rRad2) * armLen2;

    svgEl("line", { x1: rShX, y1: rShY, x2: rElX, y2: rElY, stroke: profile.outfitColor, "stroke-width": "24", "stroke-linecap": "round" }, bodyG);
    svgEl("line", { x1: rElX, y1: rElY, x2: rWrX, y2: rWrY, stroke: profile.skinColor, "stroke-width": "20", "stroke-linecap": "round" }, bodyG);

    if (isPaper) {
      // Brass split-pin pivot brads at shoulder and elbow
      svgEl("circle", { cx: rShX, cy: rShY, r: 7, fill: "#FBBF24", stroke: "#78350F", "stroke-width": "2.5" }, bodyG);
      svgEl("circle", { cx: rElX, cy: rElY, r: 6, fill: "#FBBF24", stroke: "#78350F", "stroke-width": "2.5" }, bodyG);
    }

    // Strict Wrist Anchor Group for Hand Prop + Hand Palm
    const wristG = svgEl("g", { transform: "translate(" + rWrX + "," + rWrY + ")" }, bodyG);
    drawHandPropSvg(handProp, wristG);
    svgEl("circle", { cx: 0, cy: 0, r: 14, fill: profile.skinColor, stroke: "#0B0E14", "stroke-width": "3.5" }, wristG);
  }

  function renderFullSceneFocalProp(scene, sceneElapsed, tSec) {
    // Never draw anything generic on top of bespoke SVG or 2D flat vector backdrop artwork
    if (scene.customSvg || scene.backdropUri) return;

    const bob = Math.sin(tSec * 2.2) * 8;
    const enterSpring = springOvershoot(clamp(sceneElapsed / 0.48, 0, 1));

    if (scene.theme === "BANK_VAULT_FINANCE" || scene.handProp === "MONEY_BAG") {
      const fg = svgEl("g", {
        id: "full-scene-focal",
        transform: "translate(540," + (830 + bob).toFixed(1) + ") scale(" + (1.15 * enterSpring).toFixed(4) + ")",
        class: PRESET === "PAPER_DIORAMA" ? "paper-shadow" : ""
      }, elCast);
      svgEl("ellipse", { cx: 0, cy: 175, rx: 190, ry: 32, fill: "#020617", opacity: "0.48" }, fg);
      svgEl("rect", { x: -135, y: -95, width: 270, height: 240, rx: 28, fill: "#0F766E", stroke: "#141821", "stroke-width": "5" }, fg);
      svgEl("circle", { cx: 0, cy: 25, r: 72, fill: "#FACC15", stroke: "#141821", "stroke-width": "5" }, fg);
      svgEl("circle", { cx: 0, cy: 25, r: 44, fill: "#FEF08A", stroke: "#B45309", "stroke-width": "3.5" }, fg);
      const pt = svgEl("text", { x: 0, y: 42, "text-anchor": "middle", fill: "#78350F", "font-size": "52", class: "title-card-txt" }, fg);
      pt.textContent = "₱";
    } else if (scene.theme === "MODERN_OFFICE_DESK" || scene.handProp === "PHONE_SCREEN") {
      const fg = svgEl("g", {
        id: "full-scene-focal",
        transform: "translate(540," + (830 + bob).toFixed(1) + ") scale(" + (1.15 * enterSpring).toFixed(4) + ")",
        class: PRESET === "PAPER_DIORAMA" ? "paper-shadow" : ""
      }, elCast);
      svgEl("ellipse", { cx: 0, cy: 180, rx: 210, ry: 30, fill: "#020617", opacity: "0.45" }, fg);
      svgEl("rect", { x: -175, y: -125, width: 350, height: 225, rx: 18, fill: "#0F172A", stroke: "#38BDF8", "stroke-width": "5" }, fg);
      svgEl("rect", { x: -152, y: -102, width: 304, height: 178, rx: 10, fill: "#1E293B" }, fg);
      const barH = [65, 95, 130, 108, 148];
      for (let b = 0; b < 5; b++) {
        const pulse = 1 + 0.08 * Math.sin(tSec * 4 + b);
        const bh = barH[b] * pulse;
        svgEl("rect", {
          x: -120 + b * 54, y: 56 - bh, width: 34, height: bh, rx: 6,
          fill: b === 4 ? "#2DD4BF" : "#38BDF8"
        }, fg);
      }
      svgEl("rect", { x: -34, y: 100, width: 68, height: 42, fill: "#475569" }, fg);
      svgEl("rect", { x: -95, y: 138, width: 190, height: 18, rx: 9, fill: "#64748B" }, fg);
    }
    // Zero generic Play-button fallback in center screen.
  }

  function renderCast(scene, sceneElapsed, tSec) {
    clearNode(elCast);
    // Strict No-Default-Host Rule: only draw characters if story explicitly requires one
    if (!scene.hasCharacter) {
      renderFullSceneFocalProp(scene, sceneElapsed, tSec);
      return;
    }

    const isPaper = PRESET === "PAPER_DIORAMA";
    const mainProf = PROFILES[scene.actorRole] || PROFILES["HERO"] || {
      gender: "MALE", age: "YOUNG_ADULT", outfit: "HOODIE",
      outfitColor: "#2563EB", hairColor: "#1E293B", skinColor: "#EAC096", skinShadow: "#C9966B"
    };

    const enterT = easeOutExpoSnap(clamp(sceneElapsed / 0.45, 0, 1));
    let charX = 540;
    let charY = 1045;
    let charScale = 1.62;
    let facing = 1;

    switch (scene.shotFraming) {
      case "CLOSE_UP_FACE":
        charX = 540; charY = 1140; charScale = 2.25; break;
      case "MEDIUM_WAIST_UP":
        charX = 540; charY = 1085; charScale = 1.85; break;
      case "RULE_OF_THIRDS_LEFT":
        charX = 355; charY = 1055; charScale = 1.66; facing = 1; break;
      case "RULE_OF_THIRDS_RIGHT":
        charX = 725; charY = 1055; charScale = 1.66; facing = -1; break;
      default:
        charX = 540; charY = 1045; charScale = 1.56; break;
    }

    if (scene.characterPose === "WALK_ACROSS_LEFT_TO_RIGHT") {
      charX = lerp(charX - 180, charX + 40, clamp(sceneElapsed / 2.2, 0, 1));
    } else {
      charY += lerp(36, 0, enterT);
    }

    if (scene.secondaryRole && PROFILES[scene.secondaryRole]) {
      const partnerProf = PROFILES[scene.secondaryRole];
      drawVectorCharacter(elCast, partnerProf, {
        baseX: 770, baseY: 1050, scale: 1.48, facing: -1,
        pose: "THREE_QUARTER_EXPLAIN", expr: "HAPPY_TALK", handProp: "NONE",
        tSec: tSec + 0.5, isPaper: isPaper
      });
      charX = 310;
      charScale = 1.52;
      facing = 1;
    }

    drawVectorCharacter(elCast, mainProf, {
      baseX: charX, baseY: charY, scale: charScale, facing: facing,
      pose: scene.characterPose, expr: scene.expression, handProp: scene.handProp,
      tSec: tSec, isPaper: isPaper
    });
  }

  function renderWorldFx(scene, tSec) {
    clearNode(elFx);
    if (scene.lightingState === "RAIN_STORM") {
      for (let r = 0; r < 16; r++) {
        const rf = (tSec * 1.8 + r * 0.19) % 1;
        const rx = 80 + (r * 63) % 940 - rf * 60;
        const ry = 180 + rf * 1120;
        svgEl("line", { x1: rx, y1: ry, x2: rx - 12, y2: ry + 24, stroke: "#7DD3FC", "stroke-width": "3", opacity: "0.55" }, elFx);
      }
    }
    if (scene.worldFx === "COIN_RAIN") {
      for (let i = 0; i < 6; i++) {
        const cx = 150 + i * 155;
        const cy = ((tSec * 220 + i * 190) % 1300) + 120;
        svgEl("circle", { cx: cx, cy: cy, r: 22, fill: "#FACC15", stroke: "#B45309", "stroke-width": "4", opacity: "0.75" }, elFx);
        const t = svgEl("text", { x: cx, y: cy + 8, "text-anchor": "middle", fill: "#78350F", "font-size": "22", class: "diagram-txt" }, elFx);
        t.textContent = "₱";
      }
    } else if (scene.worldFx === "FIRE_SPARKS") {
      for (let i = 0; i < 8; i++) {
        const prog = (tSec * 0.55 + i * 0.14) % 1;
        const sx = 180 + (i * 112) % 720 + Math.sin(tSec * 4 + i) * 18;
        const sy = 1280 - prog * 680;
        svgEl("circle", { cx: sx, cy: sy, r: 8 * (1 - prog * 0.6), fill: i % 2 === 0 ? "#FB923C" : "#FDE047", opacity: (1 - prog) * 0.78 }, elFx);
      }
    }
  }

  function applyCameraMotion(scene, sceneElapsed, sceneDur, tSec) {
    const p = clamp(sceneElapsed / Math.max(0.5, sceneDur), 0, 1);
    const ep = easeInOut(p);

    // 2.5s Retention Sub-Beat (1.04x subtle emphasis punch-in) + ±3px continuous camera drift
    const beatT = sceneElapsed % 2.5;
    const subBeatZoom = beatT < 0.38 ? 1 + 0.04 * Math.sin((beatT / 0.38) * Math.PI) : 1.0;
    const driftX = Math.sin(tSec * 1.1) * 3.0;
    const driftY = Math.cos(tSec * 0.85) * 3.0;

    let scale = 1.0, tx = driftX, ty = driftY;
    switch (scene.cameraMotion) {
      case "ZOOM_IN": scale = lerp(1.0, 1.10, ep); break;
      case "ZOOM_OUT": scale = lerp(1.11, 1.0, ep); break;
      case "PAN_LEFT": scale = 1.06; tx += lerp(34, -34, ep); break;
      case "PAN_RIGHT": scale = 1.06; tx += lerp(-34, 34, ep); break;
      case "WHIP_PAN_LEFT": scale = 1.06; tx += lerp(110, 0, easeOutExpoSnap(clamp(sceneElapsed / 0.45, 0, 1))); break;
      case "WHIP_PAN_RIGHT": scale = 1.06; tx += lerp(-110, 0, easeOutExpoSnap(clamp(sceneElapsed / 0.45, 0, 1))); break;
      case "IMPACT_SHAKE":
      case "SHAKE":
        scale = 1.05;
        const decay = Math.max(0, 1 - sceneElapsed / 0.75);
        tx += Math.sin(tSec * 44) * 16 * decay;
        ty += Math.cos(tSec * 38) * 12 * decay;
        break;
      default:
        scale = lerp(1.01, 1.05, ep);
        break;
    }
    scale *= subBeatZoom;
    elCamera.setAttribute(
      "transform",
      "translate(540,960) scale(" + scale.toFixed(4) + ") translate(" + (-540 + tx).toFixed(2) + "," + (-960 + ty).toFixed(2) + ")"
    );
    // Subtle multi-plane parallax on far/mid layers
    elBgFar.setAttribute("transform", "translate(" + (tx * 0.25).toFixed(2) + "," + (ty * 0.25).toFixed(2) + ")");
  }

  function animateDustMotes(tSec) {
    for (let i = 0; i < 10; i++) {
      const m = document.getElementById("dust-mote-" + i);
      if (!m) continue;
      // Keep dust motes strictly within the diagonal beam cone
      const lane = (i % 5) / 4;
      const frac = ((tSec * (0.04 + (i % 3) * 0.015)) + i * 0.11) % 1;
      const topX = lerp(705, 935, lane);
      const botX = lerp(120, 580, lane);
      const x = lerp(topX, botX, frac) + Math.sin(tSec * 1.4 + i) * 10;
      const y = lerp(160, 1380, frac);
      m.setAttribute("cx", x.toFixed(1));
      m.setAttribute("cy", y.toFixed(1));
    }
  }

  function renderViralCaptions(scene, tSec) {
    clearNode(elCaption);
    const captions = scene.captions || [];
    if (captions.length === 0) return;

    let active = captions[0];
    for (let i = 0; i < captions.length; i++) {
      if (tSec >= captions[i].startSec) active = captions[i];
    }

    const popElapsed = Math.max(0, tSec - active.startSec);
    const popFrac = clamp(popElapsed / 0.12, 0, 1);
    const popScale = lerp(1.20, 1.0, easeOutExpoSnap(popFrac));

    const chunkG = svgEl("g", { transform: "scale(" + popScale.toFixed(4) + ")" }, elCaption);
    const tokens = active.tokens || [];
    const txt = svgEl("text", { x: 0, y: 0, "text-anchor": "middle", class: "cap-text" }, chunkG);

    for (let i = 0; i < tokens.length; i++) {
      const tok = tokens[i];
      const fill = tok.tone === "DANGER" ? "#FF453A" : (tok.tone === "ACCENT" ? "#FFD60A" : "#FFFFFF");
      const span = svgEl("tspan", { fill: fill }, txt);
      span.textContent = (i > 0 ? " " : "") + tok.text;
    }
  }

  window.seekToTime = function(seconds) {
    if (SCENES.length === 0) return "empty";
    const tSec = Math.max(0, Number(seconds) || 0);
    window.__toonforgeCurrentSec = tSec;

    let idx = 0;
    for (let i = 0; i < SCENES.length; i++) {
      if (tSec >= SCENES[i].startSec) idx = i;
    }
    const scene = SCENES[idx];
    const sceneElapsed = Math.max(0, tSec - scene.startSec);
    const sceneDur = Math.max(0.5, scene.endSec - scene.startSec);

    if (idx !== lastSceneIdx) {
      renderEnvironment(scene);
      lastSceneIdx = idx;
    }

    applyCameraMotion(scene, sceneElapsed, sceneDur, tSec);
    animateDustMotes(tSec);
    renderAnimatedFlowOverlay(scene, sceneElapsed, tSec);
    renderWorldFx(scene, tSec);
    renderCast(scene, sceneElapsed, tSec);
    renderHudAndDiagrams(scene, sceneElapsed, sceneDur, tSec);
    renderViralCaptions(scene, tSec);
    elVignette.setAttribute("opacity", PRESET === "ATMOSPHERIC_2D" ? "0.85" : "0.45");
    return "ok";
  };

  window.__toonforgeReady = true;
  window.seekToTime(0);
})();
</script>
</body>
</html>
        """.trimIndent()
    }

    private fun inferEnvironmentTheme(scene: SceneScript, index: Int): EnvironmentTheme {
        val text = "${scene.tagalogNarration} ${scene.visualDescription}".lowercase(Locale.US)
        return when {
            listOf("gabi", "tulog", "higaan", "kwarto", "midnight", "alas-tres").any { text.contains(it) } ->
                EnvironmentTheme.COZY_BEDROOM_MIDNIGHT
            listOf("sahod", "ipon", "banko", "piso", "pera", "vault").any { text.contains(it) } ->
                EnvironmentTheme.BANK_VAULT_FINANCE
            listOf("opisina", "trabaho", "laptop", "meeting", "desk").any { text.contains(it) } ->
                EnvironmentTheme.MODERN_OFFICE_DESK
            listOf("kape", "kusina", "umaga", "almusal").any { text.contains(it) } ->
                EnvironmentTheme.BRIGHT_MORNING_KITCHEN
            listOf("palengke", "tinda", "tindahan", "jeep", "kalye").any { text.contains(it) } ->
                EnvironmentTheme.SUNNY_MARKET_STREET
            listOf("paaralan", "aral", "klase", "teacher", "eskwela").any { text.contains(it) } ->
                EnvironmentTheme.CLASSROOM_CHALKBOARD
            listOf("ospital", "doktor", "nurse", "clinic", "sakit").any { text.contains(it) } ->
                EnvironmentTheme.HOSPITAL_CLINIC
            index % 2 == 0 -> EnvironmentTheme.DRAMATIC_SUNSET_CITY
            else -> EnvironmentTheme.COZY_BEDROOM_MIDNIGHT
        }
    }

    private fun buildSceneTitleCard(scene: SceneScript, index: Int): String {
        if (scene.calloutBanner.isNotBlank() && index == 0) return scene.calloutBanner.uppercase(Locale.US)
        if (index == 0) {
            val words = scene.tagalogNarration.trim()
                .split(' ')
                .filter { it.isNotBlank() }
                .take(4)
                .joinToString(" ")
                .uppercase(Locale.US)
            return words
        }
        return ""
    }

    private fun buildSpeechBubbleText(scene: SceneScript): String {
        val candidate = scene.topicPillLabel.ifBlank { scene.calloutBanner }.trim()
        if (candidate.isNotBlank() && candidate.length <= 24) return candidate.uppercase(Locale.US)
        return ""
    }

    private fun sanitizeHex(raw: String?, fallback: String): String {
        val trimmed = raw.orEmpty().trim()
        if (trimmed.length == 7 && trimmed.startsWith("#")) {
            val valid = trimmed.drop(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            if (valid) return trimmed.uppercase(Locale.US)
        }
        return fallback
    }

    private fun loadCutawayTemplateHtml(context: Context?): String? {
        if (context != null) {
            runCatching {
                context.assets.open("templates/CutawayExplainerTemplate.html")
                    .bufferedReader(Charsets.UTF_8)
                    .use { return it.readText() }
            }
        }
        // Fallback for JVM unit tests running from module or root working directory
        val candidates = listOf(
            File("src/main/assets/templates/CutawayExplainerTemplate.html"),
            File("app/src/main/assets/templates/CutawayExplainerTemplate.html")
        )
        for (file in candidates) {
            if (file.exists() && file.isFile) {
                runCatching { return file.readText(Charsets.UTF_8) }
            }
        }
        return null
    }

    private fun encodeBitmapToDataUri(bmp: Bitmap): String {
        val scaled = if (bmp.width > 540 || bmp.height > 960) {
            Bitmap.createScaledBitmap(bmp, 540, 960, true)
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 78, out)
        if (scaled !== bmp) scaled.recycle()
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }
}
