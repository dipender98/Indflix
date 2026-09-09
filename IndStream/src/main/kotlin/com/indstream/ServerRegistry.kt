package com.indstream

import kotlin.math.min

/**
 * FILE: ServerRegistry.kt — the IndStream server registry + health tracking
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
 * own logic lives in VidLinkSource.kt.
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
        // Indian titles AND the original/English track. It is NOT a Hindi-only
        // host, so it must NOT be flagged `hindi = true` — that blanket forces
        // every stream to label "Hindi" (StreamEngine.kt:168) even when the
        // played track is English. Leave hindi=false and let the per-master audio
        // probe (resolveVidlink legacy path) report the real language.
        ServerSpec(
            id = "vidlink", name = "VidLink",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidlink.pro/api/b/movie/{id}?multiLang=1",
            tvUrl = "https://vidlink.pro/api/b/tv/{id}/{season}/{episode}?multiLang=1",
            isJsonApi = true, referer = "https://vidlink.pro/",
            hasSubtitles = true, maxQuality = 1080, timeoutSec = 15,
            hindi = false,
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
        // DISABLED Sept 2026 (user report): links still resolve but every
        // playback errors out and the player hops to the next server — the
        // /_stream URLs die at fetch time. Re-enable alongside resolveVidem
        // when the upstream recovers.
        // ServerSpec(
        //     id = "videm", name = "VidEm",
        //     idType = ServerIdType.IMDB,
        //     movieUrl = "https://videm.xyz/embed/movie/{id}",
        //     tvUrl = "https://videm.xyz/embed/tv/{id}/{season}/{episode}",
        //     isJsonApi = true, referer = "https://videm.xyz/",
        //     hasSubtitles = false, maxQuality = 1080, timeoutSec = 15,
        // ),
        // Videasy "Fade" (Hindi) — TMDB-keyed, cipher-decrypted API (reversed
        // Sept 2026 from player.videasy.net chunk 8351; see VideasySource).
        // The decrypted /hdmovie route returns per-audio muxed HLS masters
        // labelled Hindi/English/Tamil/Telugu — the Hindi entry IS a Hindi
        // dub (verified: GOT S1E1, AOT S1E1, RRR). RANK 1 for Hindi.
        ServerSpec(
            id = "videasy-hindi", name = "Videasy Hindi",
            idType = ServerIdType.TMDB,
            movieUrl = "https://api.speedracelight.com/hdmovie/sources-with-title?tmdbId={id}",
            tvUrl = "https://api.speedracelight.com/hdmovie/sources-with-title?tmdbId={id}",
            isJsonApi = true, referer = "https://player.videasy.net/",
            hasSubtitles = false, maxQuality = 1080, timeoutSec = 15,
            hindi = true,
        ),
        // MyFlixer Hindi (hindi.myflixerapi.com): IMDB-keyed with the id in the
        // path (/embed/tt...). Whole host is Hindi audio.
        // DISABLED Sept 2026 (user report + code audit): the embed page is
        // captcha-walled and /ajax/get_stream_link is 404 — the server never
        // yields a stream, it just burns a concurrency slot and trips the
        // breaker. Re-enable alongside resolveMyFlixerHindi if it recovers.
        // ServerSpec(
        //     id = "myflixer-hindi", name = "MyFlixer Hindi",
        //     idType = ServerIdType.IMDB,
        //     movieUrl = "https://hindi.myflixerapi.com/embed/{id}",
        //     tvUrl = "https://hindi.myflixerapi.com/embed/{id}",
        //     referer = "https://hindi.myflixerapi.com/",
        //     hasSubtitles = false, maxQuality = 1080, timeoutSec = 15,
        //     hindi = true,
        // ),
        // MovieBox (h5-api.aoneroom.com app API, ported from CSX CineStream
        // Sept 2026): title-keyed JSON API — x-user bearer token from the app
        // pkgs endpoint, POST /subject/search by title, per-subject
        // /subject/download + /subject/play returning direct MP4/HLS + DASH
        // (up to 2160p 4K, vipLocked filtered) + captions. Title brackets mark
        // the audio ("Title [Hindi]" = Hindi dub) — audio priority is set per
        // stream in resolveMovieBox, so hindi=false here. Playback needs
        // Referer/Origin https://fmoviesunblocked.net/. Huge fast library,
        // zero captcha — the fastest Hindi-dub source in the farm.
        // Keyed TMDB (not IMDB): the resolver is purely title-keyed (it fetches
        // the title from TmdbService itself) and never reads the IMDB id, so
        // IMDB keying only added a serial wait on the id lookup before the
        // fastest Hindi source could even START resolving.
        ServerSpec(
            id = "moviebox", name = "MovieBox",
            idType = ServerIdType.TMDB,
            movieUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff",
            tvUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff",
            isJsonApi = true, referer = "https://fmoviesunblocked.net/",
            // timeoutSec 20→30 (Sept 2026): farm wraps resolveOne in
            // withTimeoutOrNull(spec.timeoutSec*1000); the tightened budgets
            // (bearer 6 + 2×search 7 + detail 6 + dl/play 6 ≈ 25s serial worst
            // incl. the token refresh + search retry) must fit under this kill
            // so a slow-but-alive resolve is not canned into a hard failure.
            hasSubtitles = true, maxQuality = 2160, timeoutSec = 30,
        ),
        // PrimeSrc (primesrc.me, verified live Sept 2026): IMDB-keyed JSON API.
        // GET /api/v1/s?imdb={id}&type=movie|tv[&season=&episode=] returns
        // servers[] ({name, key, file_name, audio_language, audio_type}); each
        // key exchanges at /api/v1/l?key={key} for a player URL resolved
        // through the standard pipeline (Filemoon/Voe/Streamtape/Mixdrop…).
        // audio_language "hi" marks Hindi dubs → priority 4 per stream.
        // DISABLED Sept 2026 (user report): API returns no streams any more.
        // Re-enable alongside resolvePrimeSrc if it recovers.
        // ServerSpec(
        //     id = "primesrc", name = "PrimeSrc",
        //     idType = ServerIdType.IMDB,
        //     movieUrl = "https://primesrc.me/api/v1/s?imdb={id}&type=movie",
        //     tvUrl = "https://primesrc.me/api/v1/s?imdb={id}&type=tv&season={season}&episode={episode}",
        //     isJsonApi = true, referer = "https://primesrc.me/",
        //     hasSubtitles = false, maxQuality = 1080, timeoutSec = 15,
        // ),
        // 8Stream (himanshu8443/8StreamApi): IMDB-keyed JSON API — /mediaInfo
        // returns per-language playlist entries (Hindi/English/Tamil/Telugu/
        // Bengali) whose {file,key} pair exchanges at POST /api/v1/getStream
        // for a DIRECT HLS master. Two calls per language, zero captcha —
        // bullet-train instant play. Movies only (no episode targeting).
        ServerSpec(
            id = "8stream", name = "8Stream",
            idType = ServerIdType.IMDB,
            movieUrl = "https://8-stream-api.vercel.app/api/v1/mediaInfo?id={id}",
            tvUrl = "https://8-stream-api.vercel.app/api/v1/mediaInfo?id={id}",
            isJsonApi = true, referer = "https://8-stream-api.vercel.app/",
            hasSubtitles = false, maxQuality = 1080, timeoutSec = 12,
        ),
        // Vidup (vidup.to, verified Sept 2026): TMDB-keyed (accepts IMDB too
        // via the URL path). 4-step enc-dec.app pipeline:
        // 1) page → "en":"token" → enc-dec.app/api/enc-vidup → {servers,stream,token}
        // 2) POST servers (X-CSRF-Token) → encrypted sub-server list
        // 3) enc-dec.app/api/dec-vidup → [{name,description,data}...] (Euro/CineX/Zenith/Premier)
        // 4) POST stream/{data} → dec-vidup → {url: moon.peakstorm.top/master.m3u8, tracks}
        // CDN measured 64ms latency, 4K@16Mbps ladder. "Premier" sub-server = 4K.
        // Hindi muxed on Bollywood titles (Dangal verified). No EXT-X-MEDIA audio
        // renditions — audio is muxed per-language. movies + tv.
        ServerSpec(
            id = "vidup", name = "VidUp",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidup.to/movie/{id}",
            tvUrl = "https://vidup.to/tv/{id}/{season}/{episode}",
            referer = "https://vidup.to/",
            hasSubtitles = true, maxQuality = 2160, timeoutSec = 20,
        ),
        // Vidcore (vidcore.io, verified Sept 2026): twin of Vidup — same
        // enc-dec.app pipeline (enc-vidcore/dec-vidcore), same moon.peakstorm.top
        // HLS backend, same sub-server names (Euro/CineX/Zenith/Premier).
        // Built-in redundancy: if one trips the breaker the other covers.
        ServerSpec(
            id = "vidcore", name = "VidCore",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidcore.io/movie/{id}",
            tvUrl = "https://vidcore.io/tv/{id}/{season}/{episode}",
            referer = "https://vidcore.io/",
            hasSubtitles = true, maxQuality = 2160, timeoutSec = 20,
        ),
        // Allmovieland (allmovieland.art, verified Sept 2026): DLE CMS
        // with per-language HLS playlists — Hindi, Bengali, Tamil, Telugu.
        // Pipeline: IMDB-keyed search → find card → page embeds
        // AwsIndStreamDomain (self-updating from player.js) + IMDB src →
        // /play/{imdb} → file+key → /playlist/{file}.txt (X-CSrf-Token) →
        // JSON [{title:"Hindi"|"Bengali"|..., file}] → /playlist/{lang.file}.txt
        // → direct m3u8 (360–1080p). Hindi-first, zero auth/captcha.
        // DOMAIN MOVE (verified 2026-09-08): allmovieland.one now 301-redirects
        // to allmovieland.art and the full pipeline works there; the resolver
        // (StreamEngine.allmovielandHosts) still carries a host-fallback list,
        // so this spec just pins the current live host.
        // timeoutSec 20→30 (Sept 2026): the farm kill at 20s canned
        // slow-but-alive 5-step chains → HealthMonitor marked each timeout a
        // hard failure → 5 strikes tripped the breaker → "sometimes doesn't
        // work" (user report). The tightened resolver budgets (8+6+6+6 serial
        // + per-lang fetches ≈ 26s worst) fit under this.
        ServerSpec(
            id = "allmovieland", name = "Allmovieland",
            idType = ServerIdType.IMDB,
            movieUrl = "https://allmovieland.art/?do=search&subaction=search&story={id}",
            tvUrl = "https://allmovieland.art/?do=search&subaction=search&story={id}",
            referer = "https://allmovieland.art/",
            hasSubtitles = false, maxQuality = 1080, timeoutSec = 30,
            hindi = true,
        ),
        // MP4Hydra (mp4hydra.org): title-slug keyed multipart POST to /info2
        // returns per-quality HLS sources across Beta servers with embedded
        // subtitle tracks. Hindi duals appear as rows labelled Hindi; the slug
        // falls back title+year → title. Referer required at playback.
        // DISABLED Sept 2026 (verified live: host serves a "Back soon"
        // maintenance page — no API response) — re-enable alongside
        // resolveMp4Hydra when the service returns.
        // ServerSpec(
        //     id = "mp4hydra", name = "MP4Hydra",
        //     idType = ServerIdType.TMDB,
        //     movieUrl = "https://mp4hydra.org/info2?v=8",
        //     tvUrl = "https://mp4hydra.org/info2?v=8",
        //     isJsonApi = true, referer = "https://mp4hydra.org/",
        //     hasSubtitles = true, maxQuality = 2160, timeoutSec = 12,
        // ),
        // VidZee (player.vidzee.wtf): TMDB-keyed multi-server JSON API —
        // sr=1..10 return per-language sources ({link,name,language}); some
        // links are AES-256-CBC tokens decrypted locally (static key, see
        // decodeVidZeeToken). Playback needs Referer core.vidzee.wtf.
        // DISABLED Sept 2026 (verified live: /api/server returns 404 for every
        // id) — re-enable alongside resolveVidZee when the API returns.
        // ServerSpec(
        //     id = "vidzee", name = "VidZee",
        //     idType = ServerIdType.TMDB,
        //     movieUrl = "https://player.vidzee.wtf/api/server?id={id}",
        //     tvUrl = "https://player.vidzee.wtf/api/server?id={id}&ss={season}&ep={episode}",
        //     isJsonApi = true, referer = "https://player.vidzee.wtf/",
        //     hasSubtitles = false, maxQuality = 2160, timeoutSec = 15,
        // ),
        // VixSrc (vixsrc.to): TMDB-keyed embed page whose window.masterPlaylist
        // {url, token, expires} assembles a signed adaptive HLS master. The
        // page URL is the playback Referer; wyzie.ru serves the subtitles.
        // DISABLED Sept 2026 (verified live: /movie/{id} → 403 Cloudflare
        // zone block) — re-enable alongside resolveVixSrc when it unblocks.
        // ServerSpec(
        //     id = "vixsrc", name = "VixSrc",
        //     idType = ServerIdType.TMDB,
        //     movieUrl = "https://vixsrc.to/movie/{id}",
        //     tvUrl = "https://vixsrc.to/tv/{id}/{season}/{episode}",
        //     isJsonApi = true, referer = "https://vixsrc.to/",
        //     hasSubtitles = true, maxQuality = 2160, timeoutSec = 12,
        // ),
        // StreamProvider (byteful): TMDB-keyed one-shot GET returning a direct
        // cached m3u8 (plain text or {url} JSON) — the simplest fast server in
        // the farm; audio is probed from the master itself.
        // DISABLED Sept 2026 (verified live: host returns 502 Bad Gateway) —
        // re-enable alongside resolveStreamProvider when it comes back.
        // ServerSpec(
        //     id = "streamprovider", name = "StreamProvider",
        //     idType = ServerIdType.TMDB,
        //     movieUrl = "https://streamprovider.byteful.me/?tmdbId={id}",
        //     tvUrl = "https://streamprovider.byteful.me/?tmdbId={id}&season={season}&episode={episode}",
        //     isJsonApi = true, referer = "https://streamprovider.byteful.me/",
        //     hasSubtitles = false, maxQuality = 1080, timeoutSec = 10,
        // ),
        // NHD API: TMDB-keyed. Temporarily disabled because the service is broken
        // (returns no streams and causes delays). Uncomment if it comes back.
        // ServerSpec(
        //     id = "nhd", name = "NHD",
        //     idType = ServerIdType.TMDB,
        //     movieUrl = "https://nhdapi.com/movie/{id}",
        //     tvUrl = "https://nhdapi.com/tv/{id}/{season}/{episode}",
        //     referer = "https://nhdapi.com/",
        //     hasSubtitles = true, maxQuality = 1080, timeoutSec = 15,
        // ),
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
 * Per-server health tracking: consecutive-failure circuit breaker.
 * Thread-safe (backed by concurrent maps). Sept 2026 rewrite: latency /
 * throughput bookkeeping was removed — the plugin no longer ranks servers
 * (user spec: "do not prioritize based on anything"), so the only health
 * signal left is whether a host still answers.
 */
object HealthMonitor {

    private data class ServerHealth(
        val failCount: Int = 0,
        val trippedUntil: Long = 0L, // System.currentTimeMillis() when tripped
    )

    private val healthMap = java.util.concurrent.ConcurrentHashMap<String, ServerHealth>()
    private val lock = Any()

    /** Max consecutive failures before tripping a server. 5 (raised from 3,
     *  user spec Sept 2026): empty-success / library-miss hosts (MovieBox,
     *  Allmovieland) must not vanish after a few unlucky taps — only real
     *  network/parse failures reach here now (clean misses bypass it). */
    private const val MAX_CONSECUTIVE_FAILURES = 5
    /** Trip duration in ms (5 min). Kept short: embed hosts flap, and a long trip
     *  window plus a farm-wide trip shows "no link found" for the entire duration. */
    private const val TRIP_DURATION_MS = 5 * 60 * 1000L

    /** Record a successful resolution from a server. */
    fun recordSuccess(serverId: String) {
        synchronized(lock) {
            val h = healthMap[serverId] ?: ServerHealth()
            healthMap[serverId] = h.copy(failCount = 0)
        }
    }

    /** Record a failure from a server. */
    fun recordFailure(serverId: String) {
        synchronized(lock) {
            val h = healthMap[serverId] ?: ServerHealth()
            val newFailCount = h.failCount + 1
            healthMap[serverId] = h.copy(
                failCount = newFailCount,
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

    /** Clear only circuit-breaker trip state. A farm-wide trip must never lock
     *  the plugin out for the full trip window (StreamEngine re-probes once
     *  per cooldown). */
    fun resetTrips() {
        synchronized(lock) {
            healthMap.replaceAll { _, h -> h.copy(failCount = 0, trippedUntil = 0L) }
        }
    }
}



