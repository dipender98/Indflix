package com.ottmirror

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

internal fun str(obj: JSONObject, key: String): String? {
    val v: String = obj.optString(key)
    return if (v.isBlank()) null else v
}

/**
 * The opaque string CloudStream passes between load() and loadLinks(). Pure
 * JVM codec (no CloudStream deps) so it is unit-testable and stable across
 * payload versions — old payloads without tmdbId/season/episode still decode.
 */
data class LoadData(
    val id: String,
    val title: String,
    val tmdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

fun encodeLoadData(d: LoadData): String = JSONObject()
    .put("id", d.id)
    .put("title", d.title)
    .apply { d.tmdbId?.let { put("tmdbId", it) } }
    .apply { d.season?.let { put("season", it) } }
    .apply { d.episode?.let { put("episode", it) } }
    .toString()

fun decodeLoadData(data: String): LoadData? = try {
    val m = JSONObject(data)
    val id = m.optString("id").takeIf { it.isNotBlank() } ?: return null
    LoadData(
        id,
        m.optString("title"),
        m.optString("tmdbId").takeIf { it.isNotBlank() },
        m.optInt("season", 0).takeIf { it > 0 },
        m.optInt("episode", 0).takeIf { it > 0 },
    )
} catch (e: Exception) {
    null
}

data class SearchHit(val id: String, val title: String, val type: String?)

data class NetMirrorEpisode(
    val id: String,
    val title: String?,
    val season: Int?,
    val episode: Int?,
    val time: String?,
)

data class NetMirrorSeason(val id: String, val label: String?)

data class NetMirrorPost(
    val title: String,
    val id: String,
    val tmdbId: String?,
    val imdbId: String?,
    val type: String?,
    val year: String?,
    val description: String?,
    val genre: String?,
    val cast: String?,
    val rating: String?,
    val runtime: String?,
    val poster: String?,
    val episodes: List<NetMirrorEpisode>,
    val seasons: List<NetMirrorSeason>,
    val nextPageShow: Boolean,
    val nextPageSeason: String?,
)

data class PlaylistSource(
    val file: String,
    val label: String,
    val type: String,
    val default: String? = null,
)

data class PlaylistTrack(val kind: String, val file: String, val label: String)

data class PlaylistResponse(
    val title: String?,
    val sources: List<PlaylistSource>?,
    val tracks: List<PlaylistTrack>?,
)

data class NewTvTokenResponse(val tokenHash: String?)

data class NewTvPlayerResponse(val status: String?, val videoLink: String?, val referer: String?)

data class EmbedTmdbStream(val url: String, val resolution: Int, val size: Long?)

data class EmbedTmdbCaption(val lang: String, val name: String, val url: String)

data class EmbedTmdbResult(
    val noSource: Boolean,
    val type: String?,
    val streams: List<EmbedTmdbStream>,
    val captions: List<EmbedTmdbCaption>,
)

object NetMirrorParsers {

    fun parseHomeRows(doc: Document): List<Pair<String, List<String>>> {
        val rows = doc.select(".tray-container, #top10")
        return rows.mapNotNull { tray ->
            val name = tray.selectFirst("h2, span")?.text()?.trim() ?: return@mapNotNull null
            val ids = tray.select("article, .top10-post").mapNotNull {
                it.selectFirst("a")?.attr("data-post") ?: it.attr("data-post")
            }.filter { it.isNotBlank() }
            if (ids.isEmpty()) null else name to ids
        }.filter { it.second.isNotEmpty() }
    }

    fun parseSearch(raw: String?): List<SearchHit> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val trimmed = raw.trim()
            val arr: JSONArray = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val obj = JSONObject(trimmed)
                if (obj.optString("status") == "n" && obj.optString("head") == "Top Searches") return emptyList()
                obj.optJSONArray("searchResult") ?: return emptyList()
            }
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = str(m, "id") ?: return@mapNotNull null
                val title = str(m, "t") ?: return@mapNotNull null
                SearchHit(id, title, str(m, "type"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun parsePost(raw: String?): NetMirrorPost? {
        if (raw.isNullOrBlank()) return null
        return try {
            val m = JSONObject(raw)
            val title = str(m, "title") ?: return null
            val id = str(m, "id") ?: str(m, "post_id") ?: ""
            val episodes = m.optJSONArray("episodes")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val e = arr.optJSONObject(i) ?: return@mapNotNull null
                    val eid = str(e, "id") ?: return@mapNotNull null
                    NetMirrorEpisode(eid, str(e, "t"), str(e, "s")?.removePrefix("S")?.toIntOrNull(), str(e, "ep")?.removePrefix("E")?.toIntOrNull(), str(e, "time"))
                }
            } ?: emptyList()
            val seasons = m.optJSONArray("season")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    val sid = str(s, "id") ?: return@mapNotNull null
                    NetMirrorSeason(sid, str(s, "name") ?: str(s, "s"))
                }
            } ?: emptyList()
            NetMirrorPost(
                title = title, id = id,
                tmdbId = str(m, "tmdb_id"), imdbId = str(m, "imdb_id"), type = str(m, "type"),
                year = str(m, "year"), description = str(m, "desc"), genre = str(m, "genre"),
                cast = str(m, "cast"), rating = str(m, "match"), runtime = str(m, "runtime"),
                poster = str(m, "image2") ?: str(m, "image"),
                episodes = episodes, seasons = seasons,
                nextPageShow = m.optInt("nextPageShow", 0) == 1,
                nextPageSeason = str(m, "nextPageSeason"),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun parseEpisodes(raw: String?): Pair<List<NetMirrorEpisode>, Boolean> {
        if (raw.isNullOrBlank()) return emptyList<NetMirrorEpisode>() to false
        return try {
            val m = JSONObject(raw)
            val arr = m.optJSONArray("episodes") ?: return emptyList<NetMirrorEpisode>() to false
            val list = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val eid = str(e, "id") ?: return@mapNotNull null
                NetMirrorEpisode(eid, str(e, "t"), str(e, "s")?.removePrefix("S")?.toIntOrNull(), str(e, "ep")?.removePrefix("E")?.toIntOrNull(), str(e, "time"))
            }
            list to (m.optInt("nextPageShow", 0) == 1)
        } catch (e: Exception) {
            emptyList<NetMirrorEpisode>() to false
        }
    }

    fun parsePlaylist(raw: String?): PlaylistResponse? {
        if (raw.isNullOrBlank()) return null
        return try {
            val trimmed = raw.trim()
            val m = if (trimmed.startsWith("[")) {
                JSONArray(trimmed).optJSONObject(0)
            } else JSONObject(trimmed)
            if (m == null) return null
            val sources = m.optJSONArray("sources")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    val file = str(s, "file") ?: return@mapNotNull null
                    PlaylistSource(file, str(s, "label") ?: "", str(s, "type") ?: "", str(s, "default"))
                }
            }
            val tracks = m.optJSONArray("tracks")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val t = arr.optJSONObject(i) ?: return@mapNotNull null
                    val file = str(t, "file") ?: return@mapNotNull null
                    PlaylistTrack(str(t, "kind") ?: "", file, str(t, "label") ?: "")
                }
            }
            PlaylistResponse(str(m, "title"), sources, tracks)
        } catch (e: Exception) {
            null
        }
    }

    fun parseNewTvToken(raw: String?): NewTvTokenResponse? {
        if (raw.isNullOrBlank()) return null
        return try {
            NewTvTokenResponse(str(JSONObject(raw), "token_hash"))
        } catch (e: Exception) {
            null
        }
    }

    fun parseNewTvPlayer(raw: String?): NewTvPlayerResponse? {
        if (raw.isNullOrBlank()) return null
        return try {
            val m = JSONObject(raw)
            NewTvPlayerResponse(str(m, "status"), str(m, "video_link"), str(m, "referer"))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Collapse a master HLS playlist to ONE rendition URL.
     *
     * Returns the absolute URL of the server-default variant (or the lowest
     * bandwidth when none is marked) so the player opens a single stream
     * instead of fetching every adaptive rendition concurrently. Returns null
     * when the input is already a media playlist, or on any parse failure —
     * the caller then uses the original URL unchanged.
     */
    fun pickSingleVariant(masterUrl: String, raw: String?): String? {
        if (raw.isNullOrBlank() || !raw.startsWith("#EXTM3U")) return null
        val lines = raw.split('\n')
        if (lines.none { it.startsWith("#EXT-X-STREAM-INF") }) return null

        var defaultUrl: String? = null
        var defaultBw = Long.MAX_VALUE
        var lowUrl: String? = null
        var lowBw = Long.MAX_VALUE
        var pendingBw: Long? = null
        var pendingDefault = false

        for (line in lines) {
            val t = line.trim()
            when {
                t.startsWith("#EXT-X-STREAM-INF") -> {
                    pendingBw = Regex("BANDWIDTH=(\\d+)").find(t)?.groupValues?.get(1)?.toLongOrNull()
                    pendingDefault = t.contains("DEFAULT=YES", ignoreCase = true)
                }
                t.isNotBlank() && !t.startsWith("#") && pendingBw != null -> {
                    val abs = if (t.startsWith("http", ignoreCase = true)) t
                    else masterUrl.substringBeforeLast('/', "") + "/" + t
                    val bw = pendingBw
                    if (pendingDefault && bw < defaultBw) {
                        defaultBw = bw; defaultUrl = abs
                    }
                    if (bw < lowBw) {
                        lowBw = bw; lowUrl = abs
                    }
                    pendingBw = null; pendingDefault = false
                }
            }
        }
        return defaultUrl ?: lowUrl
    }

    // ------------------------------------------------------------------
    // net27.cc/api/embed-tmdb — sessionless TMDB-keyed stream API
    // ------------------------------------------------------------------

    /**
     * Parse the embed-tmdb JSON. Live shape (probed Aug 2026):
     * {"ok":true,"tmdbId":..,"type":"movie|tv","mode":"proxy","mp4":"<best>",
     *  "resolution":"720","streams":[{"url":..,"resolution":720,"size":..}],
     *  "captions":[{"lang":"hi","name":"हिन्दी","url":"/api/proxy/video?url=.."}],
     *  "fallbackHls":"/api/loffe/tt..","exp":..,"sig":..}
     * Uncovered titles answer {"ok":true,"noSource":true,"mode":"none",
     *  "error":"We couldn't find this title on NetMirror yet. .."}.
     */
    fun parseEmbedTmdb(raw: String?): EmbedTmdbResult? {
        if (raw.isNullOrBlank()) return null
        return try {
            val m = JSONObject(raw)
            if (!m.optBoolean("ok", false)) return null
            val noSource = m.optBoolean("noSource", false)
            val streams = mutableListOf<EmbedTmdbStream>()
            m.optJSONArray("streams")?.let { arr ->
                (0 until arr.length()).forEach { i ->
                    val s = arr.optJSONObject(i) ?: return@forEach
                    val url = str(s, "url") ?: return@forEach
                    streams += EmbedTmdbStream(url, s.optInt("resolution", 0), s.optLong("size", 0L).takeIf { it > 0 })
                }
            }
            if (streams.isEmpty()) {
                // Some responses only carry the top-level "mp4" + "resolution".
                val mp4 = str(m, "mp4")
                if (mp4 != null) {
                    streams += EmbedTmdbStream(mp4, m.optString("resolution").toIntOrNull() ?: 0, null)
                }
            }
            val captions = mutableListOf<EmbedTmdbCaption>()
            m.optJSONArray("captions")?.let { arr ->
                (0 until arr.length()).forEach { i ->
                    val c = arr.optJSONObject(i) ?: return@forEach
                    val url = str(c, "url") ?: return@forEach
                    val abs = when {
                        url.startsWith("http", ignoreCase = true) -> url
                        url.startsWith("/") -> "https://net27.cc$url"
                        else -> return@forEach
                    }
                    captions += EmbedTmdbCaption(str(c, "lang") ?: "", str(c, "name") ?: "", abs)
                }
            }
            EmbedTmdbResult(noSource, str(m, "type"), streams, captions)
        } catch (e: Exception) {
            null
        }
    }

    /** Highest resolution up to 1080, tie-broken by file size. */
    fun pickEmbedStream(streams: List<EmbedTmdbStream>): EmbedTmdbStream? =
        streams.filter { it.resolution in 1..1080 }
            .maxWithOrNull(compareBy({ it.resolution }, { it.size ?: 0L }))

    /**
     * True when a fetched NewTV master playlist is the dead sessionless
     * template: video variants carry the literal "in=unknown" key (probed:
     * they 404) and/or the audio group URI has an empty host
     * ("https:///files/.."). Never emit such a master — fall through instead.
     */
    fun newTvMasterIsDead(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        if (!raw.startsWith("#EXTM3U")) return true
        if (raw.contains("in=unknown", ignoreCase = true)) return true
        if (raw.contains("URI=\"https:///")) return true
        return false
    }
}
