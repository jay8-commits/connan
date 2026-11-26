package com.example.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.example.model.AnimationStyle
import com.example.model.WordTiming
import com.example.model.WorldFx
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object WorldFxAndCaptionRenderer {

    fun drawWorldFx(
        canvas: Canvas,
        w: Float,
        h: Float,
        fx: WorldFx,
        style: AnimationStyle,
        sceneElapsedSec: Float,
        globalTimeSec: Float
    ) {
        val u = w / 1080f
        when (fx) {
            WorldFx.NONE -> Unit
            WorldFx.COIN_RAIN -> drawCoinRain(canvas, w, h, u, globalTimeSec)
            WorldFx.FIRE_SPARKS -> drawFireSparks(canvas, w, h, u, globalTimeSec)
            WorldFx.CHALKBOARD_DRAW -> drawChalkboardDiagram(canvas, w, h, u, style, sceneElapsedSec)
        }
    }

    fun drawStyleOverlay(
        canvas: Canvas,
        w: Float,
        h: Float,
        style: AnimationStyle,
        globalTimeSec: Float
    ) {
        val u = w / 1080f
        if (style == AnimationStyle.DARK_SUSPENSE) {
            // Procedural dark vignette
            val vigPaint = Paint().apply {
                shader = RadialGradient(
                    w * 0.5f, h * 0.45f, h * 0.62f,
                    intArrayOf(0x00000000, 0xCC050509.toInt()),
                    floatArrayOf(0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w, h, vigPaint)

            // Animated film grain overlay
            val rng = Random((globalTimeSec * 24f).toInt())
            val grainPaint = Paint().apply { color = 0x1EFFFFFF }
            for (i in 0 until 140) {
                val gx = rng.nextFloat() * w
                val gy = rng.nextFloat() * h
                canvas.drawRect(gx, gy, gx + 3f * u, gy + 3f * u, grainPaint)
            }
        }
    }

    private val DANGER_KEYWORDS = setOf(
        "UBOS", "UTANG", "MALI", "DELIKADO", "TAKOT", "MULTO", "HIRAP", "SAYANG",
        "HINDI", "BAWAL", "LUGI", "GASTOS", "PROBLEMA", "KULANG", "BROKE", "TRAP",
        "LAGOT", "CRISIS", "DANGER", "STOP", "MASAKIT"
    )

    private val HIGHLIGHT_KEYWORDS = setOf(
        "SIKRETO", "LIBRE", "TAMA", "IPON", "YAMAN", "BAKIT",
        "LAKAS", "PERA", "GRABE", "SAHOD", "SWELDO",
        "NEGOSYO", "DISKARTE", "GOAL", "SOLUSYON", "TIP", "RULE", "PAANO"
    )

    private fun cleanToken(word: String): String = buildString {
        for (ch in word.uppercase()) {
            if (ch.isLetterOrDigit() || ch == '₱' || ch == '%') append(ch)
        }
    }

    private fun isDangerWord(word: String): Boolean = cleanToken(word) in DANGER_KEYWORDS

    private fun isEmphasizedWord(word: String): Boolean {
        val clean = cleanToken(word)
        if (clean.isEmpty()) return false
        if (word.contains("₱") || word.contains("%") || clean.any { it.isDigit() }) return true
        if (clean in HIGHLIGHT_KEYWORDS) return true
        return false
    }

    fun drawKaraokeCaptions(
        canvas: Canvas,
        w: Float,
        h: Float,
        style: AnimationStyle,
        wordTimings: List<WordTiming>,
        fallbackNarration: String,
        currentMs: Double
    ) {
        val u = w / 1080f
        val words = if (wordTimings.isNotEmpty()) {
            wordTimings.map { it.copy(word = it.word.uppercase()) }
        } else {
            val raw = fallbackNarration.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            raw.mapIndexed { idx, s -> WordTiming(s.uppercase(), idx * 350.0, (idx + 1) * 350.0) }
        }
        if (words.isEmpty()) return

        // Group words into 1–2 punchy uppercase words for crisp 9:16 readability
        val activeWordIdx = words.indexOfFirst { currentMs >= it.startMs && currentMs <= it.endMs }
            .let { if (it == -1) words.indexOfLast { w -> currentMs >= w.startMs }.coerceAtLeast(0) else it }

        val chunkSize = 2
        val chunkIndex = activeWordIdx / chunkSize
        val startIdx = chunkIndex * chunkSize
        val endIdx = (startIdx + chunkSize).coerceAtMost(words.size)
        val visibleWords = words.subList(startIdx, endIdx)

        val captionCenterY = h * 0.74f
        val textSizePx = 88f * u

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setStyle(Paint.Style.STROKE)
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            textAlign = Paint.Align.LEFT
        }

        // Measure total line width
        val spaceW = fillPaint.measureText(" ")
        val wordWidths = visibleWords.map { fillPaint.measureText(it.word) }
        val totalW = wordWidths.sum() + spaceW * (visibleWords.size - 1).coerceAtLeast(0)
        val startX = (w - totalW) * 0.5f

        var cursorX = startX
        visibleWords.forEachIndexed { localIdx, wt ->
            val globalIdx = startIdx + localIdx
            val isActive = globalIdx == activeWordIdx
            val isDanger = isDangerWord(wt.word)
            val isKeyword = isEmphasizedWord(wt.word)

            // 0.12s spring scale-pop (1.20x -> 1.0x) on the currently spoken word
            val popScale = if (isActive) {
                val elapsedSec = ((currentMs - wt.startMs) / 1000.0).toFloat().coerceAtLeast(0f)
                val popFrac = (elapsedSec / 0.12f).coerceIn(0f, 1f)
                1.20f - 0.20f * Easing.cubicBezier(0.16f, 1f, 0.3f, 1f, popFrac)
            } else 1.0f

            canvas.save()
            val wordW = wordWidths[localIdx]
            canvas.scale(popScale, popScale, cursorX + wordW * 0.5f, captionCenterY - textSizePx * 0.3f)

            val dropPaint = Paint(strokePaint).apply {
                color = 0xAA000000.toInt()
                strokeWidth = 18f * u
            }
            canvas.drawText(wt.word, cursorX, captionCenterY + 7f * u, dropPaint)

            strokePaint.color = if (style == AnimationStyle.STICKMAN_WHITEBOARD) 0xFFFAF6EE.toInt() else 0xFF0B0E14.toInt()
            strokePaint.strokeWidth = 17f * u
            fillPaint.color = when {
                isDanger -> 0xFFFF453A.toInt()
                isKeyword || isActive -> 0xFFFFD60A.toInt()
                style == AnimationStyle.STICKMAN_WHITEBOARD -> 0xFF111827.toInt()
                else -> Color.WHITE
            }
            canvas.drawText(wt.word, cursorX, captionCenterY, strokePaint)
            canvas.drawText(wt.word, cursorX, captionCenterY, fillPaint)

            canvas.restore()
            cursorX += wordW + spaceW
        }
    }

    private fun drawCoinRain(canvas: Canvas, w: Float, h: Float, u: Float, globalTimeSec: Float) {
        val coinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC300.toInt() }
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB45309.toInt()
            setStyle(Paint.Style.STROKE)
            strokeWidth = 4f * u
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF92400E.toInt()
            textSize = 28f * u
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        for (i in 0 until 14) {
            val seedX = ((i * 197 + 60) % 960 + 60) * u
            val speed = 380f + (i % 4) * 90f
            val cy = ((globalTimeSec * speed + i * 180f) % (h + 160f)) - 80f
            val spinScaleX = abs(cos(globalTimeSec * 5f + i)).coerceIn(0.22f, 1f)
            val r = 28f * u
            canvas.save()
            canvas.scale(spinScaleX, 1f, seedX, cy)
            canvas.drawCircle(seedX, cy, r, coinPaint)
            canvas.drawCircle(seedX, cy, r, rimPaint)
            if (spinScaleX > 0.45f) {
                canvas.drawText("₱", seedX, cy + 10f * u, textPaint)
            }
            canvas.restore()
        }
    }

    private fun drawFireSparks(canvas: Canvas, w: Float, h: Float, u: Float, globalTimeSec: Float) {
        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 22) {
            val baseX = ((i * 149 + 80) % 920 + 80) * u
            val progress = ((globalTimeSec * (0.45f + (i % 3) * 0.2f) + i * 0.13f) % 1f)
            val cy = h * 0.85f - progress * h * 0.55f
            val cx = baseX + sin(globalTimeSec * 4f + i) * 35f * u
            val alpha = ((1f - progress) * 230).toInt().coerceIn(0, 255)
            glow.color = if (i % 2 == 0) Color.argb(alpha, 255, 110, 30) else Color.argb(alpha, 255, 215, 70)
            canvas.drawCircle(cx, cy, (14f - progress * 8f) * u, glow)
        }
    }

    private fun drawChalkboardDiagram(
        canvas: Canvas,
        w: Float,
        h: Float,
        u: Float,
        style: AnimationStyle,
        sceneElapsedSec: Float
    ) {
        val progress = (sceneElapsedSec / 1.4f).coerceIn(0f, 1f)
        val strokeColor = if (style == AnimationStyle.STICKMAN_WHITEBOARD) 0xFFD9383A.toInt() else 0xFFFFD166.toInt()

        // Chalkboard backing card if not whiteboard
        if (style != AnimationStyle.STICKMAN_WHITEBOARD) {
            val boardRect = RectF(w * 0.14f, h * 0.14f, w * 0.86f, h * 0.34f)
            canvas.drawRoundRect(boardRect, 20f * u, 20f * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDD14362B.toInt() })
            canvas.drawRoundRect(boardRect, 20f * u, 20f * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF8B5E3C.toInt()
                setStyle(Paint.Style.STROKE)
                strokeWidth = 10f * u
            })
        }

        // Progressive upward growth chart arrow via PathMeasure
        val fullPath = Path().apply {
            moveTo(w * 0.22f, h * 0.30f)
            lineTo(w * 0.38f, h * 0.25f)
            lineTo(w * 0.52f, h * 0.27f)
            lineTo(w * 0.74f, h * 0.18f)
            lineTo(w * 0.70f, h * 0.22f)
            moveTo(w * 0.74f, h * 0.18f)
            lineTo(w * 0.68f, h * 0.17f)
        }

        val partial = Path()
        val pm = PathMeasure(fullPath, false)
        var remaining = pm.length * progress
        do {
            val len = pm.length
            if (remaining <= len) {
                pm.getSegment(0f, remaining, partial, true)
                break
            } else {
                pm.getSegment(0f, len, partial, true)
                remaining -= len
            }
        } while (pm.nextContour())

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            setStyle(Paint.Style.STROKE)
            strokeWidth = 10f * u
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(partial, linePaint)
    }
}
