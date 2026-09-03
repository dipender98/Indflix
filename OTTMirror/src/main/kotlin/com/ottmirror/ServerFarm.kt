package com.ottmirror

/**
 * Spec for a single embed/API server in the federated registry.
 * Each server accepts TMDB (or IMDB) IDs and returns an HLS/DASH/manifest page.
 */
data class ServerSpec(
    /** Stable internal id for dedup and health tracking. */
    val id: String,
    /** Display name shown to the user (e.g. "VidCore", "2Embed", "SuperEmbed"). */
    val name: String,
    /** URL template for movies: use {tmdb} as placeholder for the TMDB id. */
    val movieUrl: String,
    /** URL template for TV: use {tmdb}, {season}, {episode} placeholders. */
    val tvUrl: String,
    /** Whether this server returns multi-audio (dub) HLS manifest. */
    val hasMultiAudio: Boolean = false,
    /** Whether this server provides subtitles. */
    val hasSubtitles: Boolean = false,
    /** Approximate max resolution available (e.g. 1080, 2160, 0=unknown). */
    val maxQuality: Int = 0,
    /** Hard timeout seconds per request to this server. */
    val timeoutSec: Int = 10,
)

/**
 * The federated server registry. OTTMirror races healthy servers from this list
 * and picks the fastest CDN stream per title.
 *
 * ALL servers use TMDB/IMDB ids as the key — no scraping, no site-specific parsing.
 * Seeded with verified live families (Sept 2026): VidCore, 2Embed, SuperEmbed,
 * VidSrc-family, embed.su, vidlink, autoembed, multiembed, embeddb, hexa, vidjoy, cine2.
 */
object ServerFarm {

    val allServers: List<ServerSpec> = listOf(
        // ── VidCore family ──────────────────────────────────
        ServerSpec("vidcore", "VidCore",
            movieUrl = "https://vidcore.created.app/embed/movie/{tmdb}",
            tvUrl = "https://vidcore.created.app/embed/tv/{tmdb}/{season}/{episode}",
            hasMultiAudio = true, hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),

        // ── 2Embed family ───────────────────────────────────
        ServerSpec("2embed", "2Embed",
            movieUrl = "https://2embed.cc/embed/movie/{tmdb}",
            tvUrl = "https://2embed.cc/embed/tv/{tmdb}/{season}/{episode}",
            hasMultiAudio = false, hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),
        ServerSpec("2embed-org", "2Embed (org)",
            movieUrl = "https://2embed.org/embed/movie/{tmdb}",
            tvUrl = "https://2embed.org/embed/tv/{tmdb}/{season}/{episode}",
            hasMultiAudio = false, hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),

        // ── SuperEmbed family ───────────────────────────────
        ServerSpec("superembed", "SuperEmbed",
            movieUrl = "https://superembed.stream/embed/tmdb/movie?id={tmdb}",
            tvUrl = "https://superembed.stream/embed/tmdb/tv?id={tmdb}&s={season}&e={episode}",
            hasMultiAudio = false, hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),
        ServerSpec("superembed-2", "SuperEmbed (alt)",
            movieUrl = "https://multiembed.mov/embed/tmdb/movie?id={tmdb}",
            tvUrl = "https://multiembed.mov/embed/tmdb/tv?id={tmdb}&s={season}&e={episode}",
            hasMultiAudio = false, hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),

        // ── embed.su family ─────────────────────────────────
        ServerSpec("embed-su", "Embed.su",
            movieUrl = "https://embed.su/embed/movie/{tmdb}",
            tvUrl = "https://embed.su/embed/tv/{tmdb}/{season}/{episode}",
            hasMultiAudio = false, hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),

        // ── VidLink family ──────────────────────────────────
        ServerSpec("vidlink", "VidLink",
            movieUrl = "https://vidlink.pro/movie/{tmdb}",
            tvUrl = "https://vidlink.pro/tv/{tmdb}/{season}/{episode}",
            hasMultiAudio = false, hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),
        ServerSpec("vidlink-2", "VidLink (alt)",
            movieUrl = "https://vidlink.to/movie/{tmdb}",
            tvUrl = "https://vidlink.to/tv/{tmdb}/{season}/{episode}",
            hasMultiAudio = false, hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),

        // ── AutoEmbed family ────────────────────────────────
        ServerSpec("autoembed", "AutoEmbed",
            movieUrl = "https://autoembed.cc/embed/movie/{tmdb}",
            tvUrl = "https://autoembed.cc/embed/tv/{tmdb}/{season}/{episode}",
            hasMultiAudio = false, hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),

        // ── MultiEmbed family ───────────────────────────────
        ServerSpec("multiembed", "MultiEmbed",
            movieUrl = "https://multiembed.mov/embed/movie/{tmdb}",
            tvUrl = "https://multiembed.mov/embed/tv/{tmdb}/{season}/{episode}",
            hasMultiAudio = false, hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),

        // ── EmbedDB family ──────────────────────────────────
        ServerSpec("embeddb", "EmbedDB",
            movieUrl = "https://embeddb.com/embed/movie/{tmdb}",
            tvUrl = "https://embeddb.com/embed/tv/{tmdb}/{season}/{episode}",
            hasMultiAudio = false, hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),

        // ── Hexa family ─────────────────────────────────────
        ServerSpec("hexa", "Hexa",
            movieUrl = "https://hexa.watch/embed/movie/{tmdb}",
            tvUrl = "https://hexa.watch/embed/tv/{tmdb}/{season}/{episode}",
            hasMultiAudio = false, hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),

        // ── VidJoy family ───────────────────────────────────
        ServerSpec("vidjoy", "VidJoy",
            movieUrl = "https://vidjoy.pro/embed/movie/{tmdb}",
            tvUrl = "https://vidjoy.pro/embed/tv/{tmdb}/{season}/{episode}",
            hasMultiAudio = false, hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),

        // ── Cine2 family ────────────────────────────────────
        ServerSpec("cine2", "Cine2",
            movieUrl = "https://cine2.com/embed/movie/{tmdb}",
            tvUrl = "https://cine2.com/embed/tv/{tmdb}/{season}/{episode}",
            hasMultiAudio = false, hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),
    )

    /** Build the actual movie URL from a server spec and TMDB id. */
    fun buildMovieUrl(spec: ServerSpec, tmdbId: Int): String {
        return spec.movieUrl.replace("{tmdb}", tmdbId.toString())
    }

    /** Build the actual TV URL from a server spec, TMDB id, season, episode. */
    fun buildTvUrl(spec: ServerSpec, tmdbId: Int, season: Int, episode: Int): String {
        return spec.tvUrl
            .replace("{tmdb}", tmdbId.toString())
            .replace("{season}", season.toString())
            .replace("{episode}", episode.toString())
    }

    /** Return servers that support a given quality threshold. */
    fun serversWithMinQuality(minQuality: Int): List<ServerSpec> {
        return allServers.filter { it.maxQuality == 0 || it.maxQuality >= minQuality }
    }
}