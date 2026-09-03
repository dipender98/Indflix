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
    val hasSubtitles: Boolean = false,
    val maxQuality: Int = 0,
    val timeoutSec: Int = 10,
)

/**
 * Federated server registry — seeded with verified-live hosts (Sept 2026).
 */
object ServerFarm {

    val allServers: List<ServerSpec> = listOf(
        // ── Verified live (IMDB-keyed, handled by CloudStream extractor registry) ──
        ServerSpec(
            id = "2embed.cc", name = "2Embed",
            idType = ServerIdType.IMDB,
            movieUrl = "https://www.2embed.cc/embed/movie?imdb={id}",
            tvUrl = "https://www.2embed.cc/embed/tv?imdb={id}&s={season}&e={episode}",
            hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),
        ServerSpec(
            id = "vsembed", name = "VidSrc",
            idType = ServerIdType.IMDB,
            movieUrl = "https://vsembed.ru/embed/{id}",
            tvUrl = "https://vsembed.ru/embed/{id}/{season}-{episode}",
            hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),
        // ── Verified live (JSON API, deterministic) ──
        ServerSpec(
            id = "shows.st", name = "111Movies",
            idType = ServerIdType.IMDB,
            movieUrl = "https://api.shows.st/movie?id={id}&mode=json",
            tvUrl = "https://api.shows.st/tv?id={id}&season={season}&episode={episode}&mode=json",
            isJsonApi = true, hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),
        // ── Verified responding (TMDB-keyed) ──
        ServerSpec(
            id = "vidlink", name = "VidLink",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidlink.pro/movie/{id}",
            tvUrl = "https://vidlink.pro/tv/{id}/{season}/{episode}",
            hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),
        ServerSpec(
            id = "2embed-org", name = "2Embed (org)",
            idType = ServerIdType.TMDB,
            movieUrl = "https://2embed.org/embed/movie/{id}",
            tvUrl = "https://2embed.org/embed/tv/{id}/{season}/{episode}",
            hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),
        ServerSpec(
            id = "embeddb", name = "EmbedDB",
            idType = ServerIdType.TMDB,
            movieUrl = "https://embeddb.com/embed/movie/{id}",
            tvUrl = "https://embeddb.com/embed/tv/{id}/{season}/{episode}",
            hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
        ),
        ServerSpec(
            id = "cine2", name = "Cine2",
            idType = ServerIdType.TMDB,
            movieUrl = "https://cine2.com/embed/movie/{id}",
            tvUrl = "https://cine2.com/embed/tv/{id}/{season}/{episode}",
            hasSubtitles = true, maxQuality = 1080, timeoutSec = 10,
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