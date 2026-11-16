package com.indstream

/**
 * Servers that were researched and intentionally NOT added to [ServerFarm].
 *
 * Working/live servers live in [ServerFarm.allServers] ([ServerRegistry.kt]).
 * Before proposing or adding a new server, check this list first: every entry
 * here was already evaluated and rejected for the recorded reason. Re-add only
 * with fresh live-probe evidence that the underlying issue is fixed.
 *
 * Single source of truth (machine/agent-readable): [RejectedServers.ids],
 * [RejectedServers.find], [RejectedServers.isRejected]. Do not maintain mirrors.
 */
data class RejectedServer(
    val id: String,
    val name: String,
    val category: String,
    val reason: String,
    val evidence: String? = null,
    val removedIn: String? = null,
    val recheck: String = "Do not re-add without fresh live-probe evidence.",
)

object RejectedServers {

    val all: List<RejectedServer> = listOf(
        RejectedServer("vixsrc", "VixSrc", "not-reachable",
            "Cloudflare bot challenge on the whole domain; cannot resolve streams client-side.",
            "CF challenge on embed pages", "v43"),
        RejectedServer("videasy", "Videasy", "not-reachable",
            "Upstream flappy: answers then empty windows; removed as dead source.",
            "Empty stream windows within 2 min", "v46/v62"),
        RejectedServer("vidrock", "VidRock", "removed-dead", "Removed in v63 (dead source).", removedIn = "v63"),
        RejectedServer("vidrock-net", "VidRock Net", "removed-dead", "Removed in v63 (dead source).", removedIn = "v63"),
        RejectedServer("vidfast", "VidFast", "removed-dead", "Embed batch removed in v63 (dead embed sources).", removedIn = "v63"),
        RejectedServer("autoembed", "AutoEmbed", "removed-dead", "Embed batch removed in v63 (dead embed sources).", removedIn = "v63"),
        RejectedServer("vidphantom", "VidPhantom", "removed-dead", "Embed batch removed in v63 (dead embed sources).", removedIn = "v63"),
        RejectedServer("vsembed", "VSEmbed", "removed-dead", "Embed batch removed in v63 (dead embed sources).", removedIn = "v63"),
        RejectedServer("twoembed", "2Embed", "removed-dead", "Embed batch removed in v63 (dead embed sources).", removedIn = "v63"),
        RejectedServer("twoembed-skin", "2Embed Skin", "removed-dead", "Embed batch removed in v63 (dead embed sources).", removedIn = "v63"),
        RejectedServer("vidsrcme", "VidSrc.me", "removed-dead", "Embed batch removed in v63 (dead embed sources).", removedIn = "v63"),
        RejectedServer("vidsrc-pm", "VidSrc.pm", "removed-dead", "Embed batch removed in v63 (dead embed sources).", removedIn = "v63"),
        RejectedServer("rive", "Rive", "removed-dead", "Embed batch removed in v63 (dead embed sources).", removedIn = "v63"),
        RejectedServer("vidsrcto", "VidSrc.to", "removed-dead", "Embed batch removed in v63 (dead embed sources).", removedIn = "v63"),
        RejectedServer("nhd", "NHD", "not-reachable",
            "Upstream movie pipeline returns 404; TV path untested without movies.",
            "playUrl 404 upstream", "v46"),
        RejectedServer("vidapi", "VidAPI", "not-reachable", "DNS dead (vaplayer.ru).", "NXDOMAIN", "v46"),
        RejectedServer("zxcstreams", "ZXCStreams", "not-reachable", "Sub-server hosts NXDOMAIN; portal chain dead.", "sub DNS NXDOMAIN", "v46"),
        RejectedServer("vidsrc-to", "VidSrc.to", "not-reachable", "Cloudflare bot challenge on embed pages.", "CF challenge", "v46"),
        RejectedServer("nontongo", "Nontongo", "not-reachable", "Cloudflare bot challenge on embed pages.", "CF challenge", "v46"),
        RejectedServer("braflix", "Braflix", "removed-dead", "Removed in v91 (dead source).", removedIn = "v91"),
        RejectedServer("onetouchtv", "OneTouchTV", "removed-dead", "Dropped in v92.", removedIn = "v92"),
        RejectedServer("vidcore", "VidCore", "removed-dead", "Retired dead enc-dec upstream.", "enc-dec dead", "v48"),
        RejectedServer("vidcore-api", "VidCore API", "not-reachable", "Soft miss only; upstream dead.", "soft miss", "v48"),
        RejectedServer("castletv", "CastleTV", "not-suitable",
            "Free tier yields index_preview clips only, not playable streams.",
            "index_preview clips only", "v65"),
        RejectedServer("streamflix", "StreamFlix", "not-reachable", "Catalogue ok but all mirrors dead.", "all mirrors dead", "v65"),
        RejectedServer("dudefilms", "DudeFilms", "removed-dead", "Replaced by KatMovieHD in v93 (dead).", removedIn = "v93"),
        RejectedServer("multimovies", "Multimovies", "removed-dead", "Dropped in v93 (dead/blocked).", removedIn = "v93"),
        RejectedServer("rivestream", "Rivestream", "removed-dead", "Dropped in v93 (dead).", removedIn = "v93"),
        RejectedServer("katmoviehd", "KatMovieHD", "removed-dead", "Dropped in v94 (dead).", removedIn = "v94"),
        RejectedServer("vaplayer", "VaPlayer", "not-reachable", "API dead.", "API dead", "v68"),
        RejectedServer("videm", "VidEm", "not-suitable", "IMDB-keyed, no working resolver; not added.", removedIn = null),
        RejectedServer("myflixer-hindi", "MyFlixer Hindi", "removed-dead", "Not added (dead)."),
        RejectedServer("primesrc", "PrimeSrc", "removed-dead", "Not added (dead)."),
        RejectedServer("vidup", "VidUp", "removed-dead", "Removed in v63 (dead).", removedIn = "v63"),
        RejectedServer("allmovieland", "AllMovieLand", "not-suitable", "Unusable provider; must not be in farm.", "unusable provider", "v76"),
        RejectedServer("vidnest", "VidNest", "removed-dead", "Removed (dead upstream)."),
        RejectedServer("mp4hydra", "MP4Hydra", "removed-dead", "Removed (dead)."),
        RejectedServer("vidzee", "VidZee", "removed-dead", "Removed (dead)."),
        RejectedServer("streamprovider", "StreamProvider", "removed-dead", "Removed (dead)."),
        RejectedServer("8stream", "8Stream", "not-reachable",
            "Shared Vercel instance hard 429/403 rate-limit-gates all unauthenticated traffic; unfixable client-side.",
            "429/403 on Vercel", "v12"),
        RejectedServer("dahmermovies", "DahmerMovies", "removed-dead", "Dropped as dead (videasy/dahmer).", "dead", "v25"),
        RejectedServer("vidzy", "VidZy", "not-suitable", "Ad-tainted fstream host.", "ad-tainted", "v41"),
        RejectedServer("showbox", "ShowBox", "removed-dead", "Removed (dead)."),
        RejectedServer("vidspark", "VidSpark", "removed-dead", "Removed (dead)."),
        RejectedServer("hdmovie2", "HDMovie2", "removed-dead", "Removed (dead)."),
        RejectedServer("olamovies", "OlaMovies", "removed-dead", "Removed (dead)."),
        RejectedServer("ezvidapi", "EzVidApi", "not-reachable", "Cloudflare 502.", "CF 502", "v65"),
        RejectedServer("hdghartv", "HDGharTV", "not-suitable", "Dead; skipped per user.", "dead", "v65"),
        RejectedServer("newtv", "NewTV", "removed-dead", "Alternate feed dropped; netmirror moved to net79.", "alternate feed dropped", "v78"),
        RejectedServer("dahmer", "Dahmer", "removed-dead", "Alias of dahmermovies (dead)."),
        RejectedServer("vidking", "VidKing", "removed-dead", "Removed from Multimovies (dead).", removedIn = "v32"),
        RejectedServer("111movies", "111Movies", "removed-dead", "Blocked; dropped from Multimovies.", removedIn = "v43"),
        RejectedServer("vidsync", "VidSync", "removed-dead", "Dropped as dead from Multimovies.", removedIn = "v44"),

        // --- 2026 provider sweep (researched, not added) ---
        RejectedServer("vidsrc-cc", "Vidsrc.cc", "not-reachable", "HTTP 522 origin down.", "522", "2026-sweep"),
        RejectedServer("vidsrc-xyz", "Vidsrc.xyz", "not-reachable", "DNS failure.", "NXDOMAIN", "2026-sweep"),
        RejectedServer("vidsrc-in", "Vidsrc.in", "not-reachable", "DNS failure.", "NXDOMAIN", "2026-sweep"),
        RejectedServer("vidsrc-vip", "Vidsrc.vip", "not-reachable", "DNS failure.", "NXDOMAIN", "2026-sweep"),
        RejectedServer("vidsrc-pm-host", "Vidsrc.pm", "not-reachable", "HTTP 520.", "520", "2026-sweep"),
        RejectedServer("vidsrc-net-host", "Vidsrc.net", "not-reachable", "TLS failure / connection reset.", "TLS reset", "2026-sweep"),
        RejectedServer("vidsrc-wtf", "Vidsrc.wtf", "not-reachable", "Redirects to parked viduki.net.", "parked", "2026-sweep"),
        RejectedServer("viduki", "Viduki.net", "not-reachable", "Parked domain.", "parked", "2026-sweep"),
        RejectedServer("vidsrc-buzz", "Vidsrc.buzz", "not-reachable", "Connection reset from this region.", "reset", "2026-sweep"),
        RejectedServer("vidora-me", "Vidora.me", "not-reachable", "HTTP 503.", "503", "2026-sweep"),
        RejectedServer("vidora-io", "Vidora.io", "not-reachable", "Hostinger parked page.", "parked", "2026-sweep"),
        RejectedServer("hexa-family", "Hexa.* (watch/wf/run/sh/net/al)", "not-reachable", "DNS failure / 404 across all fronts.", "NXDOMAIN/404", "2026-sweep"),
        RejectedServer("hexa-su", "Hexa.su", "unverified", "Alive SPA but JS-only; not crackable via plain HTTP; 4K unverified.", "SPA", "2026-sweep"),
        RejectedServer("vidfast-pro", "Vidfast.pro", "not-suitable", "Next.js clone of vaplayer app; same 1080p pool, not a distinct provider.", "clone", "2026-sweep"),
        RejectedServer("vidfast-vc", "Vidfast.vc", "not-suitable", "Same as vidfast.pro.", "clone", "2026-sweep"),
        RejectedServer("filmpire-sc", "Filmpire.sc", "not-suitable", "Shell only.", "shell", "2026-sweep"),
        RejectedServer("flixpire-sc", "Flixpire.sc", "not-suitable", "Shell only.", "shell", "2026-sweep"),
        RejectedServer("movienova-sc", "Movienova.sc", "not-suitable", "Shell only.", "shell", "2026-sweep"),
        RejectedServer("movielysium-net", "Movielysium.net", "not-suitable", "Shell only.", "shell", "2026-sweep"),
        RejectedServer("moviepire-sc", "Moviepire.sc", "not-suitable", "Connection reset on root.", "reset", "2026-sweep"),
        RejectedServer("cinemaflix-one", "Cinemaflix.one", "not-suitable", "Redirects /embed to root.", "redirect", "2026-sweep"),
        RejectedServer("cinegram-tv", "Cinegram.tv", "not-suitable", "Redirects /embed to root.", "redirect", "2026-sweep"),
        RejectedServer("moviesapi-club", "MoviesAPI.club", "not-reachable", "DNS failure.", "NXDOMAIN", "2026-sweep"),
        RejectedServer("moviesapi-to", "MoviesAPI.to", "not-reachable", "403 Cloudflare.", "403 CF", "2026-sweep"),
        RejectedServer("vidapi-xyz", "VidAPI.xyz", "not-reachable", "404 on both shapes.", "404", "2026-sweep"),
        RejectedServer("frembed-com", "Frembed.com", "not-reachable", "Connect timeout.", "timeout", "2026-sweep"),
        RejectedServer("autoembed-cc", "AutoEmbed.cc", "not-reachable", "DNS failure.", "NXDOMAIN", "2026-sweep"),
        RejectedServer("multiembed-mov", "MultiEmbed.mov", "not-reachable", "403 Cloudflare aggregator.", "403 CF", "2026-sweep"),
        RejectedServer("vidjoy-pro", "VidJoy.pro", "not-suitable", "Ad-redirect junk.", "ad redirect", "2026-sweep"),
        RejectedServer("2embed-cc", "2Embed.cc", "not-suitable", "Aggregator linking to vidsrc.buzz.", "aggregator", "2026-sweep"),
        RejectedServer("vidzy-org", "VidZy.org", "unverified", "Unverified clone; not cracked.", "unverified", "2026-sweep"),
        RejectedServer("vidplay-cc", "VidPlay.cc", "not-reachable", "HTTP 403.", "403", "2026-sweep"),
        RejectedServer("vidplay-pro", "VidPlay.pro", "not-reachable", "HTTP 456.", "456", "2026-sweep"),
        RejectedServer("vidplay-site", "VidPlay.site", "not-reachable", "Parked (instantfwding).", "parked", "2026-sweep"),
        RejectedServer("vidplay-lol", "VidPlay.lol", "not-reachable", "Cloudflare verification wall.", "CF verify", "2026-sweep"),
        RejectedServer("vidplay-vip", "VidPlay.vip", "not-reachable", "DNS failure.", "NXDOMAIN", "2026-sweep"),
        RejectedServer("vidplay-one", "VidPlay.one", "not-reachable", "DNS failure.", "NXDOMAIN", "2026-sweep"),
        RejectedServer("vidplay-io", "VidPlay.io", "not-reachable", "TLS failure.", "TLS", "2026-sweep"),
        RejectedServer("vidbinge", "VidBinge", "not-reachable", "DNS/TLS failure across fronts.", "NXDOMAIN/TLS", "2026-sweep"),
        RejectedServer("vidsrc-sh", "Vidsrc.sh", "unverified",
            "Chain works end-to-end but 1080p max only (no real 4K); needs 7.2 KB wasm decryptor; CST captcha gate after ~15-20 rapid resolves; title-mismatch risk (cross-check data.title).",
            "1080p ceiling", "2026-sweep"),
    )

    val ids: Set<String> = all.map { it.id }.toSet()

    fun find(id: String): RejectedServer? = all.firstOrNull { it.id == id }

    fun isRejected(id: String): Boolean = id in ids
}
