package com.ottmirror

import org.json.JSONArray
import org.json.JSONObject

internal fun str(obj: JSONObject, key: String): String? {
    val v: String = obj.optString(key)
    return if (v.isBlank()) null else v
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

object NetMirrorParsers {

    fun parseSearch(raw: String?): List<SearchHit> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val trimmed = raw.trim()
            val arr: JSONArray = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONObject(trimmed).optJSONArray("searchResult") ?: return emptyList()
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
}