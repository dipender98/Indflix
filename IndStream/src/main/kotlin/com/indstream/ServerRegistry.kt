package com.indstream

import kotlin.math.min

/** FILE: ServerRegistry. kt - the server registry + health tracking (WHICH servers exist and how they are doing). whether a server is keyed by TMDB. */
enum class ServerIdType { TMDB, IMDB }

/** Spec for a single embed/API server in the federated registry. */
data class ServerSpec(
    val id: String,
    val name: String,
    val idType: ServerIdType = ServerIdType.TMDB,
    /** Whether this server also accepts an IMDB id, by media type. */
    val supportsImdbMovie: Boolean = false,
    val supportsImdbTv: Boolean = false,
    /** Whether resolver outcomes update the health breaker. */
    val recordHealth: Boolean = true,
    val movieUrl: String,
    val tvUrl: String,
    val isJsonApi: Boolean = false,
    /** Referer this server requires for its embed/API/stream requests. */
    val referer: String? = null,
    val hasSubtitles: Boolean = false,
    // No quality cap: servers emit whatever ladder the upstream carries (up to 4K+).
    val timeoutSec: Int = 10,
    /** Languages this host itself declares (canonical names. INDIAN_DUB_LANGUAGES]: "Hindi", "Tamil", "Telugu", …). */
    val declaredLanguages: Set<String> = emptySet(),
) {
    fun supportsImdb(type: String): Boolean =
        if (type == "movie") supportsImdbMovie else supportsImdbTv
}

/** Federated server registry - seeded with). */
object ServerFarm {

    val allServers: List<ServerSpec> = listOf(
        // Multi-language expansion (movies + TV).
// NHD RE-ENABLED below: TV.
        ServerSpec(
            id = "vidlink", name = "VidLink",
            idType = ServerIdType.TMDB,
            supportsImdbMovie = true,
            movieUrl = "https://vidlink.pro/api/b/movie/{id}?multiLang=1",
            tvUrl = "https://vidlink.pro/api/b/tv/{id}/{season}/{episode}?multiLang=1",
            isJsonApi = true, referer = "https://vidlink.pro/",
            hasSubtitles = true, timeoutSec = 15,
            declaredLanguages = emptySet(),
        ),
        // VaPlayer ('s top live server, ): IMDB-keyed JSON API returning 3 direct HLS master playlists (up to 1920x800 ≈.
// 1080p). Zero crypto, zero captcha.
        ServerSpec(
            id = "vaplayer", name = "VaPlayer",
            idType = ServerIdType.IMDB,
            movieUrl = "https://streamdata.vaplayer.ru/api.php?imdb={id}&type=movie",
            tvUrl = "https://streamdata.vaplayer.ru/api.php?imdb={id}&type=tv&season={season}&episode={episode}",
            isJsonApi = true, referer = "https://nextgencloudfabric.com/",
            hasSubtitles = true, timeoutSec = 12,
        ),
        // VidRock (+ .net mirror) removed (v63): /api/movie/{id}/ now returns the
        // SPA index.html, not JSON — endpoint gone on both hosts.
        // MovieBox app API: bearer token first, then a title-keyed POST search.
        ServerSpec(
            id = "moviebox", name = "MovieBox",
            idType = ServerIdType.TMDB,
            movieUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox",
            tvUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox",
            isJsonApi = true, referer = "https://movieboxonline.net/",
            // timeoutSec raised to 65: 4-step chain (bearer→search→detail→download+play)
            // with 8s sub-timeouts needs headroom; 55 was cutting off slow upstreams.
            hasSubtitles = true, timeoutSec = 65,
        ),
        // VidNest removed (v63): every one of its 7 sub-servers returns HTTP 403.
        // VidUp removed (v63): its enc-dec.app/api/enc-vidup helper returns HTTP
        // 400 — the 4-step chain is structurally broken.
        // Vidcore (vidcore. io, ) removed (v48): upstream servers endpoint 404s on every play; the working VidCore is
        // vidcore-api (vidrack) below.
        // Allmovieland (allmovieland. art, ): DLE CMS with per-language HLS playlists - Hindi, Bengali, Tamil, Telugu.
// Pipeline: IMDB-keyed search → find.
        ServerSpec(
            id = "allmovieland", name = "Allmovieland",
            idType = ServerIdType.IMDB,
            movieUrl = "https://allmovieland.art/?do=search&subaction=search&story={id}",
            tvUrl = "https://allmovieland.art/?do=search&subaction=search&story={id}",
            referer = "https://allmovieland.art/",
            hasSubtitles = false, timeoutSec = 60,
            declaredLanguages = setOf("Hindi", "Tamil", "Telugu", "Bengali"),
        ),
        // MP4Hydra (mp4hydra. org): title-slug keyed multipart POST to /info2 returns per-quality HLS sources across Beta.
// servers with embedded subtitle.
// NHD removed (v46): upstream movie pipeline 404s, TV path untested without movies.
        // (net27. cc - Netflix-grade OTT, ): TMDB-keyed JSON API. Default GET /api/embed-tmdb/{tmdb} → {ok, streams: } =.
// Netflix-ladder MP4 360→1080p.
        ServerSpec(
            id = "netmirror", name = "NetMirror",
            idType = ServerIdType.TMDB,
            movieUrl = "https://net27.cc/api/embed-tmdb/{id}",
            tvUrl = "https://net27.cc/api/embed-tmdb/{id}?type=tv&se={season}&ep={episode}",
            isJsonApi = true, referer = "https://net27.cc/",
            hasSubtitles = true, timeoutSec = 50,
            declaredLanguages = emptySet(),
        ),
        // ZXCStreams removed (v46): sub-server hosts NXDOMAIN, portal chain dead.
        // VidAPI removed (v46): vaplayer.ru DNS dead.
        // 2Embed removed (v63): player iframes are lazy data-src (src is
        // "about:blank"), so the generic pipeline lands 0 streams.
        // Fast TMDB-keyed embed batch removed (v63): VidFast, AutoEmbed,
        // VidPhantom, VsEmbed, 2EmbedSkin — all land 0 streams (players load
        // via JS; the generic pipeline cannot follow them).
        // VidsrcTo removed (v46): Cloudflare bot challenge on embed pages.
        // VidsrcMe removed (v63): host unreachable — 15s connect timeout on
        // both the movie and TV endpoints.
        // Nontongo removed (v46): Cloudflare bot challenge on embed pages.
        // Hindi/Indian-language expansion: per-language audio tracks (TMDB title-keyed API).
        // Chain entry is the security-key fetch; search/detail/video are POSTs needing that key.
        ServerSpec(
            id = "castletv", name = "CastleTV",
            idType = ServerIdType.TMDB,
            movieUrl = "https://api.hlowb.com/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US",
            tvUrl = "https://api.hlowb.com/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US",
            referer = "https://api.hlowb.com/",
            hasSubtitles = false, timeoutSec = 30,
            declaredLanguages = setOf("Hindi", "Tamil", "Telugu"),
        ),
        // TMDB-keyed catalog match with direct file links.
        ServerSpec(
            id = "streamflix", name = "StreamFlix",
            idType = ServerIdType.TMDB,
            supportsImdbMovie = true, supportsImdbTv = true,
            movieUrl = "https://api.streamflix.app/data.json",
            tvUrl = "https://api.streamflix.app/data.json",
            hasSubtitles = false, timeoutSec = 25,
        ),
        // VidsrcPm + Rive removed (v63): generic-pipeline embeds, 0 streams.
        // OneTouchTV (api3.devcorp.me): title search, AES-256-CBC envelope, per-episode HLS.
        // Chain entry is a title search; detail + episode calls need the matched id.
        ServerSpec(
            id = "onetouchtv", name = "OneTouchTV",
            idType = ServerIdType.TMDB,
            movieUrl = "https://api3.devcorp.me/vod/search?keyword={id}",
            tvUrl = "https://api3.devcorp.me/vod/search?keyword={id}",
            referer = "https://onetouchtv.xyz/",
            hasSubtitles = true, timeoutSec = 30,
        ),
        // VidCore (vidcore.org -> vidrack API): TMDB-keyed JSON API.
        // Returns multi-quality HLS sources (up to 4K) via peakstorm CDN.
        ServerSpec(
            id = "vidcore-api", name = "VidCore",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidrack.created.app/api/sources/movy?id={id}",
            tvUrl = "https://vidrack.created.app/api/sources/movy?id={id}&season={season}&episode={episode}",
            isJsonApi = true, referer = "https://www.vidcore.org/",
            hasSubtitles = false, timeoutSec = 20,
        ),
        // Bingr (bingr.one): TMDB-keyed multi-CDN API. Each srv id is an
        // independent HLS/DASH ladder; the resolver cascades the live set and
        // merges whatever answers, so one dead srv never sinks the row.
        ServerSpec(
            id = "bingr", name = "Bingr",
            idType = ServerIdType.TMDB,
            movieUrl = "https://api.bingr.one/api/stream",
            tvUrl = "https://api.bingr.one/api/stream",
            referer = "https://bingr.one/",
            hasSubtitles = false, timeoutSec = 30,
        ),
        // ShowBox (showbox.media -> febbox): IMDB-keyed search then a token-gated
        // quality list. Stays dormant (clean miss) until a FebBox token is saved.
        ServerSpec(
            id = "showbox", name = "ShowBox",
            idType = ServerIdType.IMDB,
            movieUrl = "https://showbox.media/search?keyword={id}",
            tvUrl = "https://showbox.media/search?keyword={id}",
            referer = "https://showbox.media/",
            hasSubtitles = false, timeoutSec = 40,
        ),
        // VidsrcTo removed (v63): generic-pipeline embed, 0 streams on every title.
        // MyFlixerHindi removed (v63): status API answers "This movie hasn't in our
        // database" for every title.
        // PrimeSrc removed (v63): host answers 200 but every entry is an external
        // file-host embed the pipeline cannot resolve (0 streams).
        // SSR embed page bakes its direct HLS ladder (and per-dub variants) into the play config.
        ServerSpec(
            id = "vidrift", name = "Vidrift",
            idType = ServerIdType.TMDB,
            movieUrl = "https://embed.vidrift.net/embed/movie/{id}",
            tvUrl = "https://embed.vidrift.net/embed/tv/{id}/{season}/{episode}",
            referer = "https://embed.vidrift.net/",
            hasSubtitles = true, timeoutSec = 25,
        ),
        // Browserless JSON API: a salted signature key authorizes a per-service source call.
        ServerSpec(
            id = "rivestream", name = "Rivestream",
            idType = ServerIdType.TMDB,
            movieUrl = "https://www.rivestream.app/api/backendfetch?requestID=movieVideoProvider&id={id}",
            tvUrl = "https://www.rivestream.app/api/backendfetch?requestID=tvVideoProvider&id={id}&season={season}&episode={episode}",
            referer = "https://www.rivestream.app/",
            hasSubtitles = true, timeoutSec = 25,
        ),
        // Browserless JSON API: a short-lived seed keys an XOR-encrypted per-lane source payload.
        ServerSpec(
            id = "movy", name = "Movy",
            idType = ServerIdType.TMDB,
            movieUrl = "https://api.wecollege.net/seed?mediaId={id}",
            tvUrl = "https://api.wecollege.net/seed?mediaId={id}",
            referer = "https://www.movy.sx/",
            hasSubtitles = true, timeoutSec = 25,
        ),
        // Browserless JSON API: a static player key authorizes the direct HLS ladder.
        ServerSpec(
            id = "vidspark", name = "VidSpark",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidspark.to/api/vidora/v1/movie/{id}",
            tvUrl = "https://vidspark.to/api/vidora/v1/tv/{id}/{season}/{episode}",
            referer = "https://vidspark.to/",
            hasSubtitles = true, timeoutSec = 20,
        ),
        // Browserless JSON API: answers with a proxied DASH manifest URL.
        ServerSpec(
            id = "braflix", name = "Braflix",
            idType = ServerIdType.TMDB,
            supportsImdbMovie = true, supportsImdbTv = true,
            movieUrl = "https://braflix.dev/api/play/movie/{id}",
            tvUrl = "https://braflix.dev/api/play/tv/{id}/{season}/{episode}",
            referer = "https://braflix.dev/",
            hasSubtitles = false, timeoutSec = 20,
        ),
        // Hub-family file index (downloadable direct files): title-keyed search,
        // post pages carry per-file size/language badges; downloads ride a
        // rotator gateway to hubcloud/hubdrive token pages (decoded client-side).
        ServerSpec(
            id = "4khdhub", name = "4KHDHub",
            idType = ServerIdType.TMDB,
            movieUrl = "https://4khdhub.one/?s={id}",
            tvUrl = "https://4khdhub.one/?s={id}",
            referer = "https://4khdhub.one/",
            hasSubtitles = false, timeoutSec = 40,
        ),
        // VegaMovies-family fast R2/FSL server: title-keyed search, gateway
        // expansion, R2 direct links ("FSL" host tags). Rogmovies rides along.
        ServerSpec(
            id = "fsl", name = "FSL",
            idType = ServerIdType.TMDB,
            movieUrl = "https://new1.vegamovies.lease/",
            tvUrl = "https://new1.vegamovies.lease/",
            referer = "https://new1.vegamovies.lease/",
            hasSubtitles = false, timeoutSec = 60,
            declaredLanguages = setOf("Hindi", "Tamil", "Telugu"),
        ),
        // Id-keyed index: /search/{tmdb|imdb-id}/ answers directly, so the
        // resolver needs no title lookup. Domains churn - the resolver self-heals.
        ServerSpec(
            id = "hdmovie2", name = "HDMovie2",
            idType = ServerIdType.TMDB,
            supportsImdbMovie = true, supportsImdbTv = true,
            movieUrl = "https://hdmovie2a.live/search/{id}/",
            tvUrl = "https://hdmovie2a.live/search/{id}",
            referer = "https://hdmovie2a.live/",
            hasSubtitles = false, timeoutSec = 35,
        ),
        // OlaMovies file index: parking-page domain self-heal, then title search
        // and download-box harvest. Stays dormant (clean miss) when parked.
        ServerSpec(
            id = "olamovies", name = "OlaMovies",
            idType = ServerIdType.TMDB,
            movieUrl = "https://olamovies.rent/",
            tvUrl = "https://olamovies.rent/",
            referer = "https://olamovies.rent/",
            hasSubtitles = false, timeoutSec = 35,
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

/** Per-server health tracking: consecutive-failure circuit breaker. Thread-safe (backed by concurrent maps). ), so the. only health signal left is. */
object HealthMonitor {

    private data class ServerHealth(
        val failCount: Int = 0,
        val trippedUntil: Long = 0L, // System. currentTimeMillis() when tripped.
    )

    private val healthMap = java.util.concurrent.ConcurrentHashMap<String, ServerHealth>()
    private val lock = Any()

    /** Max consecutive failures before tripping a server. 5 (raised, ): empty-success / library-miss hosts (MovieBox. Allmovieland) must not vanish after. */
    private const val MAX_CONSECUTIVE_FAILURES = 5
    /** Trip duration in ms (5 min). Kept short: embed hosts flap, and a long trip window plus a farm-wide trip shows "no. link found" for the entire duration. */
    private const val TRIP_DURATION_MS = 5 * 60 * 1000L

    /** Record a successful resolution. */
    fun recordSuccess(serverId: String) {
        synchronized(lock) {
            val h = healthMap[serverId] ?: ServerHealth()
            healthMap[serverId] = h.copy(failCount = 0)
        }
    }

    /** Record a failure. */
    fun recordFailure(serverId: String) {
        synchronized(lock) {
            val h = healthMap[serverId] ?: ServerHealth()
            val newFailCount = h.failCount + 1
            healthMap[serverId] = h.copy(
                failCount = newFailCount,
                // Trip if consecutive failures exceed threshold.
                trippedUntil = if (newFailCount >= MAX_CONSECUTIVE_FAILURES)
                    System.currentTimeMillis() + TRIP_DURATION_MS
                else h.trippedUntil,
            )
        }
    }

    /** Whether a server is currently healthy (not tripped and not failed too often). */
    fun isHealthy(serverId: String): Boolean {
        // Read and write under the same lock, so a concurrent failure is not overwritten by a stale copy.
        synchronized(lock) {
            val h = healthMap[serverId] ?: return true // unknown = healthy (first probe).
            if (h.failCount >= MAX_CONSECUTIVE_FAILURES) {
                if (System.currentTimeMillis() < h.trippedUntil) return false
                // Trip expired - allow re-probe, reset fail count.
                healthMap[serverId] = h.copy(failCount = 0)
            }
            return true
        }
    }

    /** Clear only circuit-breaker trip state. A farm-wide trip must never lock the plugin out for the full trip window. (StreamEngine re-probes once per. */
    fun resetTrips() {
        synchronized(lock) {
            healthMap.replaceAll { _, h -> h.copy(failCount = 0, trippedUntil = 0L) }
        }
    }
}



