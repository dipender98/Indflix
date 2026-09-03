package com.ottmirror

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
)

/**
 * Federated server registry — seeded with verified-live hosts (Sept 2026).
 */
object ServerFarm {

    val allServers: List<ServerSpec> = listOf(
        // ── JSON API (deterministic, TMDB-keyed) — verified live Sept 2026 ──
        // api.shows.st returns {"source":{url, manifest (inline HLS master), qualities[]}, "subtitles":[]}.
        // ONLY TMDB ids yield a non-null source (IMDB ids give source:null).
        // source.url is a signed URL with no file extension; source.manifest is the
        // full master playlist text — StreamResolver parses it inline.
        ServerSpec(
            id = "shows.st", name = "111Movies",
            idType = ServerIdType.TMDB,
            movieUrl = "https://api.shows.st/movie?id={id}&mode=json",
            tvUrl = "https://api.shows.st/tv?id={id}&season={season}&episode={episode}&mode=json",
            isJsonApi = true, referer = "https://player.vidlove.cc/",
            hasSubtitles = true, maxQuality = 1080, timeoutSec = 12,
        ),
        // ── Verified responding embeds (handled by CloudStream extractor registry / harvest) ──
        ServerSpec(
            id = "2embed.cc", name = "2Embed",
            idType = ServerIdType.IMDB,
            movieUrl = "https://www.2embed.cc/embed/movie?imdb={id}",
            tvUrl = "https://www.2embed.cc/embed/tv?imdb={id}&s={season}&e={episode}",
            referer = "https://www.2embed.cc/",
            hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),
        ServerSpec(
            id = "vsembed", name = "VidSrc",
            idType = ServerIdType.IMDB,
            movieUrl = "https://vsembed.ru/embed/{id}",
            tvUrl = "https://vsembed.ru/embed/{id}/{season}-{episode}",
            referer = "https://vsembed.ru/",
            hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),
        // ── Vidsrc family (TMDB-keyed) — live per tmdb-embed-providers core list, Sept 2026 ──
        ServerSpec(
            id = "vidsrc.pm", name = "VidSrc PM",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidsrc.pm/embed/movie?tmdb={id}",
            tvUrl = "https://vidsrc.pm/embed/tv?tmdb={id}&season={season}&episode={episode}",
            referer = "https://vidsrc.pm/",
            hasSubtitles = false, maxQuality = 1080, timeoutSec = 10,
        ),
        ServerSpec(
            id = "vidsrc.to", name = "VidSrc TO",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidsrc.to/embed/movie/{id}",
            tvUrl = "https://vidsrc.to/embed/tv/{id}/{season}/{episode}",
            referer = "https://vidsrc.to/",
            hasSubtitles = false, maxQuality = 1080, timeoutSec = 10,
        ),
        ServerSpec(
            id = "vidsrc.cc", name = "VidSrc CC",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidsrc.cc/embed/movie/{id}",
            tvUrl = "https://vidsrc.cc/embed/tv/{id}/{season}/{episode}",
            referer = "https://vidsrc.cc/",
            hasSubtitles = false, maxQuality = 1080, timeoutSec = 10,
        ),
        ServerSpec(
            id = "2embed.skin", name = "2Embed Skin",
            idType = ServerIdType.IMDB,
            movieUrl = "https://2embed.skin/embed/movie?imdb={id}",
            tvUrl = "https://2embed.skin/embed/tv?imdb={id}&s={season}&e={episode}",
            referer = "https://2embed.skin/",
            hasSubtitles = false, maxQuality = 1080, timeoutSec = 10,
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