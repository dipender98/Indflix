package com.indstream

import android.content.Context
import com.indstream.StreamEngine.RawStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Stremio-compatible addon support. Nuvio uses the same manifest and stream protocol, so both
 * are handled by the same parser.
 */
object StremioNuvioAddons {

    const val SERVER_ID = "stremio"
    const val SAMPLE_IMDB = "tt1375666"

    private const val MANIFEST_TIMEOUT_MS = 5_000L
    private const val STREAM_TIMEOUT_MS = 7_000L
    private const val ADDON_RESOLVE_TIMEOUT_MS = 12_000L
    private const val MANIFEST_TTL_MS = 6 * 60 * 60 * 1000L
    private const val MANIFEST_MISS_TTL_MS = 60_000L

    val DEFAULT_URLS = listOf(
        "https://v3-cinemeta.strem.io",
        "https://opensubtitles-v3.strem.io",
        "https://torrentio.strem.fun",
    )

    data class Manifest(
        val baseUrl: String,
        val id: String,
        val name: String,
        val types: Set<String>,
        val resources: Set<String>,
        val idPrefixes: Set<String>,
    )

    data class CheckResult(
        val name: String,
        val detail: String,
        val ok: Boolean,
    )

    data class StreamSource(
        val url: String,
        val name: String,
        val title: String,
        val headers: Map<String, String>,
    )

    data class StreamPayload(
        val direct: List<StreamSource> = emptyList(),
        val torrents: Int = 0,
        val other: Int = 0,
    )

    private data class CachedManifest(val manifest: Manifest?, val expiresAt: Long)

    private val manifestCache = ConcurrentHashMap<String, CachedManifest>()

    fun urls(): List<String> = Settings.stremioNuvioAddonUrls()

    fun enabled(url: String): Boolean = Settings.stremioNuvioAddonEnabled(url)

    fun serverSpec(): ServerSpec? {
        val configured = urls()
        if (configured.isEmpty() || configured.none { enabled(it) }) return null
        return ServerSpec(
            id = SERVER_ID,
            name = "Stremio/Nuvio",
            idType = ServerIdType.IMDB,
            recordHealth = false,
            movieUrl = "",
            tvUrl = "",
            timeoutSec = 14,
            speed = ServerSpeed.FAST,
        )
    }

    fun cached(url: String): Manifest? {
        val base = normalizeUrl(url) ?: return null
        val entry = manifestCache[base] ?: return null
        return entry.manifest.takeIf { System.currentTimeMillis() <= entry.expiresAt }
    }

    fun add(context: Context, raw: String): String? {
        val url = normalizeUrl(raw) ?: return null
        val current = urls()
        if (current.any { it.equals(url, true) }) return null
        Settings.saveStremioNuvioAddons(context, current + url)
        return url
    }

    fun remove(context: Context, url: String) {
        Settings.saveStremioNuvioAddons(context, urls().filterNot { it == url })
        Settings.saveStremioNuvioAddonEnabled(context, url, true)
    }

    fun setEnabled(context: Context, url: String, on: Boolean) {
        Settings.saveStremioNuvioAddonEnabled(context, url, on)
    }

    fun normalizeUrl(raw: String?): String? {
        var value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("stremio://", ignoreCase = true)) {
            value = "https://" + value.substring("stremio://".length)
        }
        val declaredScheme = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*)://""")
            .find(value)?.groupValues?.get(1)
        if (declaredScheme != null &&
            !declaredScheme.equals("http", ignoreCase = true) &&
            !declaredScheme.equals("https", ignoreCase = true)
        ) {
            return null
        }
        if (declaredScheme == null) value = "https://$value"
        value = value.substringBefore("#").trim().trimEnd('/')
        val manifestSuffix = "/manifest.json"
        if (value.endsWith(manifestSuffix, ignoreCase = true)) {
            value = value.dropLast(manifestSuffix.length).trimEnd('/')
        }
        val afterScheme = value.substringAfter("://", "")
        val host = afterScheme.substringBefore("/").substringBefore("?").substringBefore("#")
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        return value
    }

    fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore("/").substringBefore("?").ifBlank { url }

    fun parseManifest(json: String?, baseUrl: String): Manifest? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(json)
            Manifest(
                baseUrl = baseUrl,
                id = root.optString("id").trim(),
                name = root.optString("name").trim().ifBlank { hostOf(baseUrl) },
                types = stringSet(root.optJSONArray("types")).map { it.lowercase() }.toSet(),
                resources = resourceSet(root.optJSONArray("resources")),
                idPrefixes = stringSet(root.optJSONArray("idPrefixes")),
            )
        }.getOrNull()
    }

    fun parseStreamPayload(json: String?): StreamPayload {
        if (json.isNullOrBlank()) return StreamPayload()
        val streams = runCatching {
            JSONObject(json).optJSONArray("streams")
        }.getOrNull() ?: return StreamPayload()

        val direct = ArrayList<StreamSource>()
        var torrents = 0
        var other = 0
        for (index in 0 until streams.length()) {
            val item = streams.optJSONObject(index) ?: continue
            val rawUrl = item.optString("url").trim()
            val url = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
            val infoHash = item.optString("infoHash").trim()
            val external = item.optString("externalUrl").trim()
            val youtube = item.optString("ytId").trim()
            when {
                (url.startsWith("http://", ignoreCase = true) ||
                    url.startsWith("https://", ignoreCase = true)) &&
                    !url.endsWith(".torrent", ignoreCase = true) -> {
                    direct += StreamSource(
                        url = url,
                        name = item.optString("name").trim(),
                        title = item.optString("title").trim(),
                        headers = streamHeaders(item),
                    )
                }
                infoHash.isNotBlank() || url.startsWith("magnet:", ignoreCase = true) -> torrents++
                external.isNotBlank() || youtube.isNotBlank() -> other++
                else -> other++
            }
        }
        return StreamPayload(direct, torrents, other)
    }

    fun qualityOf(vararg values: String?): Int {
        val text = values.filterNotNull().joinToString(" ")
        if (text.isBlank()) return 0
        val p = Regex("""(?<!\d)(\d{3,4})p(?!\d)""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .maxOfOrNull { it.groupValues[1].toIntOrNull() ?: 0 } ?: 0
        val fourK = Regex("""\b(?:4k|uhd|2160p)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
        val twoK = Regex("""\b(?:2k|1440p)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
        return maxOf(p, if (fourK) 2160 else 0, if (twoK) 1440 else 0)
    }

    fun sizeOf(vararg values: String?): String {
        val text = values.filterNotNull().joinToString(" ")
        val match = Regex("""(?<!\d)(\d+(?:\.\d+)?)\s*(GB|GiB|MB|MiB)(?!\w)""", RegexOption.IGNORE_CASE)
            .find(text) ?: return ""
        val unit = if (match.groupValues[2].uppercase().startsWith("G")) "GB" else "MB"
        return "${match.groupValues[1]} $unit"
    }

    suspend fun resolve(imdbId: String, type: String, season: Int, episode: Int): List<RawStream> {
        val baseId = imdbId.trim()
        if (!baseId.startsWith("tt")) return emptyList()
        val series = type != "movie"
        if (series && (season <= 0 || episode <= 0)) return emptyList()
        val addonType = if (series) "series" else "movie"
        val addonId = if (series) "$baseId:$season:$episode" else baseId
        val configured = urls().filter { enabled(it) }
        if (configured.isEmpty()) return emptyList()

        val streams = coroutineScope {
            configured.map { url ->
                async {
                    try {
                        withTimeoutOrNull(ADDON_RESOLVE_TIMEOUT_MS) {
                            val manifest = manifest(url) ?: return@withTimeoutOrNull emptyList()
                            if (!supportsStream(manifest) ||
                                !supportsType(manifest, addonType) ||
                                !supportsId(manifest, addonId)
                            ) {
                                return@withTimeoutOrNull emptyList()
                            }
                            fetchStreams(manifest, addonId, addonType)
                        }.orEmpty()
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (_: Throwable) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        return streams.distinctBy { it.url }
    }

    suspend fun check(rawUrl: String): CheckResult {
        val base = normalizeUrl(rawUrl)
            ?: return CheckResult(rawUrl.ifBlank { "Addon" }, "Invalid addon URL", false)
        val manifest = manifest(base, force = true)
            ?: return CheckResult(hostOf(base), "Manifest unreachable", false)
        val resources = manifest.resources.sorted().joinToString(", ")
            .ifBlank { "no resources declared" }
        if (!supportsStream(manifest) || !supportsType(manifest, "movie")) {
            return CheckResult(manifest.name, "Manifest OK - $resources", true)
        }
        val body = HttpKit.get("${manifest.baseUrl}/stream/movie/$SAMPLE_IMDB.json", timeout = 8)
        val payload = parseStreamPayload(body)
        val detail = if (body == null) {
            "Manifest OK - $resources; stream check failed"
        } else {
            "Manifest OK - $resources; ${payload.direct.size} direct, " +
                "${payload.torrents} torrent, ${payload.other} other"
        }
        return CheckResult(manifest.name, detail, true)
    }

    suspend fun manifest(rawUrl: String, force: Boolean = false): Manifest? {
        val base = normalizeUrl(rawUrl) ?: return null
        val now = System.currentTimeMillis()
        if (!force) {
            manifestCache[base]?.let { if (now <= it.expiresAt) return it.manifest }
        }
        val body = HttpKit.get("$base/manifest.json", timeout = MANIFEST_TIMEOUT_MS / 1000L)
        val parsed = parseManifest(body, base)
        manifestCache[base] = CachedManifest(
            parsed,
            now + if (parsed != null) MANIFEST_TTL_MS else MANIFEST_MISS_TTL_MS,
        )
        return parsed
    }

    private suspend fun fetchStreams(manifest: Manifest, id: String, type: String): List<RawStream> {
        val body = HttpKit.get(
            "${manifest.baseUrl}/stream/$type/$id.json",
            timeout = STREAM_TIMEOUT_MS / 1000L,
        ) ?: return emptyList()
        val payload = parseStreamPayload(body)
        val serverKey = "stremio:${manifest.id.ifBlank { hostOf(manifest.baseUrl) }}"
        return payload.direct.mapNotNull { source ->
            val u = source.url
            if (u.isBlank()) return@mapNotNull null
            val haystack = listOf(source.name, source.title).filter { it.isNotBlank() }.joinToString(" ")
            RawStream(
                serverId = serverKey,
                serverName = manifest.name,
                url = u,
                isM3u8 = u.contains(".m3u8", ignoreCase = true),
                referer = source.headers.entries
                    .firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value,
                qualityHint = qualityOf(u).takeIf { it > 0 } ?: qualityOf(haystack),
                audioLabel = LinkNaming.languageLabel(haystack),
                sizeLabel = sizeOf(haystack),
                extraHeaders = source.headers,
                isDash = u.contains(".mpd", ignoreCase = true),
            )
        }
    }

    internal fun supportsStream(manifest: Manifest): Boolean =
        manifest.resources.isEmpty() || manifest.resources.contains("stream")

    internal fun supportsType(manifest: Manifest, type: String): Boolean =
        manifest.types.isEmpty() ||
            manifest.types.contains(type) ||
            (type == "series" && manifest.types.contains("tv"))

    internal fun supportsId(manifest: Manifest, id: String): Boolean =
        manifest.idPrefixes.isEmpty() || manifest.idPrefixes.any { id.startsWith(it) }

    private fun stringSet(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val out = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotEmpty()) out += value
        }
        return out
    }

    private fun resourceSet(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val out = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            when (val value = array.opt(index)) {
                is String -> value.trim().takeIf { it.isNotEmpty() }?.let { out += it.lowercase() }
                is JSONObject -> value.optString("name").trim()
                    .takeIf { it.isNotEmpty() }?.let { out += it.lowercase() }
            }
        }
        return out
    }

    private fun streamHeaders(item: JSONObject): Map<String, String> {
        val hints = item.optJSONObject("behaviorHints") ?: return emptyMap()
        val request = hints.optJSONObject("proxyHeaders")?.optJSONObject("request")
            ?: hints.optJSONObject("headers")
            ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = request.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = request.optString(key)
            if (key.isNotBlank() && value.isNotBlank()) out[key] = value
        }
        return out
    }
}
