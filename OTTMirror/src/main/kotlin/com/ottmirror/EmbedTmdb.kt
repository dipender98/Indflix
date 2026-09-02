package com.ottmirror

import com.lagradost.cloudstream3.app

/**
 * Sessionless TMDB-keyed stream resolution via net27.cc /api/embed-tmdb
 * (live-probed Aug 2026, and the CNC Verse extension's production fallback
 * since PR #24):
 *
 *   GET https://net27.cc/api/embed-tmdb/{tmdbId}            (movie)
 *   GET https://net27.cc/api/embed-tmdb/{tmdbId}?type=tv&s=&e=   (episode)
 *
 * Answers with direct signed MP4 renditions on the hakunaymatata CDN —
 * NO t_hash_t, NO verify, NO Ott header, and a completely different
 * backend from the net7x.cc per-IP limiter behind "Too many request in
 * short..". Uncovered titles answer {"ok":true,"noSource":true,...}.
 *
 * This is the PRIMARY playback path: one request, no adaptive playlist,
 * no session, no net7x traffic. The signed links live ~8 h (exp), well
 * inside the LinkCache replay window.
 */
internal object EmbedTmdb {

    data class Resolved(
        val streams: List<EmbedTmdbStream>,
        val subs: List<EmbedTmdbCaption>,
    ) {
        val bestQuality: Int get() = streams.filter { it.resolution in 1..1080 }
            .maxOfOrNull { it.resolution } ?: 0
    }

    private const val NEG_TTL_MS = 30 * 60 * 1000L   // noSource — don't re-ask per episode tap
    private const val POS_TTL_MS = 10 * 60 * 1000L   // raw result — absorb player refreshes
    private const val MAX_SIZE = 128

    private val noSourceUntil = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > MAX_SIZE
    }
    private val resultCache = object : LinkedHashMap<String, Pair<EmbedTmdbResult, Long>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<EmbedTmdbResult, Long>>) = size > MAX_SIZE
    }

    private fun key(tmdbId: String, season: Int?, episode: Int?) = "$tmdbId|$season|$episode"

    fun embedHeaders(): Map<String, String> = mapOf(
        "User-Agent" to MOBILE_UA,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to EMBED_REFERER,
    )

    /**
     * Resolve the single best stream for a TMDB id (with season/episode for
     * series). Returns null when the title is not covered or the endpoint is
     * unreachable — callers fall through to the next path. NEVER throws for
     * endpoint-level failures.
     */
    suspend fun resolve(tmdbId: String, season: Int?, episode: Int?): Resolved? {
        if (tmdbId.isBlank() || tmdbId == "null") return null
        val k = key(tmdbId, season, episode)
        val now = System.currentTimeMillis()

        synchronized(noSourceUntil) {
            val until = noSourceUntil[k]
            if (until != null && now < until) return null
            if (until != null) noSourceUntil.remove(k)
        }
        synchronized(resultCache) {
            val hit = resultCache[k]
            if (hit != null) {
                if (now < hit.second) return toResolved(hit.first)
                resultCache.remove(k)
            }
        }

        val host = EMBED_HOSTS.firstOrNull() ?: return null
        val url = if (season != null && episode != null) {
            "$host/api/embed-tmdb/$tmdbId?type=tv&s=$season&e=$episode"
        } else {
            "$host/api/embed-tmdb/$tmdbId?type=movie"
        }

        HostThrottler.gate()
        val resp = softCatch { app.get(url, headers = embedHeaders(), timeout = 10) } ?: return null
        // Classify for insurance: if net27 ever sprouts the same anti-abuse
        // body we must not parse it as JSON. Deliberately NOT recorded on the
        // net7x cooldown ladder — this is a different backend.
        when (NetMirrorGuard.classify(resp.code, resp.text)) {
            NetMirrorGuard.Verdict.OK -> {}
            else -> return null
        }

        val parsed = NetMirrorParsers.parseEmbedTmdb(resp.text) ?: return null
        if (parsed.noSource || parsed.streams.isEmpty()) {
            synchronized(noSourceUntil) { noSourceUntil[k] = now + NEG_TTL_MS }
            return null
        }
        val resolved = toResolved(parsed) ?: return null
        synchronized(resultCache) { resultCache[k] = parsed to now + POS_TTL_MS }
        return resolved
    }

    private fun toResolved(parsed: EmbedTmdbResult): Resolved? {
        // Keep every rendition ≤ 1080p — the player gets a real quality
        // picker (one ExtractorLink per stream) and skips 2160p which the
        // hakunaymatata CDN almost never serves anyway. Order is preserved
        // (server-issued); the caller sorts for display.
        val streams = parsed.streams.filter { it.resolution in 1..1080 }
        if (streams.isEmpty()) return null
        return Resolved(streams, parsed.captions)
    }
}
