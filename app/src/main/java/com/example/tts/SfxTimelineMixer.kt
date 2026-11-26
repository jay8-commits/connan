package com.example.tts

import com.example.model.AnimationStyle
import com.example.model.HudWidget
import com.example.model.SceneScript
import com.example.model.StatusPill
import com.example.model.WorldFx
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

enum class ProceduralSfxCue {
    WHOOSH_CUT,
    POP_CALLOUT,
    DING_SUCCESS,
    BUZZ_ERROR,
    SUB_BASS_IMPACT,
    DOOR_KNOCK_3X,
    HEARTBEAT_THUMP,
    TENSION_RISER,
    ELECTRIC_HUM;

    companion object {
        fun fromRaw(raw: String?): ProceduralSfxCue? {
            val clean = raw?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.name == clean } ?: when (clean) {
                "DOOR_KNOCK", "KNOCK", "KNOCK_3X" -> DOOR_KNOCK_3X
                "HEARTBEAT", "THUMP" -> HEARTBEAT_THUMP
                "RISER", "TENSION" -> TENSION_RISER
                "HUM", "ELECTRIC", "BUZZ_ELECTRIC" -> ELECTRIC_HUM
                else -> null
            }
        }
    }
}

data class ScheduledSfxEvent(
    val timeSec: Double,
    val cue: ProceduralSfxCue,
    val seed: Int = 0
)

/**
 * Pure-Kotlin 44.1kHz mono procedural SFX synthesizer and timeline scheduler.
 * Synthesizes WHOOSH_CUT, POP_CALLOUT, DING_SUCCESS, BUZZ_ERROR, and SUB_BASS_IMPACT
 * with zero external audio assets and deterministic frame-accurate timing.
 */
object SfxTimelineMixer {

    const val SAMPLE_RATE = 44100

    fun buildSfxTimeline(
        scenes: List<SceneScript>,
        sceneStartMs: List<Double>,
        sceneEndMs: List<Double>,
        style: AnimationStyle,
        alwaysTriggerSceneBoundarySfx: Boolean = false
    ): List<ScheduledSfxEvent> {
        if (scenes.isEmpty() || sceneStartMs.isEmpty()) return emptyList()
        val events = mutableListOf<ScheduledSfxEvent>()

        // 1. Opening hook impact (0.1s for custom HTML/SVG storyboards, 0.2s default)
        val openingImpactTime = if (alwaysTriggerSceneBoundarySfx) 0.10 else 0.20
        events += ScheduledSfxEvent(
            timeSec = openingImpactTime,
            cue = ProceduralSfxCue.SUB_BASS_IMPACT,
            seed = 101
        )

        for (idx in scenes.indices) {
            val scene = scenes[idx]
            val startMs = sceneStartMs.getOrNull(idx) ?: continue
            val endMs = sceneEndMs.getOrNull(idx) ?: startMs
            val durMs = (endMs - startMs).coerceAtLeast(300.0)
            val startSec = startMs / 1000.0

            // 2. Scene cut whoosh on transitions (idx > 0)
            if (idx > 0) {
                events += ScheduledSfxEvent(timeSec = startSec, cue = ProceduralSfxCue.WHOOSH_CUT, seed = 409 + idx)
            }

            // 3. Status pill & callout banner triggers
            val effectivePill = if (scene.statusPill != StatusPill.NONE) {
                scene.statusPill
            } else if (style == AnimationStyle.TECH_EXPLAINER || style == AnimationStyle.CROSS_SECTION) {
                when ((scene.sceneId - 1).coerceAtLeast(0) % 4) {
                    0 -> StatusPill.GOAL
                    1 -> StatusPill.PROBLEM
                    2 -> StatusPill.FIX
                    else -> StatusPill.SECRET
                }
            } else {
                StatusPill.NONE
            }

            val hasBanner = scene.calloutBanner.isNotBlank() ||
                scene.topicPillLabel.isNotBlank() ||
                style == AnimationStyle.TECH_EXPLAINER ||
                style == AnimationStyle.CROSS_SECTION

            if (alwaysTriggerSceneBoundarySfx) {
                events += ScheduledSfxEvent(timeSec = startSec, cue = ProceduralSfxCue.POP_CALLOUT, seed = idx)
            } else if (hasBanner || effectivePill != StatusPill.NONE) {
                events += ScheduledSfxEvent(timeSec = startSec + 0.08, cue = ProceduralSfxCue.POP_CALLOUT, seed = idx)
            }

            when (effectivePill) {
                StatusPill.GOAL, StatusPill.FIX -> {
                    events += ScheduledSfxEvent(timeSec = startSec + 0.14, cue = ProceduralSfxCue.DING_SUCCESS, seed = idx)
                }
                StatusPill.PROBLEM -> {
                    events += ScheduledSfxEvent(timeSec = startSec + 0.12, cue = ProceduralSfxCue.BUZZ_ERROR, seed = idx)
                }
                else -> Unit
            }

            // Textual ✓ / ✕ cue markers in banners
            if (scene.calloutBanner.contains("✓") || scene.calloutBanner.contains("FIX", ignoreCase = true)) {
                events += ScheduledSfxEvent(timeSec = startSec + 0.18, cue = ProceduralSfxCue.DING_SUCCESS, seed = idx + 11)
            }
            if (scene.calloutBanner.contains("✕") || scene.calloutBanner.contains("ERROR", ignoreCase = true)) {
                events += ScheduledSfxEvent(timeSec = startSec + 0.18, cue = ProceduralSfxCue.BUZZ_ERROR, seed = idx + 19)
            }

            // 4. Checklist row ding reveals (every 0.6s)
            val effectiveWidget = if (scene.hudWidget != HudWidget.NONE) {
                scene.hudWidget
            } else if ((style == AnimationStyle.TECH_EXPLAINER || style == AnimationStyle.CROSS_SECTION) &&
                ((scene.sceneId - 1).coerceAtLeast(0) % 3 == 2)
            ) {
                HudWidget.ANIMATED_CHECKLIST
            } else {
                HudWidget.NONE
            }
            if (effectiveWidget == HudWidget.ANIMATED_CHECKLIST) {
                val itemCount = if (scene.checklistItems.isEmpty()) 3 else scene.checklistItems.size.coerceIn(1, 4)
                for (itemIdx in 0 until itemCount) {
                    val tickMs = itemIdx * 600.0 + 160.0
                    if (tickMs < durMs - 120.0) {
                        events += ScheduledSfxEvent(
                            timeSec = startSec + tickMs / 1000.0,
                            cue = ProceduralSfxCue.DING_SUCCESS,
                            seed = 700 + itemIdx
                        )
                    }
                }
            }

            // 5. Coin rain shimmer chimes
            if (scene.worldFx == WorldFx.COIN_RAIN) {
                val offsetsMs = listOf(160.0, 960.0, 1820.0, 2680.0)
                offsetsMs.forEachIndexed { hitIdx, off ->
                    if (off < durMs - 240.0) {
                        events += ScheduledSfxEvent(
                            timeSec = startSec + off / 1000.0,
                            cue = ProceduralSfxCue.DING_SUCCESS,
                            seed = 900 + hitIdx
                        )
                    }
                }
            }

            // 6. Explicit scene SFX cues from JSON "sfx" field
            val scheduledCuesInScene = mutableSetOf<ProceduralSfxCue>()
            scene.sfxCues.forEachIndexed { sIdx, rawCue ->
                val cue = ProceduralSfxCue.fromRaw(rawCue) ?: return@forEachIndexed
                scheduledCuesInScene += cue
                val offsetSec = 0.06 + sIdx * 0.35
                if (offsetSec * 1000.0 < durMs - 80.0) {
                    events += ScheduledSfxEvent(
                        timeSec = startSec + offsetSec,
                        cue = cue,
                        seed = 1200 + idx * 17 + sIdx
                    )
                }
            }

            // 7. Contextual atmospheric/technical SFX from Tagalog narration & callouts
            val contextText = "${scene.tagalogNarration} ${scene.calloutBanner} ${scene.topicPillLabel}".lowercase()
            val contextualCues = buildList {
                if (contextText.contains("katok") || contextText.contains("kumakatok")) {
                    add(ProceduralSfxCue.DOOR_KNOCK_3X)
                }
                if (contextText.contains("tibok") || contextText.contains("puso")) {
                    add(ProceduralSfxCue.HEARTBEAT_THUMP)
                }
                if (contextText.contains("panganib") || contextText.contains("delikado") || contextText.contains("babala")) {
                    add(ProceduralSfxCue.TENSION_RISER)
                }
                if (contextText.contains("solar") || contextText.contains("kuryente") || contextText.contains("inverter")) {
                    add(ProceduralSfxCue.ELECTRIC_HUM)
                }
            }
            contextualCues
                .filter { it !in scheduledCuesInScene }
                .forEachIndexed { cIdx, cue ->
                    val offsetSec = if (durMs > 600.0) 0.14 + cIdx * 0.30 else 0.04
                    if (offsetSec * 1000.0 < durMs - 60.0) {
                        events += ScheduledSfxEvent(
                            timeSec = startSec + offsetSec,
                            cue = cue,
                            seed = 1500 + idx * 23 + cIdx
                        )
                    }
                }
        }
        return events
    }

    fun synthesizeCueSamples(
        cue: ProceduralSfxCue,
        sampleRate: Int = SAMPLE_RATE,
        seed: Int = 0
    ): FloatArray = when (cue) {
        ProceduralSfxCue.WHOOSH_CUT -> synthesizeWhoosh(sampleRate, seed)
        ProceduralSfxCue.POP_CALLOUT -> synthesizePop(sampleRate)
        ProceduralSfxCue.DING_SUCCESS -> synthesizeDingSuccess(sampleRate, seed)
        ProceduralSfxCue.BUZZ_ERROR -> synthesizeBuzzError(sampleRate)
        ProceduralSfxCue.SUB_BASS_IMPACT -> synthesizeSubBassImpact(sampleRate)
        ProceduralSfxCue.DOOR_KNOCK_3X -> synthesizeDoorKnock3x(sampleRate)
        ProceduralSfxCue.HEARTBEAT_THUMP -> synthesizeHeartbeatThump(sampleRate)
        ProceduralSfxCue.TENSION_RISER -> synthesizeTensionRiser(sampleRate, seed)
        ProceduralSfxCue.ELECTRIC_HUM -> synthesizeElectricHum(sampleRate)
    }

    fun mixSfxEventsIntoBuffer(
        mix: FloatArray,
        events: List<ScheduledSfxEvent>,
        sampleRate: Int = SAMPLE_RATE
    ) {
        if (mix.isEmpty() || events.isEmpty()) return
        for (ev in events) {
            val startSample = (ev.timeSec * sampleRate).toInt()
            if (startSample < 0 || startSample >= mix.size) continue
            val samples = synthesizeCueSamples(ev.cue, sampleRate = sampleRate, seed = ev.seed)
            for (k in samples.indices) {
                val pos = startSample + k
                if (pos >= mix.size) break
                mix[pos] += samples[k]
            }
        }
    }

    private fun synthesizeWhoosh(sampleRate: Int, seed: Int): FloatArray {
        val len = (0.28 * sampleRate).toInt().coerceAtLeast(1)
        val out = FloatArray(len)
        val rng = Random(seed)
        var lp = 0f
        for (k in 0 until len) {
            val frac = k.toFloat() / len.toFloat()
            val env = sin(PI * frac).toFloat()
            val cutoff = 0.045f + 0.24f * env
            val white = rng.nextFloat() * 2f - 1f
            lp += (white - lp) * cutoff
            out[k] = lp * env * 0.24f
        }
        return out
    }

    private fun synthesizePop(sampleRate: Int): FloatArray {
        val len = (0.09 * sampleRate).toInt().coerceAtLeast(1)
        val out = FloatArray(len)
        val attackSamples = (0.004 * sampleRate).toInt().coerceAtLeast(1)
        val startFreq = 520.0
        val peakFreq = 1280.0
        var phase = 0.0
        for (k in 0 until len) {
            val t = k.toDouble() / sampleRate.toDouble()
            val frac = k.toDouble() / len.toDouble()
            val freq = if (frac < 0.45) {
                startFreq + (peakFreq - startFreq) * (frac / 0.45)
            } else {
                peakFreq - (peakFreq - startFreq * 1.4) * ((frac - 0.45) / 0.55)
            }
            phase += (2.0 * PI * freq) / sampleRate.toDouble()
            val attack = if (k < attackSamples) k.toFloat() / attackSamples else 1f
            val decay = exp(-t * 34.0).toFloat()
            out[k] = sin(phase).toFloat() * attack * decay * 0.20f
        }
        return out
    }

    private fun synthesizeDingSuccess(sampleRate: Int, seed: Int): FloatArray {
        val len = (0.26 * sampleRate).toInt().coerceAtLeast(1)
        val out = FloatArray(len)
        val attackSamples = (0.002 * sampleRate).toInt().coerceAtLeast(1)
        val baseFreq = 1318.51 * (1.0 + (seed % 4) * 0.05)
        for (k in 0 until len) {
            val t = k.toDouble() / sampleRate.toDouble()
            val attack = if (k < attackSamples) k.toFloat() / attackSamples else 1f
            val decay1 = exp(-t * 14.0)
            val decay2 = exp(-t * 24.0)
            val wave = (
                sin(2.0 * PI * baseFreq * t) * 0.55 * decay1 +
                    sin(2.0 * PI * (baseFreq * 1.498) * t) * 0.30 * decay1 +
                    sin(2.0 * PI * (baseFreq * 2.0) * t) * 0.20 * decay2
                ).toFloat()
            out[k] = wave * attack * 0.16f
        }
        return out
    }

    private fun synthesizeBuzzError(sampleRate: Int): FloatArray {
        val len = (0.24 * sampleRate).toInt().coerceAtLeast(1)
        val out = FloatArray(len)
        val attackSamples = (0.004 * sampleRate).toInt().coerceAtLeast(1)
        for (k in 0 until len) {
            val t = k.toDouble() / sampleRate.toDouble()
            val frac = k.toDouble() / len.toDouble()
            // Double-pulse damped warning buzz (145 Hz fundamental + odd harmonics)
            val pulseEnv = if (frac < 0.44) {
                sin(PI * (frac / 0.44)).toFloat()
            } else if (frac > 0.52) {
                sin(PI * ((frac - 0.52) / 0.48)).toFloat()
            } else {
                0.08f
            }
            val attack = if (k < attackSamples) k.toFloat() / attackSamples else 1f
            val raw = (
                sin(2.0 * PI * 145.0 * t) * 0.62 +
                    sin(2.0 * PI * 290.0 * t) * 0.25 +
                    sin(2.0 * PI * 435.0 * t) * 0.14
                ).toFloat()
            out[k] = raw * pulseEnv * attack * exp(-t * 4.0).toFloat() * 0.22f
        }
        return out
    }

    private fun synthesizeSubBassImpact(sampleRate: Int): FloatArray {
        val len = (0.26 * sampleRate).toInt().coerceAtLeast(1)
        val out = FloatArray(len)
        val attackSamples = (0.005 * sampleRate).toInt().coerceAtLeast(1)
        var phase = 0.0
        for (k in 0 until len) {
            val t = k.toDouble() / sampleRate.toDouble()
            val frac = k.toDouble() / len.toDouble()
            val freq = 122.0 * exp(-frac * 2.4) + 38.0
            phase += (2.0 * PI * freq) / sampleRate.toDouble()
            val attack = if (k < attackSamples) k.toFloat() / attackSamples else 1f
            val decay = exp(-t * 13.0).toFloat()
            out[k] = sin(phase).toFloat() * attack * decay * 0.32f
        }
        return out
    }

    private fun synthesizeDoorKnock3x(sampleRate: Int): FloatArray {
        val len = (0.58 * sampleRate).toInt().coerceAtLeast(1)
        val out = FloatArray(len)
        val knockStartsSec = doubleArrayOf(0.0, 0.18, 0.36)
        val knockPitchHz = doubleArrayOf(158.0, 148.0, 140.0)
        for (k in 0 until len) {
            val t = k.toDouble() / sampleRate.toDouble()
            var sum = 0.0
            for (n in knockStartsSec.indices) {
                val localT = t - knockStartsSec[n]
                if (localT >= 0.0 && localT < 0.14) {
                    val attack = (localT / 0.0025).coerceIn(0.0, 1.0)
                    val woodyDecay = exp(-localT * 30.0)
                    val freq = knockPitchHz[n] * exp(-localT * 6.0) + 72.0
                    val body = sin(2.0 * PI * freq * localT) * 0.72 +
                        sin(2.0 * PI * (freq * 2.15) * localT) * 0.28
                    val tapClick = sin(2.0 * PI * 680.0 * localT) * exp(-localT * 110.0) * 0.25
                    sum += (body * woodyDecay + tapClick) * attack
                }
            }
            out[k] = (sum * 0.30).toFloat().coerceIn(-1.0f, 1.0f)
        }
        return out
    }

    private fun synthesizeHeartbeatThump(sampleRate: Int): FloatArray {
        val len = (0.54 * sampleRate).toInt().coerceAtLeast(1)
        val out = FloatArray(len)
        val beatStarts = doubleArrayOf(0.0, 0.19)
        val beatGains = doubleArrayOf(1.0, 0.72)
        for (k in 0 until len) {
            val t = k.toDouble() / sampleRate.toDouble()
            var sum = 0.0
            for (b in beatStarts.indices) {
                val localT = t - beatStarts[b]
                if (localT >= 0.0 && localT < 0.17) {
                    val attack = (localT / 0.008).coerceIn(0.0, 1.0)
                    val decay = exp(-localT * 18.5)
                    val freq = (66.0 + b * 8.0) * exp(-localT * 3.5) + 34.0
                    val pulse = sin(2.0 * PI * freq * localT) * 0.85 +
                        sin(2.0 * PI * (freq * 2.0) * localT) * 0.18
                    sum += pulse * attack * decay * beatGains[b]
                }
            }
            out[k] = (sum * 0.34).toFloat().coerceIn(-1.0f, 1.0f)
        }
        return out
    }

    private fun synthesizeTensionRiser(sampleRate: Int, seed: Int): FloatArray {
        val len = (0.68 * sampleRate).toInt().coerceAtLeast(1)
        val out = FloatArray(len)
        val rng = Random(seed + 77)
        var lp = 0f
        var hpPrevIn = 0f
        var hpPrevOut = 0f
        var synthPhase = 0.0
        for (k in 0 until len) {
            val frac = k.toFloat() / len.toFloat()
            val swell = frac * frac
            val tailFade = if (frac > 0.90f) ((1.0f - frac) / 0.10f).coerceIn(0f, 1f) else 1f
            val white = rng.nextFloat() * 2f - 1f
            // High-pass followed by sweeping low-pass for clean cinematic noise riser
            val hp = 0.94f * (hpPrevOut + white - hpPrevIn)
            hpPrevIn = white
            hpPrevOut = hp
            val cutoff = 0.03f + 0.38f * frac * frac
            lp += (hp - lp) * cutoff

            val sweepFreq = 115.0 + 245.0 * frac * frac
            synthPhase += (2.0 * PI * sweepFreq) / sampleRate.toDouble()
            val shimmer = sin(synthPhase).toFloat() * 0.30f

            out[k] = ((lp * 0.65f + shimmer) * swell * tailFade * 0.30f).coerceIn(-1.0f, 1.0f)
        }
        return out
    }

    private fun synthesizeElectricHum(sampleRate: Int): FloatArray {
        val len = (0.50 * sampleRate).toInt().coerceAtLeast(1)
        val out = FloatArray(len)
        val fadeInSamples = (0.025 * sampleRate).toInt().coerceAtLeast(1)
        val fadeOutSamples = (0.060 * sampleRate).toInt().coerceAtLeast(1)
        for (k in 0 until len) {
            val t = k.toDouble() / sampleRate.toDouble()
            val envIn = if (k < fadeInSamples) k.toFloat() / fadeInSamples else 1f
            val envOut = if (k > len - fadeOutSamples) {
                ((len - k).coerceAtLeast(0).toFloat() / fadeOutSamples)
            } else 1f
            val hum = (
                sin(2.0 * PI * 60.0 * t) * 0.52 +
                    sin(2.0 * PI * 120.0 * t) * 0.28 +
                    sin(2.0 * PI * 180.0 * t) * 0.14 +
                    sin(2.0 * PI * 240.0 * t) * 0.06
                ).toFloat()
            out[k] = (hum * envIn * envOut * 0.22f).coerceIn(-1.0f, 1.0f)
        }
        return out
    }
}
