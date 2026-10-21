package com.indstream.foryou

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

object SimklParser {

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun parse(json: String): List<ProfileTitle> {
        if (json.isBlank()) return emptyList()
        return try {
            val root = JSONObject(json)
            val out = ArrayList<ProfileTitle>()
            readGroup(root.optJSONArray("shows"), "show", "tv", out)
            readGroup(root.optJSONArray("anime"), "show", "tv", out)
            readGroup(root.optJSONArray("movies"), "movie", "movie", out)
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun readGroup(
        arr: org.json.JSONArray?,
        mediaKey: String,
        type: String,
        out: MutableList<ProfileTitle>,
    ) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val media = entry.optJSONObject(mediaKey) ?: continue
            val title = media.optString("title").takeIf { it.isNotBlank() } ?: continue
            val ids = media.optJSONObject("ids")
            val tmdbId = ids?.optString("tmdb")?.trim()?.toIntOrNull()
            val imdbId = ids?.optString("imdb")?.trim()?.takeIf { it.startsWith("tt") }
            val status = entry.optString("status").lowercase()
            val rating = entry.optDouble("user_rating", 0.0).takeIf { it > 0 } ?: 0.0
            val watchedEpisodes = entry.optInt("watched_episodes_count", 0)
            val totalEpisodes = entry.optInt("total_episodes_count", 0)
            val watched = status == "completed" || status == "watching" ||
                (watchedEpisodes > 0 && totalEpisodes > 0 && watchedEpisodes >= totalEpisodes)
            if (!watched && status.isBlank() && rating <= 0) continue
            out += ProfileTitle(
                tmdbId = tmdbId,
                imdbId = imdbId,
                title = title,
                year = media.optInt("year", 0).takeIf { it > 0 },
                type = type,
                watched = watched,
                rating = rating,
                lastActivityAt = parseTime(entry.optString("last_watched_at")),
                status = status,
            )
        }
    }

    fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            timeFormat.parse(raw)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
