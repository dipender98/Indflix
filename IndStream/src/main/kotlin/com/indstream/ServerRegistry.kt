package com.indstream

import kotlin.math.min

/** FILE: ServerRegistry. kt - the server registry + health tracking (WHICH servers exist and how they are doing). whether a server is keyed by TMDB. */
enum class ServerIdType { TMDB, IMDB }

/** Spec for a single embed/API server in the federated registry. */
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
    // No quality cap: servers emit whatever ladder the upstream carries (up to 4K+).
    val timeoutSec: Int = 10,
    /** Languages this host itself declares (canonical names. INDIAN_DUB_LANGUAGES]: "Hindi", "Tamil", "Telugu", …). */
    val declaredLanguages: Set<String> = emptySet(),
)

/** Federated server registry - seeded with). */
object ServerFarm {

    val allServers: List<ServerSpec> = listOf(
        // Multi-language expansion (movies + TV).
// NHD RE-ENABLED below: TV.
        ServerSpec(
            id = "vidlink", name = "VidLink",
            idType = ServerIdType.TMDB,
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
        // VidRock (registry, ): TMDB-keyed JSON API with per-server map (Nova/Atlas/Luna/Orion/Astra). URLs are AES-GCM.
// encrypted - decrypted locally in.
        ServerSpec(
            id = "vidrock", name = "VidRock",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidrock.to/api/movie/{id}/",
            tvUrl = "https://vidrock.to/api/tv/{id}/{season}/{episode}/",
            isJsonApi = true, referer = "https://vidrock.to/",
            hasSubtitles = false, timeoutSec = 12,
        ),
        // MovieBox app API: bearer token first, then a title-keyed POST search.
        ServerSpec(
            id = "moviebox", name = "MovieBox",
            idType = ServerIdType.TMDB,
            movieUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox",
            tvUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox",
            isJsonApi = true, referer = "https://fmoviesunblocked.net/",
            // timeoutSec 30→40 (user report: MovieBox absent on the same
            // device/network): slow responses were cut off early.
            hasSubtitles = true, timeoutSec = 55,
        ),
        // PrimeSrc (primesrc. me, ): IMDB-keyed JSON API. GET /api/v1/s?imdb={id}&type=movie|tv returns servers ({name, key.
// file_name, audio_language.
        ServerSpec(
            id = "vidnest", name = "VidNest",
            idType = ServerIdType.TMDB,
            movieUrl = "https://new.vidnest.fun/{server}/movie/{id}",
            tvUrl = "https://new.vidnest.fun/{server}/tv/{id}/{season}/{episode}",
            isJsonApi = true, referer = "https://vidnest.fun/",
            hasSubtitles = false, timeoutSec = 20,
        ),
        // Vidup (vidup. to, ): TMDB-keyed (accepts IMDB too via the URL path). 4-step enc-dec. app pipeline: 1) page → "en".
// "token" → enc-dec.
        ServerSpec(
            id = "vidup", name = "VidUp",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidup.to/movie/{id}",
            tvUrl = "https://vidup.to/tv/{id}/{season}/{episode}",
            referer = "https://vidup.to/",
            hasSubtitles = true, timeoutSec = 20,
        ),
        // Vidcore (vidcore. io, ): twin of Vidup - same enc-dec. app pipeline (enc-vidcore/dec-vidcore), same moon. peakstorm.
// top HLS backend, same.
        ServerSpec(
            id = "vidcore", name = "VidCore",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidcore.io/movie/{id}",
            tvUrl = "https://vidcore.io/tv/{id}/{season}/{episode}",
            referer = "https://vidcore.io/",
            hasSubtitles = true, timeoutSec = 20,
        ),
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
        ServerSpec(
            id = "nhd", name = "NHD",
            idType = ServerIdType.TMDB,
            movieUrl = "https://nhdapi.com/movie/{id}",
            tvUrl = "https://nhdapi.com/tv/{id}/{season}/{episode}",
            referer = "https://nhdapi.com/",
            hasSubtitles = true, timeoutSec = 20,
        ),
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
        // ZXCStreams (portal-discovered backend): TMDB-keyed.
        // Portal (zxcstream.xyz/zxcprime.xyz) redirect -> base; sha512 token POST.
        // 4 sub-servers (Icarus/Berkas/Orion/Athena) queried in parallel.
        ServerSpec(
            id = "zxcstreams", name = "ZXCStreams",
            idType = ServerIdType.TMDB,
            movieUrl = "https://zxcstream.xyz/player/movie/{id}",
            tvUrl = "https://zxcstream.xyz/player/tv/{id}/{season}/{episode}",
            referer = "https://zxcstream.xyz/",
            // Discovery + token + 4 servers + master-measure pass.
            hasSubtitles = false, timeoutSec = 30,
        ),

        // VidAPI (vaplayer.ru embed): TMDB-keyed embed page.
        // Generic pipeline (unwrap/harvest/extractor registry), no custom crypto.
        ServerSpec(
            id = "vidapi", name = "VidAPI",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vaplayer.ru/embed/movie/{id}",
            tvUrl = "https://vaplayer.ru/embed/tv/{id}/{season}/{episode}",
            referer = "https://vaplayer.ru/",
            hasSubtitles = true, timeoutSec = 30,
        ),
        // 2Embed (embed page, servers Vsrc/Videm/Vcr): IMDB-keyed.
        // Generic pipeline handles the iframe chain.
        ServerSpec(
            id = "twoembed", name = "2Embed",
            idType = ServerIdType.IMDB,
            movieUrl = "https://2embed.cc/embed/{id}",
            tvUrl = "https://2embed.cc/embedtv/{id}&s={season}&e={episode}",
            referer = "https://2embed.cc/",
            hasSubtitles = true, timeoutSec = 30,
        ),
        // Fast TMDB-keyed embed batch: direct iframe chains, no crypto.
        // All eight run through the generic pipeline (fetch, unwrap, harvest, extractor registry).
        ServerSpec(
            id = "vidfast", name = "VidFast",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidfast.pro/movie/{id}?autoPlay=true",
            tvUrl = "https://vidfast.pro/tv/{id}/{season}/{episode}?autoPlay=true",
            referer = "https://vidfast.pro/",
            hasSubtitles = true, timeoutSec = 30,
        ),
        ServerSpec(
            id = "autoembed", name = "AutoEmbed",
            idType = ServerIdType.TMDB,
            movieUrl = "https://autoembed.co/movie/tmdb/{id}",
            tvUrl = "https://autoembed.co/tv/tmdb/{id}-{season}-{episode}",
            referer = "https://autoembed.co/",
            hasSubtitles = true, timeoutSec = 30,
        ),
        ServerSpec(
            id = "vidphantom", name = "VidPhantom",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidphantom.com/movie/{id}",
            tvUrl = "https://vidphantom.com/tv/{id}/{season}/{episode}",
            referer = "https://vidphantom.com/",
            hasSubtitles = true, timeoutSec = 30,
        ),
        ServerSpec(
            id = "vsembed", name = "VsEmbed",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vsembed.su/embed/movie/{id}",
            tvUrl = "https://vsembed.su/embed/tv/{id}/{season}/{episode}",
            referer = "https://vsembed.su/",
            hasSubtitles = false, timeoutSec = 30,
        ),
        ServerSpec(
            id = "twoembed-skin", name = "2EmbedSkin",
            idType = ServerIdType.TMDB,
            movieUrl = "https://www.2embed.skin/embed/{id}",
            tvUrl = "https://www.2embed.skin/embedtv/{id}&s={season}&e={episode}",
            referer = "https://www.2embed.skin/",
            hasSubtitles = true, timeoutSec = 30,
        ),
        ServerSpec(
            id = "vidsrc-to", name = "VidsrcTo",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidsrc.to/embed/movie/{id}",
            tvUrl = "https://vidsrc.to/embed/tv/{id}/{season}/{episode}",
            referer = "https://vidsrc.to/",
            hasSubtitles = false, timeoutSec = 30,
        ),
        ServerSpec(
            id = "vidsrcme", name = "VidsrcMe",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidsrcme.su/embed/movie/{id}",
            tvUrl = "https://vidsrcme.su/embed/tv/{id}/{season}/{episode}",
            referer = "https://vidsrcme.su/",
            hasSubtitles = false, timeoutSec = 30,
        ),
        ServerSpec(
            id = "nontongo", name = "Nontongo",
            idType = ServerIdType.TMDB,
            movieUrl = "https://www.nontongo.win/embed/movie/{id}",
            tvUrl = "https://www.nontongo.win/embed/tv/{id}/{season}/{episode}",
            referer = "https://www.nontongo.win/",
            hasSubtitles = true, timeoutSec = 30,
        ),
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
            movieUrl = "https://api.streamflix.app/data.json",
            tvUrl = "https://api.streamflix.app/data.json",
            hasSubtitles = false, timeoutSec = 25,
        ),
        // Fast global batch: TMDB-keyed embeds, generic pipeline, 30s kill.
        ServerSpec(
            id = "vidsrc-pm", name = "VidsrcPm",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidsrc.pm/embed/movie/{id}",
            tvUrl = "https://vidsrc.pm/embed/tv/{id}/{season}/{episode}",
            referer = "https://vidsrc.pm/",
            hasSubtitles = true, timeoutSec = 30,
        ),
        ServerSpec(
            id = "rive", name = "Rive",
            idType = ServerIdType.TMDB,
            movieUrl = "https://www.rivestream.app/embed?type=movie&id={id}",
            tvUrl = "https://www.rivestream.app/embed?type=tv&id={id}&season={season}&episode={episode}",
            referer = "https://www.rivestream.app/",
            hasSubtitles = true, timeoutSec = 30,
        ),
        // Videasy multi-route API with local mvm1 decrypt. CDN carries HLS up to 2160p; hdmovie carries Hindi.
        // Chain entry is the per-title seed fetch; route queries need that seed plus title and media type.
        ServerSpec(
            id = "videasy", name = "Videasy",
            idType = ServerIdType.TMDB,
            movieUrl = "https://api.speedracelight.com/seed?mediaId={id}",
            tvUrl = "https://api.speedracelight.com/seed?mediaId={id}",
            referer = "https://player.videasy.net/",
            hasSubtitles = false, timeoutSec = 30,
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
        val h = healthMap[serverId] ?: return true // unknown = healthy (first probe).
        if (h.failCount >= MAX_CONSECUTIVE_FAILURES) {
            if (System.currentTimeMillis() < h.trippedUntil) return false
            // Trip expired - allow re-probe, reset fail count.
            synchronized(lock) {
                healthMap[serverId] = h.copy(failCount = 0)
            }
            return true
        }
        return true
    }

    /** Clear only circuit-breaker trip state. A farm-wide trip must never lock the plugin out for the full trip window. (StreamEngine re-probes once per. */
    fun resetTrips() {
        synchronized(lock) {
            healthMap.replaceAll { _, h -> h.copy(failCount = 0, trippedUntil = 0L) }
        }
    }
}



