package com.indstream

import com.indstream.StreamEngine.RawStream

/**
 * FILE: LinkNaming.kt — the ONE naming rule every emitted link must follow
 * (user spec, Sept 2026):
 *
 *   `{ServerName[-n]} ({Language}) {Resolution}`
 *
 *  - {ServerName}  base server name. When several links share the exact same
 *                  (name, language, resolution), ALL members are numbered
 *                  right after the server name with "-n": 2 servers render as
 *                  "VidLink-1 (Hindi) 1080p" / "VidLink-2 (Hindi) 1080p", 5
 *                  servers number "-1"…"-5", 20 number "-1"…"-20" — to the
 *                  last one. A unique server keeps its plain name (no "-1").
 *  - ({Language})  always present, in brackets. Full display names with a
 *                  capital initial: Hindi, English, Multi, Japanese, …  — see
 *                  [languageTag]. A stream that carries several languages at
 *                  once (dual-audio master) is "Multi".
 *  - {Resolution}  the stream's tallest known height ("1080p", "4K"). A
 *                  resolution that already appears inside the server-name
 *                  indicator is NOT repeated (spec: drop it).
 */
object LinkNaming {

    /**
     * Canonical display name for a language: FULL name with a capital
     * initial — "Hindi", "English", "Japanese", "Multi" (user spec, Sept
     * 2026: never short forms like "eng" or lowercase like "hindi").
     * Accepts anything a resolver might recover (ISO codes, API strings,
     * native-script names like "हिन्दी"/"اُردُو"). Unknown/blank falls
     * back to "Multi" (the honest bucket for unlabeled or dual-audio
     * streams), never empty.
     */
    fun languageTag(raw: String?): String {
        if (raw.isNullOrBlank()) return "Multi"
        val s = raw.trim().lowercase()
        // Dual/multi audio FIRST: duo wording or the "a+b" combo form must
        // win over the single-language checks below (a string like
        // "Hindi+English" contains "hindi").
        if (s.contains("dual") || s.contains("multi") || s.contains("+") || s.contains("&") ||
            s.contains("hindi+english") || s.contains("hindi & english") || s.contains("both")) return "Multi"
        // "Original" is not a language — callers pass the TMDB original_language
        // so languageTagFor() maps it to the real name; on its own it stays.
        if (s == "original" || s == "orig" || s == "org") return "Original"
        return canonicalSubtitleName(s)
    }

    /**
     * Map a raw audio label to the tag for a stream of a title whose original
     * language is [originalLang] (TMDB code, e.g. "ja"). "Original"/blank
     * labels become the actual language of the title.
     */
    fun languageTagFor(raw: String?, originalLang: String? = null): String {
        val s = raw?.trim()?.lowercase().orEmpty()
        if (s.isEmpty() || s == "original" || s == "orig" || s == "org") {
            return originalLang?.let { languageTag(it) } ?: "Original"
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
        // separated by "-": "VidLink-2 (Hindi) 1080p" — NOT at the end of the
        // whole label.
        val serverPart = if (duplicateIndex > 0) "$nameWithSub-$duplicateIndex" else nameWithSub

        // Brands already naming the language ("MyFlixer Hindi" + Hindi tag)
        // don't repeat it: the (lang) bracket is dropped for them.
        val tagPart = if (
            tag != "Multi" &&
            Regex("\\b${Regex.escape(tag)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(nameWithSub)
        ) "" else "($tag)"

        // Spec: if the server name already shows the SAME resolution token, drop
        // the extra one. Compare the exact output token (e.g. "1080p", "4K") rather
        // than a loose \d{3,4}p regex, so glued forms like "Server1080p"/"x1080p"
        // and alt tokens like "4K" are detected and not appended a second time
        // (otherwise the label renders as "… 1080p 1080p").
        val res = qualityLabel(qualityHint)
        val resPart = if (res.isNotEmpty() && nameWithSub.contains(res, ignoreCase = true)) "" else res
        val parts = listOfNotNull(
            serverPart,
            tagPart.takeIf { it.isNotBlank() },
            resPart.takeIf { it.isNotBlank() },
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
     *  never read, defaulting every track to "English").
     *  Also maps the 3-letter ISO 639-2 codes (ara, ger, eng, hin, tam …)
     *  used by OpenSubtitles-based subtitle providers. */
    fun canonicalSubtitleName(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return "Subtitle"
        val lower = s.lowercase()
        return when {
            lower.contains("हिन्द") || lower.contains("हिंद") || lower == "hi" || lower == "hin" || lower.contains("hindi") -> "Hindi"
            lower.contains("اُردُو") || lower.contains("اردو") || lower.contains("urdu") || lower == "ur" || lower == "urd" -> "Urdu"
            lower.contains("বাংলা") || lower.contains("bengali") || lower.contains("bangla") || lower == "bn" || lower == "ben" || lower == "bang" -> "Bengali"
            lower.contains("العربية") || lower.contains("عرب") || lower.contains("arabic") || lower == "ar" || lower == "ara" || lower == "arb" -> "Arabic"
            lower.contains("русск") || lower.contains("russian") || lower == "ru" || lower == "rus" -> "Russian"
            lower.contains("中文") || lower.contains("mandarin") || lower.contains("chinese") || lower == "zh" || lower == "chi" || lower == "zho" -> "Chinese"
            lower.contains("日本語") || lower.contains("日本") || lower.contains("japanese") || lower == "ja" || lower == "jp" || lower == "jpn" -> "Japanese"
            lower.contains("한국어") || lower.contains("한국") || lower.contains("korean") || lower == "ko" || lower == "kor" -> "Korean"
            lower.contains("français") || lower.contains("francais") || lower.contains("french") || lower == "fr" || lower == "fre" || lower == "fra" -> "French"
            lower.contains("español") || lower.contains("espanol") || lower.contains("spanish") || lower == "es" || lower == "spa" -> "Spanish"
            lower.contains("português") || lower.contains("portugues") || lower.contains("portuguese") || lower == "pt" || lower == "por" -> "Portuguese"
            lower.contains("filipino") || lower.contains("tagalog") || lower == "fil" -> "Filipino"
            lower.contains("indonesian") || lower.contains("bahasa") || lower == "id" || lower == "ind" -> "Indonesian"
            lower.contains("english") || lower == "en" || lower == "eng" -> "English"
            lower.contains("tamil") || lower == "ta" || lower == "tam" -> "Tamil"
            lower.contains("telugu") || lower == "te" || lower == "tel" -> "Telugu"
            lower.contains("malayalam") || lower == "ml" || lower == "mal" -> "Malayalam"
            lower.contains("kannada") || lower == "kn" || lower == "kan" -> "Kannada"
            lower.contains("marathi") || lower == "mr" || lower == "mar" -> "Marathi"
            lower.contains("punjabi") || lower == "pa" || lower == "pan" -> "Punjabi"
            lower.contains("gujarati") || lower == "gu" || lower == "guj" -> "Gujarati"
            lower.contains("nepali") || lower == "ne" || lower == "nep" -> "Nepali"
            lower.contains("sinhala") || lower.contains("singhalese") || lower == "si" || lower == "sin" -> "Sinhala"
            lower.contains("thai") || lower == "th" || lower == "tha" -> "Thai"
            lower.contains("turkish") || lower == "tr" || lower == "tur" -> "Turkish"
            lower.contains("german") || lower == "de" || lower == "ger" || lower == "deu" -> "German"
            lower.contains("italian") || lower == "it" || lower == "ita" -> "Italian"
            lower.contains("hindi dubbed") || lower == "dubbed" -> "Hindi"
            else -> s.substringBefore('-').substringBefore(' ').ifBlank { "Subtitle" }
        }
    }
}
