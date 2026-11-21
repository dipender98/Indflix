package com.indstream

import java.util.concurrent.ConcurrentHashMap

object LinkNaming {

    private val WHITESPACE = Regex("""\s+""")

    private val RESOLUTION_TOKEN =
        Regex("""\s*(?:\d{3,4}\s*p(?:\d{2,3})?|[24]\s*k)\b""", RegexOption.IGNORE_CASE)

    private val QUALITY_ALIAS =
        Regex("""(?:\s*\b(?:FHD|UHD|QHD)\b)|(?:\s+\bHD\b(?=\s*$))""", RegexOption.IGNORE_CASE)

    private val MULTI_MARKER =
        Regex("""\b(?:multi|dual|both)(?:\s*-?\s*audio)?\b""", RegexOption.IGNORE_CASE)

    private data class LanguageSpec(
        val canonical: String,
        val native: String?,
        val country: String,
        val codes: List<String>,
        val aliases: List<String>,
    )

    private fun language(
        canonical: String,
        country: String,
        codes: String,
        names: String,
        native: String? = null,
    ): LanguageSpec {
        val aliases = (names.split('|') + listOfNotNull(native))
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
        return LanguageSpec(
            canonical = canonical,
            native = native,
            country = country,
            codes = codes.split(' ').filter { it.isNotBlank() },
            aliases = aliases,
        )
    }

    private val LANGUAGES = listOf(
        language("Hindi", "IN", "hi hin", "hindi|hindustani|devanagari|देवनागरी|हिंदी|हिन्दी", native = "हिन्दी"),
        language("Bengali", "BD", "bn ben bang", "bengali|bangla|বাংলা", native = "বাংলা"),
        language("Tamil", "IN", "ta tam", "tamil|தமிழ்", native = "தமிழ்"),
        language("Telugu", "IN", "te tel", "telugu|తెలుగు", native = "తెలుగు"),
        language("Malayalam", "IN", "ml mal", "malayalam|malyalam|മലയാളം", native = "മലയാളം"),
        language("Kannada", "IN", "kn kan", "kannada|kannad|ಕನ್ನಡ", native = "ಕನ್ನಡ"),
        language("Marathi", "IN", "mr mar", "marathi|मराठी", native = "मराठी"),
        language("Punjabi", "IN", "pa pan", "punjabi|panjabi|ਪੰਜਾਬੀ", native = "ਪੰਜਾਬੀ"),
        language("Gujarati", "IN", "gu guj", "gujarati|ગુજરાતી", native = "ગુજરાતી"),
        language("Urdu", "PK", "ur urd", "urdu|اردو|اُردُو", native = "اردو"),
        language("Nepali", "NP", "ne nep", "nepali|नेपाली", native = "नेपाली"),
        language("Sinhala", "LK", "si sin", "sinhala|singhalese|sinhalese|සිංහල", native = "සිංහල"),
        language("Assamese", "IN", "as asm", "assamese|asamiya|অসমীয়া"),
        language("Odia", "IN", "or ori", "odia|oriya|ଓଡ଼ିଆ"),
        language("Bhojpuri", "IN", "bho", "bhojpuri|भोजपुरी"),
        language("Sanskrit", "IN", "sa san", "sanskrit|संस्कृतम्|संस्कृत"),
        language("Kashmiri", "IN", "ks kas", "kashmiri|कॉशुर|کٲشُر"),
        language("Sindhi", "PK", "sd snd", "sindhi|सिंधी|سنڌي"),
        language("Konkani", "IN", "kok", "konkani|कोंकणी"),
        language("Manipuri", "IN", "mni", "manipuri|meitei|মৈতৈলোন্"),
        language("Dogri", "IN", "doi", "dogri|डोगरी"),
        language("Maithili", "IN", "mai", "maithili|मैथिली"),
        language("Tulu", "IN", "tcy", "tulu|ತುಳು"),
        language("English", "GB", "en eng", "english|inglés|ingles"),
        language("Arabic", "SA", "ar ara arb", "arabic|العربية|عربي|عرب"),
        language("Russian", "RU", "ru rus", "russian|русский"),
        language(
            "Chinese", "CN", "zh chi zho cmn yue wuu hsn cn",
            "chinese|mandarin|cantonese|中文|普通话|國語|粤语|粵語",
        ),
        language("Japanese", "JP", "ja jpn jp", "japanese|日本語|日本"),
        language("Korean", "KR", "ko kor kr", "korean|한국어|한국"),
        language("French", "FR", "fr fre fra", "french|français|francais"),
        language("Spanish", "ES", "es spa", "spanish|español|espanol|castellano|latino"),
        language("Portuguese", "PT", "pt por", "portuguese|português|portugues"),
        language("German", "DE", "de ger deu", "german|deutsch"),
        language("Italian", "IT", "it ita", "italian|italiano"),
        language("Dutch", "NL", "nl dut nld", "dutch|nederlands|flemish"),
        language("Polish", "PL", "pl pol", "polish|polski"),
        language("Turkish", "TR", "tr tur", "turkish|türkçe|turkce"),
        language("Thai", "TH", "th tha", "thai|ไทย"),
        language("Vietnamese", "VN", "vi vie", "vietnamese|tiếng việt|tieng viet"),
        language("Indonesian", "ID", "id ind", "indonesian|bahasa|indonesia"),
        language("Malaysian", "MY", "ms may msa zsm", "malaysian|malay|bahasa melayu|bahasa malaysia|melayu|malaysia"),
        language("Filipino", "PH", "fil tl tgl ph", "filipino|tagalog"),
        language("Swedish", "SE", "sv swe", "swedish|svenska"),
        language("Norwegian", "NO", "no nor nb nob nn nno", "norwegian|norsk"),
        language("Danish", "DK", "da dan", "danish|dansk"),
        language("Finnish", "FI", "fi fin", "finnish|suomi"),
        language("Czech", "CZ", "cs cze ces", "czech|čeština|cestina"),
        language("Slovak", "SK", "sk slo slk", "slovak|slovenčina|slovencina"),
        language("Hungarian", "HU", "hu hun", "hungarian|magyar"),
        language("Romanian", "RO", "ro rum ron", "romanian|română|romana"),
        language("Bulgarian", "BG", "bg bul", "bulgarian|български"),
        language("Greek", "GR", "el gre ell", "greek|ελληνικά|ellinika"),
        language("Hebrew", "IL", "he iw heb", "hebrew|עברית"),
        language("Persian", "IR", "fa per fas pes", "persian|farsi|فارسی"),
        language("Ukrainian", "UA", "uk ukr", "ukrainian|українська"),
        language("Serbian", "RS", "sr srp", "serbian|српски"),
        language("Croatian", "HR", "hr hrv", "croatian|hrvatski"),
        language("Slovenian", "SI", "sl slv", "slovenian|slovene|slovenščina"),
        language("Bosnian", "BA", "bs bos", "bosnian|bosanski"),
        language("Albanian", "AL", "sq alb sqi", "albanian|shqip"),
        language("Lithuanian", "LT", "lt lit", "lithuanian|lietuvių"),
        language("Latvian", "LV", "lv lav", "latvian|latviešu"),
        language("Estonian", "EE", "et est", "estonian|eesti"),
        language("Icelandic", "IS", "is ice isl", "icelandic|íslenska"),
        language("Irish", "IE", "ga gle iri", "irish|gaeilge"),
        language("Welsh", "GB", "cy wel cym", "welsh|cymraeg"),
        language("Georgian", "GE", "ka geo kat", "georgian|ქართული"),
        language("Armenian", "AM", "hy arm hye", "armenian|հայերեն"),
        language("Azerbaijani", "AZ", "az aze", "azerbaijani|azeri|azərbaycan"),
        language("Kazakh", "KZ", "kk kaz", "kazakh|қазақ"),
        language("Uzbek", "UZ", "uz uzb", "uzbek|o'zbek|ozbek"),
        language("Mongolian", "MN", "mn mon", "mongolian|монгол"),
        language("Khmer", "KH", "km khm", "khmer|ខ្មែរ"),
        language("Lao", "LA", "lo lao", "lao|ລາວ"),
        language("Burmese", "MM", "my bur mya", "burmese|myanmar|မြန်မာ"),
        language("Swahili", "KE", "sw swa", "swahili|kiswahili"),
        language("Afrikaans", "ZA", "af afr", "afrikaans"),
        language("Zulu", "ZA", "zu zul", "zulu|isizulu"),
        language("Amharic", "ET", "am amh", "amharic|አማርኛ"),
        language("Somali", "SO", "so som", "somali|soomaali"),
        language("Hausa", "NG", "ha hau", "hausa"),
        language("Yoruba", "NG", "yo yor", "yoruba"),
        language("Igbo", "NG", "ig ibo", "igbo"),
        language("Pashto", "AF", "ps pus", "pashto|پښتو"),
        language("Kurdish", "IQ", "ku kur", "kurdish|kurdî|kurdi"),
        language("Latin", "VA", "la lat", "latin|latina"),
        language("Catalan", "AD", "ca cat", "catalan|català|catala|valencian"),
        language("Basque", "ES", "eu baq eus", "basque|euskara"),
        language("Galician", "ES", "gl glg", "galician|galego"),
        language("Belarusian", "BY", "be bel", "belarusian|беларуская"),
        language("Macedonian", "MK", "mk mac mkd", "macedonian|македонски"),
        language("Maori", "NZ", "mi mao mri", "maori|māori"),
        language("Samoan", "WS", "sm smo", "samoan"),
        language("Tongan", "TO", "to ton", "tongan"),
    )

    private val LANGUAGE_NAMES: Set<String> = LANGUAGES.mapTo(LinkedHashSet()) { it.canonical }

    private val LANGUAGE_BY_NAME: Map<String, String> = buildMap {
        for (language in LANGUAGES) {
            put(language.canonical.lowercase(), language.canonical)
            for (alias in language.aliases) put(alias, language.canonical)
        }
    }

    private val LANGUAGE_BY_CODE: Map<String, String> = buildMap {
        for (language in LANGUAGES) {
            for (code in language.codes) put(code, language.canonical)
        }
    }

    private val LANGUAGE_TOKEN: Map<String, String> = LANGUAGE_BY_CODE + LANGUAGE_BY_NAME

    private val FLAG_BY_LANGUAGE: Map<String, String> = LANGUAGES
        .filter { it.country.isNotBlank() }
        .associate { it.canonical to flag(it.country) }

    private fun aliasPattern(alias: String): String =
        alias.split(Regex("""[\s._-]+"""))
            .filter { it.isNotBlank() }
            .joinToString("""[\s._-]+""") { Regex.escape(it) }

    private val PHRASE_ALIASES: List<Pair<Regex, String>> = LANGUAGES
        .flatMap { language -> language.aliases.filter { it.contains(' ') }.map { it to language.canonical } }
        .sortedByDescending { (alias, _) -> alias.length }
        .map { (alias, canonical) ->
            Regex(
                """(?<![\p{L}\p{M}\p{N}])${aliasPattern(alias)}(?![\p{L}\p{M}\p{N}])""",
                RegexOption.IGNORE_CASE,
            ) to canonical
        }

    private val LANGUAGE_TOKEN_SPLIT = Regex("""[^\p{L}\p{M}\p{N}'’]+""")
    private val WRITTEN_NUMBER_SPLIT = Regex("""(?<=\p{L})(?=\p{N})|(?<=\p{N})(?=\p{L})""")
    private val BCP47_TAG = Regex(
        """(?<![\p{L}\p{M}\p{N}])([a-z]{2,3})(?:[-_](?:[a-z]{2}|[a-z]{4}|[0-9]{3})(?:[-_][a-z0-9]{1,8})*)(?![\p{L}\p{M}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )

    private val LANGUAGE_MODIFIERS = setOf(
        "audio", "dub", "dubbed", "version", "track", "sub", "subs", "subtitle", "subtitles",
    )

    private val REGION_QUALIFIERS = setOf("Latin")

    private val TRAILING_LANGUAGE: Regex = run {
        val names = LANGUAGES
            .flatMap { it.aliases }
            .filter { alias -> alias.length >= 3 && alias.all { it.code < 128 } }
            .distinct()
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        Regex(
            """(?i)(?:[\s,+/&()\[\]{}-]*\b(?:$names|multi|dual)\b(?:\s*[(\[]?\s*(?:audio|dub|dubbed)\s*[)\]]?)?[\s)\]}]*)+$""",
        )
    }

    /**
     * Languages in the order they are declared. Free-form labels should pass
     * [includeCodes] as false: a two-letter word there is usually not a language.
     */
    fun languageNames(raw: String?, includeCodes: Boolean = true): List<String> {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return emptyList()
        var text = s.lowercase()
        for ((pattern, canonical) in PHRASE_ALIASES) {
            text = pattern.replace(text, " ${canonical.lowercase()} ")
        }
        // A regional tag names one language; its region subtag is not a second language.
        text = BCP47_TAG.replace(text, "$1")
        text = WRITTEN_NUMBER_SPLIT.replace(text, " ")
        val out = LinkedHashSet<String>()
        for (token in LANGUAGE_TOKEN_SPLIT.split(text)) {
            val value = token.trim { it.isWhitespace() || it == '\'' || it == '’' }
            if (value.length < 2 || value in LANGUAGE_MODIFIERS) continue
            val canonical = LANGUAGE_TOKEN[value] ?: continue
            if (!includeCodes && value in LANGUAGE_BY_CODE && value !in LANGUAGE_BY_NAME) continue
            out += canonical
        }
        return out.toList()
    }

    fun languageLabel(
        raw: String?,
        originalLang: String? = null,
        includeCodes: Boolean = true,
    ): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return originalLang?.let { languageLabel(it) }.orEmpty()
        if (s.equals("original", true) || s.equals("orig", true) || s.equals("org", true)) {
            return originalLang?.let { languageLabel(it) }.orEmpty().ifBlank { "Original" }
        }
        val names = languageNames(s, includeCodes)
        return when {
            names.size == 1 -> names[0]
            names.size > 1 -> names.joinToString(", ")
            MULTI_MARKER.containsMatchIn(s) -> "Multi"
            else -> ""
        }
    }

    fun languageNameOrNull(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return null
        return canonicalSubtitleName(s).takeIf { it in LANGUAGE_NAMES }
    }

    fun languageCode(raw: String?): String? {
        val names = languageNames(raw)
            .filterIndexed { index, name -> index == 0 || name !in REGION_QUALIFIERS }
            .distinct()
        if (names.size != 1) return null
        return LANGUAGES.firstOrNull { it.canonical == names[0] }?.codes?.firstOrNull()
    }

    fun richerLanguageLabel(primary: String?, fallback: String?): String {
        val p = languageNames(primary).size
        val f = languageNames(fallback).size
        return if (f > p) fallback.orEmpty().ifBlank { primary.orEmpty() }
        else primary.orEmpty().ifBlank { fallback.orEmpty() }
    }

    internal fun hasMultiMarker(text: String?): Boolean =
        text != null && MULTI_MARKER.containsMatchIn(text)

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

    fun languageFlag(tag: String?): String {
        if (tag.isNullOrBlank()) return ""
        return FLAG_BY_LANGUAGE[tag]
            ?: FLAG_BY_LANGUAGE[languageNameOrNull(tag).orEmpty()].orEmpty()
    }

    private fun flag(cc: String): String =
        cc.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")

    fun displayName(
        serverName: String,
        audioLabel: String? = "",
        subIndicator: String? = null,
        sizeLabel: String? = null,
        mediaLabel: String? = null,
        originalLang: String? = null,
    ): String {
        val rawName = serverName.trim().ifBlank { "Server" }
        val named = baseName(rawName, subIndicator)

        val names = displayLanguageNames(audioLabel, mediaLabel)
        val tagPart = languageTagPart(names, audioLabel, mediaLabel, originalLang)

        val tokens = renditionTokens(listOfNotNull(mediaLabel, rawName).joinToString(" "))
            .filterNot { containsToken(named, it) }
            .joinToString(" ")
        val sizePart = sizeLabel?.trim().orEmpty()
        return listOf(named, tokens, tagPart, sizePart)
            .filter { it.isNotBlank() }.joinToString(" ")
    }

    fun priorityName(
        serverName: String,
        audioLabel: String? = "",
        mediaLabel: String? = null,
        originalLang: String? = null,
    ): String {
        val base = baseName(serverName.trim().ifBlank { "Server" }, null)
        val named = stripRendition(base).ifBlank { base }

        val names = displayLanguageNames(audioLabel, mediaLabel)
        return listOf(named, languageTagPart(names, audioLabel, mediaLabel, originalLang))
            .filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun baseName(rawName: String, subIndicator: String?): String {
        val cleanName = stripQuality(rawName).ifBlank { "Server" }
        val composed = if (subIndicator.isNullOrBlank()) cleanName else "$cleanName ${subIndicator.trim()}".trim()
        return stripQuality(TRAILING_LANGUAGE.replace(stripQuality(composed), ""))
            .trim().ifBlank { "Server" }
    }

    private val REMUX_TOKEN = Regex("""\bREMUX\b""", RegexOption.IGNORE_CASE)
    private val SIZE_WORD = Regex("""\b\d+(?:\.\d+)?\s?(?:GB|MB)\b""", RegexOption.IGNORE_CASE)
    private val EMPTY_BRACKETS = Regex("""\[\s*]|\(\s*\)""")

    private fun stripRendition(text: String): String {
        var out = text
        for ((pattern, _) in ALL_TOKENS) out = pattern.replace(out, " ")
        out = REMUX_TOKEN.replace(out, " ")
        out = SIZE_WORD.replace(out, " ")
        out = EMPTY_BRACKETS.replace(out, " ")
        return WHITESPACE.replace(out, " ").trim()
    }

    private fun stripQuality(text: String): String =
        QUALITY_ALIAS.replace(RESOLUTION_TOKEN.replace(text, " "), " ")
            .replace(WHITESPACE, " ")
            .trim()

    private fun qualityFromText(text: String?): String {
        val s = text?.trim().orEmpty()
        if (s.isBlank()) return ""
        val numeric = RESOLUTION_TOKEN.find(s)?.value?.trim()
        if (!numeric.isNullOrBlank()) {
            return numeric.replace(WHITESPACE, "")
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
    ): List<String> {
        val audio = languageNames(audioLabel)
        val media = languageNames(mediaLabel, includeCodes = false)
        return when {
            // Prefer audio only when it is at least as rich as media; otherwise media may hold
            // more languages (e.g. addon reports 2 audio langs but 5 in the stream metadata).
            audio.size >= 2 && audio.size >= media.size -> audio
            media.size > audio.size -> media
            audio.isNotEmpty() -> audio
            media.isNotEmpty() && languageLabel(audioLabel).isBlank() -> media
            else -> emptyList()
        }
    }

    private const val MAX_DISPLAY_LANGUAGES = 3

    private fun orderedDisplayNames(names: List<String>, originalLang: String?): List<String> {
        val anchor = originalLang?.let { languageLabel(it) }?.takeIf { it in LANGUAGE_NAMES }
        val priority = listOfNotNull(anchor, "Hindi", "English")
        val out = ArrayList<String>(names.size)
        for (p in priority) if (p in names && p !in out) out += p
        for (n in names) if (n !in out) out += n
        return out
    }

    private fun displayTagAndNames(
        names: List<String>,
        audioLabel: String?,
        mediaLabel: String?,
        originalLang: String?,
    ): Pair<String, List<String>> = when {
        names.size > MAX_DISPLAY_LANGUAGES -> "Multi" to emptyList()
        names.isNotEmpty() -> {
            val shown = orderedDisplayNames(names, originalLang)
            shown.joinToString(", ") to shown
        }
        else -> {
            // "Original" resolves via originalLang; a blank label stays Unknown.
            val a = if (explicitOriginalMarker(audioLabel)) languageLabel(audioLabel, originalLang)
                else languageLabel(audioLabel)
            val b = if (explicitOriginalMarker(mediaLabel)) languageLabel(mediaLabel, originalLang)
                else languageLabel(mediaLabel, includeCodes = false)
            a.ifBlank { b }.ifBlank { "Unknown" } to emptyList()
        }
    }

    private fun explicitOriginalMarker(raw: String?): Boolean =
        raw?.trim()?.lowercase().let { it == "original" || it == "orig" || it == "org" }

    private fun languageTagPart(
        names: List<String>,
        audioLabel: String?,
        mediaLabel: String?,
        originalLang: String?,
    ): String {
        val (label, shown) = displayTagAndNames(names, audioLabel, mediaLabel, originalLang)
        val parts = if (shown.isNotEmpty()) shown
            else label.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size >= 2 && parts.none { it == "Multi" || it == "Unknown" || it == "Original" }) {
            return "(" + parts.map { p ->
                languageFlag(p).takeIf { it.isNotBlank() }?.let { "$it $p" } ?: p
            }.joinToString(", ") + ")"
        }
        val single = parts.singleOrNull()
        val flag = single?.let { languageFlag(it) }.orEmpty()
        return if (flag.isBlank()) "($label)" else "($flag $single)"
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

    private val ALL_TOKENS =
        SOURCE_TOKENS + VIDEO_TOKENS + VISION_TOKENS + DEPTH_TOKENS + AUDIO_TOKENS

    private val TOKEN_PATTERNS = ConcurrentHashMap<String, Regex>()

    private fun containsToken(text: String, token: String): Boolean =
        TOKEN_PATTERNS.getOrPut(token) {
            Regex("""(?<!\w)${Regex.escape(token)}(?!\w)""", RegexOption.IGNORE_CASE)
        }.containsMatchIn(text)

    fun renditionTokens(text: String?): List<String> {
        val s = text?.trim().orEmpty()
        if (s.isBlank()) return emptyList()
        val found = LinkedHashMap<String, String>()
        for ((re, canon) in ALL_TOKENS) {
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
        languageNames(s).firstOrNull()?.let { return it }
        if (s.equals("dubbed", true)) return "Hindi"
        return s
    }

    private val SUBTITLE_NATIVE_LANGUAGES = setOf(
        "Hindi", "Tamil", "Telugu", "Malayalam", "Kannada", "Marathi", "Bengali",
        "Punjabi", "Gujarati", "Urdu", "Nepali", "Sinhala",
    )

    val INDIAN_NATIVE: Map<String, String> = LANGUAGES.mapNotNull { language ->
        val native = language.native ?: return@mapNotNull null
        if (language.canonical !in SUBTITLE_NATIVE_LANGUAGES) null else language.canonical to native
    }.toMap()

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
