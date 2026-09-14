package Test

import com.multimovies.SubtilesProvider
import com.multimovies.WyzieSubs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TEMPORARY DEBUG - delete after verifying Wyzie end-to-end. Needs WYZIE_KEY + TMDB_KEY env. */
class WyzieReacherProbeTest {

    private fun get(url: String): Triple<Int?, String?, Boolean> {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 12_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        return try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            Triple(code, body, false)
        } catch (t: Exception) {
            Triple(null, null, true)
        } finally {
            c.disconnect()
        }
    }

    private fun tmdb(path: String, key: String): String {
        val sep = if (path.contains("?")) "&" else "?"
        val (code, body, _) = get("https://api.themoviedb.org/3$path${sep}api_key=$key")
        assertEquals(200, code, "TMDB $path failed: ${body?.take(200)}")
        return body.orEmpty()
    }

    @Test
    fun reacherS01E01_wyzieServesEnglishSubs() {
        val wyzieKey = System.getenv("WYZIE_KEY").orEmpty()
        val tmdbKey = System.getenv("TMDB_KEY").orEmpty()
        if (wyzieKey.isBlank() || tmdbKey.isBlank()) {
            println("SKIP: needs WYZIE_KEY + TMDB_KEY env")
            return
        }

        // Resolve the show -> TMDB id -> IMDb id live (no hardcoded ids).
        val search = tmdb("/search/tv?query=Reacher&include_adult=false", tmdbKey)
        val arr = org.json.JSONObject(search).optJSONArray("results") ?: org.json.JSONArray()
        val hit = (0 until arr.length()).map { arr.optJSONObject(it) }
            .firstOrNull { it.optString("name").equals("Reacher", ignoreCase = true) }
            ?: arr.optJSONObject(0) ?: error("no search hit")
        val tmdbId = hit.optInt("id")
        assertTrue(tmdbId > 0, "no tmdb id")
        val imdb = org.json.JSONObject(tmdb("/tv/$tmdbId/external_ids", tmdbKey)).optString("imdb_id")
        assertTrue(imdb.startsWith("tt"), "no imdb id: $imdb")
        println("Reacher S01E01 resolves to $imdb (tmdb $tmdbId)")

        // Same chunking the app uses for a full language set.
        val codes = SubtilesProvider.codesFromLangs(SubtilesProvider.desiredLanguages())
        val groups = SubtilesProvider.groupRequests(codes)
        assertTrue(groups.isNotEmpty())
        val results = runBlocking {
            groups.map { g ->
                async {
                    val url = WyzieSubs.buildUrl(imdb, null, 1, 1, g, wyzieKey)
                        ?: error("no url for $g")
                    println("GET ${url.replace(wyzieKey, "***")}")
                    g to get(url)
                }
            }.awaitAll()
        }
        var english = 0
        var blank = 0
        for ((g, res) in results) {
            val (code, body, failed) = res
            if (failed || body.isNullOrBlank()) {
                blank++
                println("group ${g.joinToString(",")}: no reply")
                continue
            }
            assertNull(WyzieSubs.failureReason(code, body), "group $g failed: ${body.take(200)}")
            val tracks = WyzieSubs.parse(body, g)
            println("group ${g.joinToString(",")}: ${tracks.size} tracks")
            english += tracks.count { it.lang == "English" }
        }
        assertTrue(blank < results.size, "all groups blank for Reacher S01E01")
        assertTrue(english > 0, "no English subs for Reacher S01E01")
        println("OK: $english English tracks for Reacher S01E01 ($imdb)")
    }
}
