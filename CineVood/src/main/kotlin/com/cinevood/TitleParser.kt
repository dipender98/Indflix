package com.cinevood

/*
 * Pure title/label parser for CineVood post titles and download-group labels.
 * No network, no Android — unit-testable on the JVM.
 */

data class GroupInfo(
    val label: String,
    val url: String,
    val rawLabel: String,
    val languages: List<String>,
    val quality: Int,
    val season: Int?,
    val episodeFrom: Int?,
    val episodeTo: Int?,
    val sourceTag: String?,
    val sizeText: String?
)

data class ParsedTitle(
    val name: String,
    val year: Int?,
    val languages: List<String>,
    val sourceTag: String?,
    val qualities: List<Int>,
    val isSeries: Boolean,
    val seasons: List<Int>,
    val isAdult: Boolean,
    val isTrailer: Boolean
)

object TitleParser {

    private val YEAR_IN_PAREN = Regex("""\((19|20)\d{2}\)""")
    private val QUALITY_TOKEN = Regex("""(?i)\b(480|720|1080|1440|2160)\s*[pP]\b""")
    private val SEASON_PAREN = Regex("""(?i)\((?:season|s)\s*(\d{1,2})(?:\s*[-–]\s*(\d{1,2}))?\)""")
    private val SEASON_PLAIN = Regex("""(?i)\bseason\s*(\d{1,2})\b""")
    private val SERIES_HINT = Regex(
        """(?i)\b(web[\s-]?series|complete\s+(?:season|series)|k-?drama|anime|multi[\s-]?season|s\d{2}e\d{2})\b"""
    )
    private val ADULT_HINT = Regex(
        """(?i)(\[\s*18\s*\+|18\+|brazzers|moodx|vivamax|kooku|primeshots|erotic|hardcore|softcore|soft\s*core|porn|xxx|unrated.{0,12}(sex|bed|romance)?|onlyfans|leaked\s*girl|desi\s*girl|a-?films|bondage|nude|topless)"""
    )

    private val SOURCE_TAGS = listOf(
        "WEB-DL", "WEB DL", "WEBRIP", "BLURAY", "BLU-RAY", "BRRIP", "HDTCS", "HQ-HDTC", "HDTC",
        "HDTS", "CAMRIP", "PREDVD", "HDRIP", "DVDRIP", "TSRIP", "DS4K", "AMZN", "NF", "DL"
    )

    // map of language tokens (lower-case, normalized) to canonical names
    private val LANGS = linkedMapOf(
        "hindi" to "Hindi", "english" to "English", "eng" to "English",
        "tamil" to "Tamil", "telugu" to "Telugu", "kannada" to "Kannada",
        "malayalam" to "Malayalam", "bengali" to "Bengali", "punjabi" to "Punjabi",
        "marathi" to "Marathi", "gujarati" to "Gujarati", "korean" to "Korean",
        "chinese" to "Chinese", "japanese" to "Japanese", "italian" to "Italian",
        "spanish" to "Spanish", "french" to "French", "german" to "German",
        "turkish" to "Turkish", "russian" to "Russian", "tha" to "Thai",
        "tagalog" to "Tagalog", "vietnamese" to "Vietnamese", "indonesian" to "Indonesian",
        "tel" to "Telugu", "ta" to "Tamil", "kn" to "Kannada", "ml" to "Malayalam",
        "ben" to "Bengali", "guj" to "Gujarati", "mar" to "Marathi", "pan" to "Punjabi"
    )

    private val YEAR_STOP = Regex("""\b(19|20)\d{2}\b""")

    /** Cut the pretty display name out of a raw post title. */
    fun displayName(rawTitle: String): String {
        var t = rawTitle.trim()
        if (t.startsWith("`")) t = t.substring(1)
        t = t.replace(Regex("""(?i)^download[*\s-]*"""), "").trim()
        // name ends at the first year, season/episode, quality or audio marker
        val cutAt = listOf(
            YEAR_STOP.find(t)?.range?.first ?: Int.MAX_VALUE,
            SEASON_PAREN.find(t)?.range?.first ?: Int.MAX_VALUE,
            Regex("""(?i)\b(?:season\s*\d+|s\d{2}\b|episode\b|\[episode)""").find(t)?.range?.first
                ?: Int.MAX_VALUE,
            QUALITY_TOKEN.find(t)?.range?.first ?: Int.MAX_VALUE,
            Regex("""(?i)\b(dual|multi)[\s-]*audio\b""").find(t)?.range?.first ?: Int.MAX_VALUE
        ).min()
        if (cutAt < t.length) t = t.substring(0, cutAt)
        t = t.replace(Regex("""(?i)[\s|–-]*(full\s+movie|complete.*|hindi\s+movie)\s*$"""), "")
        return t.replace(Regex("""[\s\-–|,:*(\[{]+$"""), "").replace("`", "").trim()
    }

    fun parse(rawTitle: String): ParsedTitle {
        val t = rawTitle.trim()
        val name = displayName(t)
        val year = YEAR_IN_PAREN.find(t)?.value?.filter { it.isDigit() }?.toIntOrNull()
            ?: YEAR_STOP.find(t)?.value?.toIntOrNull()

        val langs = extractLanguages(t)
        val qualities = QUALITY_TOKEN.findAll(t).mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct().sortedDescending().toList()
        val source = SOURCE_TAGS.firstOrNull { tag ->
            Regex("""(?i)${Regex.escape(tag)}""").containsMatchIn(t)
        }?.replace(" ", "-")

        val seasons = mutableListOf<Int>()
        Regex("""(?i)season\s*(\d{1,2})\s*[-–]\s*(\d{1,2})""").findAll(t).forEach { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            if (a in 1..30 && b in a..(a + 30)) for (s in a..b) seasons.add(s)
        }
        Regex("""(?i)(?:season|\bs)\s*(\d{1,2})\b""").findAll(t).forEach { m ->
            m.groupValues[1].toIntOrNull()?.let { if (it in 1..30) seasons.add(it) }
        }

        val isSeries = Regex("""(?i)(\bseries\b|web.?series|k-?drama|season|complete\s+pack|\bep[\s.-]?\d|\bs\d{2}e)""")
            .containsMatchIn(t) && !Regex("""(?i)series\s*of\s*film""").containsMatchIn(t)

        return ParsedTitle(
            name = name,
            year = year,
            languages = langs,
            sourceTag = source,
            qualities = qualities,
            isSeries = isSeries,
            seasons = seasons.distinct(),
            isAdult = ADULT_HINT.containsMatchIn(t),
            isTrailer = Regex("""(?i)\btrailer\b""").containsMatchIn(t)
        )
    }

    /** Collect every canonical language mentioned anywhere in the text. */
    fun extractLanguages(text: String): List<String> {
        val found = LinkedHashSet<String>()
        for ((token, canonical) in LANGS) {
            if (Regex("""(?i)(?<![a-z])${Regex.escape(token)}(?![a-z])""").containsMatchIn(text)) {
                found.add(canonical)
            }
        }
        return found.toList()
    }

    /**
     * Parse one `.mfx-quality-title` label, e.g.:
     *   "Toxic: A Fairytale for Grown-Ups (2026) Hindi V2-HDTC 720p 10Bit x265 [990MB]"
     *   "Chumbak S01 [Episode 01-08] Complete Hindi WEB-DL 480p x264 [460MB]"
     *   "Season 4 {Hindi-English} 720p WEB-DL x264 [390MB/E]"
     */
    fun parseGroup(rawLabel: String, gateUrl: String): GroupInfo? {
        val label = rawLabel.replace("\u00A0", " ").trim()
        if (label.isEmpty() || gateUrl.isBlank()) return null
        val quality = QUALITY_TOKEN.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val season = Regex("""(?i)(?:season|\bs)\s*(\d{1,2})\b""").find(label)?.groupValues?.get(1)?.toIntOrNull()
        val epRange = Regex("""(?i)episode\s*(?:no\.?\s*)?(\d{1,2})\s*(?:[-–]\s*(\d{1,2}))?""").find(label)
        return GroupInfo(
            label = label,
            url = gateUrl,
            rawLabel = label,
            languages = extractLanguages(label),
            quality = quality,
            season = season?.takeIf { it in 1..30 },
            episodeFrom = epRange?.groupValues?.get(1)?.toIntOrNull(),
            episodeTo = epRange?.groupValues?.get(2)?.toIntOrNull(),
            sourceTag = SOURCE_TAGS.firstOrNull { it.containsMatchInSafe(label) },
            sizeText = Regex("""\[([\d.,]+\s*[GMgB][\w/]*)]""").find(label)?.groupValues?.get(1)
        )
    }

    private fun String.containsMatchInSafe(other: String): Boolean =
        Regex("""(?i)${Regex.escape(this)}""").containsMatchIn(other)

    /** A compact audio label for emitted links, e.g. "Hindi+Multi" / "Hindi-English" */
    fun audioLabel(langs: List<String>): String = when {
        langs.isEmpty() -> ""
        langs.size > 2 -> "Multi-Audio"
        else -> langs.joinToString("+")
    }
}
