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
