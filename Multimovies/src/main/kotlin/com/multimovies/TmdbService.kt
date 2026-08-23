package com.multimovies

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap

/** Read a JSON string field, returning null when blank (avoids platform-type quirks). */
private fun str(obj: JSONObject, key: String): String? {
    val v: String = obj.optString(key)
    return if (v.isBlank()) null else v
}

/**
 * Keyless metadata enrichment for the Multimovies provider.
 *
 * Uses the public Cinemeta (Stremio) metadata API, the same keyless endpoint that
 * CSX (CineStream) and VegaMoviesProvider use for enrichment (no API key required).
 *
 * Endpoint: https://v3-cinemeta.strem.io/meta/{type}/{imdbId}.json
 *
 * `addImdbId(imdbId)` is called on every LoadResponse (in the provider) so that
 * CloudStream's built-in TmdbProvider (when the user enables TMDB in the app) can
 * additionally pull richer ratings, episode descriptions, and cast with profile
 * photos -- no provider setting required.
 */
    object CinemetaService {

        private const val META_URL = "https://v3-cinemeta.strem.io/meta"
        private const val CATALOG_URL = "https://v3-cinemeta.strem.io/catalog"

        /** In-memory cache for title-based IMDB id lookups, shared by the search
         *  poster backfill and the detail page, so a given title resolves its
         *  IMDB id at most once per session. */
        private val imdbCache = ConcurrentHashMap<String, String?>()

        /** Fetch Cinemeta metadata for [imdbId] of [type] ("movie" or "series"). */
        suspend fun fetchMeta(imdbId: String, type: String): CinemetaMeta? {
            if (imdbId.isBlank()) return null
            return try {
                val text = app.get("$META_URL/$type/$imdbId.json", timeout = 15).text
                parseMeta(text)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Search for an IMDB id by [title] and optional [year] using Cinemeta's
         * public catalog search. This is a fallback when the page doesn't expose
         * an IMDB id directly. Cached by (title, year, type) so repeated lookups
         * are instant.
         */
        suspend fun searchImdbId(title: String, year: Int?, type: String): String? {
            val cached = imdbCache[cacheKey(title, year, type)]
            if (cached != null) return cached
            val query = java.net.URLEncoder.encode(title.trim(), "UTF-8")
            val json = try {
                app.get("$CATALOG_URL/$type/top/search=$query.json", timeout = 4).text
            } catch (e: Exception) {
                null
            }
            val result = if (json.isNullOrBlank()) null else pickBestImdbId(json, title, year)
            imdbCache[cacheKey(title, year, type)] = result
            return result
        }

        private fun cacheKey(title: String, year: Int?, type: String) =
            "$type:${title.trim().lowercase()}|$year"

    /** Parse a Cinemeta catalog search response and pick the best IMDB id match. */
    fun pickBestImdbId(raw: String?, title: String, year: Int?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = JSONObject(raw)
            val metas = root.optJSONArray("metas") ?: return null
            val titleLower = title.lowercase().trim()
            // try exact title match (year optional, prefer it when provided)
            for (i in 0 until metas.length()) {
                val m = metas.optJSONObject(i) ?: continue
                val name = str(m, "name")?.lowercase()?.trim() ?: continue
                val mYear = str(m, "year")?.takeIf { it.length == 4 }?.toIntOrNull()
                    ?: str(m, "releaseInfo")?.take(4)?.toIntOrNull()
                if (name == titleLower && (year == null || mYear == null || mYear == year)) {
                    str(m, "id")?.takeIf { it.startsWith("tt") }?.let { return it }
                }
            }
            // fallback: first result with an imdb id
            for (i in 0 until metas.length()) {
                val m = metas.optJSONObject(i) ?: continue
                str(m, "id")?.takeIf { it.startsWith("tt") }?.let { return it }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Extract the IMDB "tt…" id from a Multimovies/Dooplay detail page. */
    fun extractImdbId(doc: Document): String? {
        // 1. Open Graph meta tag: <meta property="og:imdb_id" content="tt...">
        doc.selectFirst("meta[property=\"og:imdb_id\"]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { return normalize(it) }

        // 2. IMDB links anywhere on the page
        doc.select("a[href*='imdb.com/title/'], a[href*='/title/tt']").firstOrNull()
            ?.attr("href")
            ?.let { normalize(it) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        // 3. Dooplay-specific containers (common patterns)
        doc.select("div.imdb a, span.imdb a, li.imdb a, .imdb-link a, .imdbRating a, [class*='imdb'] a")
            .firstOrNull()
            ?.attr("href")
            ?.let { normalize(it) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        // 4. Data attributes (some themes use data-imdb)
        doc.select("[data-imdb], [data-imdb-id], [data-imdbid]").firstOrNull()
            ?.attr("data-imdb")
            ?.takeIf { it.isNotBlank() }
            ?.let { return normalize(it) }

        // 5. Script tags with JSON-LD or embedded data
        doc.select("script[type=\"application/ld+json\"]").forEach { script ->
            val text = script.html()
            val m = Regex("""\"@id\"\s*:\s*\"https?://(?:www\.)?imdb\.com/title/(tt\d+)\"""").find(text)
                ?: Regex("""tt\d{7,8}""").find(text)
            m?.value?.let { return normalize(it) }
        }

        // 6. Inline JavaScript variables: imdb_id = "tt...", "imdb": "tt...", etc.
        doc.select("script").forEach { script ->
            val text = script.html()
            val m = Regex("""imdb[_\s]*id\s*[=:]\s*['"](tt\d+)['"]""", RegexOption.IGNORE_CASE).find(text)
                ?: Regex("""['"](?:imdb|imdb_id|imdbId|imdbid)['"]\s*:\s*['"](tt\d+)['"]""", RegexOption.IGNORE_CASE).find(text)
            m?.groupValues?.getOrNull(1)?.let { return it }
        }

        return null
    }

    private fun normalize(value: String): String {
        val m = Regex("""tt\d{7,8}""").find(value)
        return m?.value ?: value
    }

    /**
     * Parse a Cinemeta meta JSON string into [CinemetaMeta].
     * Uses org.json (already on the classpath via CloudStream) so we don't need
     * the kotlinx-serialization compiler plugin.
     */
    fun parseMeta(raw: String?): CinemetaMeta? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            val meta = obj.optJSONObject("meta") ?: return null
            CinemetaMeta(
                id = str(meta, "id"),
                name = str(meta, "name"),
                year = str(meta, "year"),
                description = str(meta, "description"),
                poster = str(meta, "poster"),
                background = str(meta, "background"),
                imdbRating = str(meta, "imdbRating"),
                genre = meta.optJSONArray("genre")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                },
                cast = meta.optJSONArray("cast")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                },
                videos = meta.optJSONArray("videos")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val v = arr.optJSONObject(i) ?: return@mapNotNull null
                        CinemetaVideo(
                            id = str(v, "id"),
                            name = str(v, "name"),
                            overview = str(v, "overview"),
                            season = v.optInt("season", -1).takeIf { it >= 0 },
                            episode = v.optInt("episode", -1).takeIf { it >= 0 },
                            released = str(v, "released"),
                            thumbnail = str(v, "thumbnail"),
                            rating = str(v, "rating"),
                        )
                    }
                },
            )
        } catch (e: Exception) {
            null
        }
    }

    data class CinemetaMeta(
        val id: String? = null,
        val name: String? = null,
        val year: String? = null,
        val description: String? = null,
        val poster: String? = null,
        val background: String? = null,
        val imdbRating: String? = null,
        val genre: List<String>? = null,
        val cast: List<String>? = null,
        val videos: List<CinemetaVideo>? = null,
    )

    data class CinemetaVideo(
        val id: String? = null,
        val name: String? = null,
        val overview: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val released: String? = null,
        val thumbnail: String? = null,
        val rating: String? = null,
    )
}

/**
 * Keyless cast + artwork enrichment, mirroring CSX's [getTvdbData].
 *
 * Pulls from a public AIOStreams / TMDB Stremio addon (no API key, no login):
 *   - https://aiometadata.elfhosted.com/stremio/<id>/meta/{type}/{imdbId}.json
 *   - fallback: https://94c8cb9f702d-tmdb-addon.baby-beamup.club/meta/{type}/{imdbId}.json
 *
 * The `meta.app_extras.cast[]` array carries name + `photo` (cast headshots) +
 * `character`, so cast images show even when the user has NOT enabled TMDB in
 * CloudStream (unlike addImdbId, which only triggers the built-in TMDB meta-provider).
 * Also provides poster / background / logo artwork.
 */
object TvdbDataService {

    private const val PRIMARY =
        "https://aiometadata.elfhosted.com/stremio/9197a4a9-2f5b-4911-845e-8704c520bdf7/meta"
    private const val FALLBACK = "https://94c8cb9f702d-tmdb-addon.baby-beamup.club/meta"
    private const val IMAGE_PROXY = "https://wsrv.nl/?url="

    /**
     * Fetch cast (with photos), poster, background and logo for [imdbId] of
     * [type] ("movie" or "series"). Returns null on any failure.
     */
    suspend fun fetchMeta(imdbId: String?, type: String): ExtractedMediaData? {
        if (imdbId.isNullOrBlank()) return null
        val candidates = listOf(
            "$PRIMARY/$type/$imdbId.json",
            "$FALLBACK/$type/$imdbId.json",
        )
        var json = ""
        for (url in candidates) {
            json = try {
                app.get(url, timeout = 4).text
            } catch (e: Exception) {
                ""
            }
            if (json.isNotBlank()) break
        }
        if (json.isBlank()) return null

        return try {
            val root = JSONObject(json)
            val meta = root.optJSONObject("meta") ?: return null

            val poster = str(meta, "poster")?.let { "$IMAGE_PROXY$it" }
            val background = str(meta, "background")?.let { "$IMAGE_PROXY$it" }
            val logo = str(meta, "logo")?.let { "$IMAGE_PROXY$it" }

            val cast = meta.optJSONObject("app_extras")
                ?.optJSONArray("cast")
                ?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val c = arr.optJSONObject(i) ?: return@mapNotNull null
                        val name = str(c, "name") ?: return@mapNotNull null
                        ActorData(
                            Actor(
                                name,
                                str(c, "photo")?.let { "$IMAGE_PROXY$it" } ?: "",
                            ),
                            roleString = str(c, "character"),
                        )
                    }
                }

            ExtractedMediaData(cast, poster, background, logo)
        } catch (e: Exception) {
            null
        }
    }

    data class ExtractedMediaData(
        val cast: List<ActorData>? = null,
        val poster: String? = null,
        val background: String? = null,
        val logo: String? = null,
    )
}
