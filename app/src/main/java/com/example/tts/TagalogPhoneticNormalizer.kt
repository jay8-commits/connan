package com.example.tts

import com.example.model.PronunciationEntry

object TagalogPhoneticNormalizer {

    private val ACRONYM_MAP = mapOf(
        "OFW" to "oh ef double-u",
        "GCASH" to "ji-kash",
        "ATM" to "ey ti em",
        "SSS" to "es es es",
        "BIR" to "bi ay ar",
        "BSP" to "bi es pi",
        "DOH" to "di oh eyts",
        "BPO" to "bi pi oh",
        "ROI" to "ar oh ay",
        "CCTV" to "si si ti vi",
        "PHP" to "piso",
        "USD" to "dolyar",
        "AI" to "ey ay",
        "CEO" to "si i oh",
        "WFH" to "work from home"
    )

    private val LOANWORD_MAP = mapOf(
        "budget" to "badyet",
        "schedule" to "iskedyul",
        "business" to "bisnes",
        "investment" to "inbestment",
        "scam" to "iskam",
        "crypto" to "kripto",
        "salary" to "salari",
        "income" to "inkam",
        "million" to "milyon",
        "billion" to "bilyon",
        "percent" to "porsyento",
        "focus" to "pokus",
        "challenge" to "tsalendj",
        "youtube" to "yutyub",
        "tiktok" to "tik-tok",
        "reels" to "rils",
        "payday" to "peydey",
        "doomscrolling" to "dum-skroling",
        "mindset" to "mayndset",
        "hustle" to "hasol"
    )

    fun normalize(
        raw: String,
        customDictionary: List<PronunciationEntry> = emptyList()
    ): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        val customPesoUnit = customDictionary
            .firstOrNull { it.original.trim() == "₱" }
            ?.spoken?.trim()?.ifBlank { null }
            ?: "piso"
        val customPercentUnit = customDictionary
            .firstOrNull { it.original.trim() == "%" }
            ?.spoken?.trim()?.ifBlank { null }
            ?: "porsyento"

        // 1. Expand Philippine Peso currency: ₱1,500.50 or ₱500
        val pesoRegex = Regex("₱\\s*([0-9,]+)(?:\\.(\\d{1,2}))?")
        text = pesoRegex.replace(text) { match ->
            val wholeStr = match.groupValues[1].replace(",", "")
            val centsStr = match.groupValues[2]
            val whole = wholeStr.toLongOrNull() ?: 0L
            val wholeWords = numberToTagalog(whole)
            if (centsStr.isNotEmpty() && (centsStr.toIntOrNull() ?: 0) > 0) {
                val cents = centsStr.padEnd(2, '0').toLongOrNull() ?: 0L
                "$wholeWords $customPesoUnit at ${numberToTagalog(cents)} sentimo"
            } else {
                "$wholeWords $customPesoUnit"
            }
        }

        // 2. Expand percentages: 72% -> pitumpu't dalawa porsyento, 3.5% -> tatlo punto lima porsyento
        val percentRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*%")
        text = percentRegex.replace(text) { match ->
            val rawNum = match.groupValues[1]
            val spokenNum = if (rawNum.contains('.')) {
                decimalStringToTagalog(rawNum)
            } else {
                numberToTagalog(rawNum.toLongOrNull() ?: 0L)
            }
            "$spokenNum $customPercentUnit"
        }

        // 3. Apply editable custom dictionary entries BEFORE integer/loanword passes so custom words win
        val sortedCustom = customDictionary
            .filter { it.original.isNotBlank() && it.spoken.isNotBlank() }
            .sortedByDescending { it.original.length }

        for (entry in sortedCustom) {
            val orig = entry.original.trim()
            val spoken = entry.spoken.trim()
            if (orig == "₱") {
                text = text.replace("₱", " $customPesoUnit ")
                continue
            }
            if (orig == "%") {
                text = text.replace("%", " $customPercentUnit ")
                continue
            }
            val isWordLike = orig.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' }
            val pattern = if (isWordLike) {
                Regex("\\b${Regex.escape(orig)}\\b", RegexOption.IGNORE_CASE)
            } else {
                Regex(Regex.escape(orig), RegexOption.IGNORE_CASE)
            }
            text = pattern.replace(text) { " $spoken " }
        }

        // 4. Expand standalone integers (up to 999,999,999)
        val numberRegex = Regex("\\b\\d{1,3}(?:,\\d{3})*\\b|\\b\\d{1,9}\\b")
        text = numberRegex.replace(text) { match ->
            val clean = match.value.replace(",", "")
            val num = clean.toLongOrNull()
            if (num != null && num in 0..999_999_999L) {
                numberToTagalog(num)
            } else {
                match.value
            }
        }

        // 5. Expand built-in acronyms (unless overridden or explicitly used as spoken target in customDictionary)
        val protectedWords = sortedCustom
            .flatMap { listOf(it.original.trim().lowercase(), it.spoken.trim().lowercase()) }
            .toSet()
        for ((acronym, spoken) in ACRONYM_MAP) {
            if (acronym.lowercase() in protectedWords) continue
            val regex = Regex("\\b${Regex.escape(acronym)}\\b", RegexOption.IGNORE_CASE)
            text = regex.replace(text, spoken)
        }

        // 6. Smooth tricky English loanwords into natural Tagalog phonetics
        for ((loan, phonetic) in LOANWORD_MAP) {
            if (loan.lowercase() in protectedWords) continue
            val regex = Regex("\\b${Regex.escape(loan)}\\b", RegexOption.IGNORE_CASE)
            text = regex.replace(text, phonetic)
        }

        // 7. Final strict zero-digit pass so ZERO ASCII digits ever reach ElevenLabs TTS
        return sanitizeDigitsForTts(text)
    }

    fun sanitizeDigitsForTts(input: String): String {
        if (input.isEmpty()) return ""
        if (!input.any { it.isDigit() }) {
            return input.replace(Regex("\\s+"), " ").trim()
        }

        // Preserve ElevenLabs audio tags like [whispers] or [tense] verbatim while sanitizing all speech around them
        val tagRegex = Regex("\\[[^\\]]+\\]")
        val matches = tagRegex.findAll(input).toList()
        if (matches.isEmpty()) {
            return sanitizeDigitsSegment(input).replace(Regex("\\s+"), " ").trim()
        }

        val sb = StringBuilder()
        var cursor = 0
        for (m in matches) {
            if (m.range.first > cursor) {
                sb.append(sanitizeDigitsSegment(input.substring(cursor, m.range.first)))
            }
            val tag = m.value
            sb.append(if (tag.any { it.isDigit() }) sanitizeDigitsSegment(tag) else tag)
            cursor = m.range.last + 1
        }
        if (cursor < input.length) {
            sb.append(sanitizeDigitsSegment(input.substring(cursor)))
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun sanitizeDigitsSegment(segment: String): String {
        var text = segment
        if (!text.any { it.isDigit() }) return text

        // A. Shorthand numbers like 500k, 2M, 1.5B
        val shorthandRegex = Regex("(?i)\\b(\\d+(?:,\\d+)*)(?:\\.(\\d+))?([kmb])\\b")
        text = shorthandRegex.replace(text) { match ->
            val intPart = match.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
            val decPart = match.groupValues[2]
            val unitChar = match.groupValues[3].lowercase()
            val multiplierUnit = when (unitChar) {
                "k" -> "libo"
                "m" -> "milyon"
                else -> "bilyon"
            }
            if (decPart.isNotEmpty()) {
                "${numberToTagalog(intPart)} punto ${spellDigitsIndividually(decPart)} $multiplierUnit"
            } else {
                val prefix = if (intPart == 1L) "isang" else numberToTagalog(intPart)
                "$prefix $multiplierUnit"
            }
        }

        // B. Time strings like 3:30 or 12:05
        val timeRegex = Regex("\\b(\\d+):(\\d+)\\b")
        text = timeRegex.replace(text) { match ->
            val hour = match.groupValues[1].toLongOrNull() ?: 0L
            val min = match.groupValues[2].toLongOrNull() ?: 0L
            if (min == 0L) {
                "alas-${numberToTagalog(hour)}"
            } else {
                "${numberToTagalog(hour)} at ${numberToTagalog(min)}"
            }
        }

        // C. Decimals like 3.5 or 99.9
        val decimalRegex = Regex("\\b(\\d+(?:,\\d+)*)\\.(\\d+)\\b")
        text = decimalRegex.replace(text) { match ->
            decimalStringToTagalog(match.value.replace(",", ""))
        }

        // D. English/Tagalog ordinals like 1st, 2nd, 3rd, 4th
        val ordinalRegex = Regex("(?i)\\b(\\d+)(st|nd|rd|th)\\b")
        text = ordinalRegex.replace(text) { match ->
            val n = match.groupValues[1].toLongOrNull() ?: 0L
            when (n) {
                1L -> "una"
                2L -> "pangalawa"
                3L -> "pangatlo"
                else -> "pang-${numberToTagalog(n)}"
            }
        }

        // E. Remaining multi-digit numbers (including >= 1 billion)
        val remainingNumbersRegex = Regex("\\d+(?:,\\d+)*")
        text = remainingNumbersRegex.replace(text) { match ->
            val clean = match.value.replace(",", "")
            val num = clean.toLongOrNull()
            if (num != null && num <= 999_999_999_999L) {
                largeNumberToTagalog(num)
            } else {
                spellDigitsIndividually(clean)
            }
        }

        // F. Absolute guarantee: replace any stray ASCII digit character 0-9 (e.g. A1B2 fallback)
        if (text.any { it.isDigit() }) {
            val out = StringBuilder(text.length * 2)
            for (i in text.indices) {
                val ch = text[i]
                if (ch.isDigit()) {
                    if (out.isNotEmpty() && out.last().isLetter()) out.append(' ')
                    out.append(digitCharToTagalog(ch))
                    val next = text.getOrNull(i + 1)
                    if (next != null && next.isLetter()) out.append(' ')
                } else {
                    out.append(ch)
                }
            }
            text = out.toString()
        }
        return text
    }

    private fun decimalStringToTagalog(rawDecimal: String): String {
        val parts = rawDecimal.split('.')
        val whole = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val frac = parts.getOrNull(1).orEmpty()
        return if (frac.isNotEmpty()) {
            "${largeNumberToTagalog(whole)} punto ${spellDigitsIndividually(frac)}"
        } else {
            largeNumberToTagalog(whole)
        }
    }

    private fun largeNumberToTagalog(n: Long): String {
        if (n <= 999_999_999L) return numberToTagalog(n)
        val billions = n / 1_000_000_000L
        val rem = n % 1_000_000_000L
        val bWord = if (billions == 1L) "isang bilyon" else "${numberToTagalog(billions)} bilyon"
        return if (rem == 0L) bWord else "$bWord ${numberToTagalog(rem)}"
    }

    private fun spellDigitsIndividually(digits: String): String =
        digits.filter { it.isDigit() }.map { digitCharToTagalog(it) }.joinToString(" ")

    private fun digitCharToTagalog(ch: Char): String = when (ch) {
        '0' -> "sero"
        '1' -> "isa"
        '2' -> "dalawa"
        '3' -> "tatlo"
        '4' -> "apat"
        '5' -> "lima"
        '6' -> "anim"
        '7' -> "pito"
        '8' -> "walo"
        '9' -> "siyam"
        else -> ""
    }

    fun numberToTagalog(n: Long): String {
        if (n == 0L) return "sero"
        if (n < 0L) return "negatibo ${numberToTagalog(-n)}"

        val ones = arrayOf(
            "", "isa", "dalawa", "tatlo", "apat", "lima",
            "anim", "pito", "walo", "siyam"
        )
        val teens = arrayOf(
            "sampu", "labing-isa", "labindalawa", "labintatlo", "labing-apat",
            "labinlima", "labing-anim", "labimpito", "labingwalo", "labinsiyam"
        )
        val tens = arrayOf(
            "", "sampu", "dalawampu", "tatlumpu", "apatnapu",
            "limampu", "animnapu", "pitumpu", "walumpu", "siyamnapu"
        )

        fun under1000(num: Int): String {
            if (num == 0) return ""
            val sb = StringBuilder()
            val h = num / 100
            val rem = num % 100
            if (h > 0) {
                val hWord = when (h) {
                    1 -> "isang daan"
                    4 -> "apat na raan"
                    6 -> "anim na raan"
                    7 -> "pitong daan"
                    8 -> "walong daan"
                    9 -> "siyam na raan"
                    else -> "${ones[h]}ng daan"
                }
                sb.append(hWord)
                if (rem > 0) sb.append(" at ")
            }
            when {
                rem in 1..9 -> sb.append(ones[rem])
                rem in 10..19 -> sb.append(teens[rem - 10])
                rem >= 20 -> {
                    val t = rem / 10
                    val o = rem % 10
                    sb.append(tens[t])
                    if (o > 0) {
                        sb.append("'t ").append(ones[o])
                    }
                }
            }
            return sb.toString()
        }

        val millions = (n / 1_000_000L).toInt()
        val thousands = ((n % 1_000_000L) / 1_000L).toInt()
        val rest = (n % 1_000L).toInt()

        val parts = mutableListOf<String>()
        if (millions > 0) {
            val mPrefix = if (millions == 1) "isang" else under1000(millions)
            parts.add("$mPrefix milyon")
        }
        if (thousands > 0) {
            val kPrefix = if (thousands == 1) "isang" else under1000(thousands)
            parts.add("$kPrefix libo")
        }
        if (rest > 0) {
            parts.add(under1000(rest))
        }
        return parts.joinToString(" ")
    }
}
