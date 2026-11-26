package com.example.render

import android.graphics.Bitmap
import android.util.Base64
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
import com.example.model.SceneScript
import com.example.model.ShotFraming
import com.example.model.StatusPill
import com.example.model.TimelineScene
import com.example.model.WordTiming
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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
    KINETIC_DIAGRAM
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
        AnimationStyle.TECH_EXPLAINER,
        AnimationStyle.CROSS_SECTION,
        AnimationStyle.PIXEL_CUTAWAY_EXPLAINER -> MotionPresetEngine.KINETIC_DIAGRAM
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
        includeAiBackdrops: Boolean = false
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
                        put("hudFrom", sc.hudFromValue)
                        put("hudTo", sc.hudToValue)
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
    <radialGradient id="vignette-grad" cx="50%" cy="46%" r="65%">
      <stop offset="48%" stop-color="#000000" stop-opacity="0" />
      <stop offset="100%" stop-color="#04060D" stop-opacity="0.64" />
    </radialGradient>
    <filter id="paper-grain" x="0%" y="0%" width="100%" height="100%">
      <feTurbulence type="fractalNoise" baseFrequency="0.65" numOctaves="2" result="noise" />
      <feColorMatrix type="matrix" values="0 0 0 0 0.95  0 0 0 0 0.92  0 0 0 0 0.85  0 0 0 0.06 0" in="noise" result="tint" />
      <feComposite in="SourceGraphic" in2="tint" operator="over" />
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
      <image id="ai-backdrop-img" x="-80" y="-120" width="1240" height="2160" preserveAspectRatio="xMidYMid slice" opacity="0" />
      <g id="bg-far"></g>
      <g id="bg-mid"></g>
      <g id="bg-props"></g>
      <g id="atmospheric-beams"></g>
      <g id="world-fx-layer"></g>
      <g id="cast-layer"></g>
      <g id="fg-silhouette"></g>
    </g>

    <!-- Non-face-overlapping Motion Graphics, Kinetic Cards, Speech Bubbles & Diagrams -->
    <g id="kinetic-hud-layer"></g>
    <g id="speech-bubble-layer"></g>

    <!-- Atmospheric Vignette -->
    <rect id="vignette-rect" x="0" y="0" width="1080" height="1920" fill="url(#vignette-grad)" pointer-events="none" />

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
  const elBeams = document.getElementById("atmospheric-beams");
  const elFx = document.getElementById("world-fx-layer");
  const elCast = document.getElementById("cast-layer");
  const elFg = document.getElementById("fg-silhouette");
  const elHud = document.getElementById("kinetic-hud-layer");
  const elSpeech = document.getElementById("speech-bubble-layer");
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

  function renderEnvironment(scene) {
    clearNode(elBgFar);
    clearNode(elBgMid);
    clearNode(elBgProps);
    clearNode(elBeams);
    clearNode(elFg);

    const pal = themePalette(scene.theme);

    if (scene.backdropUri) {
      elBackdropImg.setAttribute("href", scene.backdropUri);
      elBackdropImg.setAttribute("opacity", "0.42");
    } else {
      elBackdropImg.setAttribute("opacity", "0");
    }

    // 1. Far plane sky / backdrop
    svgEl("rect", { x: -160, y: -160, width: 1400, height: 2240, fill: pal.sky, opacity: scene.backdropUri ? "0.45" : "1" }, elBgFar);

    if (PRESET === "KINETIC_DIAGRAM") {
      // Swiss architectural grid
      for (let gx = 0; gx <= 1080; gx += 120) {
        svgEl("line", { x1: gx, y1: 0, x2: gx, y2: 1920, stroke: "#38BDF8", "stroke-width": 1.5, opacity: 0.10 }, elBgFar);
      }
      for (let gy = 0; gy <= 1920; gy += 120) {
        svgEl("line", { x1: 0, y1: gy, x2: 1080, y2: gy, stroke: "#38BDF8", "stroke-width": 1.5, opacity: 0.10 }, elBgFar);
      }
      svgEl("circle", { cx: 540, cy: 760, r: 420, fill: "none", stroke: pal.accent, "stroke-width": 2, opacity: 0.16, "stroke-dasharray": "12 10" }, elBgFar);
    }

    // 2. Mid plane architectural room / 2.5D faceted hills
    if (PRESET === "STYLIZED_25D") {
      // Layered low-poly faceted hills + perspective road
      svgEl("polygon", { points: "-160,1180 240,860 640,1040 1240,820 1240,1520 -160,1520", fill: pal.wall2, opacity: "0.88" }, elBgMid);
      svgEl("polygon", { points: "-160,1260 360,980 780,1160 1240,940 1240,1580 -160,1580", fill: pal.wall, opacity: "0.94" }, elBgMid);
      // Perspective ground + road
      svgEl("rect", { x: -160, y: 1240, width: 1400, height: 840, fill: pal.floor }, elBgMid);
      svgEl("polygon", { points: "470,1240 610,1240 940,1920 140,1920", fill: "#1E293B", opacity: "0.85" }, elBgMid);
      svgEl("line", { x1: 540, y1: 1250, x2: 540, y2: 1920, stroke: "#FACC15", "stroke-width": 8, "stroke-dasharray": "42 28", opacity: "0.7" }, elBgMid);
    } else {
      // Rich interior/exterior architectural backdrop + floor plane
      const filterAttr = PRESET === "PAPER_DIORAMA" ? { filter: "url(#paper-grain)" } : {};
      const roomGrp = svgEl("g", filterAttr, elBgMid);
      svgEl("rect", { x: -140, y: -120, width: 1360, height: 1400, fill: pal.wall, opacity: scene.backdropUri ? "0.35" : "0.94" }, roomGrp);
      // Architectural paneling & trim
      svgEl("rect", { x: -140, y: 1240, width: 1360, height: 800, fill: pal.floor }, roomGrp);
      svgEl("rect", { x: -140, y: 1232, width: 1360, height: 18, fill: pal.wall2 }, roomGrp);

      // Theme-specific rich SVG room details (zero random confetti)
      renderThemeDetails(scene.theme, pal, elBgProps);
    }

    // 3. Atmospheric volumetric light shafts & dust motes (strictly inside beam polygon)
    if (PRESET === "ATMOSPHERIC_2D" || PRESET === "PAPER_DIORAMA") {
      svgEl("polygon", {
        points: "690,120 960,120 620,1480 80,1480",
        fill: pal.beam,
        opacity: PRESET === "ATMOSPHERIC_2D" ? "0.95" : "0.45"
      }, elBeams);
      svgEl("polygon", {
        points: "120,0 310,0 590,1420 260,1420",
        fill: pal.beam,
        opacity: "0.35"
      }, elBeams);

      // 10 subtle deterministic dust motes strictly confined along the diagonal shaft axis
      for (let i = 0; i < 10; i++) {
        svgEl("circle", {
          id: "dust-mote-" + i,
          cx: 420 + i * 32,
          cy: 340 + i * 88,
          r: 3 + (i % 3) * 1.6,
          fill: "#FFFBEB",
          opacity: "0.55"
        }, elBeams);
      }
    }

    // 4. Foreground depth silhouettes (out-of-focus framing edge)
    svgEl("path", {
      d: "M-80,1920 L-80,1520 Q40,1560 95,1920 Z",
      fill: "#05070E",
      opacity: "0.52"
    }, elFg);
    svgEl("path", {
      d: "M1160,1920 L1160,1560 Q1010,1620 970,1920 Z",
      fill: "#05070E",
      opacity: "0.46"
    }, elFg);
  }

  function renderThemeDetails(theme, pal, parent) {
    const shadowClass = PRESET === "PAPER_DIORAMA" ? "paper-shadow" : "";
    if (theme === "COZY_BEDROOM_MIDNIGHT" || theme === "MODERN_OFFICE_DESK") {
      // Shaded window with moon/sky & blinds + desk
      const win = svgEl("g", { class: shadowClass }, parent);
      svgEl("rect", { x: 640, y: 170, width: 290, height: 360, rx: 14, fill: pal.isNight ? "#0B162C" : "#7DD3FC", stroke: pal.wall2, "stroke-width": 16 }, win);
      svgEl("line", { x1: 785, y1: 170, x2: 785, y2: 530, stroke: pal.wall2, "stroke-width": 12 }, win);
      svgEl("line", { x1: 640, y1: 350, x2: 930, y2: 350, stroke: pal.wall2, "stroke-width": 12 }, win);
      if (pal.isNight) {
        svgEl("circle", { cx: 855, cy: 250, r: 34, fill: "#FEF08A" }, win);
      }
      // Desk in midground
      svgEl("rect", { x: 110, y: 1090, width: 860, height: 34, rx: 8, fill: "#78350F" }, win);
      svgEl("rect", { x: 150, y: 1124, width: 32, height: 120, fill: "#451A03" }, win);
      svgEl("rect", { x: 898, y: 1124, width: 32, height: 120, fill: "#451A03" }, win);
      // Warm desk lamp
      svgEl("rect", { x: 830, y: 1005, width: 12, height: 85, fill: "#CBD5E1" }, win);
      svgEl("polygon", { points: "795,1005 875,1005 855,955 815,955", fill: pal.accent }, win);
    } else if (theme === "SUNNY_MARKET_STREET" || theme === "DRAMATIC_SUNSET_CITY") {
      // Stylized architectural silhouettes + market awning
      const street = svgEl("g", { class: shadowClass }, parent);
      svgEl("rect", { x: 60, y: 380, width: 210, height: 860, fill: pal.wall2, opacity: "0.7" }, street);
      svgEl("rect", { x: 760, y: 310, width: 250, height: 930, fill: pal.wall2, opacity: "0.78" }, street);
      // Warm glowing shop windows
      for (let wy = 440; wy < 900; wy += 120) {
        svgEl("rect", { x: 800, y: wy, width: 65, height: 60, rx: 6, fill: pal.accent, opacity: "0.65" }, street);
        svgEl("rect", { x: 900, y: wy, width: 65, height: 60, rx: 6, fill: pal.accent, opacity: "0.45" }, street);
      }
      // Sari-sari / market awning
      svgEl("polygon", { points: "40,860 390,860 430,940 20,940", fill: "#EF4444" }, street);
      svgEl("rect", { x: 50, y: 940, width: 340, height: 300, fill: "#1E293B" }, street);
    } else if (theme === "BANK_VAULT_FINANCE") {
      const vault = svgEl("g", { class: shadowClass }, parent);
      svgEl("circle", { cx: 810, cy: 520, r: 210, fill: "#115E59", stroke: "#FACC15", "stroke-width": 16, opacity: "0.65" }, vault);
      svgEl("circle", { cx: 810, cy: 520, r: 120, fill: "none", stroke: "#FDE047", "stroke-width": 10, "stroke-dasharray": "28 18", opacity: "0.7" }, vault);
      svgEl("line", { x1: 810, y1: 420, x2: 810, y2: 620, stroke: "#FACC15", "stroke-width": 14, "stroke-linecap": "round" }, vault);
      svgEl("line", { x1: 710, y1: 520, x2: 910, y2: 520, stroke: "#FACC15", "stroke-width": 14, "stroke-linecap": "round" }, vault);
    } else if (theme === "CLASSROOM_CHALKBOARD") {
      const board = svgEl("g", { class: shadowClass }, parent);
      svgEl("rect", { x: 130, y: 310, width: 820, height: 420, rx: 14, fill: "#113026", stroke: "#854D0E", "stroke-width": 20 }, board);
      svgEl("rect", { x: 160, y: 710, width: 760, height: 18, fill: "#A16207" }, board);
    } else if (theme === "BRIGHT_MORNING_KITCHEN") {
      const kit = svgEl("g", { class: shadowClass }, parent);
      svgEl("rect", { x: 640, y: 260, width: 300, height: 320, rx: 12, fill: "#BAE6FD", stroke: "#FFFFFF", "stroke-width": 16 }, kit);
      svgEl("rect", { x: 80, y: 980, width: 920, height: 260, rx: 12, fill: "#D97706", opacity: "0.88" }, kit);
      svgEl("rect", { x: 60, y: 955, width: 960, height: 36, rx: 10, fill: "#F8FAFC" }, kit);
    } else {
      const arch = svgEl("g", { class: shadowClass }, parent);
      svgEl("rect", { x: 120, y: 260, width: 320, height: 460, rx: 20, fill: pal.wall2, opacity: "0.55" }, arch);
      svgEl("rect", { x: 640, y: 220, width: 320, height: 500, rx: 20, fill: pal.wall2, opacity: "0.45" }, arch);
    }
  }

  function renderHudAndDiagrams(scene, sceneElapsed, sceneDur) {
    clearNode(elHud);
    clearNode(elSpeech);

    // Reserved SAFE ZONES (never overlap character hair/face around y=560..820)
    // Top Kinetic Title Card or Status Pill (y: 115..265)
    const entryEase = easeOutExpoSnap(clamp(sceneElapsed / 0.42, 0, 1));
    const slideY = lerp(-40, 0, entryEase);

    if (scene.titleCard) {
      const cardG = svgEl("g", { transform: "translate(540," + (158 + slideY) + ") scale(" + lerp(0.88, 1.0, entryEase) + ")", opacity: entryEase }, elHud);
      const pillLabel = scene.statusPill !== "NONE" ? scene.statusPill : "";
      const cardW = Math.min(920, Math.max(460, scene.titleCard.length * 26 + 96));
      svgEl("rect", {
        x: -cardW / 2, y: -44, width: cardW, height: 88, rx: 22,
        fill: "#0B1120", "fill-opacity": "0.90",
        stroke: scene.statusPill === "PROBLEM" ? "#FF453A" : "#FFD60A",
        "stroke-width": "4",
        class: "paper-shadow"
      }, cardG);
      const titleTxt = svgEl("text", {
        x: 0, y: 12, "text-anchor": "middle",
        fill: scene.statusPill === "PROBLEM" ? "#FF6961" : "#FFD60A",
        "font-size": "36", class: "title-card-txt"
      }, cardG);
      titleTxt.textContent = (pillLabel ? "[" + pillLabel + "] " : "") + scene.titleCard;
    }

    // Topic / Callout bracket card in top-right or top-left (strictly y <= 345, above character head)
    if (scene.calloutBanner) {
      const badgeG = svgEl("g", { transform: "translate(540," + (258 + slideY * 0.6) + ")", opacity: entryEase }, elHud);
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

    // Pop-in Speech Bubble (placed horizontally opposite character's facing side, y=420..520, never on hair/face)
    if (scene.speechBubble) {
      const charOnRight = scene.shotFraming === "RULE_OF_THIRDS_RIGHT";
      const bx = charOnRight ? 330 : 750;
      const by = 435;
      const pop = easeOutExpoSnap(clamp((sceneElapsed - 0.15) / 0.35, 0, 1));
      if (pop > 0.02) {
        const sg = svgEl("g", { transform: "translate(" + bx + "," + by + ") scale(" + pop + ")", opacity: pop }, elSpeech);
        const bw = Math.min(440, Math.max(220, scene.speechBubble.length * 19 + 64));
        svgEl("rect", {
          x: -bw / 2, y: -44, width: bw, height: 88, rx: 24,
          fill: "#FFFFFF", stroke: "#0F172A", "stroke-width": "5",
          class: "paper-shadow"
        }, sg);
        // Tail pointing down toward shoulder/mouth side without touching head
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

    // Data-driven Kinetic Diagrams / HUD Gauges (placed cleanly in y=1115..1275 lower band, zero overlap on face!)
    const prog = easeOutExpoSnap(clamp(sceneElapsed / Math.min(1.6, sceneDur * 0.65), 0, 1));
    if (scene.hudWidget !== "NONE" || scene.diagramType !== "NONE") {
      const dg = svgEl("g", { transform: "translate(540, 1185)", opacity: entryEase }, elHud);
      svgEl("rect", {
        x: -430, y: -74, width: 860, height: 148, rx: 24,
        fill: "#090D16", "fill-opacity": "0.90",
        stroke: "#38BDF8", "stroke-width": "3",
        class: "paper-shadow"
      }, dg);

      if (scene.diagramType === "BEFORE_AFTER_SPLIT") {
        svgEl("line", { x1: 0, y1: -56, x2: 0, y2: 56, stroke: "#475569", "stroke-width": "3" }, dg);
        const lLbl = svgEl("text", { x: -215, y: 10, "text-anchor": "middle", fill: "#FF453A", "font-size": "32", class: "diagram-txt" }, dg);
        lLbl.textContent = scene.leftLabel || "BEFORE";
        const rLbl = svgEl("text", { x: 215, y: 10, "text-anchor": "middle", fill: "#34D399", "font-size": "32", class: "diagram-txt" }, dg);
        rLbl.textContent = scene.rightLabel || "AFTER";
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
        // Animated counter gauge (hudFrom -> hudTo)
        const val = Math.round(lerp(scene.hudFrom, scene.hudTo, prog));
        const lbl = svgEl("text", { x: -380, y: -18, fill: "#CBD5E1", "font-size": "26", class: "diagram-txt" }, dg);
        lbl.textContent = (scene.hudLabel || "PROGRESS").toUpperCase();
        const num = svgEl("text", { x: 380, y: -14, "text-anchor": "end", fill: "#FFD60A", "font-size": "40", class: "title-card-txt" }, dg);
        num.textContent = val + "%";
        svgEl("rect", { x: -380, y: 14, width: 760, height: 28, rx: 14, fill: "#1E293B" }, dg);
        svgEl("rect", { x: -380, y: 14, width: Math.max(28, 7.6 * clamp(val, 0, 100)), height: 28, rx: 14, fill: "#2DD4BF" }, dg);
      }
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

  function renderCast(scene, sceneElapsed, tSec) {
    clearNode(elCast);
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
    let scale = 1.0, tx = 0, ty = 0;
    switch (scene.cameraMotion) {
      case "ZOOM_IN": scale = lerp(1.0, 1.10, ep); break;
      case "ZOOM_OUT": scale = lerp(1.11, 1.0, ep); break;
      case "PAN_LEFT": scale = 1.06; tx = lerp(34, -34, ep); break;
      case "PAN_RIGHT": scale = 1.06; tx = lerp(-34, 34, ep); break;
      case "WHIP_PAN_LEFT": scale = 1.06; tx = lerp(110, 0, easeOutExpoSnap(clamp(sceneElapsed / 0.45, 0, 1))); break;
      case "WHIP_PAN_RIGHT": scale = 1.06; tx = lerp(-110, 0, easeOutExpoSnap(clamp(sceneElapsed / 0.45, 0, 1))); break;
      case "IMPACT_SHAKE":
      case "SHAKE":
        scale = 1.05;
        const decay = Math.max(0, 1 - sceneElapsed / 0.75);
        tx = Math.sin(tSec * 44) * 16 * decay;
        ty = Math.cos(tSec * 38) * 12 * decay;
        break;
      default:
        scale = lerp(1.01, 1.05, ep);
        break;
    }
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
    renderWorldFx(scene, tSec);
    renderCast(scene, sceneElapsed, tSec);
    renderHudAndDiagrams(scene, sceneElapsed, sceneDur);
    renderViralCaptions(scene, tSec);
    elVignette.setAttribute("opacity", PRESET === "ATMOSPHERIC_2D" ? "0.95" : "0.45");
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
