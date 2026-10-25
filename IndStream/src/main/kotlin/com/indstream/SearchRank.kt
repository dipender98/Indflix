package com.indstream

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min

object SearchRank {

    private val NON_ALNUM = Regex("""[^\p{L}\p{M}\p{N}]+""")
    private val LATIN_MARKS = Regex("""[\u0300-\u036F\u1AB0-\u1AFF\u1DC0-\u1DFF\u20D0-\u20F0\uFE20-\uFE2F]""")
    private val YEAR_TOKEN = Regex("""^[12]\d{3}$""")
    private val YEAR_IN_TEXT = Regex("""\b[12]\d{3}\b""")
    private val REPEATED_LETTER = Regex("""(\p{L})\1+""")
    private val WHITESPACE = Regex("""\s+""")

    internal val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "of", "for", "in", "on", "at", "to", "with",
        "vs", "v", "s", "e", "de", "la", "le", "el", "en", "feat", "ft", "ze",
    )

    private val VARIANT_QUALIFIERS = setOf(
        "hindi", "marathi", "telugu", "kanada", "tamil", "malayalam", "bengali", "bangla",
        "punjabi", "gujarati", "urdu", "nepali", "sinhala", "bhojpuri", "odia", "oriya",
        "assamese", "tulu", "korean", "chinese", "japanese", "arabic", "russian",
        "ot", "dub", "dubbed", "dubed", "season", "part", "extended", "uncut",
    )

    private val SCRIPT_RANGES = listOf(
        '\u0900'..'\u097F' to ("hi" to "IN"),
        '\u0980'..'\u09FF' to ("bn" to "IN"),
        '\u0A00'..'\u0A7F' to ("pa" to "IN"),
        '\u0A80'..'\u0AFF' to ("gu" to "IN"),
        '\u0B00'..'\u0B7F' to ("or" to "IN"),
        '\u0B80'..'\u0BFF' to ("ta" to "IN"),
        '\u0C00'..'\u0C7F' to ("te" to "IN"),
        '\u0C80'..'\u0CFF' to ("kn" to "IN"),
        '\u0D00'..'\u0D7F' to ("ml" to "IN"),
        '\u0D80'..'\u0DFF' to ("si" to "LK"),
        '\u0600'..'\u06FF' to ("ur" to "PK"),
        '\u0400'..'\u04FF' to ("ru" to null),
        '\u0370'..'\u03FF' to ("el" to null),
        '\u0590'..'\u05FF' to ("he" to null),
    )

    private val LANGUAGE_KEYWORDS = mapOf(
        "hindi" to "hi", "marathi" to "mr", "bhojpuri" to "bho", "nepali" to "ne",
        "tamil" to "ta", "telugu" to "te", "kannada" to "kn", "malayalam" to "ml",
        "bengali" to "bn", "bangla" to "bn", "gujarati" to "gu", "punjabi" to "pa",
        "odia" to "or", "oriya" to "or", "urdu" to "ur", "assamese" to "as",
        "english" to "en", "korean" to "ko", "japanese" to "ja", "chinese" to "zh",
        "french" to "fr", "german" to "de", "spanish" to "es", "italian" to "it",
        "russian" to "ru", "arabic" to "ar", "portuguese" to "pt", "turkish" to "tr",
    )

    private val REGION_KEYWORDS = mapOf(
        "india" to "IN", "indian" to "IN", "pakistan" to "PK", "bangladesh" to "BD",
        "nepal" to "NP", "srilanka" to "LK", "korea" to "KR", "japan" to "JP",
        "china" to "CN", "russia" to "RU", "france" to "FR", "spain" to "ES",
        "britain" to "GB", "england" to "GB", "america" to "US", "italy" to "IT",
    )

    private val LANGUAGE_REGIONS = mapOf(
        "hi" to "IN", "mr" to "IN", "ta" to "IN", "te" to "IN", "kn" to "IN",
        "ml" to "IN", "bn" to "IN", "gu" to "IN", "pa" to "IN", "or" to "IN",
        "as" to "IN", "bho" to "IN", "ur" to "PK", "ne" to "NP", "si" to "LK",
    )

    internal data class QueryLocale(val language: String?, val country: String?)

    internal fun queryLocale(query: String): QueryLocale? {
        if (query.isBlank()) return null
        val script = SCRIPT_RANGES.firstOrNull { (range, _) -> query.any { it in range } }?.second
        if (script != null) return QueryLocale(script.first, script.second)
        val tokens = normalizeKey(query).split(' ').toSet()
        if (tokens.isEmpty()) return null
        val language = LANGUAGE_KEYWORDS.entries.firstOrNull { it.key in tokens }?.value
        val country = REGION_KEYWORDS.entries.firstOrNull { it.key in tokens }?.value
            ?: language?.let { LANGUAGE_REGIONS[it] }
        return if (language == null && country == null) null else QueryLocale(language, country)
    }

    private const val EXACT_POINTS = 100.0
    private const val COMPACT_POINTS = 96.0
    private const val PREFIX_POINTS = 85.0
    private const val TOKEN_POINTS = 75.0
    private const val FUZZY_POINTS = 55.0
    private const val PARTIAL_POINTS = 35.0
    private const val LANGUAGE_POINTS = 15.0
    private const val REGION_POINTS = 5.0
    private const val YEAR_MATCH_POINTS = 8.0
    private const val YEAR_NEAR_POINTS = 4.0
    private const val YEAR_MISS_PENALTY = -10.0
    private const val QUALIFIER_PENALTY = -12.0
    private const val POSTER_PENALTY = -5.0
    private const val OVERVIEW_PENALTY = -2.0
    private const val VOTES_FACTOR = 4.0
    private const val VOTES_CAP = 12.0

    private const val DEVANAGARI_START = '\u0900'
    private const val DEVANAGARI_END = '\u097F'
    private const val DEVANAGARI_NUKTA = '\u093C'
    private const val DEVANAGARI_VIRAMA = '\u094D'

    private val DEVANAGARI_CONJUNCTS = linkedMapOf(
        "क्ष" to "ksh", "ज्ञ" to "gy", "त्र" to "tr", "श्र" to "shr",
    )

    private val DEVANAGARI_CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh",
        'ष' to "sh", 'स' to "s", 'ह' to "h", 'ळ' to "l",
    )

    private val DEVANAGARI_NUKTA_LETTERS = mapOf(
        'क' to "q", 'ख' to "kh", 'ग' to "g", 'ज' to "z", 'ड' to "r",
        'ढ' to "rh", 'फ' to "f", 'य' to "y",
    )

    private val DEVANAGARI_PRECOMPOSED = mapOf(
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "g", '\u095B' to "z",
        '\u095C' to "r", '\u095D' to "rh", '\u095E' to "f", '\u095F' to "y",
    )

    private val DEVANAGARI_INDEPENDENT = mapOf(
        'अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u",
        'ऋ' to "ri", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au",
        'ऍ' to "e", 'ऑ' to "o", 'ॲ' to "e", 'ॐ' to "om",
    )

    private val DEVANAGARI_MATRAS = mapOf(
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u",
        'ृ' to "ri", 'ॄ' to "ri", 'े' to "e", 'ै' to "ai", 'ो' to "o",
        'ौ' to "au", 'ॅ' to "e", 'ॉ' to "o", 'ॆ' to "e", 'ॊ' to "o",
    )

    private const val DEVANAGARI_DIGITS = "०१२३४५६७८९"

    internal data class ScorePart(val label: String, val points: Double)

    internal data class Score(val total: Double, val parts: List<ScorePart>) {
        val tier: Double get() = parts.firstOrNull { it.label == "match" }?.points ?: 0.0

        fun explain(): String = parts
            .filter { it.points != 0.0 }
            .joinToString(", ") { part ->
                val sign = if (part.points > 0) "+" else ""
                "${part.label}:$sign${part.points.toInt()}"
            }
    }

    internal data class Meta(
        val year: Int? = null,
        val votes: Int = 0,
        val language: String? = null,
        val countries: List<String> = emptyList(),
        val hasPoster: Boolean = true,
        val hasOverview: Boolean = true,
    )

    private data class Match(
        val relevance: Double,
        val tier: Double,
        val queryCoverage: Double,
        val titleCoverage: Double,
    ) {
        val base: Double get() = tier + (queryCoverage + titleCoverage) * 20.0
    }

    internal fun deaccent(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD).replace(LATIN_MARKS, "")

    internal fun romanize(text: String, inherentVowel: Boolean): String {
        if (text.none { it in DEVANAGARI_START..DEVANAGARI_END }) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val conjunct = DEVANAGARI_CONJUNCTS.entries.firstOrNull { text.startsWith(it.key, i) }
            if (conjunct != null) {
                out.append(conjunct.value)
                i += conjunct.key.length
                if (inherentVowel && takesInherentVowel(text, i)) out.append('a')
                continue
            }
            val precomposed = DEVANAGARI_PRECOMPOSED[c]
            if (precomposed != null) {
                out.append(precomposed)
                i++
                if (inherentVowel && takesInherentVowel(text, i)) out.append('a')
                continue
            }
            val consonant = DEVANAGARI_CONSONANTS[c]
            if (consonant != null) {
                i++
                val hasNukta = i < text.length && text[i] == DEVANAGARI_NUKTA
                val latin = if (hasNukta) {
                    i++
                    DEVANAGARI_NUKTA_LETTERS[c] ?: consonant
                } else {
                    consonant
                }
                out.append(latin)
                if (inherentVowel && takesInherentVowel(text, i)) out.append('a')
                continue
            }
            val independent = DEVANAGARI_INDEPENDENT[c]
            if (independent != null) {
                out.append(independent)
                i++
                continue
            }
            val matra = DEVANAGARI_MATRAS[c]
            if (matra != null) {
                out.append(matra)
                i++
                continue
            }
            val digit = DEVANAGARI_DIGITS.indexOf(c)
            if (digit >= 0) {
                out.append(digit)
                i++
                continue
            }
            when (c) {
                '\u0901', '\u0902' -> out.append('n')
                '\u0903' -> out.append('h')
                DEVANAGARI_VIRAMA, DEVANAGARI_NUKTA, '\u093D', '\u0951', '\u0952' -> Unit
                in DEVANAGARI_START..DEVANAGARI_END -> Unit
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun takesInherentVowel(text: String, index: Int): Boolean {
        if (index >= text.length) return false
        val next = text[index]
        if (next == DEVANAGARI_VIRAMA || next in DEVANAGARI_MATRAS) return false
        return next == DEVANAGARI_NUKTA || next == '\u0901' || next == '\u0902' || next == '\u0903' ||
            DEVANAGARI_CONSONANTS.containsKey(next) || DEVANAGARI_PRECOMPOSED.containsKey(next)
    }

    private fun collapseRepeats(token: String): String =
        REPEATED_LETTER.replace(token) { match -> match.groupValues[1] }

    private fun normalize(t: String, inherentVowel: Boolean, collapse: Boolean): String {
        val romanized = romanize(t, inherentVowel)
        val cleaned = deaccent(romanized.lowercase().replace("'", "").replace("’", ""))
            .replace(NON_ALNUM, " ")
        val tokens = cleaned.split(' ').filter { it.isNotBlank() && it !in STOPWORDS }
        val finalTokens = if (collapse) tokens.map(::collapseRepeats) else tokens
        return finalTokens.joinToString(" ").trim()
    }

    internal fun containsIndic(text: String): Boolean =
        text.any { it in DEVANAGARI_START..DEVANAGARI_END }

    internal fun normalizeBase(t: String): String = normalize(t, inherentVowel = false, collapse = false)

    internal fun normalizeKey(t: String, inherentVowel: Boolean = true): String =
        normalize(t, inherentVowel, collapse = true)

    internal fun normalizeTitle(t: String): String = normalizeKey(t)

    internal fun canonical(t: String): String = normalizeKey(t)

    internal fun withoutYears(normalized: String): String {
        val tokens = normalized.split(' ').filter { it.isNotEmpty() }
        val kept = tokens.filterNot { YEAR_TOKEN.matches(it) }
        return if (kept.isEmpty()) normalized else kept.joinToString(" ")
    }

    internal fun scoringKeys(text: String): List<String> {
        val primary = withoutYears(normalizeKey(text, false))
        val spoken = withoutYears(normalizeKey(text, true))
        return when {
            primary.isBlank() && spoken.isBlank() -> emptyList()
            primary.isBlank() -> listOf(spoken)
            spoken.isBlank() || spoken == primary -> listOf(primary)
            else -> listOf(primary, spoken)
        }
    }

    internal fun queryYear(query: String): Int? =
        YEAR_IN_TEXT.find(query)?.value?.toIntOrNull()

    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }

    internal fun damerau(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[a.length][b.length]
    }

    private fun maxEdits(length: Int): Int = when {
        length <= 3 -> 0
        length <= 5 -> 1
        length <= 8 -> 2
        else -> 3
    }

    internal fun doubleFirstVowel(word: String): String? {
        val idx = word.indexOfFirst { it.lowercaseChar() in "aeiou" }
        if (idx < 0) return null
        if (idx > 0 && word[idx - 1] == word[idx]) return null
        if (idx + 1 < word.length && word[idx + 1] == word[idx]) return null
        return word.substring(0, idx + 1) + word[idx] + word.substring(idx + 1)
    }

    internal fun queryVariants(query: String, max: Int = 2): List<String> {
        if (containsIndic(query)) return emptyList()
        val tokens = normalizeBase(query).split(' ').filter { it.length >= 3 }
        if (tokens.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for ((i, tok) in tokens.withIndex()) {
            if (out.size >= max) break
            if (REPEATED_LETTER.containsMatchIn(tok)) continue
            val firstVowel = tok.firstOrNull { it in "aeiou" } ?: continue
            if (firstVowel != 'a') continue
            val doubled = doubleFirstVowel(tok) ?: continue
            if (doubled == tok) continue
            out += tokens.toMutableList().also { it[i] = doubled }.joinToString(" ")
        }
        return out.distinct()
    }

    private data class RomanVowel(val independent: String, val matra: String?)

    private val ROMAN_VOWELS = listOf(
        "aa" to RomanVowel("आ", "ा"),
        "ai" to RomanVowel("ऐ", "ै"),
        "au" to RomanVowel("औ", "ौ"),
        "ou" to RomanVowel("औ", "ौ"),
        "ee" to RomanVowel("ई", "ी"),
        "oo" to RomanVowel("ऊ", "ू"),
        "ea" to RomanVowel("ई", "ी"),
        "a" to RomanVowel("अ", null),
        "i" to RomanVowel("इ", "ि"),
        "u" to RomanVowel("उ", "ु"),
        "e" to RomanVowel("ए", "े"),
        "o" to RomanVowel("ओ", "ो"),
    ).sortedByDescending { it.first.length }

    private val ROMAN_CONSONANTS = listOf(
        "chh" to "छ",
        "kh" to "ख", "gh" to "घ", "ch" to "च", "jh" to "झ",
        "th" to "थ", "dh" to "ध", "ph" to "फ", "bh" to "भ", "sh" to "श",
        "ng" to "ङ", "ny" to "ञ",
        "k" to "क", "g" to "ग", "j" to "ज", "t" to "त", "d" to "द", "n" to "न",
        "p" to "प", "b" to "ब", "m" to "म", "y" to "य", "r" to "र", "l" to "ल",
        "v" to "व", "w" to "व", "s" to "स", "h" to "ह", "f" to "फ़", "z" to "ज़",
        "c" to "क", "q" to "क़", "x" to "क्स",
    ).sortedByDescending { it.first.length }

    private val DOUBLE_LATIN_CONSONANT = Regex("""([bcdfgjklmnpqrstvwxyz])\1+""")
    private val DOUBLE_LATIN_ANY = Regex("""([A-Za-z])\1+""")
    private val CLOSED_O = Regex("""ो([\u0915-\u0939])(?=\s|$)""")

    private fun cleanText(s: String): String =
        deaccent(s.lowercase().replace("'", "").replace("’", ""))
            .replace(NON_ALNUM, " ")
            .trim()

    private fun toDevanagari(input: String): String {
        val text = DOUBLE_LATIN_CONSONANT.replace(cleanText(input)) { it.groupValues[1] }
        val out = StringBuilder(text.length)
        var i = 0
        var afterConsonant = false
        while (i < text.length) {
            val c = text[i]
            if (c == ' ') {
                out.append(' ')
                afterConsonant = false
                i++
                continue
            }
            val consonant = ROMAN_CONSONANTS.firstOrNull { text.startsWith(it.first, i) }
            if (consonant != null) {
                out.append(consonant.second)
                i += consonant.first.length
                afterConsonant = true
                continue
            }
            val vowel = ROMAN_VOWELS.firstOrNull { text.startsWith(it.first, i) }
            if (vowel != null) {
                if (afterConsonant) {
                    vowel.second.matra?.let { out.append(it) }
                } else {
                    out.append(vowel.second.independent)
                }
                i += vowel.first.length
                afterConsonant = false
                continue
            }
            out.append(c)
            i++
            afterConsonant = false
        }
        return out.toString()
    }

    private fun closedOForm(devanagari: String): String =
        CLOSED_O.replace(devanagari) { "ॉ" + it.groupValues[1] }

    internal fun scriptVariants(query: String, max: Int = 1): List<String> {
        if (!containsIndic(query)) return emptyList()
        val trimmed = query.trim()
        val primary = normalizeKey(trimmed, inherentVowel = false)
        val spoken = normalizeKey(trimmed, inherentVowel = true)
        val latin = when {
            primary.any { it in "aeiou" } -> primary
            spoken.isNotBlank() -> spoken
            else -> primary
        }
        return listOf(latin).filter { it.isNotBlank() && !it.equals(trimmed, ignoreCase = true) }.take(max)
    }

    internal fun respellLatin(input: String): String =
        input.split(' ').joinToString(" ") { word ->
            DOUBLE_LATIN_ANY.replace(word) { match ->
                val letter = match.value.first().lowercaseChar()
                val atEnd = match.range.last == word.lastIndex
                if (atEnd && letter in "slfz") match.value else match.value.first().toString()
            }
        }

    internal fun recallQueries(query: String, max: Int = 3): List<String> {
        val base = query.trim()
        if (base.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        out += base
        if (containsIndic(base)) {
            val latin = scriptVariants(base, max = 1).firstOrNull()
            if (!latin.isNullOrBlank()) {
                out += latin
                out += queryVariants(latin, max = 1)
            }
        } else {
            val devanagari = toDevanagari(base)
            if (devanagari.isNotBlank()) out += closedOForm(devanagari)
            val respelled = respellLatin(base)
            if (!respelled.equals(base, ignoreCase = true)) out += respelled
            out += queryVariants(base, max = 1)
            if (devanagari.isNotBlank()) out += devanagari
        }
        return out.filter { it.isNotBlank() }.take(max)
    }

    internal fun queryTokens(query: String): List<String> =
        normalizeKey(query).split(' ').filter { it.length >= 2 && it !in STOPWORDS }.distinct()

    internal fun tokenScore(token: String, titleTokens: List<String>): Double {
        if (titleTokens.any { it == token }) return 1.0
        if (token.length >= 4 && titleTokens.any { it.length >= 4 && it.contains(token) }) return 0.97
        if (token.length >= 4 && titleTokens.any { it.length >= 4 && token.contains(it) }) return 0.97
        if (token.length >= 3 &&
            titleTokens.any { it.length >= 3 && (it.startsWith(token) || token.startsWith(it)) }
        ) {
            return 0.9
        }
        val tolerance = maxEdits(token.length)
        if (tolerance <= 0) return 0.0
        if (titleTokens.any { damerau(it, token) <= tolerance }) return 0.8
        return 0.0
    }

    private fun wholeSimilarity(a: String, b: String): Double {
        val x = a.replace(" ", "")
        val y = b.replace(" ", "")
        val maxLen = maxOf(x.length, y.length)
        if (maxLen == 0) return 1.0
        return (1.0 - damerau(x, y).toDouble() / maxLen).coerceIn(0.0, 1.0)
    }

    private fun matchKeys(qNorm: String, tNorm: String): Match? {
        if (qNorm.isBlank() || tNorm.isBlank()) return null
        if (qNorm == tNorm) return Match(1.0, EXACT_POINTS, 1.0, 1.0)
        val qCompact = qNorm.replace(" ", "")
        val tCompact = tNorm.replace(" ", "")
        if (qCompact.length >= 3 && qCompact == tCompact) {
            return Match(1.0, COMPACT_POINTS, 1.0, 1.0)
        }
        val qTokens = qNorm.split(' ').filter { it.isNotEmpty() }
        val tTokens = tNorm.split(' ').filter { it.isNotEmpty() }
        if (qTokens.isEmpty() || tTokens.isEmpty()) return null
        val matchedTitle = BooleanArray(tTokens.size)
        var sum = 0.0
        var matchedCount = 0
        for (qt in qTokens) {
            var best = 0.0
            var bestIndex = -1
            for ((index, tt) in tTokens.withIndex()) {
                val score = tokenScore(qt, listOf(tt))
                if (score > best) {
                    best = score
                    bestIndex = index
                }
            }
            if (best <= 0.0) continue
            sum += best
            matchedCount++
            if (bestIndex >= 0) matchedTitle[bestIndex] = true
        }
        val prefix = qNorm.length >= 3 && tNorm.startsWith(qNorm)
        val compactPrefix = qCompact.length >= 3 && tCompact.startsWith(qCompact)
        if (matchedCount == 0) {
            if (compactPrefix) return Match(0.9, PREFIX_POINTS, 0.0, 0.0)
            val similarity = wholeSimilarity(qNorm, tNorm)
            if (!prefix && similarity < 0.78) return null
            return Match(similarity, if (prefix) PREFIX_POINTS else PARTIAL_POINTS, 0.0, 0.0)
        }
        val extra = (tTokens.size - qTokens.size).coerceAtLeast(0)
        val relevance = (sum / qTokens.size - 0.03 * min(extra, 5)).coerceIn(0.0, 1.0)
        val queryCoverage = matchedCount.toDouble() / qTokens.size
        val titleCoverage = matchedTitle.count { it }.toDouble() / tTokens.size
        val tier = when {
            prefix || compactPrefix -> PREFIX_POINTS
            matchedCount == qTokens.size && qTokens.all { qt -> tTokens.any { it == qt } } -> TOKEN_POINTS
            matchedCount == qTokens.size -> FUZZY_POINTS
            else -> PARTIAL_POINTS
        }
        if (tier == PARTIAL_POINTS && relevance < 0.55) return null
        return Match(relevance, tier, queryCoverage, titleCoverage)
    }

    internal fun relevance(query: String, title: String): Double {
        val tKeys = scoringKeys(title)
        if (tKeys.isEmpty()) return 0.0
        fun bestFor(qKeys: List<String>): Double {
            var best = 0.0
            for (q in qKeys) {
                for (t in tKeys) {
                    val match = matchKeys(q, t) ?: continue
                    if (match.relevance > best) best = match.relevance
                }
            }
            return best
        }
        val full = bestFor(scoringKeys(query))
        if (full > 0.0) return full
        return bestFor(scoringKeys(matchQuery(query)))
    }

    private fun matchQuery(query: String): String {
        val tokens = query.split(WHITESPACE).filter { it.isNotBlank() }
        val kept = tokens.filterNot { token ->
            val key = normalizeKey(token).trim()
            key in LANGUAGE_KEYWORDS || key in REGION_KEYWORDS
        }
        return kept.joinToString(" ").ifBlank { query }
    }

    private fun queryKeys(query: String, withYear: Boolean): List<String> {
        val keys = scoringKeys(query).toMutableList()
        if (withYear) {
            val raw = normalizeKey(query, inherentVowel = true)
            if (raw.isNotBlank() && raw !in keys) keys.add(raw)
        }
        return keys
    }

    private fun bestMatch(
        qKeys: List<String>,
        titles: List<String?>,
        withYear: Boolean,
    ): Pair<Match, String?>? {
        var best: Match? = null
        var bestTitle: String? = null
        for (title in titles) {
            if (title.isNullOrBlank()) continue
            val tKeys = scoringKeys(title).toMutableList()
            if (withYear) {
                val raw = normalizeKey(title, inherentVowel = true)
                if (raw.isNotBlank() && raw !in tKeys) tKeys.add(raw)
            }
            for (tKey in tKeys) {
                for (qKey in qKeys) {
                    val match = matchKeys(qKey, tKey) ?: continue
                    if (best == null || match.base > best.base) {
                        best = match
                        bestTitle = title
                    }
                }
            }
        }
        val found = best ?: return null
        return found to bestTitle
    }

    internal fun score(
        query: String,
        titles: List<String?>,
        meta: Meta = Meta(),
    ): Score {
        val qYear = queryYear(query)
        val withYear = qYear != null
        val fullKeys = queryKeys(query, withYear)
        if (fullKeys.isEmpty()) return Score(0.0, emptyList())
        val matched = bestMatch(fullKeys, titles, withYear)
            ?: bestMatch(queryKeys(matchQuery(query), withYear), titles, withYear)
            ?: return Score(0.0, emptyList())
        val match = matched.first
        val bestTitle = matched.second
        val parts = ArrayList<ScorePart>(8)
        parts += ScorePart("match", match.tier)
        parts += ScorePart("query", match.queryCoverage * 20.0)
        parts += ScorePart("title", match.titleCoverage * 20.0)

        if (qYear != null && meta.year != null) {
            parts += ScorePart(
                "year",
                when (abs(qYear - meta.year)) {
                    0, 1 -> YEAR_MATCH_POINTS
                    2, 3 -> YEAR_NEAR_POINTS
                    else -> YEAR_MISS_PENALTY
                },
            )
        }
        val locale = queryLocale(query)
        val language = locale?.language
        val country = locale?.country
        if (language != null && meta.language != null && meta.language.equals(language, ignoreCase = true)) {
            parts += ScorePart("lang", LANGUAGE_POINTS)
        }
        if (country != null && meta.countries.any { it.equals(country, ignoreCase = true) }) {
            parts += ScorePart("region", REGION_POINTS)
        }
        if (meta.votes > 0) {
            parts += ScorePart("votes", min(log10(meta.votes + 1.0) * VOTES_FACTOR, VOTES_CAP))
        }
        var metadataPenalty = 0.0
        if (!meta.hasPoster) metadataPenalty += POSTER_PENALTY
        if (!meta.hasOverview) metadataPenalty += OVERVIEW_PENALTY
        if (metadataPenalty != 0.0) parts += ScorePart("meta", metadataPenalty)

        if (bestTitle != null) {
            val queryTokens = normalizeKey(query, false).split(' ').filter { it.isNotEmpty() }.toSet()
            val titleTokens = normalizeKey(bestTitle, false).split(' ').filter { it.isNotEmpty() }
            val missing = queryTokens.count { it in VARIANT_QUALIFIERS && it !in titleTokens }
            val extra = titleTokens.count { it in VARIANT_QUALIFIERS && it !in queryTokens }
            val qualifiers = min(missing + extra, 2)
            if (qualifiers > 0) {
                parts += ScorePart("variant", QUALIFIER_PENALTY * qualifiers)
            }
        }
        return Score(parts.sumOf { it.points }, parts)
    }

    internal fun score(query: String, item: TmdbService.TmdbItem): Score = score(
        query,
        buildList<String?> {
            add(item.name)
            item.originalName?.let { add(it) }
            addAll(item.aliases)
        },
        Meta(
            year = item.year?.toIntOrNull(),
            votes = item.voteCount,
            language = item.originalLanguage,
            countries = item.originCountry,
            hasPoster = !item.poster.isNullOrBlank(),
            hasOverview = !item.overview.isNullOrBlank(),
        ),
    )

    internal fun rank(query: String, items: List<TmdbService.TmdbItem>): List<TmdbService.TmdbItem> {
        if (items.isEmpty()) return items
        val scored = items.map { item -> item to score(query, item) }
        val ordered = scored.filter { it.second.tier > 0.0 }.sortedWith(
            compareByDescending<Pair<TmdbService.TmdbItem, Score>> { it.second.tier }
                .thenByDescending { it.second.total }
                .thenByDescending { it.first.voteCount }
                .thenByDescending { it.first.popularity },
        )
        return ordered.map { it.first }
    }

    internal fun rankBand(rank: Int?): Double = when {
        rank == null || rank <= 0 -> 0.0
        rank <= 1000 -> 1.0
        rank <= 10000 -> 0.9
        rank <= 50000 -> 0.8
        rank <= 200000 -> 0.6
        else -> 0.4
    }

    fun combined(query: String, title: String, imdbRank: Int?, tmdbRating: Double?): Double {
        val rel = relevance(query, title)
        if (rel <= 0.0) return 0.0
        if (rel >= 0.999) return 1.0
        val pop = maxOf(rankBand(imdbRank), (tmdbRating ?: 0.0) / 10.0)
        return 0.6 * rel + 0.4 * pop
    }

    fun dedupeKey(name: String, year: Int?): String = normalizeTitle(name) + "|" + (year ?: 0)
}
