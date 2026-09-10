package com.cinevood

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import java.util.concurrent.ConcurrentHashMap

/*
 * Resolves CineVood download gates. Every `.mfx-download-link` points at a
 * Cloudflare-managed shortener (mobilejsr.rest/genxfm<id>/). One
 * CloudflareKiller instance keeps a shared cookie jar: after the first
 * successful solve, sibling sequential ids resolve fast. Results are cached
 * (per gate URL) so repeat episode/season loads are instant.
 */

class GateResult(val finalUrl: String, val pageText: String, viaKiller: Boolean)

class CineVoodGate(private val refererProvider: () -> String) {

    companion object {
        private val GATE_HOSTS = setOf("mobilejsr.rest")

        // pure: meta-refresh target extraction (also used by file hosts)
        private val META_REFRESH = Regex(
            """(?i)<meta[^>]+content\s*=\s*["'][^"']*?url=([^"'>\s]+)""",
            RegexOption.DOT_MATCHES_ALL
        )

        fun metaRefreshUrl(html: String): String? =
            META_REFRESH.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private val killer = CloudflareKiller()
    private val resolved = ConcurrentHashMap<String, GateResult>()

    fun isGate(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore("/")
        return GATE_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** Solve gate -> final URL behind Cloudflare. Null when unresolvable. */
    suspend fun resolve(gateUrl: String): GateResult? {
        resolved[gateUrl]?.let { return it }
        if (!isGate(gateUrl)) return null
        var url = gateUrl
        repeat(4) {
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
                return null
            }
            if (!isGate(url)) {
                val r = GateResult(url, body, viaKiller = true)
                if (resolved.size > 500) resolved.clear()
                resolved[gateUrl] = r
                return r
            }
            if (!body.contains("cf-error") && !body.contains("Just a moment")) {
                val next = metaRefreshUrl(body) ?: return GateResult(url, body, true)
                url = if (next.startsWith("http")) next else url.substringBeforeLast("/") + "/" + next.trimStart('/')
            }
        }
        return null
    }
}
