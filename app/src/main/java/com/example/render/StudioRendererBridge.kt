package com.example.render

import android.content.Context
import com.example.model.ActorAction
import com.example.model.ActorExpression
import com.example.model.ActorRole
import com.example.model.CameraMotion
import com.example.model.CutawaySubject
import com.example.model.EnvironmentTheme
import com.example.model.LightingState
import com.example.model.SceneScript
import com.example.model.StoryNiche
import com.example.model.StudioActorSpec
import com.example.model.StudioCameraSpec
import com.example.model.TimelineScene
import com.example.model.VideoAspectRatio
import com.example.model.WorldFx
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Bridges Kotlin storyboard timelines into the permanent offline GSAP + 2.5D Vector Canvas
 * engine (`assets/studio_renderer.html`). Never generates HTML/SVG via LLM—only injects
 * structured JSON episode choreography into the pre-bundled studio engine.
 */
object StudioRendererBridge {

    private const val ASSET_FILE_NAME = "studio_renderer.html"
    @Volatile
    private var cachedTemplateHtml: String? = null

    fun loadStudioRendererTemplate(context: Context? = null): String {
        cachedTemplateHtml?.let { return it }

        if (context != null) {
            runCatching {
                context.assets.open(ASSET_FILE_NAME).bufferedReader(Charsets.UTF_8).use {
                    it.readText()
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { loaded ->
                cachedTemplateHtml = loaded
                return loaded
            }
        }

        val fallbackPaths = listOf(
            File("src/main/assets/$ASSET_FILE_NAME"),
            File("app/src/main/assets/$ASSET_FILE_NAME"),
            File("assets/$ASSET_FILE_NAME")
        )
        for (file in fallbackPaths) {
            if (file.exists() && file.canRead()) {
                val loaded = file.readText(Charsets.UTF_8)
                if (loaded.isNotBlank()) {
                    cachedTemplateHtml = loaded
                    return loaded
                }
            }
        }

        throw IllegalStateException("Missing bundled $ASSET_FILE_NAME in APK assets")
    }

    fun buildEpisodeJson(
        scenes: List<TimelineScene>,
        title: String = "",
        niche: StoryNiche = StoryNiche.SA_BAYANG_ITO,
        aspectRatio: VideoAspectRatio = VideoAspectRatio.PORTRAIT_9_16
    ): String {
        val firstScene = scenes.firstOrNull()?.scene
        val effectiveNiche = firstScene?.storyNiche?.takeIf {
            scenes.any { sc -> sc.scene.bgTheme.isNotBlank() || sc.scene.actors.isNotEmpty() }
        } ?: niche
        val effectiveTitle = title.trim().ifBlank {
            firstScene?.episodeTitle?.trim().orEmpty()
        }.ifBlank {
            effectiveNiche.seriesBadge
        }

        val scenesArray = JSONArray()
        scenes.forEachIndexed { idx, ts ->
            val sc = ts.scene
            val durSec = if (sc.durationSec > 0.2) {
                sc.durationSec
            } else {
                ((ts.endMs - ts.startMs) / 1000.0).coerceAtLeast(0.6)
            }
            val bgTheme = sc.bgTheme.ifBlank { inferBgTheme(sc, effectiveNiche, idx) }
            val camera = if (sc.bgTheme.isNotBlank() || sc.actors.isNotEmpty()) {
                sc.cameraSpec ?: inferCameraSpec(sc.cameraMotion, idx)
            } else {
                inferCameraSpec(sc.cameraMotion, idx)
            }
            val actors = if (sc.actors.isNotEmpty()) {
                sc.actors
            } else {
                inferActorsForScene(sc, effectiveNiche, idx)
            }
            val fx = sc.fxKey.ifBlank { inferFxKey(sc, effectiveNiche) }
            val speaker = sc.speaker.ifBlank {
                if (effectiveNiche == StoryNiche.MATALINONG_DAGA) {
                    if (idx % 2 == 1) "cat" else "rat"
                } else {
                    actors.firstOrNull()?.role ?: "narrator"
                }
            }
            val callout = sc.calloutBanner.trim().take(28)

            scenesArray.put(
                JSONObject().apply {
                    put("id", sc.sceneId.coerceAtLeast(idx + 1))
                    put("duration", (durSec * 1000.0).toLong() / 1000.0)
                    put("speaker", speaker)
                    put("bgTheme", bgTheme)
                    put("camera", JSONObject().apply {
                        put("preset", camera.preset)
                        put("zoom", camera.zoom.toDouble())
                    })
                    put("actors", JSONArray().apply {
                        actors.forEach { a ->
                            put(
                                JSONObject().apply {
                                    put("role", a.role)
                                    put("x", a.x.toDouble())
                                    put("y", a.y.toDouble())
                                    put("scale", a.scale.toDouble())
                                    put("targetScale", a.targetScale.toDouble())
                                    put("facing", a.facing)
                                    put("mood", a.mood)
                                    put("action", a.action)
                                }
                            )
                        }
                    })
                    put("fx", fx)
                    put("callout", callout)
                    put("narration", sc.tagalogNarration)
                }
            )
        }

        return JSONObject().apply {
            put("title", effectiveTitle)
            put("niche", effectiveNiche.nicheKey)
            put("aspectRatio", aspectRatio.ratioKey)
            put("scenes", scenesArray)
        }.toString()
    }

    fun buildStudioHtmlDocument(
        scenes: List<TimelineScene>,
        title: String = "",
        niche: StoryNiche = StoryNiche.SA_BAYANG_ITO,
        aspectRatio: VideoAspectRatio = VideoAspectRatio.PORTRAIT_9_16,
        context: Context? = null
    ): String {
        val template = loadStudioRendererTemplate(context)
        val episodeJson = buildEpisodeJson(
            scenes = scenes,
            title = title,
            niche = niche,
            aspectRatio = aspectRatio
        )
        // Escape closing tags so inline JSON script block never terminates prematurely
        val safeJson = episodeJson.replace("</", "<\\/")
        val dataScriptTag = "<script id=\"episode-data\" type=\"application/json\">$safeJson</script>"

        val scriptAnchor = "<script id=\"gsap-inline\">"
        return when {
            template.contains(scriptAnchor) ->
                template.replaceFirst(scriptAnchor, "$dataScriptTag\n$scriptAnchor")
            template.contains("</head>", ignoreCase = true) ->
                template.replaceFirst("</head>", "$dataScriptTag\n</head>", ignoreCase = true)
            template.contains("</body>", ignoreCase = true) ->
                template.replaceFirst("</body>", "$dataScriptTag\n</body>", ignoreCase = true)
            else -> "$template\n$dataScriptTag"
        }
    }

    private fun inferBgTheme(scene: SceneScript, niche: StoryNiche, index: Int): String {
        if (niche == StoryNiche.ZACK_EXPLAINER || scene.cutawaySubject != null) {
            return "anatomy_lab"
        }
        if (scene.lightingState == LightingState.RAIN_STORM) {
            return "town_storm"
        }
        return when (scene.environmentTheme) {
            EnvironmentTheme.COZY_BEDROOM_MIDNIGHT -> "bedroom_night"
            EnvironmentTheme.CLASSROOM_CHALKBOARD -> "school_hallway"
            EnvironmentTheme.SCIENCE_LAB_INTERIOR, EnvironmentTheme.HOSPITAL_CLINIC -> "anatomy_lab"
            EnvironmentTheme.DRAMATIC_SUNSET_CITY -> "town_sunset"
            EnvironmentTheme.SUNNY_MARKET_STREET, EnvironmentTheme.SUNNY_PARK_NATURE -> "town_day"
            EnvironmentTheme.BANK_VAULT_FINANCE -> "dark_tunnel"
            else -> when (niche) {
                StoryNiche.MATALINONG_DAGA -> if (index % 2 == 0) "town_sunset" else "town_day"
                StoryNiche.ZACK_EXPLAINER -> "anatomy_lab"
                StoryNiche.SA_BAYANG_ITO -> listOf("town_day", "town_sunset", "school_hallway", "bedroom_night")[index % 4]
            }
        }
    }

    private fun inferCameraSpec(motion: CameraMotion, index: Int): StudioCameraSpec {
        return when (motion) {
            CameraMotion.ZOOM_IN -> StudioCameraSpec("push_in", 1.12f)
            CameraMotion.ZOOM_OUT -> StudioCameraSpec("wide", 1.0f)
            CameraMotion.PAN_LEFT, CameraMotion.PAN_RIGHT,
            CameraMotion.WHIP_PAN_LEFT, CameraMotion.WHIP_PAN_RIGHT -> StudioCameraSpec("pan_right", 1.08f)
            CameraMotion.IMPACT_SHAKE, CameraMotion.SHAKE -> StudioCameraSpec("impact_shake", 1.15f)
            else -> {
                val presets = listOf("push_in", "wide", "close_up", "pan_right")
                StudioCameraSpec(presets[index % presets.size], 1.08f)
            }
        }
    }

    private fun inferActorsForScene(
        scene: SceneScript,
        niche: StoryNiche,
        index: Int
    ): List<StudioActorSpec> {
        val mood = when (scene.expression) {
            ActorExpression.SHOCKED_WIDE_EYES -> "shocked"
            ActorExpression.PANIC_SWEAT_FIRE -> "scared"
            ActorExpression.ANGRY_EVIL -> "squint"
            ActorExpression.TONGUE_STING -> "crying"
            ActorExpression.HAPPY_TALK -> if (index % 2 == 0) "happy" else "determined"
            else -> "determined"
        }
        val action = when (scene.actorAction) {
            ActorAction.WALK_IN -> "walk"
            ActorAction.THUMBS_UP -> "wave"
            ActorAction.CRY_SHAKE -> "cry_shake"
            ActorAction.SURPRISED_JUMP -> "block"
            ActorAction.POINT_STICK -> "chop"
            ActorAction.TALK_BOUNCE -> "idle"
        }

        if (niche == StoryNiche.ZACK_EXPLAINER || scene.cutawaySubject == CutawaySubject.BRAIN_NEURON_SIGNAL ||
            scene.cutawaySubject == CutawaySubject.HEART_ARTERY_FLOW
        ) {
            return listOf(
                StudioActorSpec(
                    role = "anatomy_cutaway",
                    x = 0.5f,
                    y = 0.52f,
                    scale = 1.02f,
                    targetScale = 1.08f,
                    facing = 1,
                    mood = "determined",
                    action = "idle"
                )
            )
        }

        if (niche == StoryNiche.MATALINONG_DAGA ||
            scene.speaker.equals("cat", ignoreCase = true) ||
            scene.speaker.equals("rat", ignoreCase = true)
        ) {
            val catSpeaks = scene.speaker.equals("cat", ignoreCase = true)
            return listOf(
                StudioActorSpec(
                    role = "rat",
                    x = 0.34f,
                    y = 0.60f,
                    scale = if (!catSpeaks) 1.06f else 0.94f,
                    targetScale = if (!catSpeaks) 1.08f else 0.94f,
                    facing = 1,
                    mood = if (!catSpeaks) "squint" else "determined",
                    action = if (!catSpeaks) action else "idle"
                ),
                StudioActorSpec(
                    role = "cat",
                    x = 0.68f,
                    y = 0.58f,
                    scale = if (catSpeaks) 1.08f else 0.95f,
                    targetScale = if (catSpeaks) 1.10f else 0.95f,
                    facing = -1,
                    mood = if (catSpeaks) mood else "shocked",
                    action = if (catSpeaks) action else "idle"
                )
            )
        }

        val role = when (scene.actorRole) {
            ActorRole.RICH_MAN -> if (index % 2 == 0) "mayor" else "dad"
            ActorRole.POOR_MAN -> "bully"
            ActorRole.HERO -> if (index % 3 == 1) "lily" else "hero"
            else -> "hero"
        }
        return listOf(
            StudioActorSpec(
                role = role,
                x = 0.5f,
                y = 0.58f,
                scale = 1.0f,
                targetScale = 1.04f,
                facing = 1,
                mood = mood,
                action = action
            )
        )
    }

    private fun inferFxKey(scene: SceneScript, niche: StoryNiche): String {
        if (scene.lightingState == LightingState.RAIN_STORM) return "rain_storm"
        if (niche == StoryNiche.ZACK_EXPLAINER) return "nerve_sparks"
        return when (scene.worldFx) {
            WorldFx.FIRE_SPARKS -> "nerve_sparks"
            WorldFx.COIN_RAIN -> "sunbeams"
            WorldFx.CHALKBOARD_DRAW -> "dust_motes"
            WorldFx.NONE -> if (scene.lightingState == LightingState.SUNNY) "sunbeams" else "dust_motes"
        }
    }
}
