package com.indstream

enum class ServerIdType { TMDB, IMDB }

enum class ServerSpeed { VERY_FAST, FAST, NORMAL, SLOW }

data class ServerSpec(
    val id: String,
    val name: String,
    val idType: ServerIdType = ServerIdType.TMDB,
    val supportsImdbMovie: Boolean = false,
    val supportsImdbTv: Boolean = false,
    val recordHealth: Boolean = true,
    val movieUrl: String,
    val tvUrl: String,
    val probeUrl: String? = null,
    val isJsonApi: Boolean = false,
    val referer: String? = null,
    val hasSubtitles: Boolean = false,
    val timeoutSec: Int = 10,
    val declaredLanguages: Set<String> = emptySet(),
    val animeOnly: Boolean = false,
    val speed: ServerSpeed = ServerSpeed.NORMAL,
    val reliable: Boolean = false,
    val downloadFriendly: Boolean = false,
) {
    fun supportsImdb(type: String): Boolean =
        if (type == "movie") supportsImdbMovie else supportsImdbTv
}

object ServerFarm {

    val allServers: List<ServerSpec> = listOf(
        ServerSpec(
            id = "vidy", name = "Vidy",
            idType = ServerIdType.TMDB,
            movieUrl = "https://www.vidy.st/movie/{id}?color=DC2626",
            tvUrl = "https://www.vidy.st/tv/{id}/{season}/{episode}?color=DC2626",
            referer = "https://www.vidy.st/",
            hasSubtitles = false, timeoutSec = 40,
        ),
        ServerSpec(
            id = "vidbolt", name = "VidBolt",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidbolt.xyz/api/resolve?type=movie&id={id}",
            tvUrl = "https://vidbolt.xyz/api/resolve?type=tv&id={id}&season={season}&episode={episode}",
            isJsonApi = true, referer = "https://vidbolt.xyz/",
            hasSubtitles = true, timeoutSec = 25,
            speed = ServerSpeed.FAST,
        ),
        ServerSpec(
            id = "vidly", name = "Vidly",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidly.pro/embed/movie/{id}",
            tvUrl = "https://vidly.pro/embed/tv/{id}/{season}/{episode}",
            referer = "https://vidly.pro/",
            hasSubtitles = true, timeoutSec = 25,
            speed = ServerSpeed.FAST,
        ),
        ServerSpec(
            id = "vidlink", name = "VidLink",
            idType = ServerIdType.TMDB,
            supportsImdbMovie = true,
            movieUrl = "https://vidlink.pro/api/b/movie/{id}?multiLang=1",
            tvUrl = "https://vidlink.pro/api/b/tv/{id}/{season}/{episode}?multiLang=1",
            isJsonApi = true, referer = "https://vidlink.pro/",
            hasSubtitles = true, timeoutSec = 15,
            declaredLanguages = emptySet(),
            speed = ServerSpeed.VERY_FAST,
        ),
        ServerSpec(
            id = "vaplayer", name = "VaPlayer",
            idType = ServerIdType.IMDB,
            movieUrl = "https://streamdata.vaplayer.ru/api.php?imdb={id}&type=movie",
            tvUrl = "https://streamdata.vaplayer.ru/api.php?imdb={id}&type=tv&season={season}&episode={episode}",
            isJsonApi = true, referer = "https://nextgencloudfabric.com/",
            hasSubtitles = true, timeoutSec = 12,
            speed = ServerSpeed.VERY_FAST,
        ),
        ServerSpec(
            id = "moviebox", name = "MovieBox",
            idType = ServerIdType.TMDB,
            movieUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox",
            tvUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox",
            isJsonApi = true, referer = "https://movieboxonline.net/",
            hasSubtitles = true, timeoutSec = 65,
            speed = ServerSpeed.FAST, reliable = true, downloadFriendly = true,
        ),
        ServerSpec(
            id = "netmirror", name = "NetMirror",
            idType = ServerIdType.TMDB,
            movieUrl = "https://net79.cc/api/embed-tmdb/{id}?type=movie",
            tvUrl = "https://net79.cc/api/embed-tmdb/{id}?type=tv&se={season}&ep={episode}",
            isJsonApi = true, referer = "https://net79.cc/",
            hasSubtitles = true, timeoutSec = 50,
            declaredLanguages = emptySet(),
            speed = ServerSpeed.FAST, reliable = true, downloadFriendly = true,
        ),
        ServerSpec(
            id = "bingr", name = "Bingr",
            idType = ServerIdType.TMDB,
            movieUrl = "https://api.bingr.one/api/stream",
            tvUrl = "https://api.bingr.one/api/stream",
            referer = "https://bingr.one/",
            hasSubtitles = true, timeoutSec = 30,
            speed = ServerSpeed.FAST,
        ),
        ServerSpec(
            id = "vidrift", name = "Vidrift",
            idType = ServerIdType.TMDB,
            movieUrl = "https://embed.vidrift.net/embed/movie/{id}",
            tvUrl = "https://embed.vidrift.net/embed/tv/{id}/{season}/{episode}",
            referer = "https://embed.vidrift.net/",
            hasSubtitles = true, timeoutSec = 25,
            speed = ServerSpeed.FAST,
        ),
        ServerSpec(
            id = "movy", name = "Movy",
            idType = ServerIdType.TMDB,
            movieUrl = "https://api.wecollege.net/seed?mediaId={id}",
            tvUrl = "https://api.wecollege.net/seed?mediaId={id}",
            referer = "https://www.movy.sx/",
            hasSubtitles = true, timeoutSec = 25,
            speed = ServerSpeed.FAST,
        ),
        ServerSpec(
            id = "atlantic", name = "Atlantic",
            idType = ServerIdType.TMDB,
            movieUrl = "https://zcdn.bluevelvet.space/cdn?tmdb={id}&type=movie",
            tvUrl = "https://zcdn.bluevelvet.space/cdn?tmdb={id}&type=tv&season={season}&episode={episode}",
            isJsonApi = true, referer = "https://atlantic.st/",
            hasSubtitles = false, timeoutSec = 30,
            declaredLanguages = emptySet(),
            speed = ServerSpeed.VERY_FAST, reliable = true,
        ),
        ServerSpec(
            id = "4khdhub", name = "4KHDHub",
            idType = ServerIdType.TMDB,
            movieUrl = "https://4khdhub.one/?s={id}",
            tvUrl = "https://4khdhub.one/?s={id}",
            referer = "https://4khdhub.one/",
            hasSubtitles = false, timeoutSec = 70,
            speed = ServerSpeed.SLOW, downloadFriendly = true,
        ),
        ServerSpec(
            id = "fsl", name = "FSL",
            idType = ServerIdType.TMDB,
            movieUrl = "https://new1.vegamovies.lease/",
            tvUrl = "https://new1.vegamovies.lease/",
            referer = "https://new1.vegamovies.lease/",
            hasSubtitles = false, timeoutSec = 60,
            declaredLanguages = setOf("Hindi", "Tamil", "Telugu"),
            speed = ServerSpeed.SLOW, downloadFriendly = true,
        ),
        ServerSpec(
            id = "hindmoviez", name = "HindMoviez",
            idType = ServerIdType.TMDB,
            movieUrl = "https://hindmovie.one/",
            tvUrl = "https://hindmovie.one/",
            referer = "https://hindmovie.one/",
            hasSubtitles = false, timeoutSec = 60,
            speed = ServerSpeed.SLOW, downloadFriendly = true,
        ),
        ServerSpec(
            id = "hdhub4u", name = "HDHub4u",
            idType = ServerIdType.TMDB,
            movieUrl = "https://new8.hdhub4u.cl/",
            tvUrl = "https://new8.hdhub4u.cl/",
            referer = "https://new8.hdhub4u.cl/",
            hasSubtitles = false, timeoutSec = 60,
            speed = ServerSpeed.SLOW, downloadFriendly = true,
        ),
        ServerSpec(
            id = "moviesdrive", name = "MoviesDrive",
            idType = ServerIdType.TMDB,
            movieUrl = "https://new3.moviesdrive.beer/",
            tvUrl = "https://new3.moviesdrive.beer/",
            referer = "https://new3.moviesdrive.beer/",
            hasSubtitles = false, timeoutSec = 60,
            speed = ServerSpeed.SLOW, downloadFriendly = true,
        ),
        ServerSpec(
            id = "animeworld", name = "AnimeWorld",
            idType = ServerIdType.TMDB,
            movieUrl = "https://watchanimeworld.one/?s={id}",
            tvUrl = "https://watchanimeworld.one/?s={id}",
            referer = "https://watchanimeworld.one/",
            hasSubtitles = false, timeoutSec = 45,
            declaredLanguages = setOf("Hindi", "Tamil", "Telugu", "Malayalam", "Bengali"),
            animeOnly = true,
        ),
        ServerSpec(
            id = "hianime", name = "HiAnime",
            idType = ServerIdType.TMDB,
            movieUrl = "https://hianime.at/search?keyword={id}",
            tvUrl = "https://hianime.at/search?keyword={id}",
            referer = "https://hianime.at/",
            hasSubtitles = false, timeoutSec = 45,
            animeOnly = true,
        ),
    )

    fun buildMovieUrl(spec: ServerSpec, id: String): String =
        spec.movieUrl.replace("{id}", id)

    fun buildProbeUrl(spec: ServerSpec, id: String, title: String, year: String): String =
        (spec.probeUrl ?: spec.movieUrl)
            .replace("{id}", id)
            .replace("{title}", java.net.URLEncoder.encode(title, "UTF-8"))
            .replace("{year}", year)

    fun buildTvUrl(spec: ServerSpec, id: String, season: Int, episode: Int): String =
        spec.tvUrl
            .replace("{id}", id)
            .replace("{season}", season.toString())
            .replace("{episode}", episode.toString())
}

object HealthMonitor {

    private data class ServerHealth(
        val failCount: Int = 0,
        val trippedUntil: Long = 0L,
    )

    private val healthMap = java.util.concurrent.ConcurrentHashMap<String, ServerHealth>()
    private val lock = Any()

    private const val MAX_CONSECUTIVE_FAILURES = 5
    private const val TRIP_DURATION_MS = 5 * 60 * 1000L

    fun recordSuccess(serverId: String) {
        synchronized(lock) {
            val h = healthMap[serverId] ?: ServerHealth()
            healthMap[serverId] = h.copy(failCount = 0)
        }
    }

    fun recordFailure(serverId: String) {
        synchronized(lock) {
            val h = healthMap[serverId] ?: ServerHealth()
            val newFailCount = h.failCount + 1
            healthMap[serverId] = h.copy(
                failCount = newFailCount,
                trippedUntil = if (newFailCount >= MAX_CONSECUTIVE_FAILURES)
                    System.currentTimeMillis() + TRIP_DURATION_MS
                else h.trippedUntil,
            )
        }
    }

    fun isHealthy(serverId: String): Boolean {
        synchronized(lock) {
            val h = healthMap[serverId] ?: return true
            if (h.failCount >= MAX_CONSECUTIVE_FAILURES) {
                if (System.currentTimeMillis() < h.trippedUntil) return false
                healthMap[serverId] = h.copy(failCount = 0)
            }
            return true
        }
    }

    fun resetTrips() {
        synchronized(lock) {
            healthMap.replaceAll { _, h -> h.copy(failCount = 0, trippedUntil = 0L) }
        }
    }
}

