package com.ottmirror

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * Lean HTTP helpers for the OTTMirror module.
 * Shares the CloudStream app client (with its cookie jar) but keeps
 * OTTMirror-specific timeouts and header logic in one place.
 */
object HttpKit2 {

    const val DEFAULT_TIMEOUT = 12L
    const val SHORT_TIMEOUT = 6L
    const val PLAYBACK_TIMEOUT = 15L

    val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    val commonHeaders = mapOf("User-Agent" to userAgent)

    /** GET with timeout. Returns null on any error. */
    suspend fun get(url: String, timeout: Long = DEFAULT_TIMEOUT): String? {
        return withTimeoutOrNull(timeout * 1000L) {
            runCatching { app.get(url, timeout = timeout, headers = commonHeaders).text }.getOrNull()
        }
    }

    /** GET with referer. */
    suspend fun getWithReferer(url: String, referer: String, timeout: Long = DEFAULT_TIMEOUT): String? {
        return withTimeoutOrNull(timeout * 1000L) {
            runCatching {
                app.get(url, timeout = timeout, headers = commonHeaders + mapOf("Referer" to referer)).text
            }.getOrNull()
        }
    }

    /** GET returning a parsed Jsoup Document. */
    suspend fun getDocument(url: String, timeout: Long = DEFAULT_TIMEOUT): Document? {
        return withTimeoutOrNull(timeout * 1000L) {
            runCatching { app.get(url, timeout = timeout, headers = commonHeaders).document }.getOrNull()
        }
    }

    /** GET returning parsed JSON Object. */
    suspend fun getJson(url: String, timeout: Long = DEFAULT_TIMEOUT): JSONObject? {
        val text = get(url, timeout) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    /** GET returning parsed JSON Array. */
    suspend fun getJsonArray(url: String, timeout: Long = DEFAULT_TIMEOUT): JSONArray? {
        val text = get(url, timeout) ?: return null
        return runCatching { JSONArray(text) }.getOrNull()
    }

    /** Resolve a possibly-relative URL against a base. */
    fun resolveUrl(base: String, path: String): String {
        if (path.startsWith("http", ignoreCase = true)) return path
        if (path.startsWith("//")) return "https:$path"
        val schemeHost = Regex("""^https?://[^/]+""").find(base)?.value ?: return path
        return if (path.startsWith("/")) "$schemeHost$path" else "$schemeHost/$path"
    }

    /** Measure approximate throughput of a stream URL via ranged GET (first 128KB). */
    suspend fun probeSpeed(url: String, referer: String? = null): Long? {
        return withTimeoutOrNull(5000L) {
            runCatching {
                val start = System.currentTimeMillis()
                val headers = mutableMapOf("User-Agent" to userAgent, "Range" to "bytes=0-131071")
                if (!referer.isNullOrBlank()) headers["Referer"] = referer
                val resp = app.get(url, timeout = 5, headers = headers)
                val elapsed = System.currentTimeMillis() - start
                if (elapsed < 1) return@runCatching null
                val bytes = resp.text.length.coerceAtLeast(1)
                // throughput in KB/s
                (bytes * 1000L) / (elapsed * 1024L)
            }.getOrNull()
        }
    }
}