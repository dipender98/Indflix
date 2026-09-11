package com.cinevood

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/*
 * Resolves CineVood download gates. Every `.mfx-download-link` points at a
 * Cloudflare-managed shortener (mobilejsr.rest/genxfm<id>/). One lazily-built
 * CloudflareKiller instance keeps a shared cookie jar: after the first
 * successful solve, sibling sequential ids resolve fast. Results are cached
 * (per gate URL) so repeat episode/season loads are instant.
 */

class GateResult(val finalUrl: String, val pageText: String)

/**
 * ONE shared CloudflareKiller for the whole provider: the cinevood.loan
 * network started serving CF managed challenges on the SITE itself (not just
 * the download gates), so listing, post JSON, HTML fallback, gates and file
 * hosts all ride the same solved cookie jar.
 */
object CfHolder {
    val killer: CloudflareKiller by lazy { CloudflareKiller() }
}

class CineVoodGate(private val refererProvider: () -> String) {

    companion object {
        private const val TAG = "CineVood"

        // gate hosts seen on the network; the /genx path check keeps us
        // resilient when the shortener domain rotates like the site TLDs do
        private val GATE_HOSTS = setOf("mobilejsr.rest")
        private val GATE_PATH = Regex("""/genx[a-z]*\d{3,}""", RegexOption.IGNORE_CASE)

        // pure: meta-refresh target extraction (also used by file hosts)
        private val META_REFRESH = Regex(
            """(?i)<meta[^>]+content\s*=\s*["'][^"']*?url=([^"'>\s]+)""",
            RegexOption.DOT_MATCHES_ALL
        )

        // pure: common JS redirect hops used by gate pages
        private val JS_REDIRECTS = listOf(
            Regex("""(?i)window\.location(?:\.href)?\s*=\s*["']([^"']+)["']"""),
            Regex("""(?i)location\.replace\(\s*["']([^"']+)["']"""),
            Regex("""(?i)window\.location\.replace\(\s*["']([^"']+)["']""")
        )

        fun metaRefreshUrl(html: String): String? =
            META_REFRESH.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

        fun jsRedirectUrl(html: String): String? =
            JS_REDIRECTS.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }
                ?.takeIf { it.isNotBlank() }

        fun redirectTarget(html: String): String? =
            metaRefreshUrl(html) ?: jsRedirectUrl(html)
    }

    // rebuildable killer: a STALE cf_clearance cookie makes Cloudflare stall
    // requests (60s hangs) instead of re-challenging; after repeated stalls
    // we throw the instance away and solve fresh.
    @Volatile
    private var killer: CloudflareKiller = CloudflareKiller()
    private val stallCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val solveMutex = Mutex()
    private val resolved = ConcurrentHashMap<String, GateResult>()

    private fun rebuildKiller() {
        killer = CloudflareKiller()
        stallCount.set(0)
        resolved.clear()
        diag("KILLER rebuilt (stale clearance suspected)")
    }

    fun isGate(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore("/")
        return GATE_HOSTS.any { host == it || host.endsWith(".$it") } ||
            GATE_PATH.containsMatchIn(url)
    }

    /**
     * Solve gate -> final URL behind Cloudflare. Null when unresolvable.
     * PLAIN-FIRST: a fresh client often gets the redirect/200 with no
     * challenge at all; the WebView solve (serialized) is only used when an
     * actual challenge appears. Repeated stalls/challenges rebuild the killer
     * to discard a stale cf_clearance (stale cookies make CF stall us).
     */
    suspend fun resolve(gateUrl: String): GateResult? {
        resolved[gateUrl]?.let { return it }
        if (!isGate(gateUrl)) return null
        var url = gateUrl
        repeat(3) { attempt ->
            // 1) plain attempt (no clearance cookies)
            var body: String? = null
            var challenged = false
            try {
                val res = app.get(
                    url,
                    headers = SharedServices.browserHeaders(refererProvider()),
                    timeout = 20
                )
                url = res.url
                body = res.text
                challenged = res.code == 403 || res.code == 429 || res.code == 503 ||
                    isChallenge(body!!)
            } catch (e: Exception) {
                challenged = true
                diag("GATE plain EXC ${typeOf(e)} $url")
            }
            if (!challenged) {
                stallCount.set(0)
                val done = finish(url, body!!, gateUrl)
                if (done != null) return done
                val next = redirectTarget(body!!) ?: return GateResult(url, body!!)
                url = absolutize(url, next)
                return@repeat
            }
            // 2) WebView solve, one at a time
            var solveBody: String? = null
            var solveOk = false
            solveMutex.withLock {
                try {
                    val res = app.get(
                        url,
                        headers = SharedServices.browserHeaders(refererProvider()),
                        interceptor = killer,
                        timeout = 35
                    )
                    url = res.url
                    solveBody = res.text
                    stallCount.set(0)
                    solveOk = true
                    diag("GATE solved-attempt#${attempt + 1} cf=${isChallenge(solveBody!!)} $url")
                } catch (e: Exception) {
                    diag("GATE solve EXC ${typeOf(e)} $url")
                    if (stallCount.incrementAndGet() >= 2) rebuildKiller()
                }
            }
            if (!solveOk) return@repeat
            val body2 = solveBody!!
            if (isChallenge(body2)) {
                if (stallCount.incrementAndGet() >= 3) rebuildKiller()
                return@repeat
            }
            val done2 = finish(url, body2, gateUrl)
            if (done2 != null) return done2
            val next = redirectTarget(body2)
            if (next == null) {
                diag("GATE STUCK no-redirect $url")
                return GateResult(url, body2)
            }
            url = absolutize(url, next)
        }
        diag("GATE FAIL after attempts $gateUrl")
        return null
    }

    private fun finish(url: String, body: String, gateUrl: String): GateResult? {
        if (isGate(url)) return null
        val r = GateResult(url, body)
        if (resolved.size > 500) resolved.clear()
        resolved[gateUrl] = r
        diag("GATE SOLVED $gateUrl -> $url")
        return r
    }

    /**
     * Killer-backed page fetch for file hosts (gdflix family) so the shared
     * cookie jar answers their Cloudflare too (RC-1C).
     */
    suspend fun fetchText(url: String, referer: String): Pair<Int, String>? = try {
        val res = app.get(
            url,
            headers = SharedServices.browserHeaders(referer),
            interceptor = killer,
            timeout = 30
        )
        val body = res.text
        diag("FETCH $url code=${res.code} cf=${isChallenge(body)} left=${body.length}")
        res.code to body
    } catch (e: Exception) {
        diag("FETCH EXC ${typeOf(e)} $url")
        null
    }

    private fun isChallenge(body: String): Boolean =
        body.contains("_cf_chl_opt") || body.contains("Just a moment")

    private fun typeOf(e: Exception) = e.javaClass.simpleName

    private fun absolutize(base: String, next: String): String =
        if (next.startsWith("http")) next
        else base.substringBeforeLast("/") + "/" + next.trimStart('/')

    internal fun diag(msg: String) {
        runCatching { Log.d(TAG, msg) }
    }
}
