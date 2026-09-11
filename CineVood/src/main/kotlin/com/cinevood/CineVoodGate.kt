package com.cinevood

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import java.util.concurrent.ConcurrentHashMap

/*
 * Resolves CineVood download gates. Every `.mfx-download-link` points at a
 * Cloudflare-managed shortener (mobilejsr.rest/genxfm<id>/). One lazily-built
 * CloudflareKiller instance keeps a shared cookie jar: after the first
 * successful solve, sibling sequential ids resolve fast. Results are cached
 * (per gate URL) so repeat episode/season loads are instant.
 */

class GateResult(val finalUrl: String, val pageText: String)

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

    // lazy: CloudflareKiller needs the app's WebView and must not be built at
    // plugin-registration time (RC-1A)
    private val killer by lazy { CloudflareKiller() }
    private val resolved = ConcurrentHashMap<String, GateResult>()

    fun isGate(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore("/")
        return GATE_HOSTS.any { host == it || host.endsWith(".$it") } ||
            GATE_PATH.containsMatchIn(url)
    }

    /** Solve gate -> final URL behind Cloudflare. Null when unresolvable. */
    suspend fun resolve(gateUrl: String): GateResult? {
        resolved[gateUrl]?.let { return it }
        if (!isGate(gateUrl)) return null
        var url = gateUrl
        repeat(4) { attempt ->
            val body = try {
                val res = app.get(
                    url,
                    headers = SharedServices.browserHeaders(refererProvider()),
                    interceptor = killer,
                    timeout = 45
                )
                url = res.url
                res.text
            } catch (e: Exception) {
                diag("GATE try#${attempt + 1} EXC ${typeOf(e)} $gateUrl")
                return null
            }
            val challenged = isChallenge(body)
            diag(
                "GATE try#${attempt + 1} code-ok cf=$challenged " +
                    "final=$url left=${body.length}"
            )
            if (!isGate(url)) {
                val r = GateResult(url, body)
                if (resolved.size > 500) resolved.clear()
                resolved[gateUrl] = r
                diag("GATE SOLVED $gateUrl -> $url")
                return r
            }
            if (!challenged) {
                val next = redirectTarget(body)
                if (next == null) {
                    diag("GATE STUCK no-redirect $url")
                    return GateResult(url, body)
                }
                url = absolutize(url, next)
            }
        }
        diag("GATE FAIL after 4 attempts $gateUrl")
        return null
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
