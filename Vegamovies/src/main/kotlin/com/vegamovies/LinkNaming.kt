package com.vegamovies


internal object Servers {
    const val GDRIVE = "G-Drive"
    const val VCLOUD = "V-Cloud"
    const val ZIP = "Batch/Zip"
    const val GATE = "Server"

    const val CAP_BOTH = "Streamable+Downloadable"
    const val CAP_DL = "Downloadable"

    fun labelFor(kind: String): String = when (kind) {
        GDRIVE -> "G-Drive (Google Drive)"
        VCLOUD -> "V-Cloud"
        ZIP -> "Batch/Zip (Archive)"
        else -> "Vegamovies Server"
    }

    fun kindOf(url: String): String = when {
        url.contains("fastdl") -> GDRIVE
        url.contains("vcloud") -> VCLOUD
        url.contains("hubcloud") || url.contains("nexdrive") || url.endsWith(".zip", ignoreCase = true) -> ZIP
        else -> GDRIVE
    }
}

internal data class RawLink(
    val url: String,
    val kind: String,
    val heading: String,
    val browserOnly: Boolean,
    val season: Int? = null,
    val episode: Int? = null,
    val serverTag: String = "",
)

internal object LinkNaming {

    fun qualityInt(heading: String?): Int {
        if (heading.isNullOrBlank()) return 0
        Regex("""(?i)\b(\d{3,4})p\b""").findAll(heading)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull()?.let { return it }
        if (Regex("""(?i)\b4K\b""").containsMatchIn(heading)) return 2160
        return 0
    }

    fun displayName(l: RawLink): String {
        val sb = StringBuilder(Servers.labelFor(l.kind))
        if (l.serverTag.isNotBlank()) sb.append(' ').append('(').append(l.serverTag).append(')')
        sb.append(" [").append(if (l.browserOnly) Servers.CAP_DL else Servers.CAP_BOTH).append(']')
        when {
            l.season != null && l.episode != null ->
                sb.append(" S").append(l.season.toString().padStart(2, '0'))
                    .append("E").append(l.episode.toString().padStart(2, '0'))
            l.season != null ->
                sb.append(" Season ").append(l.season)
        }
        for (tok in parseTokens(l.heading)) sb.append(' ').append(tok)
        return sb.toString().trim()
    }

    fun parseTokens(heading: String): List<String> {
        if (heading.isBlank()) return emptyList()
        val out = LinkedHashSet<String>(6)

        Regex("""(?i)\b(2160p(?:\s?4K)?|1080p|720p|480p|4K)\b""").find(heading)?.let {
            val q = it.groupValues[1].lowercase().replace(Regex("""\s+"""), " ")
            out.add(
                when {
                    q == "4k" -> "4K"
                    q.startsWith("2160p") -> "2160p" + if (q.length > 5) " 4K" else ""
                    else -> q
                }
            )
        }

        Regex("""([{\[](?:Hindi|English|Dual|Multi|Tamil|Telugu|Korean|Japanese|Chinese|French|Spanish|German|Italian|Russian|Turkish|Malayalam|Kannada|Bengali|Marathi|Gujarati|Punjabi)[^)}\]]{0,60}[}\]])""", RegexOption.IGNORE_CASE)
            .find(heading)?.let { out.add(it.groupValues[1]) }

        Regex("""(?i)\b(web[\s-]?dl|webrip|bluray|bdrip|brrip|hdrip|hdcam|hdtc|hdts|telesync|dvdrip|ppvrip|amzn|dsny\+?|atvp\+?|hmax|nfx?|x264|x265|hevc|avc|aac\d(?:\.\d)?|ddp?\d(?:\.\d)?|e-?ac3|atmos|hdr10\+?|hdr|60\s?fps)\b""")
            .findAll(heading)
            .map { normalizeCodec(it.value) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(2)
            .forEach { out.add(it) }

        Regex("""\[\s*(\d+(?:\.\d+)?\s*(?:GB|MB)(?:/E\b|/EP\b|/ZiP|/ZIP)?)\s*]""", RegexOption.IGNORE_CASE)
            .find(heading)?.let { out.add(normalizeSize(it.groupValues[1])) }

        if (isPack(heading)) out.add("Complete")

        return out.toList()
    }

    private fun normalizeSize(size: String): String =
        size.uppercase()
            .replace("/ZIP", "")
            .replace(Regex("""/E(?=P?$)"""), "/ep")
            .replace("/EP", "/ep")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun normalizeCodec(raw: String): String {
        val v = raw.lowercase().trim().replace(Regex("""[\s-]"""), "")
        return when {
            v == "webdl" -> "WEB-DL"
            v.startsWith("x264") || v == "avc" -> "x264"
            v == "x265" || v == "hevc" -> "x265"
            v.startsWith("blu") || v == "bdrip" || v == "brrip" -> "BluRay"
            v == "hdrip" -> "HDRip"
            v == "hdcam" -> "HDCAM"
            v == "hdtc" -> "HDTSC"
            v == "hdts" || v == "telesync" -> "HDTS"
            v == "dvdrip" -> "DVDRip"
            v == "ppvrip" -> "PPVrip"
            v == "webrip" -> "WEBRip"
            v == "amzn" -> "AMZN"
            v == "nf" || v == "nfx" -> "NF"
            v.startsWith("dsny") -> "DSNP"
            v.startsWith("atvp") -> "ATVP"
            v == "hmax" -> "HMAX"
            v.startsWith("hdr") -> "HDR"
            v == "60fps" -> "60fps"
            v.startsWith("dd") || v.startsWith("eac3") || v.startsWith("aac") || v == "atmos" -> "Audio"
            else -> ""
        }
    }

    fun seasonEpisodeFrom(heading: String): Pair<Int?, Int?> {
        Regex("""(?i)\bS(\d{1,2})[\s.\-_]?E(?:P)?\s?0*(\d{1,3})\b""").find(heading)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        Regex("""\b(\d{1,2})x(\d{2})\b""").find(heading)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        val season = Regex("""(?i)season\s*(\d{1,2})""").find(heading)?.groupValues?.get(1)?.toIntOrNull()
        val ep = Regex("""(?i)(?:episode|ep\.?|eps)\s*0*(\d{1,3})""").find(heading)?.groupValues?.get(1)?.toIntOrNull()
        return season to ep
    }

    fun kindFromChip(chip: String): String = when {
        chip.contains("G-Direct", true) || chip.contains("G-Drive", true) || chip.contains("Direct", true) -> Servers.GDRIVE
        chip.contains("V-Cloud", true) -> Servers.VCLOUD
        chip.contains("Batch", true) || chip.contains("Zip", true) || chip.contains("ZiP", true) -> Servers.ZIP
        else -> ""
    }

    fun isPack(text: String?): Boolean =
        text != null && Regex("""(?i)\b(batch|pack|zip)\b""").containsMatchIn(text)

    fun resolveKind(chipText: String, heading: String, gatewayTitle: String?, concreteUrl: String): String {
        kindFromChip(chipText).ifBlank { kindFromChip(heading) }.ifBlank {
            gatewayTitle?.let { kindFromChip(it) }.orEmpty()
        }.ifBlank { Servers.kindOf(concreteUrl) }.let { return it }
    }
}
