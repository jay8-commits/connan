package com.example

import com.example.art.EnvironmentRenderer
import com.example.art.MagicByteValidator
import com.example.data.BuiltInPrompts
import com.example.data.VideoGalleryRepository
import com.example.llm.DirectStoryboardParser
import com.example.llm.JsonSanitizer
import com.example.llm.MalformedSceneJsonException
import com.example.llm.TagalogNarrationValidator
import com.example.net.PublicDohFallbackDns
import com.example.net.SharedHttpClients
import com.example.tts.ElevenLabsClient
import com.example.tts.ElevenLabsHttpException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.net.UnknownHostException
import com.example.model.ActorAction
import com.example.model.ActorExpression
import com.example.model.ActorHeadgear
import com.example.model.ActorRole
import com.example.model.AnimationStyle
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
import com.example.model.MouthShape
import com.example.model.PointerCallout
import com.example.model.ProductionMode
import com.example.model.PronunciationEntry
import com.example.model.SceneScript
import com.example.model.ScriptSourceMode
import com.example.model.ShotFraming
import com.example.model.ShotType
import com.example.model.StatusPill
import com.example.model.StoryNiche
import com.example.model.StudioActorSpec
import com.example.model.StudioCameraSpec
import com.example.model.StudioSettings
import com.example.model.TimelineScene
import com.example.model.VideoAspectRatio
import com.example.model.WordTiming
import com.example.model.WorldFx
import com.example.model.ElevenLabsKeyEntry
import com.example.render.CameraController
import com.example.render.CharacterSpriteProcessor
import com.example.render.DeterministicVideoExporter
import com.example.render.DynamicDiagramRenderer
import com.example.render.KaraokeCaptionRenderer
import com.example.render.MetaphorVisualRenderer
import com.example.render.MotionHtmlDirector
import com.example.render.MotionPresetEngine
import com.example.render.ViralCaptionTone
import com.example.render.PixelCutawayRenderer
import com.example.render.ProceduralPuppetEngine
import com.example.render.PuppetSpringSolver
import com.example.render.StudioRendererBridge
import com.example.render.WebViewFrameRenderer
import com.example.service.RenderBatchItem
import com.example.service.VideoGenerationService
import com.example.tts.AudioPcmStitcher
import com.example.tts.ProceduralSfxCue
import com.example.tts.SfxTimelineMixer
import com.example.tts.TagalogPhoneticNormalizer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class ToonForgeCoreTest {

    @Test
    fun `jsonSanitizer strips think tags and markdown fences`() {
        val raw = """
            <think>
            Let me carefully plan the Tagalog scenes first...
            </think>
            ```json
            [
              {
                "scene_id": 1,
                "tagalog_narration": "Alam mo ba kung bakit laging ubos ang sahod mo?"
              }
            ]
            ```
        """.trimIndent()

        val extracted = JsonSanitizer.extractJsonArrayText(raw)
        assertTrue(extracted.startsWith("["))
        assertTrue(extracted.endsWith("]"))
        assertFalse(extracted.contains("<think>"))
        assertFalse(extracted.contains("```"))
    }

    @Test(expected = MalformedSceneJsonException::class)
    fun `jsonSanitizer rejects response without array brackets`() {
        JsonSanitizer.extractJsonArrayText("<think>only thoughts</think> no json here")
    }

    @Test
    fun `tagalogPhoneticNormalizer expands peso currency percentages and acronyms`() {
        val input = "Nakatipid ng ₱1,500 at 50% ang OFW gamit ang GCash budget."
        val out = TagalogPhoneticNormalizer.normalize(input)
        assertTrue(out.contains("isang libo limang daan piso"))
        assertTrue(out.contains("limampu porsyento"))
        assertTrue(out.contains("oh ef double-u"))
        assertTrue(out.contains("ji-kash"))
        assertTrue(out.contains("badyet"))

        // Pure English / non-Tagalog scripts skip Tagalog-only loanword/number/percent rewrites
        val englishInput = "In 2025, 50% of our budget goes to focus and salary."
        val englishOut = TagalogPhoneticNormalizer.normalize(englishInput)
        assertEquals(englishInput, englishOut)
        assertFalse(TagalogPhoneticNormalizer.hasTagalogContext(englishInput))
        assertTrue(TagalogPhoneticNormalizer.hasTagalogContext(input))
    }

    @Test
    fun `multilingualNarrationValidator accepts Tagalog English and other languages while cleaning cues`() {
        assertTrue(
            TagalogNarrationValidator.isValidNarration(
                "Huwag umasa sa iisang kita lang dahil mabilis magbago ang panahon."
            )
        )
        assertTrue(
            TagalogNarrationValidator.isValidTagalogNarration(
                "Nakakagulat pero ₱1,500 lang ang kailangan para magsimula ang OFW sa pag-iipon!"
            )
        )
        // Pure English & other languages (Japanese, Cyrillic, Spanish) are now accepted
        assertTrue(
            TagalogNarrationValidator.isValidNarration(
                "Never put every single coin inside one small pocket forever."
            )
        )
        assertTrue(
            TagalogNarrationValidator.isValidTagalogNarration(
                "English narration works cleanly now!"
            )
        )
        assertTrue(
            TagalogNarrationValidator.isValidNarration(
                "こんにちは、これはテストのナレーションです。"
            )
        )
        assertTrue(
            TagalogNarrationValidator.isValidNarration(
                "Привет мир, это тест!"
            )
        )
        assertFalse(TagalogNarrationValidator.isValidNarration("   "))
        assertFalse(TagalogNarrationValidator.isValidNarration("As an AI language model, here is a script."))

        // Cleans stray stage directions before TTS
        assertEquals(
            "Grabe talaga ang pagtitipid ng batang ito!",
            TagalogNarrationValidator.cleanForTts("Narrator: [excited] Grabe talaga ang pagtitipid ng batang ito!")
        )
    }

    @Test
    fun `magicByteValidator validates PNG JPEG WebP and rejects HTML`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        val webp = "RIFF1234WEBP".toByteArray(Charsets.US_ASCII)
        val html = "<html><body>401 Unauthorized</body></html>".toByteArray(Charsets.US_ASCII)

        assertEquals(MagicByteValidator.ImageFormat.PNG, MagicByteValidator.detectFormat(png))
        assertEquals(MagicByteValidator.ImageFormat.JPEG, MagicByteValidator.detectFormat(jpeg))
        assertEquals(MagicByteValidator.ImageFormat.WEBP, MagicByteValidator.detectFormat(webp))
        assertFalse(MagicByteValidator.isValidImageBytes(html))
    }

    @Test
    fun `builtInVoicePresetsAndPronunciationDictionaryRemainIntact`() {
        assertTrue(BuiltInPrompts.VOICE_PRESETS.isNotEmpty())
        assertEquals(ElevenLabsClient.DEFAULT_VOICE_ID, BuiltInPrompts.VOICE_PRESETS.first().id)
        assertTrue(BuiltInPrompts.DEFAULT_PRONUNCIATION_DICTIONARY.any { it.original == "GCash" })
    }

    @Test
    fun `techExplainerAndStudioProModelsMaintainSafeDefaults`() {
        assertEquals(12, AnimationStyle.entries.size)
        assertTrue(AnimationStyle.TECH_EXPLAINER.usesNetworkArt)
        assertEquals("HUD EXPLAINER", AnimationStyle.TECH_EXPLAINER.badge)
        assertEquals("Viral HUD & Motion Explainer", AnimationStyle.TECH_EXPLAINER.displayName)
        assertTrue(AnimationStyle.CROSS_SECTION.usesNetworkArt)
        assertEquals("CUTAWAY 2D", AnimationStyle.CROSS_SECTION.badge)

        assertEquals(3, ProductionMode.entries.size)
        assertEquals(ProductionMode.CLIP_AUTO_EDITOR, ProductionMode.fromRaw("clip_auto_editor"))
        assertEquals(ProductionMode.CROSS_SECTION_EXPLAINER, ProductionMode.fromRaw("CROSS_SECTION_EXPLAINER"))
        assertEquals(ProductionMode.FULL_AI_STORY, ProductionMode.fromRaw("invalid"))

        assertEquals(ShotFraming.MEDIUM_WAIST_UP, ShotFraming.fromRaw("medium_waist_up"))
        assertEquals(ShotFraming.CLOSE_UP_FACE, ShotFraming.fromRaw("close_up_face"))
        assertEquals(ShotFraming.RULE_OF_THIRDS_LEFT, ShotFraming.fromRaw("RULE_OF_THIRDS_LEFT"))
        assertEquals(ShotFraming.AUTO, ShotFraming.fromRaw("unknown"))

        assertEquals(StatusPill.GOAL, StatusPill.fromRaw("goal"))
        assertEquals(StatusPill.PROBLEM, StatusPill.fromRaw("PROBLEM"))
        assertEquals(StatusPill.NONE, StatusPill.fromRaw("unknown_value"))
        assertEquals(StatusPill.NONE, StatusPill.fromRaw(null))

        assertEquals(HudWidget.PROGRESS_GAUGE, HudWidget.fromRaw("progress_gauge"))
        assertEquals(HudWidget.WAVEFORM_GRAPH, HudWidget.fromRaw("WAVEFORM_GRAPH"))
        assertEquals(HudWidget.ANIMATED_CHECKLIST, HudWidget.fromRaw("ANIMATED_CHECKLIST"))
        assertEquals(HudWidget.NONE, HudWidget.fromRaw("invalid"))

        val scene = SceneScript(
            sceneId = 1,
            tagalogNarration = "Alam mo ba kung bakit mahalaga ang pag-iipon?",
            backgroundPrompt = "bg",
            cameraMotion = CameraMotion.STATIC,
            actorRole = ActorRole.HERO,
            actorAction = ActorAction.TALK_BOUNCE,
            worldFx = WorldFx.NONE
        )
        assertEquals(StatusPill.NONE, scene.statusPill)
        assertEquals(HudWidget.NONE, scene.hudWidget)
        assertEquals(ShotFraming.AUTO, scene.shotFraming)
        assertFalse(ProceduralPuppetEngine.hasActiveHudOverlay(scene, AnimationStyle.PAPER_DIORAMA))
        assertTrue(ProceduralPuppetEngine.hasActiveHudOverlay(scene, AnimationStyle.TECH_EXPLAINER))
        assertTrue(ProceduralPuppetEngine.hasActiveHudOverlay(scene, AnimationStyle.CROSS_SECTION))
        assertTrue(
            ProceduralPuppetEngine.hasActiveHudOverlay(
                scene,
                AnimationStyle.PAPER_DIORAMA,
                ProductionMode.CLIP_AUTO_EDITOR
            )
        )
        assertEquals(ShotFraming.MEDIUM_WAIST_UP, ProceduralPuppetEngine.resolveShotFraming(scene))
        assertEquals(ShotFraming.CLOSE_UP_FACE, ProceduralPuppetEngine.resolveShotFraming(scene.copy(sceneId = 4)))
    }

    @Test
    fun `audioPcmStitcher computes sample accurate offsets and mixes SFX plus auto-ducking BGM`() {
        // 44100 samples * 2 bytes = 88200 bytes = exactly 1000.0 ms
        val oneSecPcm = ByteArray(88200)
        val halfSecPcm = ByteArray(44100)
        val stitchedDry = AudioPcmStitcher.stitchScenes(
            decodedPcmPerScene = listOf(oneSecPcm, halfSecPcm),
            localTimingsPerScene = listOf(
                listOf(WordTiming("Una", 100.0, 400.0)),
                listOf(WordTiming("Pangalawa", 50.0, 350.0))
            ),
            narrationPerScene = listOf("Una", "Pangalawa")
        )
        assertEquals(1500.0, stitchedDry.totalDurationMs, 0.01)
        assertEquals(0.0, stitchedDry.sceneStartMs[0], 0.01)
        assertEquals(1000.0, stitchedDry.sceneStartMs[1], 0.01)
        assertEquals(1050.0, stitchedDry.sceneWordTimings[1][0].startMs, 0.01)
        assertEquals(1350.0, stitchedDry.sceneWordTimings[1][0].endMs, 0.01)

        // Mix with scenes containing WHIP_PAN, COIN_RAIN, StatusPill, and ANIMATED_CHECKLIST
        val twoSecSilent = ByteArray(44100 * 2 * 2)
        val sampleScenes = listOf(
            SceneScript(
                sceneId = 1,
                tagalogNarration = "Alam mo ba ang sikreto sa mabilis na pag-iipon?",
                backgroundPrompt = "warm village street",
                cameraMotion = CameraMotion.WHIP_PAN_LEFT,
                actorRole = ActorRole.CHICKEN_MENTOR,
                actorAction = ActorAction.POINT_STICK,
                worldFx = WorldFx.COIN_RAIN,
                statusPill = StatusPill.SECRET,
                calloutBanner = "90% LAKAS",
                hudWidget = HudWidget.ANIMATED_CHECKLIST,
                checklistItems = listOf("Una", "Pangalawa", "Pangatlo")
            )
        )
        val stitchedMixed = AudioPcmStitcher.stitchScenes(
            decodedPcmPerScene = listOf(twoSecSilent),
            localTimingsPerScene = listOf(emptyList()),
            narrationPerScene = listOf(sampleScenes[0].tagalogNarration),
            scenes = sampleScenes,
            style = AnimationStyle.TECH_EXPLAINER
        )
        assertEquals(twoSecSilent.size, stitchedMixed.unifiedPcm44100Mono.size)

        // Verify non-silent BGM + SFX energy present and safely within 16-bit PCM bounds
        val shorts = ShortArray(stitchedMixed.unifiedPcm44100Mono.size / 2)
        ByteBuffer.wrap(stitchedMixed.unifiedPcm44100Mono).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        var maxAmp = 0
        for (s in shorts) {
            val a = abs(s.toInt())
            if (a > maxAmp) maxAmp = a
        }
        assertTrue("Expected mixed BGM + SFX to produce audible non-zero PCM", maxAmp > 200)
        assertTrue("Expected mixed PCM not to overflow 16-bit range", maxAmp <= Short.MAX_VALUE.toInt())
    }

    @Test
    fun `editablePronunciationDictionaryAppliesWithoutMutatingCaptionDisplay`() {
        val dict = listOf(
            PronunciationEntry("Nike", "Nayk"),
            PronunciationEntry("WiFi", "Way-Fay"),
            PronunciationEntry("₱", "pesos"),
            PronunciationEntry("%", "percent")
        )
        val raw = "Bumili ng Nike at WiFi gamit ang ₱500 at 25% discount!"
        val spoken = TagalogPhoneticNormalizer.normalize(raw, dict)
        assertTrue(spoken.contains("Nayk"))
        assertTrue(spoken.contains("Way-Fay"))
        assertTrue(spoken.contains("limang daan pesos"))
        assertTrue(spoken.contains("dalawampu't lima percent"))
        // Original caption string remains untouched for display
        assertTrue(raw.contains("Nike") && raw.contains("WiFi") && raw.contains("₱500") && raw.contains("25%"))
    }

    @Test
    fun `characterSpriteAlphaErosionStripsEdgeHalosWhilePreservingCore`() {
        // 7x7 ARGB sprite with transparent outer border, 1px bright white semi-transparent halo ring, and 3x3 solid core
        val w = 7
        val h = 7
        val src = IntArray(w * h)
        for (y in 1..5) {
            for (x in 1..5) {
                val isCore = x in 2..4 && y in 2..4
                src[y * w + x] = if (isCore) {
                    0xFFFF4422.toInt()
                } else {
                    0x99FFFFFF.toInt() // 1px white halo edge
                }
            }
        }

        val eroded = CharacterSpriteProcessor.erodeAlphaBuffer(src, w, h, radiusPx = 1.5f)
        // Outer halo ring (touching alpha=0 neighbors) should be stripped cleanly
        assertEquals(0, (eroded[1 * w + 1] ushr 24) and 0xFF)
        assertEquals(0, (eroded[1 * w + 3] ushr 24) and 0xFF)
        // Center solid pixel remains 100% opaque
        assertEquals(255, (eroded[3 * w + 3] ushr 24) and 0xFF)
    }

    @Test
    fun `customBgmDucksTo10PercentDuringSpeechAnd28PercentInPausesAndProblemPlaysThud`() {
        assertEquals(0.10f, AudioPcmStitcher.BGM_DUCKED_LEVEL, 0.001f)
        assertEquals(0.28f, AudioPcmStitcher.BGM_PAUSE_LEVEL, 0.001f)

        // 1 second of silent dry voice + 100ms custom constant-amplitude BGM
        val oneSecDry = ByteArray(44100 * 2)
        val bgmShorts = ShortArray(4410) { 10_000 }
        val bgmBytes = ByteArray(bgmShorts.size * 2)
        ByteBuffer.wrap(bgmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(bgmShorts)

        val problemScene = listOf(
            SceneScript(
                sceneId = 2,
                tagalogNarration = "Malaking problema ito kung hindi ka mag-iipon ngayon.",
                backgroundPrompt = "city",
                cameraMotion = CameraMotion.STATIC,
                actorRole = ActorRole.NARRATOR,
                actorAction = ActorAction.TALK_BOUNCE,
                worldFx = WorldFx.NONE,
                statusPill = StatusPill.PROBLEM
            )
        )
        val mixed = AudioPcmStitcher.stitchScenes(
            decodedPcmPerScene = listOf(oneSecDry),
            localTimingsPerScene = listOf(emptyList()),
            narrationPerScene = listOf(problemScene[0].tagalogNarration),
            scenes = problemScene,
            style = AnimationStyle.CROSS_SECTION,
            customBgmPcm44100Mono = bgmBytes
        )
        val outShorts = ShortArray(mixed.unifiedPcm44100Mono.size / 2)
        ByteBuffer.wrap(mixed.unifiedPcm44100Mono).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(outShorts)
        var peak = 0
        for (s in outShorts) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        // Custom BGM (~2800 in pauses) + PROBLEM sub-bass thud (~9800 peak) must produce clear non-zero audio
        assertTrue("Expected custom BGM + PROBLEM thud to exceed 3000 amplitude", peak > 3000)
    }

    @Test
    fun `characterPoseHandPropShotTypeAndCameraControllerWorkCohesively`() {
        assertEquals(8, CharacterPose.entries.size)
        assertEquals(10, HandProp.entries.size)
        assertEquals(6, ShotType.entries.size)
        assertEquals(-230f, ProceduralPuppetEngine.HUD_SAFE_LIFT_PX, 0.01f)

        assertEquals(CharacterPose.WALK_ACROSS_LEFT_TO_RIGHT, CharacterPose.fromRaw("walk_across_left_to_right"))
        assertEquals(CharacterPose.TWO_CHARACTER_CONVERSATION, CharacterPose.fromRaw("TWO_CHARACTER_CONVERSATION"))
        assertEquals(CharacterPose.AUTO, CharacterPose.fromRaw("invalid"))
        assertEquals(HandProp.LIGHTBULB, HandProp.fromRaw("lightbulb"))
        assertEquals(HandProp.MONEY_BAG, HandProp.fromRaw("MONEY_BAG"))
        assertEquals(HandProp.NONE, HandProp.fromRaw(null))
        assertEquals(ShotType.TRACKING_FOLLOW, ShotType.fromRaw("tracking_follow"))
        assertEquals(ShotType.WHIP_CUT, ShotType.fromRaw("WHIP_CUT"))
        assertEquals(ShotType.AUTO, ShotType.fromRaw("invalid"))

        val scene = SceneScript(
            sceneId = 1,
            tagalogNarration = "Lakad lang nang lakad habang may hawak na ilaw ng ideya!",
            backgroundPrompt = "warm street",
            cameraMotion = CameraMotion.STATIC,
            actorRole = ActorRole.HERO,
            actorAction = ActorAction.WALK_IN,
            worldFx = WorldFx.NONE,
            characterPose = CharacterPose.WALK_ACROSS_LEFT_TO_RIGHT,
            handProp = HandProp.LIGHTBULB,
            shotType = ShotType.TRACKING_FOLLOW,
            secondaryActorRole = ActorRole.CHICKEN_MENTOR
        )

        // CameraController zoom & tracking verification
        val mediumCam = CameraController.computeCameraTransform(
            width = 1080f, height = 1920f,
            scene = scene.copy(shotType = ShotType.MEDIUM_WAIST_UP, characterPose = CharacterPose.THREE_QUARTER_EXPLAIN),
            style = AnimationStyle.FLAT_VECTOR,
            sceneIndex = 0, sceneElapsedSec = 1.0f, sceneDurationSec = 4f, globalTimeSec = 1.0f,
            hasActiveHud = true, isCutaway = false
        )
        assertEquals(1.45f, mediumCam.scale, 0.01f)

        val closeCam = CameraController.computeCameraTransform(
            width = 1080f, height = 1920f,
            scene = scene.copy(shotType = ShotType.CLOSE_UP_REACTION, characterPose = CharacterPose.SHOCK_AIRBORNE_RECOIL),
            style = AnimationStyle.FLAT_VECTOR,
            sceneIndex = 1, sceneElapsedSec = 1.0f, sceneDurationSec = 4f, globalTimeSec = 5.0f,
            hasActiveHud = false, isCutaway = false
        )
        assertEquals(1.90f, closeCam.scale, 0.01f)

        val trackStart = CameraController.computeCameraTransform(
            width = 1080f, height = 1920f, scene = scene,
            style = AnimationStyle.FLAT_VECTOR,
            sceneIndex = 0, sceneElapsedSec = 0.1f, sceneDurationSec = 4f, globalTimeSec = 0.1f,
            hasActiveHud = false, isCutaway = false
        )
        val trackLate = CameraController.computeCameraTransform(
            width = 1080f, height = 1920f, scene = scene,
            style = AnimationStyle.FLAT_VECTOR,
            sceneIndex = 0, sceneElapsedSec = 2.8f, sceneDurationSec = 4f, globalTimeSec = 2.8f,
            hasActiveHud = false, isCutaway = false
        )
        assertTrue(
            "TRACKING_FOLLOW should pan horizontally as walking character moves left-to-right",
            trackStart.translateX > trackLate.translateX + 100f
        )

        val whipStart = CameraController.computeCameraTransform(
            width = 1080f, height = 1920f,
            scene = scene.copy(shotType = ShotType.WHIP_CUT),
            style = AnimationStyle.FLAT_VECTOR,
            sceneIndex = 2, sceneElapsedSec = 0.05f, sceneDurationSec = 4f, globalTimeSec = 8.05f,
            hasActiveHud = false, isCutaway = false
        )
        assertTrue("WHIP_CUT should emit directional blur strength early in cut", whipStart.whipBlurStrength > 0.7f)

        // Spring solver follow-through lag verification during recoil launch
        val recoilSpring = PuppetSpringSolver.solve(
            pose = CharacterPose.SHOCK_AIRBORNE_RECOIL,
            sceneElapsedSec = 0.25f,
            sceneDurationSec = 4.0f
        )
        assertTrue(
            "Spring follow-through should produce non-zero head/hair lag during airborne recoil",
            abs(recoilSpring.hairHatTiltDeg) > 0.5f && abs(recoilSpring.trailingArmLagDeg) > 0.5f
        )

        // Two-character partner fallback verification
        assertEquals(
            ActorRole.CHICKEN_MENTOR,
            ProceduralPuppetEngine.resolvePartnerRole(ActorRole.HERO, null, sceneId = 1)
        )
        assertEquals(
            ActorRole.RICH_MAN,
            ProceduralPuppetEngine.resolvePartnerRole(ActorRole.HERO, null, sceneId = 2)
        )
    }

    @Test
    fun `viral2dExplainerEnumsMetaphorsAndSchemaStayBackwardCompatible`() {
        assertEquals(6, ActorExpression.entries.size)
        assertEquals(4, ActorHeadgear.entries.size)
        assertEquals(14, MetaphorVisual.entries.size)

        assertFalse(AnimationStyle.VIRAL_2D_EXPLAINER.usesNetworkArt)
        assertEquals("2D VIRAL", AnimationStyle.VIRAL_2D_EXPLAINER.badge)

        assertEquals(ActorExpression.PANIC_SWEAT_FIRE, ActorExpression.fromRaw("panic_sweat_fire"))
        assertEquals(ActorExpression.TONGUE_STING, ActorExpression.fromRaw("TONGUE_STING"))
        assertEquals(ActorExpression.HAPPY_TALK, ActorExpression.fromRaw("unknown"))

        assertEquals(ActorHeadgear.CHEF_HAT, ActorHeadgear.fromRaw("chef_hat"))
        assertEquals(ActorHeadgear.HARD_HAT, ActorHeadgear.fromRaw("HARD_HAT"))
        assertEquals(ActorHeadgear.CROWN, ActorHeadgear.fromRaw("crown"))
        assertEquals(ActorHeadgear.NONE, ActorHeadgear.fromRaw(null))

        assertEquals(MetaphorVisual.CHOMP_CHAIN, MetaphorVisual.fromRaw("chomp_chain"))
        assertEquals(MetaphorVisual.TISSUE_CROSS_SECTION, MetaphorVisual.fromRaw("TISSUE_CROSS_SECTION"))
        assertEquals(MetaphorVisual.BOILING_POT_PROCESS, MetaphorVisual.fromRaw("boiling_pot_process"))
        assertEquals(MetaphorVisual.SUNBURST_SPOTLIGHT, MetaphorVisual.fromRaw("sunburst_spotlight"))
        assertEquals(MetaphorVisual.NONE, MetaphorVisual.fromRaw("invalid"))

        val defaultScene = SceneScript(
            sceneId = 2,
            tagalogNarration = "Bakit nasisira ang tiyan kapag sobrang anghang?",
            backgroundPrompt = "kitchen",
            cameraMotion = CameraMotion.STATIC,
            actorRole = ActorRole.HERO,
            actorAction = ActorAction.TALK_BOUNCE,
            worldFx = WorldFx.NONE,
            statusPill = StatusPill.PROBLEM
        )
        assertEquals(ActorExpression.HAPPY_TALK, defaultScene.expression)
        assertEquals(ActorHeadgear.NONE, defaultScene.headgear)
        assertEquals(MetaphorVisual.NONE, defaultScene.metaphorVisual)

        assertEquals(
            MetaphorVisual.NONE,
            MetaphorVisualRenderer.resolveMetaphorVisual(defaultScene, AnimationStyle.VIRAL_2D_EXPLAINER)
        )
        assertEquals(
            MetaphorVisual.CHOMP_CHAIN,
            MetaphorVisualRenderer.resolveMetaphorVisual(
                defaultScene.copy(metaphorVisual = MetaphorVisual.CHOMP_CHAIN),
                AnimationStyle.VIRAL_2D_EXPLAINER
            )
        )
        assertEquals(
            MetaphorVisual.NONE,
            MetaphorVisualRenderer.resolveMetaphorVisual(defaultScene, AnimationStyle.PAPER_DIORAMA)
        )
        assertTrue(MetaphorVisualRenderer.isDiagramAlongsideCharacter(MetaphorVisual.BOILING_POT_PROCESS))
        assertFalse(MetaphorVisualRenderer.isDiagramAlongsideCharacter(MetaphorVisual.SUNBURST_SPOTLIGHT))

        assertEquals(
            ActorExpression.PANIC_SWEAT_FIRE,
            ProceduralPuppetEngine.resolveExpression(defaultScene, AnimationStyle.VIRAL_2D_EXPLAINER)
        )
        assertEquals(
            ActorExpression.HAPPY_TALK,
            ProceduralPuppetEngine.resolveExpression(defaultScene, AnimationStyle.PAPER_DIORAMA)
        )
    }

    @Test
    fun `jsonSanitizer repairs truncated json array preserving completed scenes`() {
        val truncated = """
            ```json
            [
              {
                "scene_id": 1,
                "tagalog_narration": "Alam mo ba kung bakit laging kulang ang ipon mo?",
                "background_prompt": "warm street with glowing [lanterns] and {houses}",
                "camera_motion": "ZOOM_IN",
                "actor_role": "HERO",
                "actor_action": "TALK_BOUNCE",
                "world_fx": "COIN_RAIN",
                "checklist_items": ["Magtabi agad", "Iwasan ang utang"]
              },
              {
                "scene_id": 2,
                "tagalog_narration": "Heto ang pangalawang
        """.trimIndent()

        val repaired = JsonSanitizer.extractJsonArrayText(truncated)
        assertTrue(repaired.startsWith("["))
        assertTrue(repaired.endsWith("]"))
        assertTrue(repaired.contains("\"scene_id\": 1"))
        assertTrue(repaired.contains("\"checklist_items\": [\"Magtabi agad\", \"Iwasan ang utang\"]"))
        assertFalse(repaired.contains("\"scene_id\": 2"))
        assertFalse(repaired.contains("Heto ang pangalawang"))
    }

    @Test
    fun `elevenLabsClient cleans proxy urls cleanly`() {
        assertEquals("https://api.elevenlabs.io", ElevenLabsClient.cleanTtsBaseUrl("  "))
        assertEquals("https://tts-proxy.example.com", ElevenLabsClient.cleanTtsBaseUrl("https://tts-proxy.example.com/v1/"))
        assertEquals("https://tts-proxy.example.com", ElevenLabsClient.cleanTtsBaseUrl("https://tts-proxy.example.com/"))
    }

    @Test
    fun `pixelCutawayExplainer supports all 6 stages pointer callouts and 270x480 coordinate normalization`() {
        assertEquals(6, CutawayStage.entries.size)
        assertTrue(AnimationStyle.PIXEL_CUTAWAY_EXPLAINER.usesNetworkArt)
        assertEquals("16-BIT PIXEL", AnimationStyle.PIXEL_CUTAWAY_EXPLAINER.badge)
        assertEquals(270, PixelCutawayRenderer.PIXEL_WIDTH)
        assertEquals(480, PixelCutawayRenderer.PIXEL_HEIGHT)

        assertEquals(CutawayStage.IMMUNE_CLEANUP, CutawayStage.fromRaw("immune_cleanup"))
        assertEquals(
            CutawayStage.STRAND_AND_VESSEL_REBUILD,
            CutawayStage.fromRaw("STRAND_AND_VESSEL_REBUILD")
        )
        assertEquals(null, CutawayStage.fromRaw("unknown_stage"))

        // Coordinate normalization across [0..1], 270x480, and 1080x1920 spaces
        assertEquals(135f to 240f, PixelCutawayRenderer.normalizeCalloutPoint(0.5f, 0.5f))
        assertEquals(140f to 220f, PixelCutawayRenderer.normalizeCalloutPoint(140f, 220f))
        assertEquals(135f to 200f, PixelCutawayRenderer.normalizeCalloutPoint(540f, 800f))

        val explicitScene = SceneScript(
            sceneId = 3,
            tagalogNarration = "Dumating na ang mga white blood cells para linisin ang sugat.",
            backgroundPrompt = "pixel cross-section",
            cameraMotion = CameraMotion.STATIC,
            actorRole = ActorRole.NARRATOR,
            actorAction = ActorAction.TALK_BOUNCE,
            worldFx = WorldFx.NONE,
            cutawayStage = CutawayStage.CELL_MIGRATION_CLOSE,
            pointerCallouts = listOf(
                PointerCallout("  new blood vessels ", 150f, 210f),
                PointerCallout("HEALED", 135f, 120f),
                PointerCallout("IGNORED THIRD", 100f, 100f)
            )
        )
        assertEquals(CutawayStage.CELL_MIGRATION_CLOSE, ProceduralPuppetEngine.resolveCutawayStage(explicitScene))
        val resolvedCallouts = ProceduralPuppetEngine.resolvePointerCallouts(explicitScene)
        assertEquals(2, resolvedCallouts.size)
        assertEquals("NEW BLOOD VESSELS", resolvedCallouts[0].label)
        assertEquals("HEALED", resolvedCallouts[1].label)

        val inferredImmuneScene = SceneScript(
            sceneId = 1,
            tagalogNarration = "Pumasok ang white blood cells sa cross-section.",
            backgroundPrompt = "pixel skin",
            cameraMotion = CameraMotion.STATIC,
            actorRole = ActorRole.NARRATOR,
            actorAction = ActorAction.TALK_BOUNCE,
            worldFx = WorldFx.NONE
        )
        assertEquals(CutawayStage.IMMUNE_CLEANUP, ProceduralPuppetEngine.resolveCutawayStage(inferredImmuneScene))
        assertEquals(
            "WHITE BLOOD CELLS",
            ProceduralPuppetEngine.resolvePointerCallouts(inferredImmuneScene).first().label
        )
    }

    @Test
    fun `v5MultiEnvironmentPollinationsCharacterCustomizationCutawaysAndMetaphorsWork`() {
        // 1. Multi-Environment resolution + Pollinations keyless fallback config
        assertEquals(11, EnvironmentTheme.entries.size)
        assertEquals(
            EnvironmentTheme.BRIGHT_MORNING_KITCHEN,
            EnvironmentRenderer.resolveEnvironmentTheme(
                prompt = "bright sunny morning kitchen with coffee cup on wooden table",
                explicitTheme = null,
                style = AnimationStyle.PAPER_DIORAMA
            )
        )
        assertEquals(
            EnvironmentTheme.SUNNY_MARKET_STREET,
            EnvironmentRenderer.resolveEnvironmentTheme(
                prompt = "busy palengke street with sari-sari store and sunshine",
                explicitTheme = null,
                style = AnimationStyle.FLAT_VECTOR
            )
        )
        assertEquals(
            EnvironmentTheme.COZY_BEDROOM_MIDNIGHT,
            EnvironmentRenderer.resolveEnvironmentTheme(
                prompt = "late night bedroom doomscrolling",
                explicitTheme = null,
                style = AnimationStyle.DARK_SUSPENSE
            )
        )
        assertEquals(
            EnvironmentTheme.BANK_VAULT_FINANCE,
            EnvironmentRenderer.resolveEnvironmentTheme(
                prompt = "kitchen table",
                explicitTheme = EnvironmentTheme.BANK_VAULT_FINANCE,
                style = AnimationStyle.PAPER_DIORAMA
            )
        )

        // 2. Removed obsolete floating sky FX safely map to NONE + new hand props work
        assertEquals(4, WorldFx.entries.size)
        assertEquals(WorldFx.NONE, WorldFx.fromRaw("FLOATING_HOUSES"))
        assertEquals(WorldFx.NONE, WorldFx.fromRaw("GIANT_HAND_GRAB"))
        assertEquals(WorldFx.NONE, WorldFx.fromRaw("EGG_CRACK"))
        assertEquals(HandProp.COFFEE_CUP, HandProp.fromRaw("coffee_cup"))
        assertEquals(HandProp.BASKET_GOODS, HandProp.fromRaw("EGG_BASKET"))

        // 3. Character appearance customization & pure-Kotlin hex color parser
        assertEquals(2, CharacterGender.entries.size)
        assertEquals(4, CharacterAge.entries.size)
        val tinderaScene = SceneScript(
            sceneId = 1,
            tagalogNarration = "Ang sikreto ng matandang tindera sa palengke tuwing umaga.",
            backgroundPrompt = "palengke morning",
            cameraMotion = CameraMotion.STATIC,
            actorRole = ActorRole.HERO,
            actorAction = ActorAction.TALK_BOUNCE,
            worldFx = WorldFx.NONE
        )
        assertEquals(CharacterGender.FEMALE, ProceduralPuppetEngine.resolveCharacterGender(tinderaScene))
        assertEquals(CharacterAge.ELDERLY, ProceduralPuppetEngine.resolveCharacterAge(tinderaScene))
        assertEquals(0xFFE11D48.toInt(), ProceduralPuppetEngine.parseHexColorOrNull("#E11D48"))
        assertEquals(null, ProceduralPuppetEngine.parseHexColorOrNull("invalid_hex"))

        // 4. 6-subject dynamic 16-bit cutaway resolution & pointer callouts
        assertEquals(6, CutawaySubject.entries.size)
        val brainScene = SceneScript(
            sceneId = 2,
            tagalogNarration = "Ano ang nangyayari sa utak at neuron mo kapag puro doomscrolling?",
            backgroundPrompt = "brain synapse cutaway",
            cameraMotion = CameraMotion.STATIC,
            actorRole = ActorRole.NARRATOR,
            actorAction = ActorAction.TALK_BOUNCE,
            worldFx = WorldFx.NONE,
            cutawayStage = CutawayStage.STRAND_AND_VESSEL_REBUILD
        )
        val resolvedBrainSubject = ProceduralPuppetEngine.resolveCutawaySubject(brainScene)
        assertEquals(CutawaySubject.BRAIN_NEURON_SIGNAL, resolvedBrainSubject)
        assertEquals(
            "SYNAPSE BRIDGE",
            ProceduralPuppetEngine.resolvePointerCallouts(brainScene, CutawayStage.STRAND_AND_VESSEL_REBUILD, resolvedBrainSubject)
                .first().label
        )

        // 5. Expanded 14 MetaphorVisuals & topic matching
        val moneyTreeScene = SceneScript(
            sceneId = 1,
            tagalogNarration = "Paano nagiging puno ng pera ang tamang tanim ng ipon?",
            backgroundPrompt = "bright office",
            cameraMotion = CameraMotion.STATIC,
            actorRole = ActorRole.CHICKEN_MENTOR,
            actorAction = ActorAction.POINT_STICK,
            worldFx = WorldFx.COIN_RAIN
        )
        assertEquals(
            MetaphorVisual.GROWING_MONEY_TREE,
            MetaphorVisualRenderer.resolveMetaphorVisual(moneyTreeScene, AnimationStyle.VIRAL_2D_EXPLAINER)
        )
        val rocketScene = moneyTreeScene.copy(
            tagalogNarration = "Paano gumagana ang Rule of 72 at compound interest chart?"
        )
        assertEquals(
            MetaphorVisual.ROCKET_GROWTH_CHART,
            MetaphorVisualRenderer.resolveMetaphorVisual(rocketScene, AnimationStyle.VIRAL_2D_EXPLAINER)
        )
        assertTrue(MetaphorVisualRenderer.isDiagramAlongsideCharacter(MetaphorVisual.CRACKING_EGG_CHICK))
        assertTrue(MetaphorVisualRenderer.isDiagramAlongsideCharacter(MetaphorVisual.ROCKET_GROWTH_CHART))
        assertTrue(MetaphorVisualRenderer.isDiagramAlongsideCharacter(MetaphorVisual.DOMINO_CHAIN_REACTION))

        // 6. V5 Enum fromRaw, JsonSanitizer extraction & BuiltInPrompts schema checks (JVM-safe)
        assertEquals(EnvironmentTheme.BRIGHT_MORNING_KITCHEN, EnvironmentTheme.fromRaw("bright_morning_kitchen"))
        assertEquals(CutawaySubject.BATTERY_CIRCUIT_CHARGE, CutawaySubject.fromRaw("battery_circuit_charge"))
        assertEquals(CharacterGender.FEMALE, CharacterGender.fromRaw("female"))
        assertEquals(CharacterAge.ELDERLY, CharacterAge.fromRaw("elderly"))
        assertEquals(MetaphorVisual.CRACKING_EGG_CHICK, MetaphorVisual.fromRaw("cracking_egg_chick"))
        assertEquals(MetaphorVisual.ROCKET_GROWTH_CHART, MetaphorVisual.fromRaw("ROCKET_GROWTH_CHART"))
        assertEquals(MetaphorVisual.DOMINO_CHAIN_REACTION, MetaphorVisual.fromRaw("domino_chain_reaction"))

        val rawJson = """
            ```json
            [
              {
                "scene_id": 1,
                "tagalog_narration": "Huwag umasa sa iisang kita lang dahil mabilis magbago ang buhay.",
                "background_prompt": "bright morning kitchen with sunlight through window",
                "camera_motion": "ZOOM_IN",
                "actor_role": "HERO",
                "actor_action": "TALK_BOUNCE",
                "world_fx": "COIN_RAIN",
                "environment_theme": "BRIGHT_MORNING_KITCHEN",
                "cutaway_subject": "BATTERY_CIRCUIT_CHARGE",
                "character_gender": "FEMALE",
                "character_age": "YOUNG",
                "outfit_color_hex": "#E11D48",
                "hand_prop": "COFFEE_CUP",
                "metaphor_visual": "CRACKING_EGG_CHICK"
              }
            ]
            ```
        """.trimIndent()
        val extracted = JsonSanitizer.extractJsonArrayText(rawJson)
        assertTrue(extracted.contains("\"environment_theme\": \"BRIGHT_MORNING_KITCHEN\""))
        assertTrue(extracted.contains("\"cutaway_subject\": \"BATTERY_CIRCUIT_CHARGE\""))
        assertTrue(extracted.contains("\"character_gender\": \"FEMALE\""))
        assertTrue(extracted.contains("\"outfit_color_hex\": \"#E11D48\""))
        assertTrue(extracted.contains("\"hand_prop\": \"COFFEE_CUP\""))
    }

    @Test
    fun `breathingPausesAlignmentAndNarrationSpeedClampWork`() {
        // Smart Inter-Scene Breathing Pauses (350..850ms) & <= 1200ms Outro Hold
        val plan = AudioPcmStitcher.computeBreathingPauses(
            sceneCount = 6,
            totalSpeechMs = 26_600.0,
            targetDurationMs = 30_000.0
        )
        assertEquals(5, plan.transitionPausesMs.size)
        plan.transitionPausesMs.forEach { pause ->
            assertTrue("Expected breathing pause in 350..850ms, got $pause", pause in 350.0..850.0)
        }
        assertTrue("Expected outro hold <= 1200ms, got ${plan.outroHoldMs}", plan.outroHoldMs in 0.0..1200.0)
        assertEquals(3400.0, plan.totalAddedMs, 1.0)

        // End-to-end stitchScenes alignment within +/- 1.5s of 30s target
        val sceneSpeechBytes = ((26_600.0 / 6.0 / 1000.0) * 44100.0).toInt() * 2
        val pcmChunks = List(6) { ByteArray(sceneSpeechBytes) }
        val stitched = AudioPcmStitcher.stitchScenes(
            decodedPcmPerScene = pcmChunks,
            localTimingsPerScene = List(6) { emptyList() },
            narrationPerScene = List(6) { "Isang maikling Tagalog sentence dito." },
            targetDurationMs = 30_000.0
        )
        assertTrue(
            "Expected aligned PCM duration within +/-1500ms of 30000ms, got ${stitched.totalDurationMs}",
            abs(stitched.totalDurationMs - 30_000.0) <= 1500.0
        )

        // 4. Narration Speed bounds & StudioSettings default
        assertEquals(1.0f, StudioSettings().narrationSpeed, 0.001f)
        assertEquals(0.85, ElevenLabsClient.clampNarrationSpeed(0.50f), 0.001)
        assertEquals(1.00, ElevenLabsClient.clampNarrationSpeed(1.00f), 0.001)
        assertEquals(1.15, ElevenLabsClient.clampNarrationSpeed(1.50f), 0.001)
    }

    @Test
    fun `v6TopicFirstDynamicEngineWardrobeCutawayAndDiagramsWork`() {
        // 1. New V6 enums & backward-compatible aliases
        assertEquals(7, CharacterOutfit.entries.size)
        assertEquals(4, FlowDirection.entries.size)
        assertEquals(6, DynamicDiagramType.entries.size)
        assertEquals(4, CharacterAge.entries.size)
        assertEquals(CharacterAge.YOUNG_ADULT, CharacterAge.fromRaw("YOUNG"))
        assertEquals(CharacterAge.MIDDLE_AGED, CharacterAge.fromRaw("ADULT"))
        assertEquals(CharacterAge.CHILD, CharacterAge.fromRaw("TEEN"))
        assertEquals(CharacterOutfit.MEDICAL_SCRUBS, CharacterOutfit.fromRaw("medical_scrubs"))
        assertEquals(CharacterOutfit.WORK_UNIFORM, CharacterOutfit.fromRaw("work_uniform"))
        assertEquals(FlowDirection.RADIAL_BURST, FlowDirection.fromRaw("radial_burst"))
        assertEquals(DynamicDiagramType.BEFORE_AFTER_SPLIT, DynamicDiagramType.fromRaw("before_after_split"))
        assertEquals(DynamicDiagramType.SCALE_WEIGH_OFF, DynamicDiagramType.fromRaw("SCALE_WEIGH_OFF"))
        assertEquals(HandProp.BOOK_NOTEBOOK, HandProp.fromRaw("book_notebook"))
        assertEquals(HandProp.TOOL_WRENCH, HandProp.fromRaw("tool_wrench"))
        assertEquals(EnvironmentTheme.HOSPITAL_CLINIC, EnvironmentTheme.fromRaw("hospital_clinic"))

        // 2. Topic-First Decoupling: neutral topics do NOT get forced skin wounds or protein chains
        val studyScene = SceneScript(
            sceneId = 1,
            tagalogNarration = "Paano gumawa ng maayos na study schedule ang isang estudyante?",
            backgroundPrompt = "bright classroom chalkboard",
            cameraMotion = CameraMotion.STATIC,
            actorRole = ActorRole.HERO,
            actorAction = ActorAction.POINT_STICK,
            worldFx = WorldFx.NONE
        )
        assertEquals(null, ProceduralPuppetEngine.resolveCutawaySubjectOrNull(studyScene))
        assertEquals(MetaphorVisual.NONE, MetaphorVisualRenderer.resolveMetaphorVisual(studyScene, AnimationStyle.VIRAL_2D_EXPLAINER))
        assertEquals(CharacterOutfit.STUDENT_UNIFORM, ProceduralPuppetEngine.resolveCharacterOutfit(studyScene))
        assertEquals(CharacterAge.YOUNG_ADULT, ProceduralPuppetEngine.resolveCharacterAge(studyScene))

        // Hospital/doctor scene routes to HOSPITAL_CLINIC + MEDICAL_SCRUBS
        assertEquals(
            EnvironmentTheme.HOSPITAL_CLINIC,
            EnvironmentRenderer.resolveEnvironmentTheme(
                prompt = "modern hospital clinic examination room with doctor",
                explicitTheme = null,
                style = AnimationStyle.PAPER_DIORAMA
            )
        )
        val doctorScene = studyScene.copy(
            tagalogNarration = "Payo ng doktor sa klinika tungkol sa tamang pag-inom ng tubig.",
            backgroundPrompt = "hospital clinic room"
        )
        assertEquals(CharacterOutfit.MEDICAL_SCRUBS, ProceduralPuppetEngine.resolveCharacterOutfit(doctorScene))

        // 3. DynamicDiagramRenderer routes data-driven diagrams cleanly
        val compareScene = studyScene.copy(
            leftLabel = "WALANG PLANO",
            rightLabel = "MAY SCHEDULE"
        )
        assertEquals(
            DynamicDiagramType.BEFORE_AFTER_SPLIT,
            DynamicDiagramRenderer.resolveDiagramType(compareScene, AnimationStyle.VIRAL_2D_EXPLAINER)
        )
        assertTrue(DynamicDiagramRenderer.isDiagramAlongsideCharacter(DynamicDiagramType.BEFORE_AFTER_SPLIT))

        val stepsScene = studyScene.copy(
            step1 = "Gumawa ng listahan",
            step2 = "Mag-focus 25 mins",
            step3 = "Magpahinga 5 mins"
        )
        assertEquals(
            DynamicDiagramType.STEP_FLOW_ARROWS,
            DynamicDiagramRenderer.resolveDiagramType(stepsScene, AnimationStyle.PAPER_DIORAMA)
        )

        val meterScene = studyScene.copy(
            hudLabel = "FOCUS SCORE",
            hudFromValue = 25,
            hudToValue = 92
        )
        assertEquals(
            DynamicDiagramType.METER_GAUGE_SHIFT,
            DynamicDiagramRenderer.resolveDiagramType(meterScene, AnimationStyle.VIRAL_2D_EXPLAINER)
        )
        assertFalse(DynamicDiagramRenderer.isDiagramAlongsideCharacter(DynamicDiagramType.SUNBURST_HERO_CALLOUT))
    }

    @Test
    fun `v7ProductionReadyStudioDefaultsErrorReportingAndFallbackLogicWork`() {
        // 1. StudioSettings defaults & credential trimming
        val defaults = StudioSettings()
        assertEquals("N2IVS1w4EtoT3dr4eOWO", defaults.selectedVoiceId)
        assertEquals("eleven_v4", defaults.ttsModelId)
        assertEquals("https://api.elevenlabs.io", defaults.ttsBaseUrl)
        assertEquals(1.0f, defaults.narrationSpeed, 0.001f)

        val dirtySettings = StudioSettings(
            ttsBaseUrl = " https://api.elevenlabs.io \n",
            ttsModelId = " eleven_flash_v2_5 \n",
            selectedVoiceId = " N2IVS1w4EtoT3dr4eOWO \n"
        )
        assertEquals("https://api.elevenlabs.io", dirtySettings.effectiveTtsBaseUrl)
        assertEquals("eleven_flash_v2_5", dirtySettings.effectiveTtsModelId)
        assertEquals("N2IVS1w4EtoT3dr4eOWO", dirtySettings.effectiveVoiceId)

        // 3. ElevenLabs 422 model detection, payload model_id, proportional timings & error formatting
        assertEquals("N2IVS1w4EtoT3dr4eOWO", ElevenLabsClient.DEFAULT_VOICE_ID)
        assertEquals("eleven_v4", ElevenLabsClient.DEFAULT_TTS_MODEL_ID)
        assertEquals("eleven_flash_v2_5", ElevenLabsClient.FALLBACK_TTS_MODEL_ID)

        assertTrue(
            ElevenLabsClient.isUnsupportedModel422(
                "{\"detail\":{\"status\":\"model_id_unsupported\",\"message\":\"The model_id is unsupported\"}}"
            )
        )
        assertFalse(ElevenLabsClient.isUnsupportedModel422("{\"detail\":\"Invalid text input\"}"))

        assertEquals("eleven_flash_v2_5", ElevenLabsClient.cleanCredential("  eleven_flash_v2_5\r\n "))
        assertEquals("https://tts-proxy.example.com", ElevenLabsClient.cleanTtsBaseUrl(" https://tts-proxy.example.com/v1/\n"))
        assertEquals(1.05, ElevenLabsClient.clampNarrationSpeed(1.05f), 0.001)

        val timings = ElevenLabsClient.buildProportionalWordTimings(
            displayWords = listOf("Kamusta", "kaibigan", "ko!"),
            actualDurationMs = 3000.0
        )
        assertEquals(3, timings.size)
        assertTrue(timings.first().startMs >= 0.0)
        assertTrue(timings.last().endMs <= 3000.0)
        assertTrue(timings[0].endMs <= timings[1].startMs + 0.01)
        assertTrue(timings[1].endMs <= timings[2].startMs + 0.01)

        val ttsErr = ElevenLabsHttpException(
            statusCode = 422,
            endpointUrl = "https://api.elevenlabs.io/v1/text-to-speech/N2IVS1w4EtoT3dr4eOWO",
            voiceId = "N2IVS1w4EtoT3dr4eOWO",
            modelId = "eleven_multilingual_v2",
            rawBody = "{\"detail\":\"Voice limit reached\"}"
        )
        assertTrue(ttsErr.message.orEmpty().contains("422"))
        assertTrue(ttsErr.message.orEmpty().contains("N2IVS1w4EtoT3dr4eOWO"))
        assertTrue(ttsErr.message.orEmpty().contains("eleven_multilingual_v2"))
        assertTrue(ttsErr.message.orEmpty().contains("Voice limit reached"))
    }

    @Test
    fun `magicBytesPuppetMicroActingCameraControllerAndTtsGuardsWork`() {
        // 1. Magic byte validators
        assertEquals(15_360, MagicByteValidator.MIN_VERIFIED_IMAGE_BYTES)
        assertEquals(480, MagicByteValidator.MIN_VERIFIED_WIDTH_PX)
        assertEquals(800, MagicByteValidator.MIN_VERIFIED_HEIGHT_PX)
        assertFalse(MagicByteValidator.meetsMinimumFileSize(ByteArray(1024)))
        assertTrue(MagicByteValidator.meetsMinimumFileSize(ByteArray(16_000)))
        assertFalse(MagicByteValidator.meetsMinimumDimensions(320, 960))
        assertFalse(MagicByteValidator.meetsMinimumDimensions(540, 600))
        assertTrue(MagicByteValidator.meetsMinimumDimensions(540, 960))

        // 2. Continuous Micro-Acting, Phoneme MouthShape & Non-Static CameraController
        assertEquals(0.35f, ProceduralPuppetEngine.BREATHING_FREQ_HZ, 0.001f)
        assertEquals(2.6f, ProceduralPuppetEngine.EYE_BLINK_PERIOD_SEC, 0.001f)
        assertEquals(MouthShape.CLOSED_M, ProceduralPuppetEngine.resolveMouthShape(false, "alam", 0.5f, 0.9f))
        assertEquals(MouthShape.CLOSED_M, ProceduralPuppetEngine.resolveMouthShape(true, "baha", 0.0f, 0.8f))
        assertEquals(MouthShape.OPEN_WIDE, ProceduralPuppetEngine.resolveMouthShape(true, "baha", 0.3f, 0.8f))
        assertEquals(MouthShape.ROUND_O, ProceduralPuppetEngine.resolveMouthShape(true, "bobo", 0.3f, 0.8f))
        assertEquals(MouthShape.SMILE_TEETH, ProceduralPuppetEngine.resolveMouthShape(true, "bibi", 0.3f, 0.8f))

        val staticScene = SceneScript(
            sceneId = 1,
            tagalogNarration = "Alam mo ba kung paano nakakaipon ng isang libong piso linggo-linggo ang masipag na tindera?",
            backgroundPrompt = "sunny morning street",
            visualDescription = "Young Filipino worker leaning forward at wooden market counter",
            cameraMotion = CameraMotion.STATIC,
            actorRole = ActorRole.HERO,
            actorAction = ActorAction.TALK_BOUNCE,
            worldFx = WorldFx.NONE,
            characterPose = CharacterPose.THREE_QUARTER_EXPLAIN,
            shotType = ShotType.WIDE_STAGE,
            shotFraming = ShotFraming.WIDE_FULL_BODY
        )
        val camEarly = CameraController.computeCameraTransform(
            width = 1080f, height = 1920f, scene = staticScene,
            style = AnimationStyle.FLAT_VECTOR,
            sceneIndex = 0, sceneElapsedSec = 0.2f, sceneDurationSec = 4f, globalTimeSec = 0.2f,
            hasActiveHud = false, isCutaway = false
        )
        val camLate = CameraController.computeCameraTransform(
            width = 1080f, height = 1920f, scene = staticScene,
            style = AnimationStyle.FLAT_VECTOR,
            sceneIndex = 0, sceneElapsedSec = 3.5f, sceneDurationSec = 4f, globalTimeSec = 3.5f,
            hasActiveHud = false, isCutaway = false
        )
        assertTrue(
            "Camera must continuously animate with zero static dead frames",
            abs(camLate.translateX - camEarly.translateX) > 10f && camLate.scale > camEarly.scale
        )

        val panLeft = CameraController.computeCameraTransform(
            width = 1080f, height = 1920f, scene = staticScene.copy(cameraMotion = CameraMotion.PAN_LEFT),
            style = AnimationStyle.FLAT_VECTOR,
            sceneIndex = 0, sceneElapsedSec = 0.2f, sceneDurationSec = 4f, globalTimeSec = 0.2f,
            hasActiveHud = false, isCutaway = false
        )
        val panRight = CameraController.computeCameraTransform(
            width = 1080f, height = 1920f, scene = staticScene.copy(cameraMotion = CameraMotion.PAN_RIGHT),
            style = AnimationStyle.FLAT_VECTOR,
            sceneIndex = 0, sceneElapsedSec = 0.2f, sceneDurationSec = 4f, globalTimeSec = 0.2f,
            hasActiveHud = false, isCutaway = false
        )
        assertTrue("PAN_LEFT and PAN_RIGHT should pan in opposite directions", panLeft.translateX > 0f && panRight.translateX < 0f)

        // 6. Resilient ElevenLabs Chunk Retry & Minimum 600ms PCM Guard
        assertEquals(600.0, ElevenLabsClient.MIN_DECODED_AUDIO_DURATION_MS, 0.01)
        assertEquals(listOf(2000L, 4000L, 8000L), ElevenLabsClient.TRANSIENT_RETRY_BACKOFFS_MS.toList())
        assertTrue(ElevenLabsClient.isTransientHttpStatus(429))
        assertTrue(ElevenLabsClient.isTransientHttpStatus(500))
        assertTrue(ElevenLabsClient.isTransientHttpStatus(503))
        assertFalse(ElevenLabsClient.isTransientHttpStatus(400))
        assertFalse(ElevenLabsClient.isTransientHttpStatus(422))

        val validOneSec = ByteArray(88200) // 44100 samples * 2 bytes = 1000ms
        assertEquals(1000.0, ElevenLabsClient.validateDecodedPcmOrThrow(validOneSec, "https://api.elevenlabs.io", "v1", "m1"), 0.01)

        var emptyRejected = false
        try {
            ElevenLabsClient.validateDecodedPcmOrThrow(ByteArray(0), "https://api.elevenlabs.io", "v1", "m1")
        } catch (_: Exception) {
            emptyRejected = true
        }
        assertTrue(emptyRejected)

        var shortRejected = false
        try {
            ElevenLabsClient.validateDecodedPcmOrThrow(ByteArray(44100), "https://api.elevenlabs.io", "v1", "m1") // 500ms < 600ms
        } catch (_: Exception) {
            shortRejected = true
        }
        assertTrue(shortRejected)
    }

    @Test
    fun `jsonSanitizerUnwrapsObjectWrappersShowsRawSnippetAndDirectorEnforcesJsonObjectMode`() {
        // 1. Ignores [Scene 1], ["tag1", "tag2"], and non-scene object arrays earlier in the response
        val noisyPrefixResponse = """
            [Scene 1] Draft thoughts here.
            ["tag1", "tag2"]
            [{"label": "Intro Card", "weight": 1}]
            {"title": "Budgeting Demo", "tags": ["pinoy", "ipon"], "scenes": [{"scene_id": 1, "tagalog_narration": "Una"}]}
        """.trimIndent()
        val unwrappedScenes = JsonSanitizer.extractJsonArrayText(noisyPrefixResponse)
        assertTrue(unwrappedScenes.startsWith("["))
        assertTrue(unwrappedScenes.endsWith("]"))
        assertTrue(unwrappedScenes.contains("\"scene_id\": 1"))
        assertFalse(unwrappedScenes.contains("\"tags\""))
        assertFalse(unwrappedScenes.contains("Intro Card"))
        assertFalse(unwrappedScenes.contains("Scene 1"))

        // 2. Unwrap {"storyboard": [...]} inside ```json ... ``` fence with [Note] prose outside
        val fencedStoryboard = """
            [Note] Here is the generated storyboard:
            ```json
            {
              "storyboard": [
                {"scene_id": 1, "tagalog_narration": "Alam mo ba ang sikreto sa ipon?"},
                {"scene_id": 2, "tagalog_narration": "Heto ang pangalawang
        """.trimIndent() + "\n```"
        val unwrappedStoryboard = JsonSanitizer.extractJsonArrayText(fencedStoryboard)
        assertTrue(unwrappedStoryboard.startsWith("["))
        assertTrue(unwrappedStoryboard.endsWith("]"))
        assertTrue(unwrappedStoryboard.contains("\"scene_id\": 1"))
        assertFalse(unwrappedStoryboard.contains("\"scene_id\": 2"))

        // 3. Rejects responses containing only string arrays or no scene objects and includes input snippet
        var stringArrayRejected = false
        try {
            JsonSanitizer.extractJsonArrayText("[Scene 1] Only tags [\"tag1\", \"tag2\"] here.")
        } catch (e: MalformedSceneJsonException) {
            stringArrayRejected = e.message.orEmpty().contains("Input snippet:")
        }
        assertTrue(stringArrayRejected)

        val longProse = "Pasensya na, ito ay isang kuwentong walang brackets! " + "X".repeat(300)
        var capturedMsg = ""
        try {
            JsonSanitizer.extractJsonArrayText(longProse)
        } catch (e: MalformedSceneJsonException) {
            capturedMsg = e.message.orEmpty()
        }
        assertTrue(capturedMsg.startsWith("No JSON array brackets found. Input snippet: "))
        assertTrue(capturedMsg.contains("Pasensya na, ito ay isang kuwentong walang brackets!"))
        assertTrue(capturedMsg.length <= "No JSON array brackets found. Input snippet: ".length + 220)

        // JsonSanitizer strips <think>...</think> + markdown fences and parses bare top-level array containing tagalog_narration
        val thinkAndBareArray = """
            <think>
            Let me outline scenes [1] and [2] carefully first.
            </think>
            ```json
            [{"scene_id": 5, "tagalog_narration": "Una sa lahat ay magtabi ng sampung porsyento bago gumastos."}]
            ```
        """.trimIndent()
        val bareExtracted = JsonSanitizer.extractJsonArrayText(thinkAndBareArray)
        assertTrue(bareExtracted.startsWith("["))
        assertTrue(bareExtracted.contains("\"scene_id\": 5"))
    }

    @Test
    fun `publicDohFallbackDnsResolvesViaCloudflareAndGoogleOnUnknownHostException`() {
        // 1. ElevenLabs shared client carries PublicDohFallbackDns
        assertTrue(SharedHttpClients.elevenLabsClient.dns is PublicDohFallbackDns)

        // 2. DoH JSON parser filters A records (type=1), ignores CNAME (type=5), and preserves hostname
        val dohJson = """
            {
              "Status": 0,
              "Answer": [
                {"name": "aihorde.net", "type": 5, "TTL": 120, "data": "edge.aihorde.net."},
                {"name": "edge.aihorde.net", "type": 1, "TTL": 90, "data": "104.26.10.233"},
                {"name": "edge.aihorde.net", "type": 1, "TTL": 60, "data": "104.26.11.233"}
              ]
            }
        """.trimIndent()
        val parsed = PublicDohFallbackDns.parseDohIpv4Response("aihorde.net", dohJson)
        assertEquals(2, parsed.addresses.size)
        assertEquals("aihorde.net", parsed.addresses[0].hostName)
        assertEquals("104.26.10.233", parsed.addresses[0].hostAddress)
        assertEquals("104.26.11.233", parsed.addresses[1].hostAddress)
        assertEquals(60L, parsed.minTtlSeconds)

        // 3. When system DNS throws UnknownHostException, PublicDohFallbackDns queries Cloudflare then Google DoH
        val requestedUrls = mutableListOf<String>()
        val requestedAccepts = mutableListOf<String>()
        val mockDohClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                requestedUrls.add(req.url.toString())
                requestedAccepts.add(req.header("Accept").orEmpty())
                if (req.url.host == "cloudflare-dns.com" || req.url.host == "1.1.1.1") {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .body("{}".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"Status":0,"Answer":[{"name":"aihorde.net","type":1,"TTL":120,"data":"172.67.74.226"}]}""".toResponseBody("application/dns-json".toMediaType()))
                        .build()
                }
            }
            .build()

        val dns = PublicDohFallbackDns(
            systemLookup = { host -> throw UnknownHostException("Unable to resolve host \"$host\": No address associated with hostname") },
            dohClient = mockDohClient
        )
        val resolved = dns.lookup("aihorde.net")
        assertEquals(1, resolved.size)
        assertEquals("172.67.74.226", resolved[0].hostAddress)
        assertEquals("aihorde.net", resolved[0].hostName)
        assertTrue(requestedUrls.any { it == "https://cloudflare-dns.com/dns-query?name=aihorde.net&type=A" })
        assertTrue(requestedUrls.any { it == "https://dns.google/resolve?name=aihorde.net&type=A" })
        assertTrue(requestedAccepts.all { it == "application/dns-json" })
    }

    @Test
    fun `directScriptModeAndDualFormatStoryboardParserWorkWithoutNetwork`() {
        // 1. Direct Script Mode only workflow
        assertEquals(listOf(ScriptSourceMode.DIRECT_SCRIPT), ScriptSourceMode.entries)
        val directSettings = StudioSettings(
            directScriptText = "Alam mo ba kung bakit kulang ang sahod?"
        )
        assertEquals("Alam mo ba kung bakit kulang ang sahod?", directSettings.directScriptText)

        // 2. Dual-format JSON detection vs plain script fallback (even with bracketed stage notes)
        assertTrue(DirectStoryboardParser.looksLikeJsonInput(DirectStoryboardParser.COPY_JSON_TEMPLATE))
        assertTrue(DirectStoryboardParser.looksLikeJsonInput("""[{"scene_id":1,"tagalog_narration":"Magtabi agad ng ipon sa sahod mo."}]"""))
        assertTrue(DirectStoryboardParser.looksLikeJsonInput("```json\n{\"scenes\":[{\"tagalog_narration\":\"Ipon muna.\"}]}\n```"))
        assertFalse(DirectStoryboardParser.looksLikeJsonInput("[Hook] Alam mo ba kung bakit laging kulang ang sahod mo kahit nagtatrabaho ka araw-araw?"))
        assertTrue(DirectStoryboardParser.COPY_JSON_TEMPLATE.contains("\"scenes\""))
        assertTrue(DirectStoryboardParser.COPY_JSON_TEMPLATE.contains("\"camera_motion\""))
        assertTrue(DirectStoryboardParser.COPY_JSON_TEMPLATE.contains("\"character_pose\""))
        assertTrue(DirectStoryboardParser.COPY_JSON_TEMPLATE.contains("\"shot_framing\""))
        assertTrue(DirectStoryboardParser.COPY_JSON_TEMPLATE.contains("\"outfit_color_hex\""))
        assertTrue(DirectStoryboardParser.COPY_JSON_TEMPLATE.contains("\"hand_prop\""))
        assertTrue(DirectStoryboardParser.COPY_JSON_TEMPLATE.contains("\"callout_banner\""))

        // 3. Plain Tagalog & English scripts split into balanced ~12-18 word scenes with varied motion/poses/framing
        val plainTagalog = """
            Alam mo ba kung bakit parang laging ubos ang sahod mo kahit halos walang pahinga sa trabaho araw-araw?
            Kasi kapag dumating ang sweldo ay nauuna agad ang gastos sa kape, delivery, at biglaang budol online.
            Subukan mong itabi muna ang bente porsyento pagdating ng sahod para siguradong may emergency fund ka sa kinabukasan.
            Kapag sanay ka nang mag-ipon buwan-buwan, mas gagaan ang buhay mo at makakaipon ka para sa negosyo.
        """.trimIndent()

        val result = DirectStoryboardParser.parse(plainTagalog, AnimationStyle.PAPER_DIORAMA)
        assertFalse(result.isJsonFormat)
        assertEquals("Direct Script", result.providerLabel)
        assertTrue("Expected >= 4 balanced scenes, got ${result.scenes.size}", result.scenes.size >= 4)

        val plainEnglish = """
            Did you know why most monthly budgets break before payday even arrives for busy households?
            Start by automating twenty percent savings first so unexpected bills never derail your long-term goals!
        """.trimIndent()
        val englishResult = DirectStoryboardParser.parse(plainEnglish, AnimationStyle.PAPER_DIORAMA)
        assertFalse(englishResult.isJsonFormat)
        assertTrue(englishResult.scenes.size >= 2)
        assertTrue(englishResult.scenes.first().tagalogNarration.contains("Did you know"))

        result.scenes.forEachIndexed { idx, scene ->
            assertEquals(idx + 1, scene.sceneId)
            TagalogNarrationValidator.validateOrThrow(scene.tagalogNarration)
            val wordCount = scene.tagalogNarration.split(Regex("\\s+")).count { it.isNotBlank() }
            assertTrue("Expected ~10-20 words per scene, got $wordCount in: ${scene.tagalogNarration}", wordCount in 8..22)
            assertTrue(scene.visualDescription.contains(AnimationStyle.PAPER_DIORAMA.displayName))
            assertTrue(scene.visualDescription.contains("paper-cut diorama", ignoreCase = true))
            assertTrue(scene.backgroundPrompt.isNotBlank())
        }

        // Verify exact varied rotations across scenes
        assertEquals(CameraMotion.ZOOM_IN, result.scenes[0].cameraMotion)
        assertEquals(CameraMotion.PAN_RIGHT, result.scenes[1].cameraMotion)
        assertEquals(CameraMotion.ZOOM_OUT, result.scenes[2].cameraMotion)
        assertEquals(CameraMotion.PAN_LEFT, result.scenes[3].cameraMotion)

        assertEquals(CharacterPose.THREE_QUARTER_EXPLAIN, result.scenes[0].characterPose)
        assertEquals(CharacterPose.POINT_AND_GESTURE, result.scenes[1].characterPose)
        assertEquals(CharacterPose.WALK_ACROSS_LEFT_TO_RIGHT, result.scenes[2].characterPose)
        assertEquals(CharacterPose.SHOCK_AIRBORNE_RECOIL, result.scenes[3].characterPose)

        assertEquals(ShotFraming.MEDIUM_WAIST_UP, result.scenes[0].shotFraming)
        assertEquals(ShotFraming.RULE_OF_THIRDS_LEFT, result.scenes[1].shotFraming)
        assertEquals(ShotFraming.CLOSE_UP_FACE, result.scenes[2].shotFraming)
        assertEquals(ShotFraming.RULE_OF_THIRDS_RIGHT, result.scenes[3].shotFraming)

        // 5. JSON branch delegates via jsonSceneParser and normalizes STATIC/AUTO defaults
        val parsedJson = DirectStoryboardParser.parse(
            rawInput = DirectStoryboardParser.COPY_JSON_TEMPLATE,
            style = AnimationStyle.TECH_EXPLAINER,
            jsonSceneParser = { raw ->
                val arraySlice = JsonSanitizer.extractJsonArrayText(raw)
                assertTrue(arraySlice.startsWith("["))
                listOf(
                    SceneScript(
                        sceneId = 1,
                        tagalogNarration = "Alam mo ba kung bakit kulang ang sahod mo araw-araw?",
                        backgroundPrompt = "",
                        cameraMotion = CameraMotion.STATIC,
                        actorRole = ActorRole.POOR_MAN,
                        actorAction = ActorAction.SURPRISED_JUMP,
                        worldFx = WorldFx.FIRE_SPARKS,
                        characterPose = CharacterPose.AUTO,
                        shotFraming = ShotFraming.AUTO,
                        handProp = HandProp.MONEY_BAG,
                        calloutBanner = "UBOS AGAD?",
                        visualDescription = ""
                    )
                )
            }
        )
        assertTrue(parsedJson.isJsonFormat)
        assertEquals("Direct JSON Storyboard", parsedJson.providerLabel)
        assertEquals(CameraMotion.ZOOM_IN, parsedJson.scenes[0].cameraMotion)
        assertEquals(CharacterPose.THREE_QUARTER_EXPLAIN, parsedJson.scenes[0].characterPose)
        assertEquals(ShotFraming.MEDIUM_WAIST_UP, parsedJson.scenes[0].shotFraming)
        assertTrue(parsedJson.scenes[0].visualDescription.contains(AnimationStyle.TECH_EXPLAINER.displayName))
        assertEquals(HandProp.MONEY_BAG, parsedJson.scenes[0].handProp)
        assertEquals("UBOS AGAD?", parsedJson.scenes[0].calloutBanner)
    }

    @Test
    fun `motionHtmlEngineFourViralPresetsIdentityLockAndViralCaptionsWork`() {
        // 1. Verify 5 selectable studio viral presets with ATMOSPHERIC_2D_STORY first and CROSS_SECTION 5th
        assertEquals(5, AnimationStyle.STUDIO_VIRAL_PRESETS.size)
        assertEquals(AnimationStyle.ATMOSPHERIC_2D_STORY, AnimationStyle.STUDIO_VIRAL_PRESETS[0])
        assertEquals(AnimationStyle.STYLIZED_25D_EXPLAINER, AnimationStyle.STUDIO_VIRAL_PRESETS[1])
        assertEquals(AnimationStyle.PAPER_DIORAMA, AnimationStyle.STUDIO_VIRAL_PRESETS[2])
        assertEquals(AnimationStyle.KINETIC_TYPOGRAPHY_DIAGRAM, AnimationStyle.STUDIO_VIRAL_PRESETS[3])
        assertEquals(AnimationStyle.CROSS_SECTION, AnimationStyle.STUDIO_VIRAL_PRESETS[4])
        assertEquals("2D Cutaway & System Explainer", AnimationStyle.CROSS_SECTION.displayName)

        assertEquals(MotionPresetEngine.ATMOSPHERIC_2D, MotionHtmlDirector.mapPresetEngine(AnimationStyle.ATMOSPHERIC_2D_STORY))
        assertEquals(MotionPresetEngine.STYLIZED_25D, MotionHtmlDirector.mapPresetEngine(AnimationStyle.STYLIZED_25D_EXPLAINER))
        assertEquals(MotionPresetEngine.PAPER_DIORAMA, MotionHtmlDirector.mapPresetEngine(AnimationStyle.PAPER_DIORAMA))
        assertEquals(MotionPresetEngine.KINETIC_DIAGRAM, MotionHtmlDirector.mapPresetEngine(AnimationStyle.KINETIC_TYPOGRAPHY_DIAGRAM))
        assertEquals(MotionPresetEngine.CUTAWAY_SYSTEM, MotionHtmlDirector.mapPresetEngine(AnimationStyle.CROSS_SECTION))

        // 2. HandProp aliases & DirectStoryboardParser no-chicken guardrail
        assertEquals(HandProp.MONEY_BAG, HandProp.fromRaw("WALLET_CASH"))
        assertEquals(HandProp.BOOK_NOTEBOOK, HandProp.fromRaw("DOCUMENT_PAPER"))
        assertEquals(HandProp.PHONE_SCREEN, HandProp.fromRaw("PHONE"))
        assertFalse(DirectStoryboardParser.COPY_JSON_TEMPLATE.contains("CHICKEN_MENTOR"))

        val plainTagalog = "Alam mo ba kung bakit ubos ang sahod mo kahit todo trabaho ka araw-araw? Magtabi agad ng ipon bago gumastos."
        val plainParsed = DirectStoryboardParser.parse(plainTagalog, AnimationStyle.ATMOSPHERIC_2D_STORY)
        assertTrue(plainParsed.scenes.none { it.actorRole == ActorRole.CHICKEN_MENTOR })

        // 4. Character identity lock & human-mentor protection
        val humanStoryScene1 = SceneScript(
            sceneId = 1,
            tagalogNarration = "Alam mo ba kung bakit ubos ang sahod mo sa gastos?",
            backgroundPrompt = "midnight bedroom",
            cameraMotion = CameraMotion.ZOOM_IN,
            actorRole = ActorRole.HERO,
            actorAction = ActorAction.TALK_BOUNCE,
            worldFx = WorldFx.NONE,
            characterGender = CharacterGender.FEMALE,
            characterAge = CharacterAge.YOUNG_ADULT,
            characterOutfit = CharacterOutfit.HOODIE,
            outfitColorHex = "#E11D48",
            hairColorHex = "#111827",
            handProp = HandProp.MONEY_BAG
        )
        val humanStoryScene2 = humanStoryScene1.copy(
            sceneId = 2,
            actorRole = ActorRole.CHICKEN_MENTOR,
            characterGender = null,
            characterOutfit = null,
            outfitColorHex = "",
            tagalogNarration = "Magtabi agad ng ₱500 para sa emergency fund!"
        )
        assertTrue(MotionHtmlDirector.shouldRenderAsHuman(humanStoryScene2))
        val explicitChickenScene = humanStoryScene2.copy(
            tagalogNarration = "Ang kuwento ng matalinong manok sa bukid!"
        )
        assertFalse(MotionHtmlDirector.shouldRenderAsHuman(explicitChickenScene))

        val locked = MotionHtmlDirector.resolveLockedProfiles(listOf(humanStoryScene1, humanStoryScene2))
        assertEquals(CharacterGender.FEMALE, locked[ActorRole.HERO]?.gender)
        assertEquals("#E11D48", locked[ActorRole.HERO]?.outfitColorHex)

        // 5. Viral 1-2 word caption chunking + crimson/yellow tone classification
        val chunks = MotionHtmlDirector.buildViralCaptionChunks(
            wordTimings = listOf(
                WordTiming("ubos", 0.0, 300.0),
                WordTiming("ang", 300.0, 500.0),
                WordTiming("₱500", 500.0, 900.0),
                WordTiming("ipon", 900.0, 1300.0)
            ),
            fallbackNarration = "",
            sceneStartMs = 0.0,
            sceneEndMs = 1500.0
        )
        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.tokens.size in 1..2 })
        assertEquals("UBOS", chunks[0].tokens[0].text)
        assertEquals(ViralCaptionTone.DANGER, chunks[0].tokens[0].tone)
        assertEquals("₱500", chunks[1].tokens[0].text)
        assertEquals(ViralCaptionTone.ACCENT, chunks[1].tokens[0].tone)

        // 6. Deterministic HTML5/SVG document generation with seekToTime & XSS escaping
        val timelineScenes = listOf(
            TimelineScene(
                scene = humanStoryScene1.copy(calloutBanner = "UBOS </script> SAHOD?"),
                startMs = 0.0,
                endMs = 3000.0,
                wordTimings = emptyList()
            )
        )
        val html = MotionHtmlDirector.buildHtmlDocument(
            scenes = timelineScenes,
            style = AnimationStyle.ATMOSPHERIC_2D_STORY,
            totalDurationMs = 3000.0
        )
        assertTrue(html.contains("window.seekToTime = function(seconds)"))
        assertTrue(html.contains("viewBox=\"0 0 1080 1920\""))
        assertTrue(html.contains("transform=\"translate(540, 1420)\""))
        assertTrue(html.contains("#FFD60A"))
        assertTrue(html.contains("#FF453A"))
        assertTrue(html.contains("#0B0E14"))
        assertTrue(html.contains("cinema-grain-rect"))
        assertTrue(html.contains("opacity=\"0.035\""))
        assertTrue(html.contains("rim-light-grad"))
        assertTrue(html.contains("editorial-hook-overlay"))
        assertTrue(html.contains("subBeatZoom"))
        assertFalse(html.contains("UBOS </script>"))
    }

    @Test
    fun `cutawayPresetTemplateNoDefaultHostSfxMixerAndViralLoopWork`() {
        // 1. No default standing host character unless explicitly requested or story mentions person/animal
        val systemScene = SceneScript(
            sceneId = 1,
            tagalogNarration = "Paano gumagana ang solar panel at water pump sa bahay?",
            backgroundPrompt = "solar roof and underground pipes cutaway",
            cameraMotion = CameraMotion.ZOOM_IN,
            actorRole = ActorRole.NARRATOR,
            actorAction = ActorAction.TALK_BOUNCE,
            worldFx = WorldFx.NONE,
            hudWidget = HudWidget.PROGRESS_GAUGE,
            hudLabel = "SOLAR OUTPUT",
            hudUnit = "W",
            hudFromValue = 120,
            hudToValue = 4800,
            statusPill = StatusPill.FIX,
            calloutBanner = "✓ FULL POWER",
            lightingState = LightingState.RAIN_STORM,
            sfxCues = listOf("DING_SUCCESS", "WHOOSH_CUT")
        )
        assertFalse(MotionHtmlDirector.shouldRenderCharacter(systemScene, MotionPresetEngine.CUTAWAY_SYSTEM))
        assertFalse(MotionHtmlDirector.shouldRenderCharacter(systemScene, MotionPresetEngine.ATMOSPHERIC_2D))
        assertTrue(MotionHtmlDirector.shouldRenderCharacter(systemScene.copy(hasCharacter = true), MotionPresetEngine.CUTAWAY_SYSTEM))
        assertTrue(
            MotionHtmlDirector.shouldRenderCharacter(
                systemScene.copy(tagalogNarration = "May matandang lola na nag-aayos ng tubo sa hardin."),
                MotionPresetEngine.ATMOSPHERIC_2D
            )
        )

        // 2. CutawayExplainerTemplate.html builds complete 1080x1920 cutaway HTML with path-flow & cinema post-FX
        val cutawayTimeline = listOf(
            TimelineScene(
                scene = systemScene,
                startMs = 0.0,
                endMs = 3600.0,
                wordTimings = emptyList()
            )
        )
        val cutawayHtml = MotionHtmlDirector.buildHtmlDocument(
            scenes = cutawayTimeline,
            style = AnimationStyle.CROSS_SECTION,
            totalDurationMs = 3600.0
        )
        assertTrue(cutawayHtml.contains("window.seekToTime = function(seconds)"))
        assertTrue(cutawayHtml.contains("tank-left-clip"))
        assertTrue(cutawayHtml.contains("pointAlongSegments"))
        assertTrue(cutawayHtml.contains("cinema-grain-rect"))
        assertTrue(cutawayHtml.contains("opacity=\"0.035\""))
        assertTrue(cutawayHtml.contains("RAIN_STORM"))
        assertTrue(cutawayHtml.contains("SOLAR OUTPUT"))
        assertTrue(cutawayHtml.contains("4800"))

        // 3. JsonSanitizer parses hud_unit, hud_value_from, hud_value_to, has_character, lighting_state, and sfx
        val jsonPayload = """
            {"scenes":[{
              "scene_id": 1,
              "tagalog_narration": "Tingnan kung paano umiikot ang kuryente at tubig sa ilalim ng lupa.",
              "visual_description": "Split-level vector cutaway showing rooftop solar panels feeding battery bank and underground water tank circulation pipes with bubbles.",
              "background_prompt": "clean rooftop solar and soil cross-section stage, empty foreground, no humans",
              "camera_motion": "ZOOM_IN",
              "actor_role": "NARRATOR",
              "actor_action": "POINT_STICK",
              "world_fx": "NONE",
              "hud_widget": "PROGRESS_GAUGE",
              "hud_label": "TANK FILTER",
              "hud_unit": "PPM",
              "hud_value_from": 950,
              "hud_value_to": 42,
              "has_character": false,
              "lighting_state": "NIGHT",
              "sfx": ["POP_CALLOUT", "DING_SUCCESS"]
            }]}
        """.trimIndent()
        val parsedScenes = JsonSanitizer.parseAndValidateScenes(jsonPayload)
        assertEquals(1, parsedScenes.size)
        val parsed = parsedScenes[0]
        assertEquals("PPM", parsed.hudUnit)
        assertEquals(950, parsed.hudFromValue)
        assertEquals(42, parsed.hudToValue)
        assertEquals(false, parsed.hasCharacter)
        assertEquals(LightingState.NIGHT, parsed.lightingState)
        assertEquals(listOf("POP_CALLOUT", "DING_SUCCESS"), parsed.sfxCues)

        // 4. Procedural SFX synthesizer & timeline scheduler
        for (cue in ProceduralSfxCue.entries) {
            val samples = SfxTimelineMixer.synthesizeCueSamples(cue)
            assertTrue("Expected non-empty PCM for $cue", samples.isNotEmpty())
            assertTrue("Expected non-silent signal for $cue", samples.any { abs(it) > 0.01f })
            assertTrue("Expected bounded samples for $cue", samples.all { it in -1.0f..1.0f })
        }

        val sfxTimeline = SfxTimelineMixer.buildSfxTimeline(
            scenes = listOf(
                systemScene,
                systemScene.copy(sceneId = 2, statusPill = StatusPill.PROBLEM, calloutBanner = "✕ LOW PRESSURE")
            ),
            sceneStartMs = listOf(0.0, 3000.0),
            sceneEndMs = listOf(3000.0, 6000.0),
            style = AnimationStyle.CROSS_SECTION
        )
        assertTrue(sfxTimeline.any { it.cue == ProceduralSfxCue.SUB_BASS_IMPACT && abs(it.timeSec - 0.20) < 0.01 })
        assertTrue(sfxTimeline.any { it.cue == ProceduralSfxCue.WHOOSH_CUT })
        assertTrue(sfxTimeline.any { it.cue == ProceduralSfxCue.POP_CALLOUT })
        assertTrue(sfxTimeline.any { it.cue == ProceduralSfxCue.DING_SUCCESS })
        assertTrue(sfxTimeline.any { it.cue == ProceduralSfxCue.BUZZ_ERROR })

        val mixBuf = FloatArray(SfxTimelineMixer.SAMPLE_RATE * 4)
        SfxTimelineMixer.mixSfxEventsIntoBuffer(mixBuf, sfxTimeline)
        assertTrue(mixBuf.any { abs(it) > 0.01f })

        // 5. _cover.jpg naming helper
        assertEquals("ToonForge_123_cover.jpg", VideoGalleryRepository.coverFileNameForVideo("ToonForge_123.mp4"))
    }

    @Test
    fun `placeholderGraphicsRemovedAndBespokeSvgCustomHtmlHybridWorks`() {
        // 1. Verify NO Play-button icon or generic renderThemeDetails exist in MotionHtmlDirector
        val scene = SceneScript(
            sceneId = 1,
            tagalogNarration = "Tingnan kung paano gumagana ang solar battery inverter sa bahay.",
            backgroundPrompt = "solar panel roof diagram",
            cameraMotion = CameraMotion.ZOOM_IN,
            actorRole = ActorRole.NARRATOR,
            actorAction = ActorAction.TALK_BOUNCE,
            worldFx = WorldFx.NONE,
            hasCharacter = false,
            customSvg = """
                <svg viewBox="0 0 1080 1920" xmlns="http://www.w3.org/2000/svg">
                  <path id="solar-wire" d="M220,520 L540,780 L840,780" stroke="#FACC15" stroke-width="6" fill="none" />
                  <circle class="flow-pulse" data-path="#solar-wire" r="8" fill="#38BDF8" />
                  <script>alert('xss')</script>
                </svg>
            """.trimIndent()
        )
        val html = MotionHtmlDirector.buildHtmlDocument(
            scenes = listOf(TimelineScene(scene = scene, startMs = 0.0, endMs = 3500.0, wordTimings = emptyList())),
            style = AnimationStyle.ATMOSPHERIC_2D_STORY,
            totalDurationMs = 3500.0
        )
        assertFalse(html.contains("-28,-30 48,15 -28,60"))
        assertFalse(html.contains("function renderThemeDetails"))
        assertTrue(html.contains("id=\"custom-svg-layer\""))
        assertTrue(html.contains("id=\"flow-overlay-layer\""))
        assertTrue(html.contains("elBackdropImg.setAttribute(\"opacity\", \"1\")"))
        assertTrue(html.contains("customSvg"))

        val cutawayHtml = MotionHtmlDirector.buildHtmlDocument(
            scenes = listOf(TimelineScene(scene = scene, startMs = 0.0, endMs = 3500.0, wordTimings = emptyList())),
            style = AnimationStyle.CROSS_SECTION,
            totalDurationMs = 3500.0
        )
        assertFalse(cutawayHtml.contains("M-80,930"))
        assertTrue(cutawayHtml.contains("id=\"custom-svg-layer\""))
        assertTrue(cutawayHtml.contains(".flow-pulse"))

        // 2. JsonSanitizer parses & sanitizes custom_svg safely (strips <script>, preserves vector primitives)
        val jsonWithSvg = """
            {"scenes":[{
              "scene_id": 1,
              "tagalog_narration": "Paano umiikot ang malinis na tubig sa underground filter tank?",
              "visual_description": "Layered cutaway SVG of underground gravel water filter chamber with glowing cyan flow pulses and clean dark outlines.",
              "background_prompt": "underground filter cutaway",
              "camera_motion": "PAN_RIGHT",
              "actor_role": "NARRATOR",
              "actor_action": "POINT_STICK",
              "world_fx": "NONE",
              "custom_svg": "<svg viewBox=\"0 0 1080 1920\" onload=\"evil()\"><path id=\"pipe\" d=\"M100,900 L900,900\" stroke=\"#141821\" stroke-width=\"2.5\"/><circle class=\"flow-pulse\" data-path=\"#pipe\" r=\"7\" fill=\"#22D3EE\"/><script>bad()</script></svg>"
            }]}
        """.trimIndent()
        val parsedList = JsonSanitizer.parseAndValidateScenes(jsonWithSvg)
        assertEquals(1, parsedList.size)
        val parsedSvg = parsedList[0].customSvg
        assertTrue(parsedSvg.contains("<path id=\"pipe\""))
        assertTrue(parsedSvg.contains("flow-pulse"))
        assertFalse(parsedSvg.contains("<script>"))
        assertFalse(parsedSvg.contains("onload="))

        // 3. DirectStoryboardParser detects and passes through raw HTML & SVG verbatim without template wrapping
        val rawHtml = """
            <!DOCTYPE html>
            <html><head><meta name="toonforge-narration" content="Alam mo ba ang sekreto ng solar inverter sa gabi?"></head>
            <body><svg viewBox="0 0 1080 1920"><rect width="1080" height="1920" fill="#0B1120"/></svg>
            <script>window.seekToTime = function(s) { window.__t = s; };</script></body></html>
        """.trimIndent()
        assertTrue(DirectStoryboardParser.looksLikeHtmlOrSvgInput(rawHtml))
        assertFalse(DirectStoryboardParser.looksLikeJsonInput(rawHtml))
        val parsedHtmlRes = DirectStoryboardParser.parse(rawHtml, AnimationStyle.ATMOSPHERIC_2D_STORY)
        assertTrue(parsedHtmlRes.isCustomHtmlOrSvg)
        assertEquals("Direct Custom HTML", parsedHtmlRes.providerLabel)
        assertEquals(rawHtml, parsedHtmlRes.customHtmlDocument)
        assertTrue(parsedHtmlRes.scenes[0].tagalogNarration.contains("solar inverter"))

        val rawSvg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><path id=\"p1\" d=\"M100,500 L980,500\" stroke=\"#38BDF8\"/><circle class=\"flow-pulse\" data-path=\"#p1\" r=\"8\" fill=\"#FFD60A\"/></svg>"
        assertTrue(DirectStoryboardParser.looksLikeHtmlOrSvgInput("```svg\n$rawSvg\n```"))
        assertEquals("Custom SVG • Ready", DirectStoryboardParser.estimatePreviewSummary(rawSvg))
        val parsedSvgRes = DirectStoryboardParser.parse(rawSvg, AnimationStyle.CROSS_SECTION)
        assertTrue(parsedSvgRes.isCustomHtmlOrSvg)
        assertEquals("Direct Custom SVG", parsedSvgRes.providerLabel)
        val wrappedSvgHtml = parsedSvgRes.customHtmlDocument.orEmpty()
        assertTrue(wrappedSvgHtml.contains("viewBox=\"0 0 1080 1920\""))
        assertTrue(wrappedSvgHtml.contains("window.seekToTime = function(seconds)"))
        assertTrue(wrappedSvgHtml.contains(".flow-pulse"))
    }

    @Test
    fun `customHtmlStoryboardJsonExtractionTimingScriptAndBoundarySfx`() {
        val htmlWithStoryboardJson = """
            <!DOCTYPE html>
            <html lang="tl">
            <head><meta charset="utf-8" /></head>
            <body>
              <svg viewBox="0 0 1080 1920"><rect width="1080" height="1920" fill="#0F172A" /></svg>
              <script>
                // Reference storyboard-json before the JSON tag to ensure attribute matching ignores JS code
                var sceneDurations = [2.0, 2.0, 2.0];
                window.setSceneTimings = function(arr) { sceneDurations = arr; };
                window.seekToTime = function(sec) { window.__t = sec; };
              </script>
              <script id="storyboard-json" type="application/json">
              [
                {
                  "scene_id": 1,
                  "tagalog_narration": "Una, tingnan ang kuryente sa bahay."
                },
                {
                  "scene_id": 2,
                  "tagalog_narration": "Solar panel!"
                },
                {
                  "scene_id": 3,
                  "tagalog_narration": "Bawas agad ang monthly bill mo sa Meralco."
                }
              ]
              </script>
            </body>
            </html>
        """.trimIndent()

        val parsed = DirectStoryboardParser.parse(htmlWithStoryboardJson, AnimationStyle.CROSS_SECTION)
        assertTrue(parsed.isCustomHtmlOrSvg)
        assertEquals("Direct Custom HTML", parsed.providerLabel)
        assertEquals(3, parsed.scenes.size)
        assertEquals("Una, tingnan ang kuryente sa bahay.", parsed.scenes[0].tagalogNarration)
        assertEquals("Solar panel!", parsed.scenes[1].tagalogNarration)
        assertEquals("Bawas agad ang monthly bill mo sa Meralco.", parsed.scenes[2].tagalogNarration)
        assertFalse(parsed.scenes.any { it.tagalogNarration.contains("Narito ang detalyadong animated visual explainer") })
        assertEquals("Custom HTML • 3 scene(s)", DirectStoryboardParser.estimatePreviewSummary(htmlWithStoryboardJson))
        assertTrue(DirectStoryboardParser.deriveTopicTitle(htmlWithStoryboardJson).startsWith("Una, tingnan"))

        // Verify exact JS timing sync call for WebViewFrameRenderer
        val timingJs = com.example.render.WebViewFrameRenderer.buildSetSceneTimingsScript(listOf(2.45, 1.35, 3.8))
        assertEquals("if(window.setSceneTimings){window.setSceneTimings([2.4500,1.3500,3.8000]);}", timingJs)

        // Verify custom HTML SFX timeline fires SUB_BASS_IMPACT at 0.1s and WHOOSH_CUT + POP_CALLOUT at every scene boundary
        val customSfxTimeline = SfxTimelineMixer.buildSfxTimeline(
            scenes = parsed.scenes,
            sceneStartMs = listOf(0.0, 2450.0, 3800.0),
            sceneEndMs = listOf(2450.0, 3800.0, 7600.0),
            style = AnimationStyle.ATMOSPHERIC_2D_STORY,
            alwaysTriggerSceneBoundarySfx = true
        )
        assertTrue(customSfxTimeline.any { it.cue == ProceduralSfxCue.SUB_BASS_IMPACT && abs(it.timeSec - 0.10) < 0.001 })
        assertTrue(customSfxTimeline.any { it.cue == ProceduralSfxCue.POP_CALLOUT && abs(it.timeSec - 0.0) < 0.001 })
        assertTrue(customSfxTimeline.any { it.cue == ProceduralSfxCue.WHOOSH_CUT && abs(it.timeSec - 2.45) < 0.001 })
        assertTrue(customSfxTimeline.any { it.cue == ProceduralSfxCue.POP_CALLOUT && abs(it.timeSec - 2.45) < 0.001 })
        assertTrue(customSfxTimeline.any { it.cue == ProceduralSfxCue.WHOOSH_CUT && abs(it.timeSec - 3.80) < 0.001 })
        assertTrue(customSfxTimeline.any { it.cue == ProceduralSfxCue.POP_CALLOUT && abs(it.timeSec - 3.80) < 0.001 })
    }

    @Test
    fun `contextualSfxGodPromptV2BatchQueue12MbpsAndLoopTailWork`() {
        // 1. Contextual Procedural SFX: explicit cues + Tagalog keyword auto-triggers
        assertEquals(ProceduralSfxCue.DOOR_KNOCK_3X, ProceduralSfxCue.fromRaw("door_knock_3x"))
        assertEquals(ProceduralSfxCue.HEARTBEAT_THUMP, ProceduralSfxCue.fromRaw("heartbeat"))
        assertEquals(ProceduralSfxCue.TENSION_RISER, ProceduralSfxCue.fromRaw("riser"))
        assertEquals(ProceduralSfxCue.ELECTRIC_HUM, ProceduralSfxCue.fromRaw("hum"))
        assertEquals(9, ProceduralSfxCue.entries.size)

        val keywordScenes = listOf(
            SceneScript(
                sceneId = 1,
                tagalogNarration = "May malakas na katok sa pinto habang bumibilis ang tibok ng dibdib.",
                backgroundPrompt = "dark hallway door",
                cameraMotion = CameraMotion.ZOOM_IN,
                actorRole = ActorRole.NARRATOR,
                actorAction = ActorAction.TALK_BOUNCE,
                worldFx = WorldFx.NONE
            ),
            SceneScript(
                sceneId = 2,
                tagalogNarration = "Malaking panganib kung hindi maayos ang wiring ng solar panel.",
                backgroundPrompt = "rooftop solar inverter",
                cameraMotion = CameraMotion.PAN_RIGHT,
                actorRole = ActorRole.NARRATOR,
                actorAction = ActorAction.POINT_STICK,
                worldFx = WorldFx.NONE,
                calloutBanner = "SOLAR INVERTER"
            )
        )
        val keywordTimeline = SfxTimelineMixer.buildSfxTimeline(
            scenes = keywordScenes,
            sceneStartMs = listOf(0.0, 3000.0),
            sceneEndMs = listOf(3000.0, 6000.0),
            style = AnimationStyle.ATMOSPHERIC_2D_STORY
        )
        assertTrue(keywordTimeline.any { it.cue == ProceduralSfxCue.DOOR_KNOCK_3X })
        assertTrue(keywordTimeline.any { it.cue == ProceduralSfxCue.HEARTBEAT_THUMP })
        assertTrue(keywordTimeline.any { it.cue == ProceduralSfxCue.TENSION_RISER })
        assertTrue(keywordTimeline.any { it.cue == ProceduralSfxCue.ELECTRIC_HUM })

        // 2. Multi-Video Batch Render Queue serialization & 5-item cap
        assertEquals(5, VideoGenerationService.MAX_BATCH_QUEUE_SIZE)
        val batchItems = (1..7).map { idx ->
            RenderBatchItem(
                topic = "Solar Explainer #$idx",
                style = AnimationStyle.CROSS_SECTION,
                durationSec = 30,
                voiceIdOverride = "N2IVS1w4EtoT3dr4eOWO",
                productionMode = ProductionMode.FULL_AI_STORY,
                importedClipUris = emptyList(),
                scriptSourceModeOverride = ScriptSourceMode.DIRECT_SCRIPT,
                directScriptOverride = "<!DOCTYPE html><html><body>$idx</body></html>"
            )
        }
        val serialized = VideoGenerationService.serializeBatchItemsToJson(batchItems)
        val deserialized = VideoGenerationService.deserializeBatchItemsFromJson(serialized)
        assertEquals(5, deserialized.size)
        assertEquals("Solar Explainer #1", deserialized.first().topic)
        assertEquals("Solar Explainer #5", deserialized.last().topic)
        assertEquals(ScriptSourceMode.DIRECT_SCRIPT, deserialized.first().scriptSourceModeOverride)
        assertTrue(deserialized.first().directScriptOverride.contains("<!DOCTYPE html>"))

        // 4. 12 Mbps bitrate & 0.35s (350ms) seamless loop tail padding
        assertEquals(12_000_000, DeterministicVideoExporter.VIDEO_BITRATE)
        assertEquals(350.0, AudioPcmStitcher.SEAMLESS_LOOP_TAIL_MS, 0.001)
        val oneSecPcm = ByteArray(44_100 * 2)
        val stitchedNoTail = AudioPcmStitcher.stitchScenes(
            decodedPcmPerScene = listOf(oneSecPcm),
            localTimingsPerScene = listOf(emptyList()),
            narrationPerScene = listOf("Isang segundong linya.")
        )
        val stitchedWithTail = AudioPcmStitcher.stitchScenes(
            decodedPcmPerScene = listOf(oneSecPcm),
            localTimingsPerScene = listOf(emptyList()),
            narrationPerScene = listOf("Isang segundong linya."),
            loopTailPaddingMs = AudioPcmStitcher.SEAMLESS_LOOP_TAIL_MS
        )
        assertTrue(
            "Expected >= 350ms loop tail extension (noTail=${stitchedNoTail.totalDurationMs}, withTail=${stitchedWithTail.totalDurationMs})",
            stitchedWithTail.totalDurationMs - stitchedNoTail.totalDurationMs >= 349.0
        )
    }

    @Test
    fun `elevenV4ModelDefaultsDialogueFallbackAndAudioTagCaptionStrippingWork`() {
        // 1. Default model ID & supported presets
        assertEquals("eleven_v4", ElevenLabsClient.DEFAULT_TTS_MODEL_ID)
        assertEquals("eleven_v4", StudioSettings().effectiveTtsModelId)
        assertEquals(
            listOf("eleven_v4", "eleven_v4_turbo", "eleven_multilingual_v2"),
            ElevenLabsClient.SUPPORTED_TTS_MODEL_PRESETS.map { it.id }
        )

        // 2. Strict eleven_v4 /with-timestamps payload (ONLY text, model_id, voice_settings; NO speed/language_code)
        val v4Payload = ElevenLabsClient.buildTtsRequestPayload(
            normalizedText = "[whispers] Huwag kang maingay.",
            normPrev = "Nakaraan",
            normNext = "Susunod",
            narrationSpeed = 1.15f,
            modelId = "eleven_v4"
        )
        assertEquals("[whispers] Huwag kang maingay.", v4Payload.getString("text"))
        assertEquals("eleven_v4", v4Payload.getString("model_id"))
        assertFalse(v4Payload.has("language_code"))
        assertFalse(v4Payload.has("previous_text"))
        assertFalse(v4Payload.has("next_text"))
        val v4Settings = v4Payload.getJSONObject("voice_settings")
        assertEquals(0.45, v4Settings.getDouble("stability"), 0.001)
        assertEquals(0.78, v4Settings.getDouble("similarity_boost"), 0.001)
        assertEquals(0.35, v4Settings.getDouble("style"), 0.001)
        assertTrue(v4Settings.getBoolean("use_speaker_boost"))
        assertFalse(v4Settings.has("speed"))

        // Legacy model keeps speed
        val v2Payload = ElevenLabsClient.buildTtsRequestPayload(
            normalizedText = "Normal na boses.",
            normPrev = null,
            normNext = null,
            narrationSpeed = 1.10f,
            modelId = "eleven_multilingual_v2"
        )
        assertEquals(1.10, v2Payload.getJSONObject("voice_settings").getDouble("speed"), 0.001)

        // 3. /v1/text-to-dialogue payload builder
        val dialoguePayload = ElevenLabsClient.buildTextToDialoguePayload(
            normalizedText = "[tense] Delikado ito!",
            voiceId = "voice123",
            modelId = "eleven_v4"
        )
        assertEquals("eleven_v4", dialoguePayload.getString("model_id"))
        val inputs = dialoguePayload.getJSONArray("inputs")
        assertEquals(1, inputs.length())
        assertEquals("[tense] Delikado ito!", inputs.getJSONObject(0).getString("text"))
        assertEquals("voice123", inputs.getJSONObject(0).getString("voice_id"))
        assertEquals(0.45, dialoguePayload.getJSONObject("settings").getDouble("stability"), 0.001)

        // 4. End-to-end synthesizeScene HTTP fallback: /with-timestamps 400 -> /v1/text-to-dialogue 200
        val capturedCalls = mutableListOf<Pair<String, String>>()
        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val reqBody = req.body?.let { body ->
                    val buf = Buffer()
                    body.writeTo(buf)
                    buf.readUtf8()
                }.orEmpty()
                capturedCalls.add(req.url.encodedPath to reqBody)
                when {
                    req.url.encodedPath.endsWith("/v1/user/subscription") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"character_count":0,"character_limit":10000,"tier":"creator"}""".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    req.url.encodedPath.endsWith("/with-timestamps") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(400)
                            .message("Bad Request")
                            .body("""{"detail":"timestamps unsupported for v4"}""".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    req.url.encodedPath.endsWith("/v1/text-to-dialogue") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(byteArrayOf(0x49, 0x44, 0x33, 0x03).toResponseBody("audio/mpeg".toMediaType()))
                            .build()
                    }
                    else -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("{}".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                }
            }
            .build()

        val client = ElevenLabsClient(
            context = null,
            httpClient = mockHttpClient,
            audioDecoder = { _, _, _ -> ByteArray(44_100 * 2 * 2) } // 2 seconds PCM
        )
        val result = runBlocking {
            client.synthesizeScene(
                rawTagalogNarration = "[whispers] Huwag kang maingay [tense] baka marinig tayo.",
                voiceId = "voice123",
                keyPool = listOf(ElevenLabsKeyEntry(key = "xi-test-key")),
                tempCacheDir = File("."),
                ttsModelId = "eleven_v4",
                onKeyStatusUpdate = {}
            )
        }
        val tsCall = capturedCalls.first { it.first.endsWith("/with-timestamps") }
        val tsBodyJson = JSONObject(tsCall.second)
        assertTrue(tsBodyJson.getString("text").contains("[whispers]"))
        assertTrue(tsBodyJson.getString("text").contains("[tense]"))
        assertFalse(tsBodyJson.getJSONObject("voice_settings").has("speed"))
        assertFalse(tsBodyJson.has("language_code"))

        val dialogueCall = capturedCalls.first { it.first.endsWith("/v1/text-to-dialogue") }
        val dialogueBodyJson = JSONObject(dialogueCall.second)
        assertEquals("eleven_v4", dialogueBodyJson.getString("model_id"))
        assertTrue(
            dialogueBodyJson.getJSONArray("inputs").getJSONObject(0).getString("text").contains("[whispers]")
        )
        assertEquals(
            listOf("Huwag", "kang", "maingay", "baka", "marinig", "tayo."),
            result.localWordTimings.map { it.word }
        )

        // 5. KaraokeCaptionRenderer + alignment mapping strip [whispers] / [tense] / [excited]
        assertEquals(
            "Grabe talaga ang pagtitipid!",
            KaraokeCaptionRenderer.stripAudioTags("[whispers] Grabe [excited] talaga ang pagtitipid!")
        )
        val cleanTokens = KaraokeCaptionRenderer.extractCleanCaptionTokens("[tense] Delikado ito [whispers] kaibigan!")
        assertEquals(listOf("Delikado", "ito", "kaibigan!"), cleanTokens)

        val rawTimings = listOf(
            WordTiming("[whispers]", 0.0, 200.0),
            WordTiming("Huwag", 200.0, 500.0),
            WordTiming("[slow", 500.0, 600.0),
            WordTiming("pause]", 600.0, 700.0),
            WordTiming("gastos!", 700.0, 1100.0)
        )
        val sanitizedTimings = KaraokeCaptionRenderer.sanitizeCaptionWordTimings(rawTimings)
        assertEquals(listOf("Huwag", "gastos!"), sanitizedTimings.map { it.word })
        assertEquals(200.0, sanitizedTimings[0].startMs, 0.01)
        assertEquals(700.0, sanitizedTimings[1].startMs, 0.01)

        val chunks = MotionHtmlDirector.buildViralCaptionChunks(
            wordTimings = rawTimings,
            fallbackNarration = "[whispers] Huwag gastos!",
            sceneStartMs = 0.0,
            sceneEndMs = 1500.0
        )
        val renderedTokens = chunks.flatMap { c -> c.tokens.map { it.text } }
        assertEquals(listOf("HUWAG", "GASTOS!"), renderedTokens)

        // Alignment characters containing [excited] should be skipped cleanly without shifting spoken word times
        val alignmentChars = "[excited] Ok".toCharArray().map { it.toString() }
        val alignmentJson = JSONObject().apply {
            put("characters", JSONArray(alignmentChars))
            put("character_start_times_seconds", JSONArray(List(alignmentChars.size) { it * 0.1 }))
            put("character_end_times_seconds", JSONArray(List(alignmentChars.size) { (it + 1) * 0.1 }))
        }
        val mapped = ElevenLabsClient.mapAlignmentToDisplayWords(alignmentJson, listOf("[excited]", "Ok"))
        assertEquals(1, mapped.size)
        assertEquals("Ok", mapped[0].word)
        assertEquals(1000.0, mapped[0].startMs, 1.0)
    }

    @Test
    fun `dualVoiceRoutingResponsiveCaptionsAndAspectRatioSwitcherWork`() {
        // 1. DirectStoryboardParser extracts window.STORYBOARD_SCENES = [...] with "speaker"
        val htmlWithJsStoryboard = """
            <!DOCTYPE html>
            <html>
            <body>
            <svg id="stage" viewBox="0 0 1080 1920"></svg>
            <script>
              window.STORYBOARD_SCENES = [
                {
                  "scene_id": 1,
                  "speaker": "rat",
                  "tagalog_narration": "Ako si Matalinong Daga at may plano tayo."
                },
                {
                  "scene_id": 2,
                  "speaker": "cat",
                  "tagalog_narration": "Sige Daga, ako naman si Pusa at handa na ako!"
                }
              ];
              window.setSceneTimings = function(d) {};
              window.seekToTime = function(t) {};
            </script>
            </body>
            </html>
        """.trimIndent()
        val parseResult = DirectStoryboardParser.parse(
            rawInput = htmlWithJsStoryboard,
            style = AnimationStyle.ATMOSPHERIC_2D_STORY
        )
        val parsedScenes = parseResult.scenes
        assertEquals(2, parsedScenes.size)
        assertEquals("rat", parsedScenes[0].speaker)
        assertEquals("cat", parsedScenes[1].speaker)
        assertTrue(parsedScenes[1].tagalogNarration.contains("Pusa"))

        // 2. Dual Voice ID routing logic
        val ratVoice = "voice_rat_primary"
        val catVoice = "voice_cat_secondary"
        assertEquals(catVoice, VideoGenerationService.resolveSceneVoiceId("cat", ratVoice, catVoice))
        assertEquals(catVoice, VideoGenerationService.resolveSceneVoiceId("CAT", ratVoice, catVoice))
        assertEquals(ratVoice, VideoGenerationService.resolveSceneVoiceId("rat", ratVoice, catVoice))
        assertEquals(ratVoice, VideoGenerationService.resolveSceneVoiceId("", ratVoice, catVoice))
        assertEquals(ratVoice, VideoGenerationService.resolveSceneVoiceId("cat", ratVoice, "   "))

        // 3. Batch item serialization roundtrip preserves voiceIdCatOverride & LANDSCAPE_16_9
        val batchWithDualAndWide = listOf(
            RenderBatchItem(
                topic = "Daga vs Pusa Widescreen",
                style = AnimationStyle.ATMOSPHERIC_2D_STORY,
                durationSec = 30,
                voiceIdOverride = ratVoice,
                productionMode = ProductionMode.FULL_AI_STORY,
                importedClipUris = emptyList(),
                scriptSourceModeOverride = ScriptSourceMode.DIRECT_SCRIPT,
                directScriptOverride = htmlWithJsStoryboard,
                voiceIdCatOverride = catVoice,
                aspectRatio = VideoAspectRatio.LANDSCAPE_16_9
            )
        )
        val encodedBatch = VideoGenerationService.serializeBatchItemsToJson(batchWithDualAndWide)
        val decodedBatch = VideoGenerationService.deserializeBatchItemsFromJson(encodedBatch)
        assertEquals(1, decodedBatch.size)
        assertEquals(ratVoice, decodedBatch[0].voiceIdOverride)
        assertEquals(catVoice, decodedBatch[0].voiceIdCatOverride)
        assertEquals(VideoAspectRatio.LANDSCAPE_16_9, decodedBatch[0].aspectRatio)
        assertEquals(1920, decodedBatch[0].aspectRatio.width)
        assertEquals(1080, decodedBatch[0].aspectRatio.height)

        // 4. Auto-fit responsive captions stay <= 86% canvas width and <= 2 lines on both 9:16 and 16:9
        val longTagalogPhrase = listOf(
            WordTiming("Napakadelikado", 0.0, 400.0),
            WordTiming("makipagsapalaran", 400.0, 900.0),
            WordTiming("sa", 900.0, 1100.0),
            WordTiming("kagubatan!", 1100.0, 1600.0)
        )
        val shortPhrase = listOf(
            WordTiming("Tara", 0.0, 300.0),
            WordTiming("na!", 300.0, 600.0)
        )

        val fitPortraitLong = KaraokeCaptionRenderer.fitCaptionLayout(
            words = longTagalogPhrase,
            canvasWidthPx = 1080f,
            canvasHeightPx = 1920f
        )
        val fitPortraitShort = KaraokeCaptionRenderer.fitCaptionLayout(
            words = shortPhrase,
            canvasWidthPx = 1080f,
            canvasHeightPx = 1920f
        )
        assertTrue(fitPortraitLong.lines.size <= 2)
        assertTrue(fitPortraitLong.maxLineWidthPx <= 1080f * 0.86f + 0.5f)
        assertTrue(fitPortraitLong.fontSizePx < fitPortraitShort.fontSizePx)

        val fitLandscapeLong = KaraokeCaptionRenderer.fitCaptionLayout(
            words = longTagalogPhrase,
            canvasWidthPx = 1920f,
            canvasHeightPx = 1080f
        )
        assertTrue(fitLandscapeLong.lines.size <= 2)
        assertTrue(fitLandscapeLong.maxLineWidthPx <= 1920f * 0.86f + 0.5f)

        val megaSingleWord = listOf(WordTiming("Pinakamapanganibangpaglalakbay", 0.0, 1200.0))
        val fitMegaWord = KaraokeCaptionRenderer.fitCaptionLayout(
            words = megaSingleWord,
            canvasWidthPx = 1080f,
            canvasHeightPx = 1920f
        )
        assertTrue(fitMegaWord.lines.size <= 2)
        assertTrue(fitMegaWord.maxLineWidthPx <= 1080f * 0.86f + 0.5f)
        assertTrue(fitMegaWord.fontSizePx < fitPortraitShort.fontSizePx)

        // 5. WebView aspect ratio script + MotionHtmlDirector 16:9 HTML + raw <svg> wrapper
        val aspectJs = WebViewFrameRenderer.buildSetAspectRatioScript("16:9")
        assertTrue(aspectJs.contains("window.setAspectRatio('16:9')"))

        val timelineScenes = parsedScenes.mapIndexed { idx, scene ->
            TimelineScene(
                scene = scene,
                startMs = idx * 4000.0,
                endMs = (idx + 1) * 4000.0,
                wordTimings = emptyList()
            )
        }
        val wideHtml = MotionHtmlDirector.buildHtmlDocument(
            scenes = timelineScenes,
            style = AnimationStyle.ATMOSPHERIC_2D_STORY,
            totalDurationMs = 8000.0,
            aspectRatio = VideoAspectRatio.LANDSCAPE_16_9
        )
        assertTrue(wideHtml.contains("viewBox=\"0 0 1920 1080\""))
        assertTrue(wideHtml.contains("window.setAspectRatio"))
        assertTrue(wideHtml.contains("STAGE_W * 0.86"))

        val wrappedSvg = DirectStoryboardParser.buildCustomHtmlDocumentIfNeeded("<svg viewBox=\"0 0 1080 1920\"></svg>")
        assertTrue(wrappedSvg.contains("window.setAspectRatio"))
    }

    @Test
    fun `studioRendererAssetMultiNicheJsonDirectorBridgeAndDigitSanitizerWork`() {
        // 1. Permanent offline studio_renderer.html loads and contains GSAP + all required hooks/rigs/safe-zones
        val rendererHtml = StudioRendererBridge.loadStudioRendererTemplate(null)
        assertTrue(rendererHtml.contains("masterTimeline.seek"))
        assertTrue(rendererHtml.contains("window.loadEpisodeJSON"))
        assertTrue(rendererHtml.contains("window.setAspectRatio"))
        assertTrue(rendererHtml.contains("window.setSceneTimings"))
        assertTrue(rendererHtml.contains("window.seekToTime"))
        assertTrue(rendererHtml.contains("window.STORYBOARD_SCENES"))
        assertTrue(rendererHtml.contains("anatomy_cutaway"))
        assertTrue(rendererHtml.contains("rain_storm"))
        assertTrue(rendererHtml.contains("poof_smoke_stars"))
        assertTrue(rendererHtml.contains("nerve_sparks"))
        // Top HUD y=155 / y=205 and 1.5s ending hold buffer
        assertTrue(rendererHtml.contains("155"))
        assertTrue(rendererHtml.contains("205"))
        assertTrue(rendererHtml.contains("1.5"))

        // 2. JsonSanitizer parses Studio Episode Choreography JSON cleanly
        val studioChoreographyJson = """
            {
              "title": "SI PUSA AT SI DAGA",
              "niche": "matalinong_daga",
              "aspectRatio": "9:16",
              "scenes": [
                {
                  "id": 1,
                  "duration": 3.5,
                  "speaker": "rat",
                  "bgTheme": "town_sunset",
                  "camera": { "preset": "push_in", "zoom": 1.15 },
                  "actors": [
                    { "role": "rat", "x": 0.35, "y": 0.62, "scale": 1.05, "targetScale": 1.1, "facing": 1, "mood": "squint", "action": "chop" },
                    { "role": "cat", "x": 0.70, "y": 0.60, "scale": 0.95, "targetScale": 0.95, "facing": -1, "mood": "shocked", "action": "idle" }
                  ],
                  "fx": "sunbeams",
                  "callout": "DISKARTENG DAGA",
                  "narration": "Huwag kang magpadala sa takot, Pusa!"
                },
                {
                  "id": 2,
                  "duration": 3.2,
                  "speaker": "cat",
                  "bgTheme": "town_storm",
                  "camera": { "preset": "impact_shake", "zoom": 1.18 },
                  "actors": [
                    { "role": "cat", "x": 0.62, "y": 0.60, "scale": 1.1, "targetScale": 1.12, "facing": -1, "mood": "scared", "action": "cry_shake" }
                  ],
                  "fx": "rain_storm",
                  "callout": "HINDI KO ALAM!",
                  "narration": "Teka lang Daga, nalilito na ako!"
                }
              ]
            }
        """.trimIndent()
        val parsedStudioScenes = JsonSanitizer.parseAndValidateScenes(studioChoreographyJson)
        assertEquals(2, parsedStudioScenes.size)
        assertEquals(StoryNiche.MATALINONG_DAGA, parsedStudioScenes[0].storyNiche)
        assertEquals("SI PUSA AT SI DAGA", parsedStudioScenes[0].episodeTitle)
        assertEquals("rat", parsedStudioScenes[0].speaker)
        assertEquals("cat", parsedStudioScenes[1].speaker)
        assertEquals("town_storm", parsedStudioScenes[1].bgTheme)
        assertEquals("impact_shake", parsedStudioScenes[1].cameraSpec?.preset)
        assertEquals(2, parsedStudioScenes[0].actors.size)
        assertEquals("rain_storm", parsedStudioScenes[1].fxKey)

        // 4. StudioRendererBridge injects <script id="episode-data"> before <script id="gsap-inline">
        val timelineScenes = parsedStudioScenes.mapIndexed { idx, sc ->
            TimelineScene(sc, idx * 3500.0, (idx + 1) * 3500.0, emptyList())
        }
        val doc = StudioRendererBridge.buildStudioHtmlDocument(
            scenes = timelineScenes,
            niche = StoryNiche.MATALINONG_DAGA,
            aspectRatio = VideoAspectRatio.PORTRAIT_9_16
        )
        val dataIdx = doc.indexOf("<script id=\"episode-data\"")
        val gsapIdx = doc.indexOf("<script id=\"gsap-inline\">")
        assertTrue(dataIdx >= 0)
        assertTrue(gsapIdx > dataIdx)
        val episodeJsonStr = StudioRendererBridge.buildEpisodeJson(
            scenes = timelineScenes,
            niche = StoryNiche.MATALINONG_DAGA,
            aspectRatio = VideoAspectRatio.LANDSCAPE_16_9
        )
        val epJson = JSONObject(episodeJsonStr)
        assertEquals("matalinong_daga", epJson.getString("niche"))
        assertEquals("16:9", epJson.getString("aspectRatio"))
        assertEquals(2, epJson.getJSONArray("scenes").length())

        val loadEpisodeJs = WebViewFrameRenderer.buildLoadEpisodeJsonScript(episodeJsonStr)
        assertTrue(loadEpisodeJs.contains("window.loadEpisodeJSON"))

        // 5. RenderBatchItem roundtrip preserves selected StoryNiche
        val batchItems = listOf(
            RenderBatchItem(
                topic = "Nerve Signal Test",
                style = AnimationStyle.ATMOSPHERIC_2D_STORY,
                durationSec = 30,
                voiceIdOverride = "v1",
                productionMode = ProductionMode.FULL_AI_STORY,
                importedClipUris = emptyList(),
                scriptSourceModeOverride = ScriptSourceMode.DIRECT_SCRIPT,
                directScriptOverride = "",
                niche = StoryNiche.ZACK_EXPLAINER
            )
        )
        val roundtrip = VideoGenerationService.deserializeBatchItemsFromJson(
            VideoGenerationService.serializeBatchItemsToJson(batchItems)
        )
        assertEquals(1, roundtrip.size)
        assertEquals(StoryNiche.ZACK_EXPLAINER, roundtrip[0].niche)

        // 6. Strict Client-Side TTS Digit Sanitizer guarantees ZERO ASCII digits sent to ElevenLabs
        val digitInputs = listOf(
            "₱1,500.50 ang natipid ko!",
            "3.5% lang ang tubo pero 500k ang kita at 2M sa bangko.",
            "Alas 3:30 ng hapon, 1st place sa COVID-19 quiz!",
            "Code A1B2C3 na may 0 at 9.",
            "[whispers] 2025 na, gumising ka!"
        )
        val anyDigit = Regex("[0-9]")
        digitInputs.forEach { sample ->
            val normalized = TagalogPhoneticNormalizer.normalize(sample)
            assertFalse("Unexpected digit in '$normalized' from '$sample'", anyDigit.containsMatchIn(normalized))
        }
        val whispered = TagalogPhoneticNormalizer.normalize("[whispers] 2025 na!")
        assertTrue(whispered.startsWith("[whispers]"))
        assertFalse(anyDigit.containsMatchIn(whispered))

        // 7. Temporary PNG frames, render mp4s, toonforge_work dirs, and toonforge_batch jsons get cleaned
        val tempRoot = kotlin.io.path.createTempDirectory("toonforge_clean_test").toFile()
        try {
            val framesDir = File(tempRoot, "frames").apply { mkdirs() }
            File(framesDir, "frame_001.png").writeText("png")
            val staleWorkDir = File(tempRoot, "toonforge_work_111").apply { mkdirs() }
            File(staleWorkDir, "temp.pcm").writeText("pcm")
            val activeWorkDir = File(tempRoot, "toonforge_work_222").apply { mkdirs() }
            val activeFile = File(activeWorkDir, "keep.pcm").apply { writeText("keep") }
            val strayPng = File(tempRoot, "frame_dump.png").apply { writeText("png") }
            val strayRenderMp4 = File(tempRoot, "render_abc.mp4").apply { writeText("mp4") }
            val staleBatchJson = File(tempRoot, "toonforge_batch_123.json").apply { writeText("[]") }

            DeterministicVideoExporter.cleanupTemporaryCacheBuffers(
                cacheDir = tempRoot,
                activeWorkDir = activeWorkDir
            )

            assertFalse(framesDir.exists())
            assertFalse(staleWorkDir.exists())
            assertTrue(activeFile.exists())
            assertFalse(strayPng.exists())
            assertFalse(strayRenderMp4.exists())
            assertFalse(staleBatchJson.exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `threeJsOfflineAssetManifestAndWebGlPrepWork`() {
        // 1. Offline three.min.js UMD bundle is present in Android assets and exposes THREE
        val threeJsFile = listOf(
            File("src/main/assets/three.min.js"),
            File("app/src/main/assets/three.min.js")
        ).firstOrNull { it.exists() && it.canRead() }
        assertTrue("three.min.js must exist in assets/", threeJsFile != null)
        val threeJsText = threeJsFile!!.readText(Charsets.UTF_8)
        assertTrue(threeJsText.length > 100_000)
        assertTrue(threeJsText.contains("WebGLRenderer"))
        assertTrue(threeJsText.contains("PerspectiveCamera"))

        // 2. AndroidManifest explicitly declares android:hardwareAccelerated="true" on <application> and <activity>
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.exists() && it.canRead() }
        assertTrue("AndroidManifest.xml must exist", manifestFile != null)
        val manifestXml = manifestFile!!.readText(Charsets.UTF_8)
        val hwAccelMatches = Regex("android:hardwareAccelerated\\s*=\\s*\"true\"").findAll(manifestXml).count()
        assertTrue("Expected hardwareAccelerated=true on both application and activity", hwAccelMatches >= 2)

        // 3. WebViewFrameRenderer.prepareHtmlForWebView injects three.min.js and preserveDrawingBuffer shim before </head>
        assertEquals("file:///android_asset/", WebViewFrameRenderer.ASSET_BASE_URL)
        val customThreeHtml = """
            <!DOCTYPE html>
            <html>
            <head>
              <title>3D Scene</title>
            </head>
            <body>
              <script>
                const scene = new THREE.Scene();
                const renderer = new THREE.WebGLRenderer({ antialias: true });
                window.seekToTime = function(t) {};
              </script>
            </body>
            </html>
        """.trimIndent()
        val prepared = WebViewFrameRenderer.prepareHtmlForWebView(customThreeHtml)
        val scriptIdx = prepared.indexOf("<script src=\"three.min.js\"></script>")
        val headCloseIdx = prepared.indexOf("</head>")
        assertTrue(scriptIdx >= 0)
        assertTrue(headCloseIdx > scriptIdx)
        assertTrue(prepared.contains("preserveDrawingBuffer=true"))

        // 4. Does not double-inject three.min.js if already included
        val alreadyIncluded = """
            <!DOCTYPE html>
            <html><head><script src="three.min.js"></script></head>
            <body><script>const s = new THREE.Scene();</script></body></html>
        """.trimIndent()
        val preparedAlready = WebViewFrameRenderer.prepareHtmlForWebView(alreadyIncluded)
        assertEquals(1, Regex("three\\.min\\.js").findAll(preparedAlready).count())
        assertTrue(preparedAlready.contains("preserveDrawingBuffer=true"))

        // 5. Fallback when HTML has no </head>
        val headlessHtml = "<body><script>var r = new THREE.WebGLRenderer();</script></body>"
        val preparedHeadless = WebViewFrameRenderer.prepareHtmlForWebView(headlessHtml)
        assertTrue(preparedHeadless.contains("<script src=\"three.min.js\"></script>"))

        // 6. studio_renderer.html stays untouched by Three.js injection and keeps all GSAP/storyboard hooks
        val studioHtml = StudioRendererBridge.loadStudioRendererTemplate(null)
        val preparedStudio = WebViewFrameRenderer.prepareHtmlForWebView(studioHtml)
        assertEquals(studioHtml, preparedStudio)
        assertTrue(studioHtml.contains("window.STORYBOARD_SCENES"))
        assertTrue(studioHtml.contains("window.setAspectRatio"))
        assertTrue(studioHtml.contains("window.setSceneTimings"))
        assertTrue(studioHtml.contains("window.seekToTime"))
    }
}
