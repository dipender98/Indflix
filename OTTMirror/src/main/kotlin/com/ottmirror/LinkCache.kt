package com.ottmirror

import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.concurrent.ConcurrentHashMap

internal object LinkCache {
    private data class Entry(val links: List<ExtractorLink>, val expiresAt: Long)
    // The resolved m3u8 URLs stay playable well beyond 5 min (the CDN serves
    // them with no session), so a longer TTL means more zero-traffic replays —
    // each cache hit is one less burst against the per-IP limiter. 60 min
    // keeps replays and episode browsing mostly cache-served without serving
    // stale links on the rare rotation.
    private const val TTL_MS = 60 * 60 * 1000L
    private const val MAX_SIZE = 64
    private val map = ConcurrentHashMap<String, Entry>()

    fun get(key: String): List<ExtractorLink>? {
        if (key.isBlank()) return null
        val e = map[key] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) { map.remove(key); return null }
        return e.links
    }

    fun put(key: String, links: List<ExtractorLink>) {
        if (key.isBlank() || links.isEmpty()) return
        if (map.size >= MAX_SIZE) map.entries.minByOrNull { it.value.expiresAt }?.key?.let { map.remove(it) }
        map[key] = Entry(links, System.currentTimeMillis() + TTL_MS)
    }
}