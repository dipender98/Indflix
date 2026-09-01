package com.multimovies

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One shared OkHttp stack for the id-based extractors (Nxsha, Shows, VidEm).
 * Each previously built its own client and re-implemented
 * httpGet/httpGetJson/httpPost — this object kills the duplication.
 *
 * The client carries a per-host cookie jar. Callers pass their full header set
 * (including UA) as [headers]; the client's own connect/read timeouts are a
 * high hard cap (12 s), while [budgetMs] wraps each call in
 * `withTimeoutOrNull`.
 */
internal object HttpKit {

    private val cookieJar = object : okhttp3.CookieJar {
        private val cookies = mutableMapOf<String, List<okhttp3.Cookie>>()
        override fun saveFromResponse(url: okhttp3.HttpUrl, list: List<okhttp3.Cookie>) {
            cookies[url.host] = list
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
            return cookies[url.host]?.filter { it.expiresAt > System.currentTimeMillis() } ?: emptyList()
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** GET [url] with the given [headers], returning the response body text,
     *  or null on failure / timeout after [budgetMs]. */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), budgetMs: Long = 8_000L): String? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(budgetMs) {
                runCatching {
                    val req = Request.Builder().url(url).get()
                    headers.forEach { (k, v) -> req.header(k, v) }
                    client.newCall(req.build()).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        resp.body.string()
                    }
                }.getOrNull()
            }
        }

    /** GET [url] and parse the response as JSON, or null. */
    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap(), budgetMs: Long = 8_000L): JSONObject? =
        get(url, headers, budgetMs)?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()
        }

    /** POST [url] with an empty body and the given [headers] + optional
     *  [referer] / [origin]. Returns the response body text, or null on
     *  failure / timeout after [budgetMs]. */
    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        origin: String? = null,
        budgetMs: Long = 8_000L,
    ): String? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(budgetMs) {
            runCatching {
                val req = Request.Builder().url(url).post("".toRequestBody(null))
                headers.forEach { (k, v) -> req.header(k, v) }
                if (!referer.isNullOrBlank()) req.header("Referer", referer)
                if (!origin.isNullOrBlank()) req.header("Origin", origin)
                client.newCall(req.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body.string()
                }
            }.getOrNull()
        }
    }
}