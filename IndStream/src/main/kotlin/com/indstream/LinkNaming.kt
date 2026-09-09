package com.indstream

import com.indstream.StreamEngine.RawStream

/**
 * FILE: LinkNaming.kt — the ONE naming rule every emitted link must follow
 * (user spec, Sept 2026, revised):
 *
 *   `{ServerName[-n]} ({Language})`
 *
 *  - {ServerName}  base server name. When several links share the exact same
 *                  (name, language, resolution), ALL members are numbered
 *                  right after the server name with "-n": 2 servers render as
 *                  "VidLink-1 (Hindi)" / "VidLink-2 (Hindi)", 5 servers
 *                  number "-1"…"-5", 20 number "-1"…"-20" — to the last one.
 *                  A unique server keeps its plain name (no "-1").
 *  - ({Language})  always present, in brackets. Full display names with a
 *                  capital initial: Hindi, English, Multi, Japanese, …  — see
 *                  [languageTag]. A stream that carries several languages at
 *                  once (dual-audio master) is "Multi".
 *  - {Resolution}  user spec Sept 2026 (second revision): resolution is NOT
 *                  printed in the server name at all — the CloudStream player
 *                  shows each link's quality badge itself (from
 *                  ExtractorLink.quality), so naming it here rendered it
 *                  TWICE on many servers (e.g. "VidLink (Hindi) 1080p  1080p").
 *                  Any token already inside the server name or sub-indicator
 *                  ("Server 1080p", "Server1080p", "2160p", "4K") is STRIPPED,
 *                  leaving the badge as the single source of resolution.
 *                  [qualityLabel] remains for callers that need the token.
 */
object LinkNaming {

    /** Resolution display token ("\d{3,4}p" / "4K"), glued forms included:
     *  deliberately NO leading \b so "Server1080p"/"x1080p" are caught, and a
     *  trailing \b so the "-N" duplicate suffix ("VidLink 1080p-2" → the
     *  token ends at "p", the "-2" survives) and digit runs are never eaten. */
    private val RESOLUTION_TOKEN =
        Regex("""\s*(?:\d{3,4}p|4K)\b""", RegexOption.IGNORE_CASE)

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
        // Truly unknown audio (the server gave no language signal at all) is labelled
        // "Unknown" — never guessed as "Multi"/"Original"/"English" (user spec Sept 2026:
        // if we don't know the audio, say so instead of mismatching it).
        if (raw.isNullOrBlank()) return "Unknown"
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
     * language is [originalLang] (TMDB code, e.g. "ja"). An EXPLICIT "Original"
     * label from a resolver maps to the title's actual language; a BLANK label
     * is a genuine unknown and stays "Unknown" (user spec Sept 2026: a blank
     * stream of a Telugu title may be a Hindi dub — never guess from TMDB).
     */
    fun languageTagFor(raw: String?, originalLang: String? = null): String {
        val s = raw?.trim()?.lowercase().orEmpty()
        if (s.isEmpty()) return "Unknown"
        if (s == "original" || s == "orig" || s == "org") {
            return originalLang?.let { languageTag(it) } ?: "Original"
        }
        return languageTag(s)
    }

    /**
     * True when [indicator] (a sub-server label like "Server 1080p",
     * "Fade 1080p", "Nova") carries a resolution token. Kept as a query
     * helper — [displayName] strips these tokens from the server name and
     * appends nothing (CloudStream's own badge is the only resolution print).
     */
    fun hasResolution(indicator: String?): Boolean =
        !indicator.isNullOrBlank() &&
            Regex("""\b(\d{3,4})p\b""", RegexOption.IGNORE_CASE).containsMatchIn(indicator)

    /**
     * Build the final display name: `{Server[-n]} ({Language})`. No resolution
     * token is printed — CloudStream renders the link's quality badge itself
     * (user spec Sept 2026, second revision).
     *
     * @param serverName   base server/brand, e.g. "VidLink", "PrimeSrc Nova".
     * @param audioLabel   raw audio label from the resolver ("Hindi", "hi",
     *                     "Original", "हिन्दी", "").
     * @param qualityHint  accepted for call-site compatibility (dedupe
     *                     grouping); intentionally NOT rendered in the name.
     * @param subIndicator optional sub-server label that is part of the base
     *                     name; resolution tokens in it are stripped like the
     *                     base name's.
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

        // user spec Sept 2026 (second revision): the server name carries NO
        // resolution token — CloudStream shows each link's quality badge itself
        // (ExtractorLink.quality), so printing it here duplicated it on many
        // servers. Every RESOLUTION_TOKEN inside the server part (folded
        // sub-indicator included; the "-N" duplicate suffix survives because a
        // token must end in p/K) is stripped and NOTHING is appended. With a
        // guessed token stripped, a name like "Server1080p" renders "Server"
        // and the player's badge is the single source of resolution.
        val serverDisplay = RESOLUTION_TOKEN.replace(serverPart, "").trim()
        val tagDisplay = if (tagPart.isBlank()) "" else RESOLUTION_TOKEN.replace(tagPart, "").trim()
        val parts = listOf(serverDisplay, tagDisplay).filter { it.isNotBlank() }
        return parts.joinToString(" ")
    }

    /** Same as [displayName] but for a compact "brand (lang) res" string when
     *  the caller has already folded the sub-server into the base name. */
    fun displayName(base: String, audioLabel: String?, qualityHint: Int): String =
        displayName(base, audioLabel, qualityHint, subIndicator = null, duplicateIndex = 0)

    /** Height → display token ("4K", "1080p", "720p"). Mirrors ManifestKit.
     *  [height] < 0 is the sentinel for a DIRECT file whose real resolution could not
     *  be measured (renders as "Auto"); 0 stays blank (adaptive HLS). */
    fun qualityLabel(height: Int): String = when {
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height >= 480 -> "480p"
        height >= 360 -> "360p"
        height > 0 -> "${height}p"
        height < 0 -> "Auto"
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

    /**
     * Subtitle provenance tag (user spec Sept 2026, per-server subs): the
     * player's subtitle menu is a single global list, so every track carries
     * its source — "Hindi (VidLink)" for server-owned tracks, "Hindi
     * (Fallback)" for OpenSubtitles top-ups. A blank/unknown source returns
     * the canonical name unchanged. When the source token is already inside
     * the canonical name it is not duplicated.
     */
    fun taggedSubtitleName(canonical: String, source: String?): String {
        val c = canonical.trim()
        val src = source?.trim().orEmpty()
        if (src.isEmpty() || c.isEmpty()) return c
        // Don't double-tag ("Hindi (VidLink)" + "VidLink" stays as-is).
        if (c.endsWith("($src)", ignoreCase = true)) return c
        return "$c ($src)"
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
