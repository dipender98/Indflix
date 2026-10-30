package com.indstream

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Reads media entries from M3U playlists. */
object M3uPlaylists {

    const val DEFAULT_SOURCE = "https://iptv-org.github.io/iptv/index.m3u"
    const val MAX_SOURCES = 8

    val DEFAULT_SOURCES: List<String> = listOf(DEFAULT_SOURCE)

    private const val SCHEME = "indstream-m3u://item"
    private const val PAGE_SIZE = 60
    private const val FETCH_TIMEOUT_S = 15L
    private const val SEARCH_TIMEOUT_MS = 7_000L
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private const val EMPTY_TTL_MS = 2 * 60 * 1000L
    private const val SEARCH_LIMIT = 20
    private const val RECENT_LIMIT = 64

    /** One playable item. [duration] is negative for live entries. */
    data class Entry(
        val name: String,
        val url: String,
        val logo: String?,
        val groups: List<String>,
        val height: Int,
        val duration: Int = -1,
    ) {
        val isLive: Boolean get() = duration <= 0
    }

    private data class RowSpec(val id: String, val label: String, val group: String)

    private val rowSpecs = listOf(
        RowSpec("news", "News", "News"),
        RowSpec("sports", "Sports", "Sports"),
        RowSpec("movies", "Movies", "Movies"),
        RowSpec("entertainment", "Entertainment", "Entertainment"),
        RowSpec("music", "Music", "Music"),
        RowSpec("kids", "Kids", "Kids"),
        RowSpec("documentary", "Documentary", "Documentary"),
        RowSpec("series", "Series", "Series"),
        RowSpec("comedy", "Comedy", "Comedy"),
        RowSpec("lifestyle", "Lifestyle", "Lifestyle"),
        RowSpec("religious", "Religious", "Religious"),
        RowSpec("travel", "Travel", "Travel"),
    )

    private class Slot {
        val lock = Mutex()

        @Volatile
        var at: Long = 0L

        @Volatile
        var entries: List<Entry> = emptyList()
    }

    private val cache = ConcurrentHashMap<String, Slot>()

    private val recent = object : LinkedHashMap<String, Entry>(RECENT_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > RECENT_LIMIT
    }

    /** Keeps the item behind a stream link so the player can show its name and quality. */
    fun remember(entry: Entry) {
        synchronized(recent) { recent[entry.url] = entry }
    }

    fun remembered(url: String?): Entry? {
        val key = url?.trim().orEmpty()
        if (key.isEmpty()) return null
        return synchronized(recent) { recent[key] }
    }

    fun rows(): List<Pair<String, String>> {
        if (!Settings.m3uEnabled()) return emptyList()
        val sources = sources()
        if (sources.isEmpty()) return emptyList()
        val primary = primarySource(sources)
        val rows = ArrayList<Pair<String, String>>(rowSpecs.size + sources.size)
        rowSpecs.forEach { spec -> rows += "m3u|cat:${spec.id}" to "Playlist: ${spec.label}" }
        sources.forEachIndexed { index, url ->
            if (!url.equals(primary, ignoreCase = true)) rows += "m3u|src:$index" to "Playlist: ${sourceLabel(url)}"
        }
        return rows
    }

    suspend fun fetch(rowKey: String, page: Int): List<Entry> {
        if (!Settings.m3uEnabled()) return emptyList()
        val key = rowKey.trim().substringBefore('?')
        val sources = sources()
        if (sources.isEmpty()) return emptyList()
        val entries = when {
            key.startsWith("m3u|cat:") -> {
                val id = key.removePrefix("m3u|cat:")
                val spec = rowSpecs.firstOrNull { it.id == id } ?: return emptyList()
                val primary = primarySource(sources) ?: return emptyList()
                entriesFor(primary).filter { entry -> entry.groups.any { it.equals(spec.group, true) } }
            }
            key.startsWith("m3u|src:") -> {
                val index = key.removePrefix("m3u|src:").toIntOrNull() ?: return emptyList()
                entriesFor(sources.getOrNull(index) ?: return emptyList())
            }
            else -> return emptyList()
        }
        return entries.page(page)
    }

    suspend fun search(query: String): List<Entry> {
        if (!Settings.m3uEnabled()) return emptyList()
        val needle = query.trim()
        if (needle.length < 2) return emptyList()
        val sources = sources()
        if (sources.isEmpty()) return emptyList()
        val all = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            coroutineScope { sources.map { url -> async { entriesFor(url) } }.awaitAll() }.flatten()
        }.orEmpty()
        if (all.isEmpty()) return emptyList()
        return all.asSequence()
            .filter { it.name.contains(needle, ignoreCase = true) }
            .sortedBy { if (it.name.startsWith(needle, ignoreCase = true)) 0 else 1 }
            .distinctBy { it.url }
            .take(SEARCH_LIMIT)
            .toList()
    }

    fun encode(entry: Entry): String {
        val params = listOf(
            "n" to entry.name,
            "u" to entry.url,
            "l" to entry.logo.orEmpty(),
            "h" to entry.height.toString(),
            "g" to entry.groups.joinToString(";"),
            "d" to entry.duration.toString(),
        )
        return SCHEME + "?" + params.joinToString("&") { (key, value) -> "$key=" + encodeComponent(value) }
    }

    fun decode(url: String?): Entry? {
        val text = url?.trim().orEmpty()
        // CloudStream's APIRepository.load() runs fixUrl() on every url before it
        // reaches the provider. This scheme does not start with "http", so the
        // card url arrives prefixed with mainUrl (e.g.
        // "https://.../indstream-m3u://item?..."). Recover the scheme marker
        // wherever it appears in the string instead of only at the start.
        val start = text.indexOf(SCHEME, ignoreCase = true)
        if (start < 0) return null
        val query = text.substring(start + SCHEME.length).removePrefix("?")
        if (query.isEmpty()) return null
        val params = HashMap<String, String>()
        query.split('&').forEach { part ->
            val split = part.indexOf('=')
            if (split <= 0) return@forEach
            params[part.take(split)] = decodeComponent(part.substring(split + 1))
        }
        val streamUrl = params["u"]?.trim().orEmpty()
        if (streamUrl.isBlank()) return null
        return Entry(
            name = params["n"]?.trim().orEmpty().ifBlank { "Playlist item" },
            url = streamUrl,
            logo = params["l"]?.trim()?.takeIf { it.startsWith("http") },
            groups = params["g"].orEmpty().split(';').map { it.trim() }.filter { it.isNotBlank() },
            height = params["h"]?.toIntOrNull() ?: 0,
            duration = params["d"]?.toIntOrNull() ?: -1,
        )
    }

    fun normalizeSource(raw: String?): String? {
        val text = raw?.trim().orEmpty().trim('"', ' ')
        if (text.isEmpty()) return null
        val candidate = if (text.contains("://")) text else "https://$text"
        val uri = runCatching { java.net.URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return candidate
    }

    fun sourceLabel(url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        val host = uri?.host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: return "Playlist"
        val tail = uri.path.orEmpty().trim('/').substringAfterLast('/').substringBeforeLast('.')
        return if (tail.isBlank() || tail.equals("index", true) || tail.equals("playlist", true)) {
            host
        } else {
            "$host · ${tail.take(24)}"
        }
    }

    fun heightOf(name: String?, url: String?): Int {
        val fromName = LinkNaming.qualityHeight(name)
        if (fromName > 0) return fromName
        return LinkNaming.qualityHeight(url)
    }

    internal fun parse(text: String, baseUrl: String = ""): List<Entry> {
        val entries = ArrayList<Entry>()
        val seen = HashSet<String>()
        var name: String? = null
        var logo: String? = null
        var groups: List<String> = emptyList()
        var duration: Int = -1
        var groupLine: String? = null
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val body = line.substringAfter(':', "")
                    val comma = firstOutsideQuotes(body, ',')
                    val attrs = ManifestKit.parseAttrs(if (comma >= 0) body.take(comma) else body)
                    duration = body.substringBefore(' ').substringBefore(',').trim().toIntOrNull() ?: -1
                    name = (if (comma >= 0) body.substring(comma + 1) else "").trim()
                        .ifBlank { attrs["tvg-name"].orEmpty().trim() }
                    logo = attrs["tvg-logo"]?.trim()?.takeIf { it.startsWith("http", true) }
                    groups = groupsOf(attrs["group-title"])
                    groupLine = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    groupLine = line.substringAfter(':').trim().takeIf { it.isNotBlank() }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val entryName = name
                    val entryLogo = logo
                    val entryGroups = groups.ifEmpty { listOfNotNull(groupLine) }
                    val entryDuration = duration
                    name = null
                    logo = null
                    groups = emptyList()
                    duration = -1
                    groupLine = null
                    if (entryName == null) return@forEach
                    val scheme = line.substringBefore("://", "").lowercase()
                    if (scheme.isNotBlank() && scheme != "http" && scheme != "https") return@forEach
                    val streamUrl = ManifestKit.resolveUrl(baseUrl, line)
                    if (!streamUrl.startsWith("http://", true) && !streamUrl.startsWith("https://", true)) {
                        return@forEach
                    }
                    if (!seen.add(streamUrl.lowercase())) return@forEach
                    entries += Entry(
                        name = entryName.ifBlank { streamUrl },
                        url = streamUrl,
                        logo = entryLogo,
                        groups = entryGroups,
                        height = heightOf(entryName, streamUrl),
                        duration = entryDuration,
                    )
                }
            }
        }
        return entries
    }

    private fun groupsOf(raw: String?): List<String> =
        raw.orEmpty().split(';').map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun firstOutsideQuotes(text: String, target: Char): Int {
        var quoted = false
        text.forEachIndexed { index, char ->
            when {
                char == '"' -> quoted = !quoted
                char == target && !quoted -> return index
            }
        }
        return -1
    }

    private fun encodeComponent(value: String): String =
        runCatching { URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)

    private fun decodeComponent(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun sources(): List<String> =
        Settings.m3uSources().mapNotNull { normalizeSource(it) }.distinct().take(MAX_SOURCES)

    /** The source that supplies the category rows. */
    fun primarySource(sources: List<String>): String? =
        sources.getOrNull(sources.indexOf(DEFAULT_SOURCE).takeIf { it >= 0 } ?: 0)

    private suspend fun entriesFor(url: String): List<Entry> {
        val key = normalizeSource(url) ?: return emptyList()
        val slot = cache[key] ?: Slot().let { fresh -> cache.putIfAbsent(key, fresh) ?: fresh }
        if (isFresh(slot)) return slot.entries
        return slot.lock.withLock {
            if (isFresh(slot)) return@withLock slot.entries
            val text = HttpKit.get(key, FETCH_TIMEOUT_S)
            val parsed = if (text.isNullOrBlank()) emptyList() else parse(text, key)
            slot.entries = parsed
            slot.at = System.currentTimeMillis()
            parsed
        }
    }

    private fun isFresh(slot: Slot): Boolean {
        if (slot.at <= 0L) return false
        val ttl = if (slot.entries.isEmpty()) EMPTY_TTL_MS else CACHE_TTL_MS
        return System.currentTimeMillis() - slot.at < ttl
    }

    private fun List<Entry>.page(page: Int): List<Entry> {
        val index = page.coerceAtLeast(1)
        val from = (index - 1) * PAGE_SIZE
        if (from >= size) return emptyList()
        return subList(from, minOf(from + PAGE_SIZE, size))
    }
}
