package com.example.llm

object TagalogNarrationValidator {

    private val SPEAKER_PREFIX_REGEX = Regex("^(?:narrator|speaker|voiceover|tagapagsalaysay|scene\\s*\\d+)\\s*:\\s*", RegexOption.IGNORE_CASE)

    private val CONTROL_OR_REPLACEMENT_REGEX = Regex("[\\uFFFD\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")

    private val LITERAL_IDIOM_OR_META_PATTERNS = listOf(
        "as an ai",
        "here is the json",
        "insert pause",
        "tagalog narration here",
        "narration here"
    )

    fun cleanForTts(raw: String): String {
        var s = raw.trim()
        s = SPEAKER_PREFIX_REGEX.replace(s, "")
        s = stripStageBrackets(s, '[', ']', maxSpan = 40)
        s = stripStageBrackets(s, '(', ')', maxSpan = 30)
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Cleans speaker prefixes and parenthetical notes while keeping Eleven v4 inline
     * expressive audio tags such as [whispers], [tense], or [excited] intact.
     */
    fun cleanPreservingAudioTags(raw: String): String {
        var s = raw.trim()
        s = SPEAKER_PREFIX_REGEX.replace(s, "")
        s = stripStageBrackets(s, '(', ')', maxSpan = 30)
        return s.replace(Regex("\\s+"), " ").trim()
    }

    fun stripAudioTags(raw: String): String =
        stripStageBrackets(raw, '[', ']', maxSpan = 80).replace(Regex("\\s+"), " ").trim()

    private fun stripStageBrackets(input: String, open: Char, close: Char, maxSpan: Int): String {
        if (!input.contains(open)) return input
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            if (input[i] == open) {
                val end = input.indexOf(close, i + 1)
                if (end > i + 1 && (end - i - 1) <= maxSpan) {
                    sb.append(' ')
                    i = end + 1
                    continue
                }
            }
            sb.append(input[i])
            i++
        }
        return sb.toString()
    }

    fun isValidNarration(raw: String): Boolean = isValidTagalogNarration(raw)

    fun isValidTagalogNarration(raw: String): Boolean {
        return try {
            validateOrThrow(raw)
            true
        } catch (_: MalformedSceneJsonException) {
            false
        }
    }

    fun validateOrThrow(raw: String) {
        val cleaned = cleanForTts(raw)
        if (cleaned.isEmpty()) {
            throw MalformedSceneJsonException("Narration is empty after sanitization")
        }
        if (CONTROL_OR_REPLACEMENT_REGEX.containsMatchIn(cleaned)) {
            throw MalformedSceneJsonException("Narration contains invalid control or replacement characters")
        }

        val lower = cleaned.lowercase()
        for (bad in LITERAL_IDIOM_OR_META_PATTERNS) {
            if (lower.contains(bad)) {
                throw MalformedSceneJsonException("Narration contains forbidden meta placeholder phrase: $bad")
            }
        }
    }
}
