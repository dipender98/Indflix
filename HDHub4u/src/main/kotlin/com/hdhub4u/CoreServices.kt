@file:Suppress("unused", "RedundantVisibilityModifier", "FunctionName")

package com.hdhub4u

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * FILE: CoreServices.kt — site-ONLY primitives for the HDHub4u plugin.
 *
 * Nothing here touches TMDB/IMDB/OpenSubtitles etc: search, posters, metadata
 * and links all come from the HDHub4u site itself. (The site's REAL search is
 * its own typesense proxy; the `?s=` form is dead on this theme and just echoes
 * the homepage feed, so we hit the same endpoint the site's search.html does.)
 *
 *  - [DomainResolver]  live-domain resolve via the hdhub4u.bi gateway's host
 *                      APIs (base64 JSON {h,c}); caches ~6h, self-heals.
 *  - HTTP helpers      GET text/doc + CloudflareKiller fallback (mirrors the
 *                      Multimovies fetchDoc pattern; that helper is private to
 *                      Multimovies' runtime so we re-declare it here).
 *  - Card / Title / Link parsers  pure (JVM-testable, no network/Android).
 *  - [B64]             pure base64 decoder that also runs on the JVM test class.
 *  - link RESOLVERS    network bit: hubcdn (.mkv), hdstream4u (HLS via
 *                      JsUnpacker), hubdrive (Drive), base64 redirector.
 *
 * Pure helpers live at file top-level; resolvers are `suspend` and rely on
 * `app` / JsUnpacker — only exercised by instrumented runs (the JVM unit tests
 * target the pure parsers and the probe's recorded response shapes).
 */

// ── constants ──────────────────────────────────────────────────────

internal val SITE_UA: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

private const val SEED_DOMAIN = "https://hdhub4u.ag"
private const val TYPESENSE = "https://search.pingora.fyi/collections/post/documents/search"

private val HOST_APIS = listOf(
    "https://h4.suncdn.org/host",
    "https://dns.pingora.fyi/v2/host",
    "https://points.topapi.com/host",
    "https://ml.theapi.org/host",
    "https://cdn.hub4u.cloud/host",
)

private val HDHUB4U_HOST_RE = Regex("""https?://[a-z0-9.-]*hdhub4u\.[a-z]{2,12}""")
private val URL_HOST_RE = Regex("""https?://[^/]+""")
private val SITE_HOST_RE = Regex("""[a-z0-9-]+\.hdhub4u\.[a-z]{2,12}""")
private val SKIP_HOST = Regex(
    """(?:^|\.)(?:catimages\.org|image\.tmdb\.org|imdb\.com|whatsapp\.com|wa\.me|t\.me|4khdhub\.one)""",
    RegexOption.IGNORE_CASE
)
private val EP_HEAD_RE = Regex("""EPi?SODE\s*(\d+)""", RegexOption.IGNORE_CASE)

/** Quality token -> height (ExoPlayer) used when labelling links. */
internal fun qualityFromLabel(label: String): Int {
    val lc = label.lowercase()
    return when {
        "4k" in lc || "2160" in lc -> 2160
        "1080" in lc -> 1080
        "720" in lc -> 720
        "480" in lc -> 480
        "360" in lc -> 360
        else -> getQualityFromName(label)
    }
}

internal fun resolutionFromUrl(url: String?): Int {
    if (url.isNullOrEmpty()) return 0
    return Regex("""(?<!\d)(\d{3,4})p(?!\d)""", RegexOption.IGNORE_CASE)
        .findAll(url).maxOfOrNull { it.groupValues[1].toIntOrNull() ?: 0 } ?: 0
}

internal fun qualityLabel(height: Int): String = when {
    height >= 2160 -> "4K"
    height >= 1440 -> "1440p"
    height >= 1080 -> "1080p"
    height >= 720 -> "720p"
    height >= 480 -> "480p"
    height >= 360 -> "360p"
    else -> "Auto"
}

/** Drop fixed files below 720p. Adaptive (m3u8/mpd) and unknown heights pass. */
internal fun passesQualityFloor(isAdaptive: Boolean, height: Int): Boolean =
    isAdaptive || height <= 0 || height >= 720

// ── base64 (pure: JVM-testable, no android.util dependency) ─────────

object B64 {
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Decode standard + url-safe base64 without throwing on junk. */
    fun decode(s: String): String {
        val clean = s.trim().replace("-", "+").replace("_", "/")
            .filter { ALPHABET.indexOf(it) >= 0 }
        if (clean.isEmpty()) return ""
        val out = java.io.ByteArrayOutputStream((clean.length * 3) / 4 + 1)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            if (c == '=') break
            buffer = ((buffer shl 6) or ALPHABET.indexOf(c)) and 0xFFFFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toString(Charsets.UTF_8)
    }
}

// ── domain lifecycle ────────────────────────────────────────────────

object DomainResolver {
    private var live: String? = null
    private var expiresAt: Long = 0L
    private const val TTL_MS = 6 * 3600_000L
    private var seed = System.nanoTime().toInt()

    /** Cached domain (no network). SEED until [ensure] ran at least once. */
    @Synchronized
    fun current(): String = live ?: SEED_DOMAIN

    /** Suspend: fetch the live domain when the cache is stale. */
    suspend fun ensure(force: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!force && !live.isNullOrEmpty() && now < expiresAt) return live!!
        resolveRemote()?.let { found ->
            live = if (found.startsWith("http", ignoreCase = true)) found else "https://$found"
        }
        // On total failure keep the last known (or seed); TTL not extended so
        // the next provider call retries the APIs.
        expiresAt = System.currentTimeMillis() + TTL_MS
        return live!!
    }

    private suspend fun resolveRemote(): String? {
        for (start in 0 until HOST_APIS.size) {
            val api = HOST_APIS[(start + seed) % HOST_APIS.size]
            seed = (seed + 1) % HOST_APIS.size
            val v = (seed % 100) + 1
            val txt = runCatching {
                app.get("$api?v=$v", timeout = 6L,
                    headers = mapOf("User-Agent" to SITE_UA)).text
            }.getOrNull() ?: continue
            val c = B64.decode(JSONObject(txt).optString("c", ""))
            HDHUB4U_HOST_RE.find(c)?.let { return it.value }
        }
        return null
    }
}

internal suspend fun <T> withDomainRetry(retryOnNull: Boolean, block: suspend () -> T): T {
    try {
        val r = block()
        if (!retryOnNull || r != null) return r
    } catch (e: Exception) { /* fall through to retry path */ }
    DomainResolver.ensure(force = true)
    return block()
}

/** Rewrite any hdhub4u.* host in [url] to the current live domain. */
internal fun liveUrl(url: String?): String {
    if (url.isNullOrEmpty()) return url!!
    val m = URL_HOST_RE.find(url) ?: return url
    val host = m.value.removePrefix("://")
    if (!SITE_HOST_RE.containsMatchIn(host)) return url
    val liveHost = URL_HOST_RE.find(DomainResolver.current())?.value ?: return url
    return liveHost + url.substring(m.value.length)
}

// ── HTTP: fast path + CloudflareKiller fallback (Multimovies pattern) ─

private var cfKiller: CloudflareKiller? = null
private fun solverFactories(): List<() -> CloudflareKiller> = listOf(
    { getCfKiller() }, { CloudflareKiller().also { cfKiller = it } }
)

private fun getCfKiller(): CloudflareKiller =
    cfKiller ?: CloudflareKiller().also { cfKiller = it }

/** Retry [fetch] up to [attempts] times; a "blocked" result triggers
 *  [onBlocked] and the next attempt. All attempts fail → throws [failureMessage].
 *  Mirrors Multimovies' helper (theirs is module-private; finite attempts so a
 *  dead site surfaces one error instead of the app retrying load() forever). */
internal suspend fun <T> retryUntilSolved(
    attempts: Int,
    fetch: suspend (attempt: Int) -> T,
    isBlocked: (T) -> Boolean,
    onBlocked: () -> Unit = {},
    failureMessage: (Throwable?) -> String,
): T {
    var lastErr: Throwable? = null
    repeat(attempts) { i ->
        try {
            val result = fetch(i)
            if (isBlocked(result)) {
                lastErr = IllegalStateException(failureMessage(null))
                onBlocked()
                return@repeat
            }
            return result
        } catch (e: Throwable) {
            lastErr = e
        }
    }
    throw IllegalStateException(failureMessage(lastErr))
}

private fun isChallenge(doc: Document): Boolean =
    doc.body()?.text().orEmpty().let { bodyText ->
        val title = doc.selectFirst("title")?.text()?.lowercase() ?: ""
        bodyText.contains("just a moment", ignoreCase = true) ||
            bodyText.contains("verify you are human", ignoreCase = true) ||
            bodyText.contains("checking your browser", ignoreCase = true) ||
            bodyText.contains("attention required", ignoreCase = true) ||
            title.contains("just a moment")
    }

suspend fun fetchDoc(url: String, timeoutSeconds: Long = 12, required: Boolean = false): Document? {
    val fetchUrl = liveUrl(url)
    val headers = mapOf("User-Agent" to SITE_UA, "Referer" to DomainResolver.current() + "/")
    try {
        val doc = app.get(fetchUrl, timeout = timeoutSeconds, headers = headers).document
        if (!isChallenge(doc)) return doc
    } catch (e: Exception) { /* fall through to challenge-solve path */ }
    val solved = withTimeoutOrNull(20_000L) {
        retryUntilSolved(
            attempts = solverFactories().size,
            fetch = { i ->
                val factory = solverFactories()[i.coerceAtMost(solverFactories().size - 1)]
                app.get(fetchUrl, timeout = 15L, headers = headers,
                    interceptor = factory()).document
            },
            isBlocked = ::isChallenge,
            onBlocked = { cfKiller = null },
            failureMessage = { lastErr -> lastErr?.localizedMessage ?: "Failed to load $fetchUrl" },
        )
    }
    return when {
        solved == null -> if (required) throw ErrorLoadingException("Timed out fetching $fetchUrl") else null
        isChallenge(solved) ->
            if (required) throw ErrorLoadingException("Cloudflare challenge unsolved for $fetchUrl") else null
        else -> solved
    }
}

suspend fun fetchText(url: String, timeoutSeconds: Long = 12, referer: String? = null): String? {
    val fetchUrl = liveUrl(url)
    val headers = LinkedHashMap<String, String>().apply {
        put("User-Agent", SITE_UA)
        put("Referer", referer ?: (DomainResolver.current() + "/"))
    }
    return withTimeoutOrNull(timeoutSeconds * 1000L) {
        runCatching { app.get(fetchUrl, timeout = timeoutSeconds, headers = headers).text }.getOrNull()
    }
}

/** GET `url` and parse as JSON. typesense needs a live-site Referer, so the
 *  default referer is the site's search shell. */
suspend fun fetchJson(url: String, timeoutSeconds: Long = 12): JSONObject? {
    val text = fetchText(url, timeoutSeconds, DomainResolver.current() + "/search.html") ?: return null
    return runCatching { JSONObject(text) }.getOrNull()
}

/** Build `?k=v&...` without external deps (testable). */
fun encodeQuery(base: String, params: Map<String, String>): String =
    base + "?" + params.entries.joinToString("&") { (k, v) -> "$k=$v" }

// ── site search (typesense — the site's own index) ─────────────────

data class TypesenseHit(
    val title: String,
    val permalink: String,
    val thumbnail: String,
    val imdbId: String,
    val date: String,
    val categories: List<String>,
)

suspend fun searchSite(query: String, page: Int = 1, limit: Int = 15): List<TypesenseHit> {
    if (query.isBlank()) return emptyList()
    val params = mapOf(
        "q" to query,
        "query_by" to "post_title,category,stars,director,imdb_id",
        "query_by_weights" to "4,2,2,2,4",
        "sort_by" to "sort_by_date:desc",
        "limit" to limit.toString(),
        "highlight_fields" to "none",
        "page" to page.toString(),
        "use_cache" to "true",
    )
    val root = fetchJson(encodeQuery(TYPESENSE, params)) ?: return emptyList()
    val hits = root.optJSONArray("hits") ?: return emptyList()
    return (0 until hits.length()).mapNotNull { i ->
        val doc = hits.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
        val permalink = doc.optString("permalink", "")
        if (!permalink.startsWith("http", ignoreCase = true)) return@mapNotNull null
        TypesenseHit(
            title = doc.optString("post_title", ""),
            permalink = permalink,
            thumbnail = doc.optString("post_thumbnail", ""),
            imdbId = doc.optString("imdb_id", ""),
            date = doc.optString("post_date", ""),
            categories = jsonArrayToList(doc.optJSONArray("category")),
        )
    }
}

private fun jsonArrayToList(arr: org.json.JSONArray?): List<String> =
    arr?.let { (0 until it.length()).mapNotNull { i -> it.optString(i, "") } } ?: emptyList()

// ── pure parsers ───────────────────────────────────────────────────

data class Card(val title: String, val href: String, val poster: String)

private val CARD_RE = Regex(
    """<li class="thumb[^"]*"[^>]*>\s*<figure>\s*<img[^>]*\ssrc="([^"]+)"[^>]*>\s*""" +
            """<a[^>]*href="([^"]+)"[^>]*>.*?<figcaption>\s*<a[^>]*href="[^"]*"[^>]*>""" +
            """\s*<p>(.*?)</p>""",
    RegexOption.DOT_MATCHES_ALL
)

fun parseCards(html: String?): List<Card> {
    if (html.isNullOrEmpty()) return emptyList()
    return CARD_RE.findAll(html).map { m ->
        Card(htmlUnesc(m.groupValues[3]), m.groupValues[2].trim(), m.groupValues[1].trim())
    }.toList()
}

fun isSeriesTitle(title: String): Boolean =
    Regex("""(ALL Episodes|Web Series|Season\s+\d|Episodes\s*\d)""", RegexOption.IGNORE_CASE)
        .containsMatchIn(title)

fun parseYear(raw: String?): Int? =
    raw?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }

fun cleanTitle(raw: String): String {
    var s = htmlUnesc(raw).trim()
    s = s.replace(Regex("""^\s*(episode|season|ep)\s*\d+\s*[-–]?\s*""", RegexOption.IGNORE_CASE), "")
    val yr = Regex("""\((19|20)\d{2}\)""").find(s)?.value
    val base = s.split("|").first().trim()
    val cleaned = Regex("""\[[^\]]*\]""").replace(base, "").trim()
    return if (yr != null && !cleaned.contains(yr)) "$cleaned $yr" else cleaned
}

private fun htmlUnesc(s: String): String = s
    .replace("&#038;", "&").replace("&quot;", "\"").replace("&#039;", "'")
    .replace("&#39;", "'").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

// ── post link parsing ─────────────────────────────────────────────

data class PostLink(val label: String, val href: String, val host: String, val episode: Int)

fun parsePostLinks(html: String?): List<PostLink> {
    if (html.isNullOrEmpty()) return emptyList()
    val body = Regex("""<main[^>]*page-body[^>]*>(.*?)</main>""", RegexOption.DOT_MATCHES_ALL)
        .find(html)?.groupValues?.get(1) ?: html
    val headings = Regex("""<h[1-6][^>]*>.*?</h[1-6]>""", RegexOption.DOT_MATCHES_ALL).findAll(body).toList()
    var curEp = -1
    var hi = 0
    val out = ArrayList<PostLink>()
    for (am in Regex("""<a[^>]+href="(https?://[^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(body)) {
        while (hi < headings.size && headings[hi].range.first < am.range.first) {
            val htext = htmlUnesc(Regex("<[^>]+>", RegexOption.DOT_MATCHES_ALL).replace(headings[hi].value, " "))
            // Only an EPISODE heading regroups; every link lives inside its own
            // h3/h4, which must NOT reset the current episode.
            EP_HEAD_RE.find(htext)?.groupValues?.get(1)?.toIntOrNull()?.let { curEp = it }
            hi++
        }
        val href = htmlUnesc(am.groupValues[1])
        val host = href.removePrefix("https://").removePrefix("http://").substringBefore('/').trimEnd('.')
        if (SKIP_HOST.containsMatchIn(host)) continue
        val label = htmlUnesc(Regex("<[^>]+>", RegexOption.DOT_MATCHES_ALL).replace(am.groupValues[2], " "))
            .replace(Regex("\\s+"), " ").trim()
        if (label.isEmpty()) continue
        out.add(PostLink(label, href, host, curEp))
    }
    return out
}

// ── resolved link ──────────────────────────────────────────────────

data class ResolvedSource(
    val name: String,
    val url: String,
    val quality: Int,
    val isAdaptive: Boolean,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<Pair<String, String>> = emptyList(),
)

fun parseHlsLinks(text: String?): List<String> {
    if (text.isNullOrEmpty()) return emptyList()
    val links = LinkedHashMap<String, String>()
    for (m in Regex("""hls\d["']?\s*:\s*["'](https://[^"']+)""").findAll(text)) {
        Regex("""hls(\d)""").find(m.groupValues[0])?.groupValues?.get(1)
            ?.let { links.putIfAbsent(it, m.groupValues[1].replace("\\/", "/")) }
    }
    return listOfNotNull(links["4"], links["2"], links["3"]).filter { it.isNotBlank() }
}

fun parseSubtitles(text: String?): List<Pair<String, String>> =
    Regex("""file:["'](https://[^"']+\.vtt[^"']*)["'],\s*label\s*:\s*["']([^"']+)["']""")
        .findAll(text ?: "")
        .map { it.groupValues[2].trim() to it.groupValues[1].replace("\\/", "/") }
        .toList()

// ── hub link resolvers (network — depend on `app`) ───────────────────

/** Resolve one post link into playable/usable sources. Host-agnostic:
 *  classifies by host so a renamed mirror still resolves. */
suspend fun resolveLink(link: PostLink): List<ResolvedSource> = when {
    link.host.contains("hdstream4u") || link.host.contains("hubstream") ->
        resolveHdStream4u(link.href, link.label)
    link.host.contains("hubcdn") -> resolveHubCdn(link.href, link.label)
    link.host.contains("hubdrive") -> resolveHubDrive(link.href, link.label)
    "?id=" in link.href -> resolveBase64Redirector(link.href, link.label)
    Regex("""\.(mp4|mkv|m3u8|mpd)(\?|&|$)""", RegexOption.IGNORE_CASE).containsMatchIn(link.href) ->
        listOf(ResolvedSource(
            name = link.label.ifEmpty { link.host },
            url = link.href,
            quality = qualityFromLabel(link.label).let { if (it > 0) it else resolutionFromUrl(link.href) },
            isAdaptive = Regex("""\.(m3u8|mpd)(\?|&|$)""", RegexOption.IGNORE_CASE).containsMatchIn(link.href),
            headers = mapOf("Referer" to link.href),
        ))
    else -> emptyList()
}

private suspend fun resolveHubCdn(url: String, label: String): List<ResolvedSource> {
    val html = fetchText(url, timeoutSeconds = 8, referer = "https://hdstream4u.com/") ?: return emptyList()
    val reurl = Regex("""reurl\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return emptyList()
    val r = Regex("""[?&]r=([^&"]+)""", RegexOption.IGNORE_CASE).find(reurl) ?: return emptyList()
    val inner = B64.decode(r.groupValues[1])
    val final = Regex("""[?&]link=([^&"]+)""", RegexOption.IGNORE_CASE).find(inner)?.groupValues?.get(1)
        ?: if (inner.startsWith("http", ignoreCase = true)) inner else null
    if (final.isNullOrEmpty()) return emptyList()
    val q = if (qualityFromLabel(label) >= 480) qualityFromLabel(label) else resolutionFromUrl(final)
    return listOf(ResolvedSource(
        name = "Instant ${qualityLabel(q)}",
        url = final, quality = q, isAdaptive = false,
        headers = mapOf("Referer" to "https://hdhub4u.bi/"),
    ))
}

private suspend fun resolveHdStream4u(url: String, label: String): List<ResolvedSource> {
    val html = fetchText(url, timeoutSeconds = 10) ?: return emptyList()
    // The hls URLs exist only INSIDE the eval(P.A.C.K.E.R) player block.
    val unpacked = runCatching { getAndUnpack(html) }.getOrNull() ?: ""
    val hls = parseHlsLinks(unpacked)
    if (hls.isEmpty()) return emptyList()
    return listOf(ResolvedSource(
        name = "HDHub4u Watch ${label.takeIf { label.isNotBlank() }?.let { "[$it]" } ?: ""}",
        url = hls.first(), quality = 0, isAdaptive = true,
        headers = mapOf("Referer" to "https://hdstream4u.com/"),
        subtitles = parseSubtitles(unpacked),
    ))
}

private suspend fun resolveHubDrive(url: String, label: String): List<ResolvedSource> {
    val fid = Regex("""/file/(\d+)""").find(url)?.groupValues?.get(1) ?: return emptyList()
    app.get(url, timeout = 6L, headers = mapOf("User-Agent" to SITE_UA))
    val r = app.post("https://hubdrive.tips/ajax.php?ajax=direct-download",
        data = mapOf("id" to fid), timeout = 10L,
        headers = mapOf("User-Agent" to SITE_UA, "Referer" to url,
            "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json"))
    val j = runCatching { JSONObject(r.text) }.getOrNull() ?: return emptyList()
    if (j.optString("code") != "200") return emptyList()
    val d = j.optJSONObject("data") ?: return emptyList()
    val gd = d.optString("gd", "")
    if (gd.isEmpty()) return emptyList()
    val driveUrl = if (Regex("""^[a-zA-Z0-9_-]{10,}$""").matches(gd))
        "https://drive.google.com/file/d/$gd/view" else gd
    return listOf(ResolvedSource(
        name = "Drive ${qualityLabel(qualityFromLabel(label))}",
        url = driveUrl, quality = qualityFromLabel(label), isAdaptive = false,
        headers = mapOf("Referer" to "https://hubdrive.tips/"),
    ))
}

private suspend fun resolveBase64Redirector(url: String, label: String): List<ResolvedSource> {
    val id = Regex("""[?&]id=([^&]+)""").find(url)?.groupValues?.get(1) ?: return emptyList()
    var cur = B64.decode(id)
    repeat(4) {
        val link = Regex("""[?&]link=([^&"]+)""", RegexOption.IGNORE_CASE).find(cur)
        if (link != null) {
            cur = B64.decode(link.groupValues[1])
            if (cur.startsWith("http", ignoreCase = true)) return directFromUrl(cur, label)
        } else if (cur.startsWith("http", true)) {
            return directFromUrl(cur, label)
        } else return emptyList()
    }
    return emptyList()
}

private fun directFromUrl(url: String, label: String): List<ResolvedSource> {
    val isAdaptive = Regex("""\.(m3u8|mpd)(\?|&|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)
    val q = if (qualityFromLabel(label) >= 360) qualityFromLabel(label) else resolutionFromUrl(url)
    return listOf(ResolvedSource(
        name = "HDHub4u ${qualityLabel(q)} ${label.takeIf { it.isNotBlank() }?.let { "[$it]" } ?: ""}",
        url = url, quality = q, isAdaptive = isAdaptive,
        headers = mapOf("Referer" to "https://hdhub4u.bi/"),
    ))
}
