package com.indstream

import com.indstream.StreamEngine.RawStream

/**
 * FILE: LinkNaming.kt — the ONE naming rule every emitted link must follow
 * (user spec, Sept 2026):
 *
 *   `{ServerName[-n]} ({language}) {Resolution}`
 *
 *  - {ServerName}  base server name. When several links share the exact same
 *                  (name, language, resolution), ALL members are numbered
 *                  right after the server name with "-n": 2 servers render as
 *                  "VidLink-1 (hindi) 1080p" / "VidLink-2 (hindi) 1080p", 5
 *                  servers number "-1"…"-5", 20 number "-1"…"-20" — to the
 *                  last one. A unique server keeps its plain name (no "-1").
 *  - ({language})  always present, in brackets. Tokens: hindi, eng, multi,
 *                  japanese, original, … — see [languageTag]. A stream that
 *                  carries several languages at once (dual-audio master) is
 *                  "multi".
 *  - {Resolution}  the stream's tallest known height ("1080p", "4K"). A
 *                  resolution that already appears inside the server-name
 *                  indicator is NOT repeated (spec: drop it).
 */
object LinkNaming {

    /**
     * Canonical display token for a language. Accepts anything a resolver
     * might recover (ISO codes, API strings, native-script names like
     * "हिन्दी"/"اُردُو") and maps it into the short families the UI shows:
     * hindi, eng, multi, japanese, … Falls back to the cleaned input.
     */
    fun languageTag(raw: String?): String {
        if (raw.isNullOrBlank()) return "multi"
        val s = raw.trim().lowercase()
        return when {
            // Dual/multi audio FIRST: duo wording or the "a+b" combo form must
            // win over the single-language contains() checks below (a string
            // like "Hindi+English" contains "hindi").
            s.contains("dual") || s.contains("multi") || s.contains("+") || s.contains("&") ||
                s.contains("hindi+english") || s.contains("hindi & english") || s.contains("both") -> "multi"
            // Hindi family (incl. Devanagari + short codes).
            s.contains("hindi") || s == "hi" || s == "hin" || s.contains("हिन्दी") || s.contains("हिंदी") -> "hindi"
            s.contains("urdu") || s == "ur" || s.contains("اُردُو") || s.contains("اردو") -> "urdu"
            // English family.
            s.contains("english") || s == "eng" || s == "en" || s.contains("inglés") -> "eng"
            // Common single languages.
            s.contains("japan") || s == "ja" || s == "jp" || s.contains("日本") -> "japanese"
            s.contains("tamil") -> "tamil"
            s.contains("telugu") -> "telugu"
            s.contains("kannada") -> "kannada"
            s.contains("malayalam") -> "malayalam"
            s.contains("bengali") || s.contains("বাংলা") -> "bengali"
            s.contains("marathi") -> "marathi"
            s.contains("punjabi") -> "punjabi"
            s.contains("gujarati") -> "gujarati"
            s.contains("korean") || s == "ko" -> "korean"
            s.contains("mandarin") || s.contains("chinese") || s.contains("中文") || s == "zh" -> "chinese"
            s.contains("french") || s.contains("français") || s == "fr" -> "french"
            s.contains("spanish") || s.contains("español") || s == "es" -> "spanish"
            s.contains("german") || s == "de" -> "german"
            s.contains("arabic") || s.contains("العربية") || s == "ar" -> "arabic"
            s.contains("russian") || s.contains("русский") || s == "ru" -> "russian"
            s.contains("portuguese") || s.contains("português") -> "portuguese"
            s.contains("indonesian") || s == "id" -> "indonesian"
            s.contains("filipino") || s.contains("tagalog") -> "filipino"
            s.contains("turkish") || s == "tr" -> "turkish"
            s.contains("italian") || s == "it" -> "italian"
            s.contains("thai") || s == "th" -> "thai"
            // Original = whatever language the title itself is (set upstream);
            // shown as-is in brackets.
            s == "original" || s == "orig" || s == "org" -> "original"
            // Unknown codes map straight through ("te", "kn", …) trimmed to
            // something readable — never empty.
            else -> s.substringBefore('-').substringBefore(' ').take(12).ifBlank { "multi" }
        }
    }

    /**
     * Map a raw audio label to the tag for a stream of a title whose original
     * language is [originalLang] (TMDB code, e.g. "ja"). "Original"/blank
     * labels become the actual language of the title.
     */
    fun languageTagFor(raw: String?, originalLang: String? = null): String {
        val s = raw?.trim()?.lowercase().orEmpty()
        if (s.isEmpty() || s == "original" || s == "orig" || s == "org") {
            return originalLang?.let { languageTag(it) } ?: "original"
        }
        return languageTag(s)
    }

    /**
     * True when [indicator] (a sub-server label like "Server 1080p",
     * "Fade 1080p", "Nova") already carries a resolution token. Spec: when it
     * does, [displayName] must NOT append the quality again.
     */
    fun hasResolution(indicator: String?): Boolean =
        !indicator.isNullOrBlank() &&
            Regex("""\b(\d{3,4})p\b""", RegexOption.IGNORE_CASE).containsMatchIn(indicator)

    /**
     * Build the final display name.
     *
     * @param serverName   base server/brand, e.g. "VidLink", "PrimeSrc Nova".
     * @param audioLabel   raw audio label from the resolver ("Hindi", "hi",
     *                     "Original", "हिन्दी", "").
     * @param qualityHint  tallest known height (0 = unknown/adaptive).
     * @param subIndicator optional sub-server label that is part of the base
     *                     name; if it already carries a resolution the quality
     *                     is NOT appended again.
     * @param duplicateIndex group number from [dedupeNames]: 0 = unique (no
     *                     number), 1..N = the n-th member of an identical
     *                     (name+language+resolution) group — numbered to the
     *                     last one.
     */
    fun displayName(
        serverName: String,
        audioLabel: String? = "",
        qualityHint: Int = 0,
        subIndicator: String? = null,
        duplicateIndex: Int = 0,
        originalLang: String? = null,
    ): String {
        val base = serverName.trim().ifBlank { "Server" }
        val tag = languageTagFor(audioLabel, originalLang)
        val nameWithSub = if (subIndicator.isNullOrBlank()) base else "$base $subIndicator".trim()
        // User spec: the 1/2/3… counter comes directly after the server name,
        // separated by "-": "VidLink-2 (hindi) 1080p" — NOT at the end of the
        // whole label.
        val serverPart = if (duplicateIndex > 0) "$nameWithSub-$duplicateIndex" else nameWithSub

        // Brands already naming the language ("MyFlixer Hindi" + hindi tag)
        // don't repeat it: the (lang) bracket is dropped for them.
        val tagPart = if (
            tag != "multi" &&
            Regex("\\b${Regex.escape(tag)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(nameWithSub)
        ) "" else "($tag)"

        // Spec: if the server name already shows a resolution, drop the extra one.
        val res = if (hasResolution(nameWithSub)) "" else qualityLabel(qualityHint)
        val parts = listOfNotNull(
            serverPart,
            tagPart.takeIf { it.isNotBlank() },
            res.takeIf { it.isNotBlank() },
        )
        return parts.joinToString(" ")
    }

    /** Same as [displayName] but for a compact "brand (lang) res" string when
     *  the caller has already folded the sub-server into the base name. */
    fun displayName(base: String, audioLabel: String?, qualityHint: Int): String =
        displayName(base, audioLabel, qualityHint, subIndicator = null, duplicateIndex = 0)

    /** Height → display token ("4K", "1080p", "720p"). Mirrors ManifestKit. */
    fun qualityLabel(height: Int): String = when {
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height >= 480 -> "480p"
        height >= 360 -> "360p"
        height > 0 -> "${height}p"
        else -> ""
    }

    /**
     * Number every member of a duplicate group (positional). When [RawStream]s
     * share the same (base name + language tag + resolution), they are numbered
     * 1..N in emission order — 2 identical streams become "...-1"/"...-2", five
     * become "-1".."-5", and so on to the last one. A unique (singleton) stream
     * gets 0 ("no number").
     *
     * Returns a list parallel to [streams]: result[i] is the number for
     * streams[i]. (RawStream is a data class — identical entries collide as map
     * keys, so positional output is the only reliable shape.)
     */
    fun dedupeNames(streams: List<RawStream>, originalLang: String? = null): List<Int> {
        fun keyOf(s: RawStream): String {
            val tag = languageTagFor(s.audioLabel, originalLang)
            return "${s.serverName.trim().lowercase()}|$tag|${s.qualityHint}"
        }
        val totals = HashMap<String, Int>()
        for (s in streams) totals.merge(keyOf(s), 1) { a, b -> a + b }
        val running = HashMap<String, Int>()
        return streams.map { s ->
            val key = keyOf(s)
            if ((totals[key] ?: 0) <= 1) {
                0 // singleton — no number
            } else {
                val n = (running[key] ?: 0) + 1
                running[key] = n
                n // first of the group gets -1, next -2, ... to the last
            }
        }
    }

    /** Native-script / code subtitle names -> canonical English names, so the
     *  subtitle menu shows "Urdu", "Bengali", "Arabic" … instead of a dozen
     *  identical fallback labels (verified live: VidLink captions carry
     *  `language` fields like اُردُو | বাংলা | العربية | 中文 which older code
     *  never read, defaulting every track to "English"). */
    fun canonicalSubtitleName(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return "Subtitle"
        val lower = s.lowercase()
        return when {
            lower.contains("हिन्द") || lower.contains("हिंद") || lower == "hi" || lower == "hin" || lower.contains("hindi") -> "Hindi"
            lower.contains("اُردُو") || lower.contains("اردو") || lower.contains("urdu") || lower == "ur" -> "Urdu"
            lower.contains("বাংলা") || lower.contains("bengali") || lower.contains("bangla") || lower == "bn" -> "Bengali"
            lower.contains("العربية") || lower.contains("عرب") || lower.contains("arabic") || lower == "ar" -> "Arabic"
            lower.contains("русск") || lower.contains("russian") || lower == "ru" -> "Russian"
            lower.contains("中文") || lower.contains("mandarin") || lower.contains("chinese") || lower == "zh" -> "Chinese"
            lower.contains("日本語") || lower.contains("日本") || lower.contains("japanese") || lower == "ja" || lower == "jp" -> "Japanese"
            lower.contains("한국어") || lower.contains("한국") || lower.contains("korean") || lower == "ko" -> "Korean"
            lower.contains("français") || lower.contains("francais") || lower.contains("french") || lower == "fr" -> "French"
            lower.contains("español") || lower.contains("espanol") || lower.contains("spanish") || lower == "es" -> "Spanish"
            lower.contains("português") || lower.contains("portugues") || lower.contains("portuguese") || lower == "pt" -> "Portuguese"
            lower.contains("filipino") || lower.contains("tagalog") -> "Filipino"
            lower.contains("indonesian") || lower.contains("bahasa") || lower == "id" -> "Indonesian"
            lower.contains("english") || lower == "en" || lower == "eng" -> "English"
            lower.contains("tamil") || lower == "ta" -> "Tamil"
            lower.contains("telugu") || lower == "te" -> "Telugu"
            lower.contains("malayalam") || lower == "ml" -> "Malayalam"
            lower.contains("kannada") || lower == "kn" -> "Kannada"
            lower.contains("marathi") || lower == "mr" -> "Marathi"
            lower.contains("punjabi") || lower == "pa" -> "Punjabi"
            lower.contains("gujarati") || lower == "gu" -> "Gujarati"
            lower.contains("nepali") || lower == "ne" -> "Nepali"
            lower.contains("sinhala") || lower == "si" -> "Sinhala"
            lower.contains("thai") || lower == "th" -> "Thai"
            lower.contains("turkish") || lower == "tr" -> "Turkish"
            lower.contains("german") || lower == "de" -> "German"
            lower.contains("italian") || lower == "it" -> "Italian"
            else -> s.substringBefore('-').substringBefore(' ').ifBlank { "Subtitle" }
        }
    }
}
