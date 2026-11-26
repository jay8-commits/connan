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
    SUB_BASS_IMPACT;

    companion object {
        fun fromRaw(raw: String?): ProceduralSfxCue? {
            val clean = raw?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.name == clean }
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
        style: AnimationStyle
    ): List<ScheduledSfxEvent> {
        if (scenes.isEmpty() || sceneStartMs.isEmpty()) return emptyList()
        val events = mutableListOf<ScheduledSfxEvent>()

        // 1. Opening 0.2s editorial hook banner impact
        events += ScheduledSfxEvent(timeSec = 0.20, cue = ProceduralSfxCue.SUB_BASS_IMPACT, seed = 101)

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

            if (hasBanner || effectivePill != StatusPill.NONE) {
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

            // 6. Explicit scene SFX cues
            scene.sfxCues.forEachIndexed { sIdx, rawCue ->
                val cue = ProceduralSfxCue.fromRaw(rawCue) ?: return@forEachIndexed
                val offsetSec = 0.06 + sIdx * 0.35
                if (offsetSec * 1000.0 < durMs - 80.0) {
                    events += ScheduledSfxEvent(
                        timeSec = startSec + offsetSec,
                        cue = cue,
                        seed = 1200 + idx * 17 + sIdx
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
}
