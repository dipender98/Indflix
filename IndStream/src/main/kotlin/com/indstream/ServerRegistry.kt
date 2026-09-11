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
    /** Languages this host itself declares (canonical names from
     *  [ManifestKit.INDIAN_DUB_LANGUAGES]: "Hindi", "Tamil", "Telugu", …).
     *  When the set holds EXACTLY ONE language, StreamEngine biases that
     *  host's blank-labelled streams to it (display only) — probe-based
     *  detection, which is unreliable for single-language hosts, never buries
     *  it. Multi-language hosts stay empty/unbiased: their resolvers label
     *  each entry from the API's own language fields (never guessed). */
    val declaredLanguages: Set<String> = emptySet(),
)

/**
 * Federated server registry — seeded with verified-live hosts (Sept 2026).
 */
object ServerFarm {

    val allServers: List<ServerSpec> = listOf(
        // ── 2026-09-11 multi-language expansion audit (Telugu/Tamil/Hindi dubs) ──
        // Live probe matrix (tools/server_probe.py): Inception + RRR (te) +
        // Jailer (ta) movies, GoT S1E1 + Family Man S1E1 (hi) TV.
        //  - NHD RE-ENABLED below: TV live on 2/2 probes; movie playUrl 404s
        //    upstream → clean-missed in resolveNhd (no breaker strike).
        //  - 8Stream re-probed: now hard-403 (astro HTML) — the public Vercel
        //    API is gone; still disabled. VidZee: /api/server still 404.
        //    MP4Hydra: still a maintenance page.
        //  - Brand-new embed hosts probed and REJECTED (no harvestable stream
        //    from the SPA shells): vidcore.org (≠ vidcore.io), vidphantom.com,
        //    player.embed-api.stream.
        // ── 2026-09-11 round-2 hunt (TMDB-Embed-API v1.3.0 provider ports;
        //    HDGharTV skipped — user-confirmed dead) ──
        //  - NETMIRROR ADDED below: net27.cc embed-tmdb JSON API, playable on
        //    6/6 matrix titles incl. RRR/Jailer/Family Man (Netflix-ladder MP4
        //    360→1080p + 13-language caption table incl. Bengali/Punjabi).
        //  - CastleTV REJECTED: answers with full per-language track tables
        //    (Tamil/Telugu/Hindi…) but the free tier only ever yields
        //    ~5-min `index_preview_*.m3u8` clips (permissionDenied on the
        //    rest) — nothing playable end-to-end.
        //  - OneTouchTV REJECTED: AES-256-CBC app API carries RRR/Jailer/
        //    Family Man, but each title yields ONE muxed "loklok" playlist
        //    with no declared audio language (subtitles only) — nothing to
        //    label per the never-guess rule.
        //  - StreamFlix REJECTED: data.json catalogue matches, every CDN
        //    mirror URL dead (no response at all). ezvidapi.com: CF 502.
        //  - Phase-B JS-provider ports NOT needed for coverage: Tamil/Telugu
        //    is already live in the farm (VidNest per-language streams on
        //    RRR/Jailer, Allmovieland 2–4 per-language playlists, MovieBox
        //    bracket-tagged audio) — all labelled by the generalized
        //    ManifestKit.audioLanguageLabel model.
        //  - VidLink: 200-with-`null`-body on titles without a multiLang
        //    source (RRR) — resolveVidlink clean-misses it (no breaker strike).
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
        // host, so it must NOT declare a single language — that blanket forces
        // every stream to label "Hindi" (StreamEngine bias) even when the
        // played track is English. Leave declaredLanguages empty and let the
        // per-master audio probe (resolveVidlink legacy path) report the real
        // language.
        ServerSpec(
            id = "vidlink", name = "VidLink",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidlink.pro/api/b/movie/{id}?multiLang=1",
            tvUrl = "https://vidlink.pro/api/b/tv/{id}/{season}/{episode}?multiLang=1",
            isJsonApi = true, referer = "https://vidlink.pro/",
            hasSubtitles = true, maxQuality = 1080, timeoutSec = 15,
            declaredLanguages = emptySet(),
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
        // Single declared language: blank-labelled streams bias to Hindi.
        ServerSpec(
            id = "videasy-hindi", name = "Videasy Hindi",
            idType = ServerIdType.TMDB,
            movieUrl = "https://api.speedracelight.com/hdmovie/sources-with-title?tmdbId={id}",
            tvUrl = "https://api.speedracelight.com/hdmovie/sources-with-title?tmdbId={id}",
            isJsonApi = true, referer = "https://player.videasy.net/",
            hasSubtitles = false, maxQuality = 1080, timeoutSec = 15,
            declaredLanguages = setOf("Hindi"),
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
        //     declaredLanguages = setOf("Hindi"),
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
            // timeoutSec 30→40 (Sept 2026 user report: MovieBox absent while
            // the reference CSX plugin works on the SAME device/network — CSX
            // sends no per-request timeout, so latency-parity budgets
            // (bearer 8s warm-cached + search 15s + auth-only 12s retry +
            // detail 8s + dl/play 8s parallel ≈ 39s serial worst) need this
            // kill to stay above the chain. 40s still LANDS LIVE: the LIVE_FILL
            // window is 90s, so a slow-alive MovieBox keeps streaming into an
            // open player instead of being silently canned.
            // 40→55 (F8 audit 2026-09-10): the AUTH-REJECTION retry adds a
            // second bearer fetch (8s cold) + 12s search — single-chain worst
            // 8+15+8+12+8+8 = 59s. The kill moved above the chain (55 covers
            // the realistic cold-start+reject path; resolveMovieBox also
            // SKIPS the retry once >25s has been spent), so a slow-but-alive
            // MovieBox is never canned into a breaker strike — every canned
            // timeout was one of the 5 strikes that hid the server for 5 min
            // (the "show up / vanish" flap in the live-window bug report).
            hasSubtitles = true, maxQuality = 2160, timeoutSec = 55,
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
        // for a DIRECT HLS master. Movies only (no episode targeting).
        // DISABLED Sept 2026 (verified live + user report): the shared public
        // Vercel deployment hard-rejects unauthenticated traffic — 429 "Too
        // Many Requests" / 403 on every probe across titles, so /mediaInfo
        // never answers and the two-call exchange can't even start. Nothing to
        // fix client-side (the upstream is rate-limit-gated for everyone).
        // Re-enable alongside resolve8Stream only if a self-hosted instance
        // or an open mirror appears.
        // ServerSpec(
        //     id = "8stream", name = "8Stream",
        //     idType = ServerIdType.IMDB,
        //     movieUrl = "https://8-stream-api.vercel.app/api/v1/mediaInfo?id={id}",
        //     tvUrl = "https://8-stream-api.vercel.app/api/v1/mediaInfo?id={id}",
        //     isJsonApi = true, referer = "https://8-stream-api.vercel.app/",
        //     hasSubtitles = false, maxQuality = 1080, timeoutSec = 12,
        // ),
        // VidNest (new.vidnest.fun aggregator, verified live Sept 2026):
        // TMDB-keyed fan-out across the host's own sub-servers. Each returns
        // {encrypted, data} where data is a CUSTOM-base64 blob (non-standard
        // alphabet, decoded locally in StreamEngine.decodeVidnestPayload) and
        // the per-server JSON shapes differ. moviebox/allmovies entries carry
        // lang/language fields ("Hindi", "Tamil", ...) — the only aggregator
        // backends besides MovieBox itself that LABEL language per stream.
        // Sub-servers flap (502s observed): failures inside the resolver are
        // soft-skipped per sub-server and an all-empty aggregate is a clean
        // miss, so the breaker never locks the whole host out for one bad tap.
        ServerSpec(
            id = "vidnest", name = "VidNest",
            idType = ServerIdType.TMDB,
            movieUrl = "https://new.vidnest.fun/{server}/movie/{id}",
            tvUrl = "https://new.vidnest.fun/{server}/tv/{id}/{season}/{episode}",
            isJsonApi = true, referer = "https://vidnest.fun/",
            hasSubtitles = false, maxQuality = 1080, timeoutSec = 20,
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
        // Multi-language host: NO single-language bias (never guessed) — the
        // resolver labels every playlist entry from its own title field.
        // DOMAIN MOVE (verified 2026-09-08): allmovieland.one now 301-redirects
        // to allmovieland.art and the full pipeline works there; the resolver
        // (StreamEngine.allmovielandHosts) still carries a host-fallback list,
        // so this spec just pins the current live host.
        // timeoutSec 20→30 (Sept 2026): the farm kill at 20s canned
        // slow-but-alive 5-step chains → HealthMonitor marked each timeout a
        // hard failure → 5 strikes tripped the breaker → "sometimes doesn't
        // work" (user report).
        // 30→60 (F8 budget-invariant audit 2026-09-10): the resolver now also
        // carries a title-fallback search and a two-player-src chain —
        // documented ceilings: search 8s ×2 hosts (IMDB pass) + 8s (title
        // fallback) + card 6s + 2 × (play 5s + playlist 6s) + language stage
        // 5s ≈ 55s worst, so the kill sits above the chain; a canned timeout
        // is a breaker strike, and strikes are the disappear/flap symptom the
        // user reported for this server.
        ServerSpec(
            id = "allmovieland", name = "Allmovieland",
            idType = ServerIdType.IMDB,
            movieUrl = "https://allmovieland.art/?do=search&subaction=search&story={id}",
            tvUrl = "https://allmovieland.art/?do=search&subaction=search&story={id}",
            referer = "https://allmovieland.art/",
            hasSubtitles = false, maxQuality = 1080, timeoutSec = 60,
            declaredLanguages = setOf("Hindi", "Tamil", "Telugu", "Bengali"),
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
        // NHD API (nhdapi.com): TMDB-keyed embed + page-keyed extraction API.
        // RE-ENABLED 2026-09-11 (multi-language expansion probe): the TV path
        // is live again — GoT S1E1 + The Family Man S1E1 both resolved playable
        // HLS (docs advertise a per-title audio-dub switcher + 20+ subs; the
        // resolver maps audioTracks[] labels per entry). The MOVIE pipeline is
        // still dead upstream (extraction succeeds, playUrl → 404 on every
        // header variant), so resolveNhd clean-misses movies: no breaker
        // strike, the working TV path never vanishes for movie taps. Re-visit
        // the guard when movie playback recovers.
        // Budget invariant: page 8s + extraction 10s = 18s ≤ kill 20s.
        ServerSpec(
            id = "nhd", name = "NHD",
            idType = ServerIdType.TMDB,
            movieUrl = "https://nhdapi.com/movie/{id}",
            tvUrl = "https://nhdapi.com/tv/{id}/{season}/{episode}",
            referer = "https://nhdapi.com/",
            hasSubtitles = true, maxQuality = 1080, timeoutSec = 20,
        ),
        // NetMirror (net27.cc — Netflix-grade OTT mirror captured by the
        // TMDB-Embed-API project, ported + verified live 2026-09-11):
        // TMDB-keyed JSON API. Default GET /api/embed-tmdb/{tmdb}
        // [?type=tv&se=&ep=] → {ok, streams:[{url, resolution, size}]} =
        // Netflix-ladder MP4 360→1080p (original audio, ONE row). The web
        // player's multi-language audio menu (user report "original only",
        // 2026-09-11 round 3) is /api/variants-tmdb/{type}/{id} →
        // variants[{dubSubjectId, language:"Hindi dub", detailPath}] — each
        // dub re-resolves embed-tmdb with ?dub=&dubdp= and yields a DISTINCT
        // file (verified: RRR Hindi/Telugu/Bengali + Family Man
        // Hindi/Tamil/Telugu, all different hashes) → one labelled row per
        // dub, exactly the official-OTT dub list; LANGUAGE POLICY (user spec
        // round-3): keep the ORIGINAL (any country — anime's Japanese, a
        // Korean title's Korean), ENGLISH, and every official INDIAN dub
        // (hi/ta/te/bn/ml/kn/mr/pa/gu); all other foreign dubs (ptbr/esla/
        // russian/…) are dropped. declaredLanguages stays EMPTY (labelling
        // is per-response, and the default ladder's audio is never guessed).
        // API Referer is net27.cc, playback Referer videodownloader.site (the
        // CDN 429s everything else, 206 with it — any UA incl. ExoPlayer);
        // the farm taps the API once (default‖variants) + dubs in parallel.
        // timeoutSec 50 (user spec round-3): fan-out worst ≈ 24s must fit the
        // kill with generous air — a canned resolve is a breaker strike, and a
        // dub fan-out over up to 12 subjects on a slow link needs the headroom
        // (allmovieland's flap lesson — kill sits ABOVE the chain, not at it).
        ServerSpec(
            id = "netmirror", name = "NetMirror",
            idType = ServerIdType.TMDB,
            movieUrl = "https://net27.cc/api/embed-tmdb/{id}",
            tvUrl = "https://net27.cc/api/embed-tmdb/{id}?type=tv&se={season}&ep={episode}",
            isJsonApi = true, referer = "https://net27.cc/",
            hasSubtitles = true, maxQuality = 1080, timeoutSec = 50,
            declaredLanguages = emptySet(),
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



