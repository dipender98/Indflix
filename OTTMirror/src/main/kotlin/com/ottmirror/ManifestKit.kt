package com.ottmirror

import kotlin.math.max

/**
 * Pure parsers for HLS master playlists and DASH MPDs.
 * No network, no Android, no CloudStream dependency — safe for JVM unit tests.
 */
object ManifestKit {

    /** Resolve a possibly-relative URL against a base. Pure (no network). */
    fun resolveUrl(base: String, path: String): String {
        if (path.startsWith("http", ignoreCase = true)) return path
        if (path.startsWith("//")) return "https:$path"
        val schemeHost = Regex("""^https?://[^/]+""").find(base)?.value ?: return path
        return if (path.startsWith("/")) "$schemeHost$path" else "$schemeHost/$path"
    }

    /** One video variant in an HLS master playlist. */
    data class Variant(
        val url: String,
        val height: Int,
        val bandwidth: Long,
        val codecs: String? = null,
        val audioGroup: String? = null,
        val subtitlesGroup: String? = null,
    )

    /** One EXT-X-MEDIA rendition (audio or subtitles). */
    data class MediaRendition(
        val type: String,       // "AUDIO" | "SUBTITLES"
        val groupId: String,
        val name: String,
        val language: String?,
        val uri: String?,
        val forced: Boolean = false,
        val default: Boolean = false,
    )

    /** Parsed master playlist. */
    data class MasterPlaylist(
        val variants: List<Variant>,
        val audio: List<MediaRendition>,
        val subtitles: List<MediaRendition>,
    ) {
        val isMultiAudio: Boolean get() = audio.map { it.groupId }.distinct().size > 1 ||
            audio.map { it.language }.filterNotNull().distinct().size > 1
        val hasSubtitles: Boolean get() = subtitles.isNotEmpty()
    }

    /** Parse an HLS master playlist text. Returns null if no variants found. */
    fun parseMaster(text: String?, baseUrl: String = ""): MasterPlaylist? {
        if (text.isNullOrBlank()) return null
        val lines = text.lines()
        val variants = mutableListOf<Variant>()
        val media = mutableListOf<MediaRendition>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            when {
                line.startsWith("#EXT-X-MEDIA:") -> {
                    parseMediaTag(line)?.let { media.add(it) }
                }
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val attrs = parseAttrs(line.removePrefix("#EXT-X-STREAM-INF:"))
                    val uri = lines.getOrNull(i + 1)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("#") }
                    if (uri != null) {
                        val height = attrs["RESOLUTION"]?.let { r ->
                            Regex("\\d+").findAll(r).toList().getOrNull(1)?.value?.toIntOrNull()
                        } ?: 0
                        variants.add(
                            Variant(
                                url = resolveUrl(baseUrl, uri),
                                height = height,
                                bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                                codecs = attrs["CODECS"],
                                audioGroup = attrs["AUDIO"],
                                subtitlesGroup = attrs["SUBTITLES"],
                            )
                        )
                    }
                    i++ // consume the URI line
                }
            }
            i++
        }

        return if (variants.isEmpty() && media.isEmpty()) null
        else MasterPlaylist(
            variants = variants,
            audio = media.filter { it.type == "AUDIO" },
            subtitles = media.filter { it.type == "SUBTITLES" },
        )
    }

    private fun parseMediaTag(attrStr: String): MediaRendition? {
        val attrs = parseAttrs(attrStr)
        val type = attrs["TYPE"] ?: return null
        val groupId = attrs["GROUP-ID"] ?: return null
        val name = attrs["NAME"] ?: return null
        return MediaRendition(
            type = type,
            groupId = groupId,
            name = name,
            language = attrs["LANGUAGE"],
            uri = attrs["URI"],
            forced = attrs["FORCED"] == "YES",
            default = attrs["DEFAULT"] == "YES",
        )
    }

    /** Parse `KEY=VALUE,KEY2="VALUE2"` attr lists (values may be quoted). */
    fun parseAttrs(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val regex = Regex("""([A-Za-z0-9-]+)=("([^"]*)"|[^,\s]*)""")
        regex.findAll(raw).forEach { m ->
            val key = m.groupValues[1]
            val value = m.groupValues[3].ifEmpty { m.groupValues[2] }
            out[key] = value
        }
        return out
    }

    /** True if the playlist is a master (multi-variant) rather than a media playlist. */
    fun isMaster(text: String?): Boolean = text != null && text.contains("#EXT-X-STREAM-INF")

    /** One representation in a DASH MPD. */
    data class Representation(
        val id: String,
        val height: Int,
        val bandwidth: Long,
        val codecs: String? = null,
    )

    /** Parse a DASH MPD text into video representations. */
    fun parseMpd(text: String?): List<Representation> {
        if (text.isNullOrBlank()) return emptyList()
        val reps = mutableListOf<Representation>()

        // Iterate AdaptationSets; mimeType lives on the set, not the Representation.
        val setRegex = Regex("""<AdaptationSet\b([^>]*)>(.*?)</AdaptationSet>""", RegexOption.DOT_MATCHES_ALL)
        setRegex.findAll(text).forEach { setMatch ->
            val setAttrs = parseXmlAttrs(setMatch.groupValues[1])
            val setMime = setAttrs["mimeType"] ?: ""
            if (!setMime.contains("video", ignoreCase = true)) return@forEach
            val repRegex = Regex("""<Representation\b([^>]*?)/?>""")
            repRegex.findAll(setMatch.groupValues[2]).forEach { repMatch ->
                val attrs = parseXmlAttrs(repMatch.groupValues[1])
                val height = attrs["height"]?.toIntOrNull() ?: 0
                reps.add(
                    Representation(
                        id = attrs["id"] ?: "",
                        height = height,
                        bandwidth = attrs["bandwidth"]?.toLongOrNull() ?: 0L,
                        codecs = attrs["codecs"],
                    )
                )
            }
        }
        return reps
    }

    private fun parseXmlAttrs(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val regex = Regex("""([\w:.-]+)\s*=\s*"([^"]*)"""")
        regex.findAll(raw).forEach { m -> out[m.groupValues[1]] = m.groupValues[2] }
        return out
    }

    /** Deterministic identity key for dedup: host + normalized path. */
    fun urlKey(url: String): String {
        return url
            .lowercase()
            .replace(Regex("https?://"), "")
            .substringBefore("?")
            .trimEnd('/')
    }

    /** Rank variants by quality (height desc), used to label links. */
    fun bestHeight(variants: List<Variant>): Int = variants.maxOfOrNull { it.height } ?: 0

    /** Human label for a height: "4K"/"1080p"/"720p"/"480p"/"Auto". */
    fun qualityLabel(height: Int): String = when {
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height >= 480 -> "480p"
        height >= 360 -> "360p"
        height > 0 -> "${height}p"
        else -> "Auto"
    }

    /** Max of two, but treats 0 (unknown) as -inf so known quality wins. */
    fun maxQuality(a: Int, b: Int): Int = if (a <= 0) b else if (b <= 0) a else max(a, b)

    // ── Language detection ──────────────────────────────────

    /** Language codes we know. */
    private val LANG_HINDI = setOf("hi", "hin")
    private val LANG_ENGLISH = setOf("en", "eng")

    /** True if a rendition's language is Hindi. */
    fun isHindi(rendition: MediaRendition): Boolean =
        rendition.language?.lowercase()?.let { it in LANG_HINDI } == true ||
            rendition.name.contains("hindi", ignoreCase = true) ||
            rendition.name.contains("हिन्दी", ignoreCase = true)

    /** True if a rendition's language is English. */
    fun isEnglish(rendition: MediaRendition): Boolean =
        rendition.language?.lowercase()?.let { it in LANG_ENGLISH } == true ||
            rendition.name.contains("english", ignoreCase = true)

    /** True if a master playlist has at least Hindi + English audio tracks. */
    fun hasHindiEnglishAudio(master: MasterPlaylist): Boolean {
        if (master.audio.isEmpty()) return false
        val hasHindi = master.audio.any { isHindi(it) }
        val hasEnglish = master.audio.any { isEnglish(it) }
        return hasHindi && hasEnglish
    }

    /** True if a stream URL's name/context suggests Hindi audio. */
    fun isHindiFromName(name: String?, url: String?): Boolean {
        val hay = buildString {
            name?.let { append(it.lowercase()); append(' ') }
            url?.let { append(it.lowercase()); append(' ') }
        }
        return hay.contains("hindi") || hay.contains("हिन्दी") || hay.contains("हिंदी")
    }

    /** True if a stream URL's name/context suggests English audio. */
    fun isEnglishFromName(name: String?, url: String?): Boolean {
        val hay = buildString {
            name?.let { append(it.lowercase()); append(' ') }
            url?.let { append(it.lowercase()); append(' ') }
        }
        return hay.contains("english") || hay.contains("eng") || hay.contains("english")
    }
}
