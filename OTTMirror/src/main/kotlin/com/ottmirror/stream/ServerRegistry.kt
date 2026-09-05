package com.ottmirror.stream

import kotlin.math.min

/**
 * FILE: ServerRegistry.kt — the OTTMirror server registry + health tracking
 * (WHICH servers exist and how they are doing).
 *
 *  - [ServerIdType]   whether a server is keyed by TMDB or IMDB id.
 *  - [ServerSpec]     one embed/API server: URL templates, referer, quality
 *                     cap, timeout. Add a new server = add a ServerSpec.
 *  - [ServerFarm]     the seeded registry of verified-live hosts (Sept 2026)
 *                     + URL builders.
 *  - [HealthMonitor]  per-server EMA success rate / latency / throughput
 *                     with a circuit breaker (3 strikes = 5 min trip; a fully
 *                     tripped farm is auto-reset by StreamEngine.resolve).
 *
 * Distinct from StreamEngine.kt: this is data + health state; the engine
 * holds the orchestration that consumes it. Third-party sources with their
 * own logic lives in sources/VidLinkSource.kt.
 */

/** Whether a server is keyed by a TMDB id or an IMDB id. */
enum class ServerIdType { TMDB, IMDB }

/**
 * Spec for a single embed/API server in the federated registry.
 */
data class ServerSpec(
    val id: String,
    val name: String,
    val idType: ServerIdType = ServerIdType.TMDB,
    val movieUrl: String,
    val tvUrl: String,
    val isJsonApi: Boolean = false,
    /** Referer this server requires for its embed/API/stream requests. */
    val referer: String? = null,
    val hasSubtitles: Boolean = false,
    val maxQuality: Int = 0,
    val timeoutSec: Int = 10,
    /** Marks a server whose entire host serves Hindi audio (Bollywood +
     *  Hindi-dubbed Hollywood). Ranked as Hindi (priority 4) even when the
     *  manifest declares no labelled `hi` track, so probe-based detection —
     *  which is unreliable for Hindi-only hosts — never buries it. */
    val hindi: Boolean = false,
)

/**
 * Federated server registry — seeded with verified-live hosts (Sept 2026).
 */
object ServerFarm {

    val allServers: List<ServerSpec> = listOf(
        // ── JSON API (deterministic, TMDB-keyed) ────────────────────────────
        // api.shows.st (111Movies) was removed Sept 2026: Cloudflare has
        // zone-blocked shows.st entirely ("Terms of Service violations" 403
        // for every request), so it failed resolution on every tap and only
        // burned a concurrent slot + tripped the breaker. Re-add a ServerSpec
        // here if the zone comes back or a mirror appears — the JSON API
        // branch in StreamEngine.resolveJsonApi still supports its shape:
        // {"source":{url, manifest (inline HLS master), qualities[]}, "subtitles":[]}.
        // ── Verified responding embeds (handled by CloudStream extractor registry / harvest) ──
        // VidLink: encrypted-token JSON API (XSalsa20-Poly1305, see VidlinkSource).
        // multiLang=1 returns per-quality MP4s (360→1080p) with Hindi dubs for
        // Indian titles. Token embeds the TMDB id + a +480s timestamp; only the
        // key in VidlinkSource.KEY_HEX ever needs rotating when the site updates.
        // RANK 1: Hindi-first priority — Hindi dubs + original Hindi audio.
        ServerSpec(
            id = "vidlink", name = "VidLink",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidlink.pro/api/b/movie/{id}?multiLang=1",
            tvUrl = "https://vidlink.pro/api/b/tv/{id}/{season}/{episode}?multiLang=1",
            isJsonApi = true, referer = "https://vidlink.pro/",
            hasSubtitles = true, maxQuality = 1080, timeoutSec = 15,
            hindi = true,
        ),
        // VaPlayer (CSX CineStream's top live server, verified Sept 2026):
        // IMDB-keyed JSON API returning 3 direct HLS master playlists (up to
        // 1920x800 ≈ 1080p). Zero crypto, zero captcha — fastest full pipeline.
        // Indian titles (Hanu-Man etc.) resolve in original audio.
        ServerSpec(
            id = "vaplayer", name = "VaPlayer",
            idType = ServerIdType.IMDB,
            movieUrl = "https://streamdata.vaplayer.ru/api.php?imdb={id}&type=movie",
            tvUrl = "https://streamdata.vaplayer.ru/api.php?imdb={id}&type=tv&season={season}&episode={episode}",
            isJsonApi = true, referer = "https://nextgencloudfabric.com/",
            hasSubtitles = true, maxQuality = 1080, timeoutSec = 12,
        ),
        // VidRock (CSX registry, verified Sept 2026): TMDB-keyed JSON API with
        // per-server map (Nova/Atlas/Luna/Orion/Astra). URLs are AES-GCM
        // encrypted — decrypted locally in StreamEngine (key is static, see
        // VIDROCK_KEY_HEX). `language` field marks Hindi when present.
        ServerSpec(
            id = "vidrock", name = "VidRock",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidrock.ru/api/movie/{id}/",
            tvUrl = "https://vidrock.ru/api/tv/{id}/{season}/{episode}/",
            isJsonApi = true, referer = "https://vidrock.ru/",
            hasSubtitles = false, maxQuality = 1080, timeoutSec = 12,
        ),
        // VidEm (2embed.cc's real player, reversed Sept 2026): IMDB-keyed.
        // Embed page carries a signed `Q` object with per-server refs; each ref
        // exchanges at api.php?a=play for a /_stream HLS URL (up to 1080p).
        // No key, no captcha, no Referer needed at playback.
        ServerSpec(
            id = "videm", name = "VidEm",
            idType = ServerIdType.IMDB,
            movieUrl = "https://videm.xyz/embed/movie/{id}",
            tvUrl = "https://videm.xyz/embed/tv/{id}/{season}/{episode}",
            isJsonApi = true, referer = "https://videm.xyz/",
            hasSubtitles = false, maxQuality = 1080, timeoutSec = 15,
        ),
        // NHD API: TMDB-keyed. The extraction API key is embedded per-page-load
        // (rotates), so the resolver fetches /movie/{id} first, extracts
        // API_KEY, then calls /api/movie/{id}?key=. Multi-language audio
        // switcher — Hindi for multi-dub / Indian titles.
        ServerSpec(
            id = "nhd", name = "NHD",
            idType = ServerIdType.TMDB,
            movieUrl = "https://nhdapi.com/movie/{id}",
            tvUrl = "https://nhdapi.com/tv/{id}/{season}/{episode}",
            referer = "https://nhdapi.com/",
            hasSubtitles = true, maxQuality = 1080, timeoutSec = 15,
        ),
    )

    fun buildMovieUrl(spec: ServerSpec, id: String): String =
        spec.movieUrl.replace("{id}", id)

    fun buildTvUrl(spec: ServerSpec, id: String, season: Int, episode: Int): String =
        spec.tvUrl
            .replace("{id}", id)
            .replace("{season}", season.toString())
            .replace("{episode}", episode.toString())
}

/**
 * Per-server health tracking with EMA success rate, latency, and circuit breaker.
 * Thread-safe (backed by concurrent maps).
 */
object HealthMonitor {

    private data class ServerHealth(
        val successCount: Int = 0,
        val failCount: Int = 0,
        val totalLatencyMs: Long = 0L,
        val measuredThroughput: Long = 0L, // KB/s
        val throughputCount: Int = 0,
        val trippedUntil: Long = 0L, // System.currentTimeMillis() when tripped
    )

    private val healthMap = java.util.concurrent.ConcurrentHashMap<String, ServerHealth>()
    private val lock = Any()

    /** Max consecutive failures before tripping a server. */
    private const val MAX_CONSECUTIVE_FAILURES = 3
    /** Trip duration in ms (5 min). Kept short: embed hosts flap, and a long trip
     *  window plus a farm-wide trip shows "no link found" for the entire duration. */
    private const val TRIP_DURATION_MS = 5 * 60 * 1000L
    /** EMA decay factor (0.0–1.0, higher = faster decay). */
    private const val ALPHA = 0.3

    /** Record a successful resolution from a server. */
    fun recordSuccess(serverId: String, latencyMs: Long, throughputKbps: Long? = null) {
        synchronized(lock) {
            val h = healthMap[serverId] ?: ServerHealth()
            healthMap[serverId] = h.copy(
                successCount = h.successCount + 1,
                totalLatencyMs = h.totalLatencyMs + latencyMs,
                measuredThroughput = if (throughputKbps != null) {
                    if (h.throughputCount == 0) throughputKbps
                    else ((1.0 - ALPHA) * h.measuredThroughput + ALPHA * throughputKbps).toLong()
                } else h.measuredThroughput,
                throughputCount = if (throughputKbps != null) h.throughputCount + 1 else h.throughputCount,
                // Reset fail count on success
                failCount = 0,
            )
        }
    }

    /** Record a failure from a server. */
    fun recordFailure(serverId: String) {
        synchronized(lock) {
            val h = healthMap[serverId] ?: ServerHealth()
            val newFailCount = h.failCount + 1
            healthMap[serverId] = h.copy(
                failCount = newFailCount,
                successCount = h.successCount,
                // Trip if consecutive failures exceed threshold
                trippedUntil = if (newFailCount >= MAX_CONSECUTIVE_FAILURES)
                    System.currentTimeMillis() + TRIP_DURATION_MS
                else h.trippedUntil,
            )
        }
    }

    /** Whether a server is currently healthy (not tripped and not failed too often). */
    fun isHealthy(serverId: String): Boolean {
        val h = healthMap[serverId] ?: return true // unknown = healthy (first probe)
        if (h.failCount >= MAX_CONSECUTIVE_FAILURES) {
            if (System.currentTimeMillis() < h.trippedUntil) return false
            // Trip expired — allow re-probe, reset fail count
            synchronized(lock) {
                healthMap[serverId] = h.copy(failCount = 0)
            }
            return true
        }
        return true
    }

    /** Get the average latency for a server, or null if no data. */
    fun averageLatency(serverId: String): Long? {
        val h = healthMap[serverId] ?: return null
        val total = h.successCount + h.failCount
        if (total == 0) return null
        return h.totalLatencyMs / total
    }

    /** Get the average measured throughput (KB/s) for a server, or null. */
    fun averageThroughput(serverId: String): Long? {
        val h = healthMap[serverId] ?: return null
        if (h.throughputCount == 0) return null
        return h.measuredThroughput
    }

    /** Get total attempts (success + failure) for a server. */
    fun totalAttempts(serverId: String): Int {
        val h = healthMap[serverId] ?: return 0
        return h.successCount + h.failCount
    }

    /** Reset health for a server (e.g., when re-probing). */
    fun reset(serverId: String) {
        synchronized(lock) { healthMap.remove(serverId) }
    }

    /** Reset all health. */
    fun resetAll() {
        synchronized(lock) { healthMap.clear() }
    }

    /** Clear only circuit-breaker trip state, preserving latency/throughput
     *  history so speedScore ordering survives a farm-wide re-probe. */
    fun resetTrips() {
        synchronized(lock) {
            healthMap.replaceAll { _, h -> h.copy(failCount = 0, trippedUntil = 0L) }
        }
    }

    /** EMA-based speed score: higher is better. Factors in throughput and latency. */
    fun speedScore(serverId: String): Double {
        val h = healthMap[serverId] ?: return 0.0
        val throughput = if (h.throughputCount > 0) h.measuredThroughput.toDouble() else 0.0
        val avgLat = if (h.successCount > 0) h.totalLatencyMs.toDouble() / h.successCount else 2000.0
        // Score = normalized throughput / latency. Higher throughput = better, lower latency = better.
        // Base: 1.0 for unknown; known servers get score relative to 5000 KB/s ideal.
        val throughputScore = if (throughput > 0) min(1.0, throughput / 5000.0) else 0.5
        val latencyScore = if (avgLat > 0) min(1.0, 1000.0 / avgLat) else 0.5
        return throughputScore * 0.7 + latencyScore * 0.3
    }
}



