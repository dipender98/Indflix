package com.indstream

import com.indstream.StreamEngine.RawStream

object LinkNaming {

    private val RESOLUTION_TOKEN =
        Regex("""\s*(?:\d{3,4}\s*p(?:\d{2,3})?|[24]\s*k)\b""", RegexOption.IGNORE_CASE)

    private val QUALITY_ALIAS =
        Regex("""(?:\s*\b(?:FHD|UHD|QHD)\b)|(?:\s+\bHD\b(?=\s*$))""", RegexOption.IGNORE_CASE)

    private val MULTI_MARKER =
        Regex("""\b(?:multi|dual|both)(?:\s*-?\s*audio)?\b""", RegexOption.IGNORE_CASE)

    private val LANGUAGE_NAMES = setOf(
        "Hindi", "Urdu", "Bengali", "Arabic", "Russian", "Chinese", "Japanese", "Korean",
        "French", "Spanish", "Portuguese", "Filipino", "Indonesian", "Malaysian", "Vietnamese",
        "English", "Tamil", "Telugu", "Malayalam", "Kannada", "Marathi", "Punjabi", "Gujarati",
        "Nepali", "Sinhala", "Thai", "Turkish", "German", "Italian", "Dutch", "Polish",
    )

    private val LANGUAGE_SPLIT =
        Regex("""[+,&/|.\-_]|\band\b|\bdual\b|\bmulti\b|\s+""", RegexOption.IGNORE_CASE)

    private val LANGUAGE_MODIFIERS = setOf(
        "audio", "dub", "dubbed", "version", "track", "sub", "subs", "subtitle", "subtitles",
    )

    private val TRAILING_LANGUAGE = Regex(
        """(?i)(?:[\s,+/&()\[\]{}-]*\b(?:hindi|english|tamil|telugu|malayalam|kannada|bengali|bangla|marathi|punjabi|gujarati|urdu|nepali|sinhala|arabic|russian|chinese|mandarin|japanese|korean|french|spanish|portuguese|filipino|tagalog|indonesian|malaysian|vietnamese|thai|turkish|german|italian|dutch|polish|multi|dual)\b(?:\s*[(\[]?\s*(?:audio|dub|dubbed)\s*[)\]]?)?[\s)\]}]*)+$""",
    )

    fun languageNames(raw: String?): List<String> {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        for (chunk in s.split(LANGUAGE_SPLIT)) {
            val c = chunk.trim { it.isWhitespace() || it in "-.:;()[]{}" }
            if (c.length < 2 || c.lowercase() in LANGUAGE_MODIFIERS) continue
            val canonical = canonicalSubtitleName(c)
            if (canonical in LANGUAGE_NAMES) out += canonical
        }
        return out.toList()
    }

    fun languageLabel(raw: String?, originalLang: String? = null): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return originalLang?.let { languageLabel(it) }.orEmpty()
        if (s.equals("original", true) || s.equals("orig", true) || s.equals("org", true)) {
            return originalLang?.let { languageLabel(it) }.orEmpty().ifBlank { "Original" }
        }
        val names = languageNames(s)
        return when {
            names.size == 1 -> names[0]
            names.size == 2 -> "${names[0]}, ${names[1]}"
            names.size > 2 -> "Multi"
            MULTI_MARKER.containsMatchIn(s) -> "Multi"
            else -> ""
        }
    }

    fun languageTag(raw: String?): String {
        if (raw.isNullOrBlank()) return "Unknown"
        val label = languageLabel(raw).ifBlank { return "Unknown" }
        return if (label == "Multi" || languageNames(raw).size > 1) "Multi" else label
    }

    fun languageTagFor(raw: String?, originalLang: String? = null): String {
        val s = raw?.trim()?.lowercase().orEmpty()
        if (s.isEmpty()) return "Unknown"
        if (s == "original" || s == "orig" || s == "org") {
            return originalLang?.let { languageTag(it) } ?: "Original"
        }
        return languageTag(s)
    }

    fun languageFlag(tag: String?): String = when (tag) {
        "Hindi", "Tamil", "Telugu", "Malayalam", "Kannada", "Marathi", "Punjabi", "Gujarati" -> flag("IN")
        "English" -> flag("GB")
        "Urdu" -> flag("PK")
        "Bengali" -> flag("BD")
        "Arabic" -> flag("SA")
        "Russian" -> flag("RU")
        "Chinese" -> flag("CN")
        "Japanese" -> flag("JP")
        "Korean" -> flag("KR")
        "French" -> flag("FR")
        "Spanish" -> flag("ES")
        "Portuguese" -> flag("PT")
        "Filipino" -> flag("PH")
        "Indonesian" -> flag("ID")
        "Malaysian" -> flag("MY")
        "Vietnamese" -> flag("VN")
        "Nepali" -> flag("NP")
        "Sinhala" -> flag("LK")
        "Thai" -> flag("TH")
        "Turkish" -> flag("TR")
        "German" -> flag("DE")
        "Italian" -> flag("IT")
        "Dutch" -> flag("NL")
        "Polish" -> flag("PL")
        else -> ""
    }

    private fun flag(cc: String): String =
        cc.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")

    fun hasResolution(indicator: String?): Boolean =
        !indicator.isNullOrBlank() &&
            Regex("""\b(\d{3,4})p\b""", RegexOption.IGNORE_CASE).containsMatchIn(indicator)

    fun displayName(
        serverName: String,
        audioLabel: String? = "",
        subIndicator: String? = null,
        duplicateIndex: Int = 0,
        originalLang: String? = null,
        sizeLabel: String? = null,
        mediaLabel: String? = null,
    ): String {
        val rawName = serverName.trim().ifBlank { "Server" }
        val cleanName = stripQuality(rawName).ifBlank { "Server" }
        val composed = if (subIndicator.isNullOrBlank()) cleanName else "$cleanName ${subIndicator.trim()}".trim()
        val named = stripQuality(TRAILING_LANGUAGE.replace(stripQuality(composed), ""))
            .trim().ifBlank { "Server" }
        val numbered = if (duplicateIndex > 0) "$named-$duplicateIndex" else named

        val names = displayLanguageNames(audioLabel, mediaLabel, cleanName)
        val tag = displayLanguageTag(names, audioLabel, mediaLabel, originalLang, cleanName)
        val tagPart = languageTagPart(tag, names)

        val tokens = renditionTokens(listOfNotNull(mediaLabel, rawName).joinToString(" "))
            .filterNot {
                Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(named)
            }
            .joinToString(" ")
        val sizePart = sizeLabel?.trim().orEmpty()
        return listOf(numbered, tokens, tagPart, sizePart)
            .filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun stripQuality(text: String): String =
        QUALITY_ALIAS.replace(RESOLUTION_TOKEN.replace(text, " "), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun qualityFromText(text: String?): String {
        val s = text?.trim().orEmpty()
        if (s.isBlank()) return ""
        val numeric = RESOLUTION_TOKEN.find(s)?.value?.trim()
        if (!numeric.isNullOrBlank()) {
            return numeric.replace(Regex("""\s+"""), "")
                .lowercase()
                .replace("4k", "4K")
                .replace("2k", "2K")
        }
        val alias = QUALITY_ALIAS.find(s)?.value?.trim()?.uppercase()
        return when (alias) {
            "UHD" -> "4K"
            "QHD" -> "1440p"
            "FHD" -> "1080p"
            "HD" -> "HD"
            else -> ""
        }
    }

    // Numeric height for the link's quality value; the player renders the quality label itself.
    fun qualityHeight(text: String?, fallback: String? = null): Int {
        val label = qualityFromText(text).ifBlank { qualityFromText(fallback) }
        if (label.isBlank()) return 0
        return when (label) {
            "4K" -> 2160
            "2K" -> 1440
            "HD" -> 0
            else -> label.removeSuffix("p").toIntOrNull() ?: 0
        }
    }

    private fun displayLanguageNames(
        audioLabel: String?,
        mediaLabel: String?,
        serverName: String,
    ): List<String> {
        val audio = languageNames(audioLabel)
        val media = languageNames(mediaLabel)
        return when {
            audio.size >= 2 -> audio
            media.size > audio.size -> media
            audio.isNotEmpty() -> audio
            media.isNotEmpty() -> media
            languageLabel(audioLabel).isNotBlank() || languageLabel(mediaLabel).isNotBlank() -> emptyList()
            else -> trailingLanguageNames(serverName)
        }
    }

    private fun displayLanguageTag(
        names: List<String>,
        audioLabel: String?,
        mediaLabel: String?,
        originalLang: String?,
        serverName: String,
    ): String = when {
        names.size == 1 -> names[0]
        names.size == 2 -> "${names[0]}, ${names[1]}"
        names.size > 2 -> "Multi"
        else -> languageLabel(audioLabel, originalLang)
            .ifBlank { languageLabel(mediaLabel, originalLang) }
            .ifBlank { trailingLanguageLabel(serverName, originalLang) }
            .ifBlank { "Unknown" }
    }

    private fun trailingLanguageNames(serverName: String): List<String> {
        val tail = TRAILING_LANGUAGE.find(serverName)?.value ?: return emptyList()
        return languageNames(tail)
    }

    private fun trailingLanguageLabel(serverName: String, originalLang: String?): String {
        val tail = TRAILING_LANGUAGE.find(serverName)?.value ?: return ""
        return languageLabel(tail, originalLang)
    }

    private fun languageTagPart(label: String, names: List<String>): String {
        val parts = if (names.isNotEmpty()) names else label.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size == 2 && parts.none { it == "Multi" || it == "Unknown" || it == "Original" }) {
            val first = languageFlag(parts[0])
            val second = languageFlag(parts[1])
            if (first.isNotBlank() || second.isNotBlank()) {
                val a = if (first.isBlank()) parts[0] else "$first ${parts[0]}"
                val b = if (second.isBlank()) parts[1] else "$second ${parts[1]}"
                return "($a, $b)"
            }
        }
        val single = parts.singleOrNull()
        val flag = single?.let { languageFlag(it) }.orEmpty()
        return if (flag.isBlank()) "($label)" else "($flag $single)"
    }

    fun dedupeNames(streams: List<RawStream>, originalLang: String? = null): List<Int> {
        fun keyOf(s: RawStream): String = s.serverId.trim().lowercase()
        val totals = HashMap<String, Int>()
        for (s in streams) totals.merge(keyOf(s), 1) { a, b -> a + b }
        val running = HashMap<String, Int>()
        return streams.map { s ->
            val key = keyOf(s)
            if ((totals[key] ?: 0) <= 1) {
                0
            } else {
                val n = running[key] ?: 0
                running[key] = n + 1
                n
            }
        }
    }

    private val SOURCE_TOKENS = listOf(
        Regex("""\bWEB[ \-]?DL\b""", RegexOption.IGNORE_CASE) to "WEB-DL",
        Regex("""\bWEB[ \-]?RIP\b""", RegexOption.IGNORE_CASE) to "WEBRip",
        Regex("""\bBLU[ \-]?RAY\b""", RegexOption.IGNORE_CASE) to "BluRay",
        Regex("""\bBR[ \-]?RIP\b""", RegexOption.IGNORE_CASE) to "BRRip",
        Regex("""\bHD[ \-]?RIP\b""", RegexOption.IGNORE_CASE) to "HDRip",
        Regex("""\bDVD[ \-]?RIP\b""", RegexOption.IGNORE_CASE) to "DVDRip",
        Regex("""\bHDTV\b""", RegexOption.IGNORE_CASE) to "HDTV",
        Regex("""\b(?:HD[ \-]?T[SCS]|HD[ \-]?CAM|CAM[ \-]?RIP|CAM|HDM)\b""", RegexOption.IGNORE_CASE) to "CAMRip",
    )

    private val AUDIO_TOKENS = listOf(
        Regex("""\bDOLBY[ \-]?ATMOS\b|\bATMOS\b""", RegexOption.IGNORE_CASE) to "Dolby Atmos",
        Regex("""\bTRUEHD\b""", RegexOption.IGNORE_CASE) to "TrueHD",
        Regex("""\b(?:DOLBY[ \-]*(?:DIGITAL)?[ \-]*|DDP?[ \-]*|E[ \-]?AC[ \-]?3[ \-]*|AC[ \-]?3)[5]\.1\b""", RegexOption.IGNORE_CASE) to "Dolby 5.1",
        Regex("""\b(?:DOLBY[ \-]*(?:DIGITAL)?[ \-]*|DDP?[ \-]*|E[ \-]?AC[ \-]?3[ \-]*|AC[ \-]?3)[7]\.1\b""", RegexOption.IGNORE_CASE) to "Dolby 7.1",
        Regex("""\b(?:DDP|DD[ \-]?\+|E[ \-]?AC[ \-]?3|AC[ \-]?3|DD)\b""", RegexOption.IGNORE_CASE) to "Dolby Digital",
        Regex("""\bDOLBY\b""", RegexOption.IGNORE_CASE) to "Dolby Digital",
        Regex("""\bDTS[ \-]?H[DI]\b""", RegexOption.IGNORE_CASE) to "DTS-HD",
        Regex("""\bDTS\b""", RegexOption.IGNORE_CASE) to "DTS",
        Regex("""\bAAC\b""", RegexOption.IGNORE_CASE) to "AAC",
    )

    private val VISION_TOKENS = listOf(
        Regex("""\bDOLBY[ \-]?VISION\b""", RegexOption.IGNORE_CASE) to "Dolby Vision",
        Regex("""\bDV\b""", RegexOption.IGNORE_CASE) to "Dolby Vision",
        Regex("""\bHDR10\+(?!\d)""", RegexOption.IGNORE_CASE) to "HDR10+",
        Regex("""\bHDR10\b""", RegexOption.IGNORE_CASE) to "HDR10",
        Regex("""\bHDR\b""", RegexOption.IGNORE_CASE) to "HDR",
    )

    private val VIDEO_TOKENS = listOf(
        Regex("""\bHEVC\b|\bH\.?[ \-]?265\b|\bX\.?265\b""", RegexOption.IGNORE_CASE) to "HEVC",
        Regex("""\bH\.?[ \-]?264\b|\bX\.?264\b|\bAVC\b|\bMPEG[ \-]?4\b""", RegexOption.IGNORE_CASE) to "H.264",
        Regex("""\bAV1\b""", RegexOption.IGNORE_CASE) to "AV1",
    )

    private val DEPTH_TOKENS = listOf(
        Regex("""\b10[ \-]?BIT\b""", RegexOption.IGNORE_CASE) to "10bit",
        Regex("""\b8[ \-]?BIT\b""", RegexOption.IGNORE_CASE) to "8bit",
    )

    fun renditionTokens(text: String?): List<String> {
        val s = text?.trim().orEmpty()
        if (s.isBlank()) return emptyList()
        val found = LinkedHashMap<String, String>()
        for ((re, canon) in SOURCE_TOKENS + VIDEO_TOKENS + VISION_TOKENS + DEPTH_TOKENS + AUDIO_TOKENS) {
            if (re.containsMatchIn(s) && !found.containsKey(canon.lowercase())) {
                found[canon.lowercase()] = canon
            }
        }
        if (found.containsKey("dolby 5.1") || found.containsKey("dolby 7.1") || found.containsKey("dolby atmos")) {
            found.remove("dolby digital")
        }
        if (found.containsKey("dts-hd")) found.remove("dts")
        if (found.containsKey("hdr10+")) found.remove("hdr10")
        if (found.containsKey("hdr10+") || found.containsKey("hdr10")) found.remove("hdr")
        return found.values.toList()
    }

    fun taggedSubtitleName(canonical: String, source: String?): String {
        val c = canonical.trim()
        val src = source?.trim().orEmpty()
        if (src.isEmpty() || c.isEmpty()) return c
        if (c.endsWith("($src)", ignoreCase = true)) return c
        return "$c ($src)"
    }

    fun canonicalSubtitleName(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return "Subtitle"
        val lower = s.lowercase()
        return when {
            lower.contains("हिन्द") || lower.contains("हिंद") || lower.contains("देवनागरी") || lower.contains("devanagari") || lower == "hi" || lower == "hin" || lower.contains("hindi") -> "Hindi"
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
            lower.contains("malaysian") || lower == "ms" || lower == "may" || lower == "msa" -> "Malaysian"
            lower.contains("vietnamese") || lower == "vi" || lower == "vie" -> "Vietnamese"
            lower.contains("english") || lower == "en" || lower == "eng" -> "English"
            lower.contains("தமழ") || lower.contains("tamil") || lower == "ta" || lower == "tam" -> "Tamil"
            lower.contains("తెలుగు") || lower.contains("telugu") || lower == "te" || lower == "tel" -> "Telugu"
            lower.contains("മലയാള") || lower.contains("malayalam") || lower == "ml" || lower == "mal" -> "Malayalam"
            lower.contains("ಕನ್ನಡ") || lower.contains("kannada") || lower == "kn" || lower == "kan" -> "Kannada"
            lower.contains("मराझ") || lower.contains("marathi") || lower == "mr" || lower == "mar" -> "Marathi"
            lower.contains("ਪੰਜਾਬ") || lower.contains("punjabi") || lower == "pa" || lower == "pan" -> "Punjabi"
            lower.contains("ગુજરાત") || lower.contains("gujarati") || lower == "gu" || lower == "guj" -> "Gujarati"
            lower.contains("नेपाल") || lower.contains("nepali") || lower == "ne" || lower == "nep" -> "Nepali"
            lower.contains("සිංහල") || lower.contains("sinhala") || lower.contains("singhalese") || lower == "si" || lower == "sin" -> "Sinhala"
            lower.contains("ไทย") || lower.contains("thai") || lower == "th" || lower == "tha" -> "Thai"
            lower.contains("turkish") || lower == "tr" || lower == "tur" -> "Turkish"
            lower.contains("german") || lower == "de" || lower == "ger" || lower == "deu" -> "German"
            lower.contains("italian") || lower == "it" || lower == "ita" -> "Italian"
            lower.contains("dutch") || lower == "nl" || lower == "dut" || lower == "nld" -> "Dutch"
            lower.contains("polish") || lower == "pl" || lower == "pol" -> "Polish"
            lower.contains("hindi dubbed") || lower == "dubbed" -> "Hindi"
            else -> s.substringBefore('-').substringBefore(' ').ifBlank { "Subtitle" }
        }
    }

    val INDIAN_NATIVE = mapOf(
        "Hindi" to "\u0939\u093F\u0928\u094D\u0926\u0940",
        "Tamil" to "\u0BA4\u0BAE\u0BB4\u0BCD",
        "Telugu" to "\u0C24\u0C46\u0C32\u0C41\u0C17\u0C41",
        "Malayalam" to "\u0D2E\u0D32\u0D2F\u0D3E\u0D33\u0D02",
        "Kannada" to "\u0C95\u0CA8\u0CCD\u0CA1",
        "Marathi" to "\u092E\u0930\u093E\u091F\u0940",
        "Bengali" to "\u09AC\u09BE\u0982\u09B2\u09BE",
        "Punjabi" to "\u0A2A\u0A70\u0A1C\u0A3E\u0A2C\u0A40",
        "Gujarati" to "\u0A97\u0AC1\u0A9C\u0AB0\u0ABE\u0AA4\u0AC0",
        "Urdu" to "\u0627\u0631\u062F\u0648",
        "Nepali" to "\u0928\u0947\u092A\u093E\u0932\u0940",
        "Sinhala" to "\u0DC3\u0DD2\u0D82\u0DC4\u0DBD",
    )

    fun subtitleMenuName(canon: String, raw: String?): String {
        val lower = raw?.lowercase().orEmpty()
        if (canon == "Hindi") {
            if (lower.contains("hinglish") || lower.contains("roman") || lower.contains("latin") ||
                lower.contains("latn") || lower.contains("english word")) return "Hindi (Hinglish)"
            return "Hindi (\u0939\u093F\u0928\u094D\u0926\u0940)"
        }
        val native = INDIAN_NATIVE[canon] ?: return canon
        return "$canon ($native)"
    }
}
