package com.indstream

import kotlin.math.min

enum class ServerIdType { TMDB, IMDB }

data class ServerSpec(
    val id: String,
    val name: String,
    val idType: ServerIdType = ServerIdType.TMDB,
    val supportsImdbMovie: Boolean = false,
    val supportsImdbTv: Boolean = false,
    val recordHealth: Boolean = true,
    val movieUrl: String,
    val tvUrl: String,
    val isJsonApi: Boolean = false,
    val referer: String? = null,
    val hasSubtitles: Boolean = false,
    val timeoutSec: Int = 10,
    val declaredLanguages: Set<String> = emptySet(),
    val animeOnly: Boolean = false,
) {
    fun supportsImdb(type: String): Boolean =
        if (type == "movie") supportsImdbMovie else supportsImdbTv
}

object ServerFarm {

    val allServers: List<ServerSpec> = listOf(
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
        ServerSpec(
            id = "vaplayer", name = "VaPlayer",
            idType = ServerIdType.IMDB,
            movieUrl = "https://streamdata.vaplayer.ru/api.php?imdb={id}&type=movie",
            tvUrl = "https://streamdata.vaplayer.ru/api.php?imdb={id}&type=tv&season={season}&episode={episode}",
            isJsonApi = true, referer = "https://nextgencloudfabric.com/",
            hasSubtitles = true, timeoutSec = 12,
        ),
        ServerSpec(
            id = "moviebox", name = "MovieBox",
            idType = ServerIdType.TMDB,
            movieUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox",
            tvUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox",
            isJsonApi = true, referer = "https://movieboxonline.net/",
            hasSubtitles = true, timeoutSec = 65,
        ),
        ServerSpec(
            id = "allmovieland", name = "Allmovieland",
            idType = ServerIdType.IMDB,
            movieUrl = "https://allmovieland.art/?do=search&subaction=search&story={id}",
            tvUrl = "https://allmovieland.art/?do=search&subaction=search&story={id}",
            referer = "https://allmovieland.art/",
            hasSubtitles = false, timeoutSec = 60,
            declaredLanguages = setOf("Hindi", "Tamil", "Telugu", "Bengali"),
        ),
        ServerSpec(
            id = "netmirror", name = "NetMirror",
            idType = ServerIdType.TMDB,
            movieUrl = "https://net27.cc/api/embed-tmdb/{id}",
            tvUrl = "https://net27.cc/api/embed-tmdb/{id}?type=tv&se={season}&ep={episode}",
            isJsonApi = true, referer = "https://net27.cc/",
            hasSubtitles = true, timeoutSec = 50,
            declaredLanguages = emptySet(),
        ),
        ServerSpec(
            id = "castletv", name = "CastleTV",
            idType = ServerIdType.TMDB,
            movieUrl = "https://api.hlowb.com/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US",
            tvUrl = "https://api.hlowb.com/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US",
            referer = "https://api.hlowb.com/",
            hasSubtitles = false, timeoutSec = 30,
            declaredLanguages = setOf("Hindi", "Tamil", "Telugu"),
        ),
        ServerSpec(
            id = "streamflix", name = "StreamFlix",
            idType = ServerIdType.TMDB,
            supportsImdbMovie = true, supportsImdbTv = true,
            movieUrl = "https://api.streamflix.app/data.json",
            tvUrl = "https://api.streamflix.app/data.json",
            hasSubtitles = false, timeoutSec = 25,
        ),
        ServerSpec(
            id = "onetouchtv", name = "OneTouchTV",
            idType = ServerIdType.TMDB,
            movieUrl = "https://api3.devcorp.me/vod/search?keyword={id}",
            tvUrl = "https://api3.devcorp.me/vod/search?keyword={id}",
            referer = "https://onetouchtv.xyz/",
            hasSubtitles = true, timeoutSec = 30,
        ),
        ServerSpec(
            id = "vidcore-api", name = "VidCore",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidrack.created.app/api/sources/movy?id={id}",
            tvUrl = "https://vidrack.created.app/api/sources/movy?id={id}&season={season}&episode={episode}",
            isJsonApi = true, referer = "https://www.vidcore.org/",
            hasSubtitles = false, timeoutSec = 20,
        ),
        ServerSpec(
            id = "bingr", name = "Bingr",
            idType = ServerIdType.TMDB,
            movieUrl = "https://api.bingr.one/api/stream",
            tvUrl = "https://api.bingr.one/api/stream",
            referer = "https://bingr.one/",
            hasSubtitles = false, timeoutSec = 30,
        ),
        ServerSpec(
            id = "showbox", name = "ShowBox",
            idType = ServerIdType.IMDB,
            movieUrl = "https://showbox.media/search?keyword={id}",
            tvUrl = "https://showbox.media/search?keyword={id}",
            referer = "https://showbox.media/",
            hasSubtitles = false, timeoutSec = 40,
        ),
        ServerSpec(
            id = "vidrift", name = "Vidrift",
            idType = ServerIdType.TMDB,
            movieUrl = "https://embed.vidrift.net/embed/movie/{id}",
            tvUrl = "https://embed.vidrift.net/embed/tv/{id}/{season}/{episode}",
            referer = "https://embed.vidrift.net/",
            hasSubtitles = true, timeoutSec = 25,
        ),
        ServerSpec(
            id = "rivestream", name = "Rivestream",
            idType = ServerIdType.TMDB,
            movieUrl = "https://www.rivestream.app/api/backendfetch?requestID=movieVideoProvider&id={id}",
            tvUrl = "https://www.rivestream.app/api/backendfetch?requestID=tvVideoProvider&id={id}&season={season}&episode={episode}",
            referer = "https://www.rivestream.app/",
            hasSubtitles = true, timeoutSec = 25,
        ),
        ServerSpec(
            id = "movy", name = "Movy",
            idType = ServerIdType.TMDB,
            movieUrl = "https://api.wecollege.net/seed?mediaId={id}",
            tvUrl = "https://api.wecollege.net/seed?mediaId={id}",
            referer = "https://www.movy.sx/",
            hasSubtitles = true, timeoutSec = 25,
        ),
        ServerSpec(
            id = "vidspark", name = "VidSpark",
            idType = ServerIdType.TMDB,
            movieUrl = "https://vidspark.to/api/vidora/v1/movie/{id}",
            tvUrl = "https://vidspark.to/api/vidora/v1/tv/{id}/{season}/{episode}",
            referer = "https://vidspark.to/",
            hasSubtitles = true, timeoutSec = 20,
        ),
        ServerSpec(
            id = "braflix", name = "Braflix",
            idType = ServerIdType.TMDB,
            supportsImdbMovie = true, supportsImdbTv = true,
            movieUrl = "https://braflix.dev/api/play/movie/{id}",
            tvUrl = "https://braflix.dev/api/play/tv/{id}/{season}/{episode}",
            referer = "https://braflix.dev/",
            hasSubtitles = false, timeoutSec = 20,
        ),
        ServerSpec(
            id = "4khdhub", name = "4KHDHub",
            idType = ServerIdType.TMDB,
            movieUrl = "https://4khdhub.one/?s={id}",
            tvUrl = "https://4khdhub.one/?s={id}",
            referer = "https://4khdhub.one/",
            hasSubtitles = false, timeoutSec = 40,
        ),
        ServerSpec(
            id = "fsl", name = "FSL",
            idType = ServerIdType.TMDB,
            movieUrl = "https://new1.vegamovies.lease/",
            tvUrl = "https://new1.vegamovies.lease/",
            referer = "https://new1.vegamovies.lease/",
            hasSubtitles = false, timeoutSec = 60,
            declaredLanguages = setOf("Hindi", "Tamil", "Telugu"),
        ),
        ServerSpec(
            id = "hdmovie2", name = "HDMovie2",
            idType = ServerIdType.TMDB,
            supportsImdbMovie = true, supportsImdbTv = true,
            movieUrl = "https://hdmovie2a.live/search/{id}/",
            tvUrl = "https://hdmovie2a.live/search/{id}",
            referer = "https://hdmovie2a.live/",
            hasSubtitles = false, timeoutSec = 35,
        ),
        ServerSpec(
            id = "olamovies", name = "OlaMovies",
            idType = ServerIdType.TMDB,
            movieUrl = "https://olamovies.rent/",
            tvUrl = "https://olamovies.rent/",
            referer = "https://olamovies.rent/",
            hasSubtitles = false, timeoutSec = 35,
        ),
        ServerSpec(
            id = "hindmoviez", name = "HindMoviez",
            idType = ServerIdType.TMDB,
            movieUrl = "https://hindmovie.one/",
            tvUrl = "https://hindmovie.one/",
            referer = "https://hindmovie.one/",
            hasSubtitles = false, timeoutSec = 60,
        ),
        ServerSpec(
            id = "hdhub4u", name = "HDHub4u",
            idType = ServerIdType.TMDB,
            movieUrl = "https://new8.hdhub4u.cl/",
            tvUrl = "https://new8.hdhub4u.cl/",
            referer = "https://new8.hdhub4u.cl/",
            hasSubtitles = false, timeoutSec = 60,
        ),
        ServerSpec(
            id = "moviesdrive", name = "MoviesDrive",
            idType = ServerIdType.TMDB,
            movieUrl = "https://new3.moviesdrive.beer/",
            tvUrl = "https://new3.moviesdrive.beer/",
            referer = "https://new3.moviesdrive.beer/",
            hasSubtitles = false, timeoutSec = 60,
        ),
        ServerSpec(
            id = "dudefilms", name = "DudeFilms",
            idType = ServerIdType.TMDB,
            movieUrl = "https://dudefilms.events/",
            tvUrl = "https://dudefilms.events/",
            referer = "https://dudefilms.events/",
            hasSubtitles = false, timeoutSec = 60,
        ),
        ServerSpec(
            id = "filmycab", name = "FilmyCab",
            idType = ServerIdType.TMDB,
            movieUrl = "https://filmycab.store/",
            tvUrl = "https://filmycab.store/",
            referer = "https://filmycab.store/",
            hasSubtitles = false, timeoutSec = 60,
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



