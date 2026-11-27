package com.example.render

import android.graphics.Canvas
import com.example.model.AnimationStyle
import com.example.model.WordTiming

/**
 * Shared viral karaoke caption sanitizer & renderer.
 * Strips Eleven v4 expressive inline performance tags (e.g. [whispers], [tense], [excited])
 * from caption tokens and alignment indices so audio direction cues are voice-acted
 * by ElevenLabs but never shown inside on-screen 1–2 word subtitles.
 */
object KaraokeCaptionRenderer {

    /**
     * Strips all bracketed `[...]` audio direction segments from narration/caption text
     * and collapses redundant whitespace.
     */
    fun stripAudioTags(raw: String): String {
        if (raw.isBlank()) return ""
        if (!raw.contains('[')) {
            return collapseWhitespace(raw)
        }
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '[') {
                val closeIdx = raw.indexOf(']', i + 1)
                if (closeIdx > i) {
                    sb.append(' ')
                    i = closeIdx + 1
                    continue
                }
            }
            sb.append(raw[i])
            i++
        }
        return collapseWhitespace(sb.toString())
    }

    /**
     * Extracts clean spoken caption tokens with all `[...]` audio tags stripped.
     */
    fun extractCleanCaptionTokens(narration: String): List<String> {
        val stripped = stripAudioTags(narration)
        if (stripped.isBlank()) return emptyList()
        return stripped
            .split(' ', '\n', '\t', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Strips all `[...]` bracketed audio tags from alignment [WordTiming] tokens and drops
     * standalone tag-only entries (including multi-word `[slow dramatic pause]` segments)
     * while preserving exact timing alignment indices for spoken words.
     */
    fun sanitizeCaptionWordTimings(wordTimings: List<WordTiming>): List<WordTiming> {
        if (wordTimings.isEmpty()) return emptyList()
        val result = ArrayList<WordTiming>(wordTimings.size)
        var insideSplitBracket = false
        for (wt in wordTimings) {
            val rawWord = wt.word.trim()
            if (rawWord.isEmpty()) continue

            val cleanedWord = if (insideSplitBracket) {
                val closeIdx = rawWord.indexOf(']')
                if (closeIdx >= 0) {
                    insideSplitBracket = false
                    stripAudioTags(rawWord.substring(closeIdx + 1))
                } else {
                    ""
                }
            } else {
                val stripped = stripAudioTags(rawWord)
                val openIdx = stripped.indexOf('[')
                if (openIdx >= 0 && stripped.indexOf(']', openIdx + 1) < 0) {
                    insideSplitBracket = true
                    stripped.substring(0, openIdx).trim()
                } else {
                    stripped
                }
            }

            if (cleanedWord.isNotBlank()) {
                result.add(wt.copy(word = cleanedWord))
            }
        }
        return result
    }

    data class FittedCaptionLine(
        val words: List<WordTiming>,
        val widthPx: Float
    )

    data class FittedCaptionLayout(
        val lines: List<FittedCaptionLine>,
        val fontSizePx: Float,
        val lineHeightPx: Float,
        val maxLineWidthPx: Float,
        val safeMaxWidthPx: Float
    )

    /**
     * Fits caption words inside 86% of [canvasWidthPx] (7% safe horizontal padding per side),
     * wrapping up to 2 centered lines and automatically shrinking font size for long words/phrases (>22 chars).
     */
    fun fitCaptionLayout(
        words: List<WordTiming>,
        canvasWidthPx: Float,
        canvasHeightPx: Float,
        measureTextWidth: (text: String, fontSizePx: Float) -> Float = { text, fontPx ->
            text.length * fontPx * 0.58f
        }
    ): FittedCaptionLayout {
        val safeW = (canvasWidthPx * 0.86f).coerceAtLeast(120f)
        if (words.isEmpty()) {
            return FittedCaptionLayout(
                lines = emptyList(),
                fontSizePx = 42f,
                lineHeightPx = 50f,
                maxLineWidthPx = 0f,
                safeMaxWidthPx = safeW
            )
        }

        val minDim = minOf(canvasWidthPx, canvasHeightPx).coerceAtLeast(240f)
        val dimScale = minDim / 1080f
        val baseFontPx = if (canvasWidthPx > canvasHeightPx) 44f * dimScale else 42f * dimScale
        val minFontPx = 16f * dimScale

        val joinedAll = words.joinToString(" ") { it.word }
        val totalChars = joinedAll.length
        val maxWordChars = words.maxOfOrNull { it.word.length } ?: 0
        val longestRelevantChars = maxOf(totalChars, maxWordChars)

        var fontSizePx = if (longestRelevantChars > 22) {
            val shrinkRatio = (22f / longestRelevantChars.toFloat()).coerceIn(0.54f, 1.0f)
            maxOf(minFontPx, baseFontPx * shrinkRatio)
        } else {
            baseFontPx
        }

        fun splitWordsIntoMaxTwoLines(inputWords: List<WordTiming>, fontPx: Float): List<List<WordTiming>> {
            if (inputWords.size <= 1) return listOf(inputWords)
            val singleLineText = inputWords.joinToString(" ") { it.word }
            val singleW = measureTextWidth(singleLineText, fontPx)
            if (singleW <= safeW && singleLineText.length <= 22) {
                return listOf(inputWords)
            }
            var bestSplit = 1
            var bestMaxLineW = Float.MAX_VALUE
            for (splitIdx in 1 until inputWords.size) {
                val left = inputWords.subList(0, splitIdx).joinToString(" ") { it.word }
                val right = inputWords.subList(splitIdx, inputWords.size).joinToString(" ") { it.word }
                val maxW = maxOf(measureTextWidth(left, fontPx), measureTextWidth(right, fontPx))
                if (maxW < bestMaxLineW) {
                    bestMaxLineW = maxW
                    bestSplit = splitIdx
                }
            }
            return listOf(
                inputWords.subList(0, bestSplit),
                inputWords.subList(bestSplit, inputWords.size)
            )
        }

        var rawLines = splitWordsIntoMaxTwoLines(words, fontSizePx)
        var maxLineW = rawLines.maxOf { line ->
            measureTextWidth(line.joinToString(" ") { it.word }, fontSizePx)
        }

        // Handle ultra-long single unbroken token (>22 chars) by splitting if needed after shrinking
        if (rawLines.size == 1 && words.size == 1 && words[0].word.length > 22 && maxLineW > safeW) {
            val singleWord = words[0]
            val mid = singleWord.word.length / 2
            val part1 = singleWord.copy(word = singleWord.word.substring(0, mid) + "-")
            val part2 = singleWord.copy(word = singleWord.word.substring(mid))
            rawLines = listOf(listOf(part1), listOf(part2))
            maxLineW = rawLines.maxOf { line ->
                measureTextWidth(line.joinToString(" ") { it.word }, fontSizePx)
            }
        }

        var guard = 0
        while (maxLineW > safeW && fontSizePx > minFontPx && guard < 16) {
            val factor = ((safeW / maxLineW) * 0.97f).coerceIn(0.65f, 0.94f)
            fontSizePx = maxOf(minFontPx, fontSizePx * factor)
            rawLines = splitWordsIntoMaxTwoLines(words, fontSizePx)
            maxLineW = rawLines.maxOf { line ->
                measureTextWidth(line.joinToString(" ") { it.word }, fontSizePx)
            }
            guard++
        }

        val fittedLines = rawLines.map { lineWords ->
            val lineW = measureTextWidth(lineWords.joinToString(" ") { it.word }, fontSizePx)
            FittedCaptionLine(words = lineWords, widthPx = lineW)
        }

        return FittedCaptionLayout(
            lines = fittedLines,
            fontSizePx = fontSizePx,
            lineHeightPx = fontSizePx * 1.24f,
            maxLineWidthPx = fittedLines.maxOfOrNull { it.widthPx } ?: 0f,
            safeMaxWidthPx = safeW
        )
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
        WorldFxAndCaptionRenderer.drawKaraokeCaptions(
            canvas = canvas,
            w = w,
            h = h,
            style = style,
            wordTimings = wordTimings,
            fallbackNarration = fallbackNarration,
            currentMs = currentMs
        )
    }

    private fun collapseWhitespace(text: String): String {
        val sb = StringBuilder(text.length)
        var prevSpace = true
        for (ch in text) {
            if (ch.isWhitespace()) {
                if (!prevSpace) {
                    sb.append(' ')
                    prevSpace = true
                }
            } else {
                sb.append(ch)
                prevSpace = false
            }
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
            sb.setLength(sb.length - 1)
        }
        return sb.toString()
    }
}
